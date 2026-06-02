package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.ActionCode
import com.atombits.pocopaw.CapabilityDomain
import com.atombits.pocopaw.CapabilityStack
import com.atombits.pocopaw.CommonDetailSlotKey
import com.atombits.pocopaw.ExecutionFlowRunner
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.LocalConversationState
import com.atombits.pocopaw.PrototypeStore
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.TargetType
import com.atombits.pocopaw.TaskDetailSlots
import com.atombits.pocopaw.TaskPhase
import com.atombits.pocopaw.TaskRecord
import com.atombits.pocopaw.earnings.DispatchResult
import com.atombits.pocopaw.earnings.EarningsDispatchRequest
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.resolveCurrentState
import com.atombits.pocopaw.updateCurrentIntentSlice
import java.util.Locale

private const val WATCH_VIDEOS_PER_EXECUTION_LIMIT = 10
private const val RETURN_CONFIRMATION_STEP_RESERVE = "2-3"
private const val EARNINGS_DERIVED_REWARD_GREEDY_RULE =
    "Within the current earnings execution flow, use a safe reward-greedy policy: if a visible continuation, bonus, or derived step clearly belongs to the same accepted target flow and offers extra coins/reward without leaving the allowed app/task context, prefer continuing until reward, cooldown, return, explicit completion, return-step-reserve, max-step, or per-run video-limit evidence closes the run. Do not treat this as permission to select a different task-center row/card or create a new candidate."
private const val EARNINGS_FORBIDDEN_CONTINUATION_RULE =
    "Never continue into paid, purchase, membership, payment, finance, loan, credit, cash-withdrawal, game, mini-game, creator, upload, live-streaming, social invite, contacts, coupon, identity, download, install, cross-app, cross-content-domain, or manually blacklisted paths; choose the safe exit/return/fail path instead."
private const val EARNINGS_RETURN_STEP_RESERVE_RULE =
    "Keep the final 2 to 3 execution steps reserved for exit, return, and confirmation actions. When the current run is approaching the max-step boundary, stop optional reward-greedy continuation and spend the remaining budget on safe exit/return to the task center or Pocopaw, or return completed/failed if no safe action is needed."
private const val FILLER_RUN_COMPLETION_RULE =
    "For filler repeatable earnings tasks, this run is complete when the target reward is claimed or confirmed, a reward modal is dismissed, the current task-center row/card for the target shows a countdown or cooldown, or recent history shows the target reward was accepted and the current screen is back in Pocopaw or needs no further target action. Return flow_state=completed with action=null at that point. Countdown and cooldown text are completion evidence for this run, not clickable targets."
private const val FILLER_REWARD_SUCCESS_EXIT_RULE =
    "For ad/video filler tasks, when a rewarded video has ended and a visible reward-success control appears, such as a top-right '领取成功' button, tap that control once if needed to surface or resolve the post-reward continue/exit choice. Treat the control as reward-flow navigation for the current run, not as a new task or repeat target."
private const val FILLER_AD_VIDEO_CHAIN_RULE =
    "If that post-reward choice offers another rewarded ad/video, bonus, or derived coin step for the same target flow versus exiting, prefer the safe reward continuation while it remains inside the same accepted target flow and does not hit a forbidden-continuation, cooldown, explicit completion, app-boundary, return-step-reserve, max-step, or per-run video-limit boundary. When the next offered step is forbidden, cross-domain, a different task, countdown/cooldown, no longer clearly same-flow reward, or too close to the max-step boundary to preserve return-confirmation steps, take the exit/return path such as '坚持退出' or accept automatic return to the task center, then complete when the target row/card shows countdown/cooldown or the app has returned with no further target action."

interface EarningsExecutionBridge {
    suspend fun startExecution(store: PrototypeStoreData, dispatch: EarningsDispatchRequest, now: Long): DispatchResult
}

object UnwiredEarningsExecutionBridge : EarningsExecutionBridge {
    override suspend fun startExecution(store: PrototypeStoreData, dispatch: EarningsDispatchRequest, now: Long): DispatchResult {
        return DispatchResult(
            updatedStore = store,
            executionId = dispatch.executionId,
            started = false,
            failureReason = "earnings execution bridge is not wired to the main runtime"
        )
    }
}

class MainRuntimeEarningsExecutionBridge(
    private val prototypeStore: PrototypeStore,
    private val executionFlowRunner: ExecutionFlowRunner
) : EarningsExecutionBridge {
    override suspend fun startExecution(
        store: PrototypeStoreData,
        dispatch: EarningsDispatchRequest,
        now: Long
    ): DispatchResult {
        val originalCurrentState = store.resolveCurrentState()
        val originalIntentSlice = store.currentIntentSlice
        val originalCurrentStateLegacy = store.currentState
        val originalSemanticRuntimePreferences = store.semanticRuntimePreferences
        val beforeEventCount = store.executionEvents.size
        val earningsTaskStore = store.copy().updateCurrentIntentSlice(
            currentState = originalCurrentState.copy(
                currentTaskRecord = buildEarningsTaskRecord(dispatch, now),
                currentTaskDraft = null,
                awaitingApproval = false,
                executionStartedAt = null,
                pendingExecutionRecovery = null,
                lastUpdatedAt = now
            )
        )
        val outcome = executionFlowRunner.run(earningsTaskStore)
        val executionEvents = outcome.updatedStore.executionEvents.drop(beforeEventCount)
        val terminalLifecycle = executionEvents.asReversed().firstOrNull { event ->
            event.lifecycleStatus == ExecutionLifecycleStatus.COMPLETED || event.lifecycleStatus == ExecutionLifecycleStatus.FAILED
        }?.lifecycleStatus
        val started = executionEvents.any { event ->
            event.lifecycleStatus == ExecutionLifecycleStatus.RUNNING ||
                event.lifecycleStatus == ExecutionLifecycleStatus.COMPLETED ||
                event.lifecycleStatus == ExecutionLifecycleStatus.FAILED
        }
        val terminalStatus = when (terminalLifecycle) {
            ExecutionLifecycleStatus.COMPLETED -> ExecutionLedgerStatus.COMPLETED
            ExecutionLifecycleStatus.FAILED -> ExecutionLedgerStatus.FAILED
            else -> if (started && outcome.updatedStore.currentExecutionRuntime == null) {
                ExecutionLedgerStatus.COMPLETED
            } else {
                null
            }
        }
        val completed = terminalStatus == ExecutionLedgerStatus.COMPLETED || terminalStatus == ExecutionLedgerStatus.FAILED
        val cleanedStore = outcome.updatedStore.restoreCurrentTaskState(
            originalCurrentState = originalCurrentState,
            originalIntentSlice = originalIntentSlice,
            originalCurrentStateLegacy = originalCurrentStateLegacy,
            originalSemanticRuntimePreferences = originalSemanticRuntimePreferences
        )
        val persistedStore = prototypeStore.replaceStore(cleanedStore)
        return DispatchResult(
            updatedStore = persistedStore,
            executionId = dispatch.executionId,
            started = started,
            completed = completed,
            terminalStatus = terminalStatus,
            terminalSummary = outcome.message,
            finishedAt = if (completed) System.currentTimeMillis() else null,
            failureReason = when {
                !started -> outcome.message
                terminalStatus == ExecutionLedgerStatus.FAILED -> outcome.message
                started && terminalStatus == null -> "main runtime did not return a terminal earnings result"
                else -> null
            }
        )
    }

    private fun PrototypeStoreData.restoreCurrentTaskState(
        originalCurrentState: LocalConversationState,
        originalIntentSlice: com.atombits.pocopaw.IntentSlice?,
        originalCurrentStateLegacy: LocalConversationState,
        originalSemanticRuntimePreferences: com.atombits.pocopaw.SemanticRuntimePreferences?
    ): PrototypeStoreData {
        currentIntentSlice = originalIntentSlice
        currentState = originalCurrentStateLegacy
        semanticRuntimePreferences = originalSemanticRuntimePreferences
        if (originalIntentSlice != null) {
            currentIntentSlice = originalIntentSlice.copy(currentState = originalCurrentState)
        }
        return this
    }
}

internal fun buildEarningsTaskRecord(dispatch: EarningsDispatchRequest, now: Long): TaskRecord {
    val app = dispatch.appId
    val perExecutionLimit = dispatch.perExecutionVideoLimit()
    val summary = buildString {
        append("Complete earnings task in ")
        append(app.displayName)
        append(": ")
        append(dispatch.displayName)
        if (!dispatch.windowLabel.isNullOrBlank()) {
            append(" (")
            append(dispatch.windowLabel)
            append(')')
        }
        append(". Stay inside ")
        append(app.displayName)
        append(" and stop after at most ")
        append(dispatch.maxSteps.coerceAtLeast(1))
        append(" execution steps.")
        append(' ')
        append("Before tapping any earnings task entry, verify that the visible task title in the same row/card contains this exact target task name: ")
        append(dispatch.displayName)
        append(". Do not choose a similar, adjacent, same-reward, or same-streak task; if the exact target name is not visible, keep looking or fail this execution.")
        append(' ')
        append(EARNINGS_DERIVED_REWARD_GREEDY_RULE)
        append(' ')
        append(EARNINGS_FORBIDDEN_CONTINUATION_RULE)
        append(' ')
        append(EARNINGS_RETURN_STEP_RESERVE_RULE)
        if (dispatch.category == TaskCategory.FILLER_REPEATABLE_DECAY) {
            append(' ')
            append(FILLER_RUN_COMPLETION_RULE)
            append(' ')
            append(FILLER_REWARD_SUCCESS_EXIT_RULE)
            append(' ')
            append(FILLER_AD_VIDEO_CHAIN_RULE)
        }
        if (perExecutionLimit != null) {
            append(' ')
            append("For this run, watch at most ")
            append(perExecutionLimit)
            append(" videos for the target continuous/specified-video activity, then stop this run and return to Pocopaw. If the target reward or check-in condition is confirmed earlier, stop immediately. Do not continue swiping or watching after the ")
            append(perExecutionLimit)
            append("th video is completed.")
        }
    }
    val targetLabelSuffix = perExecutionLimit?.let { limit -> " (watch up to $limit videos this run)" }.orEmpty()
    val domainSlots = linkedMapOf(
        "title" to dispatch.displayName,
        "channel" to app.displayName,
        "task_category" to dispatch.category.name,
        "derived_reward_policy" to EARNINGS_DERIVED_REWARD_GREEDY_RULE,
        "forbidden_continuation_policy" to EARNINGS_FORBIDDEN_CONTINUATION_RULE,
        "return_confirmation_step_reserve" to RETURN_CONFIRMATION_STEP_RESERVE,
        "return_confirmation_step_reserve_rule" to EARNINGS_RETURN_STEP_RESERVE_RULE
    )
    if (dispatch.category == TaskCategory.FILLER_REPEATABLE_DECAY) {
        domainSlots["run_completion_rule"] = FILLER_RUN_COMPLETION_RULE
        domainSlots["reward_success_exit_rule"] = FILLER_REWARD_SUCCESS_EXIT_RULE
        domainSlots["ad_video_chain_rule"] = FILLER_AD_VIDEO_CHAIN_RULE
        domainSlots["cooldown_handling"] = "Treat visible countdown or cooldown text on the target row/card as this run's completion evidence; do not tap or wait through it."
    }
    if (perExecutionLimit != null) {
        domainSlots["per_run_video_limit"] = perExecutionLimit.toString()
        domainSlots["run_stop_rule"] = "Watch or swipe at most $perExecutionLimit videos, then stop this run."
    }
    val commonConstraint = buildString {
        append("Visible task row/card must contain the exact target title before tapping.")
        append(' ')
        append("Within the current accepted earnings flow, prefer safe same-flow extra reward continuations, but never continue into forbidden scan-policy categories or cross-app/cross-domain paths.")
        append(' ')
        append("Reserve the final 2 to 3 execution steps for exit, return, and confirmation instead of optional reward continuation.")
        if (dispatch.category == TaskCategory.FILLER_REPEATABLE_DECAY) {
            append(' ')
            append("For filler repeatable tasks, stop this run at reward-claimed or cooldown-visible evidence.")
            append(' ')
            append("For ad/video filler tasks, use a visible reward-success exit control such as top-right '领取成功' once to close the reward/video screen before completing the run.")
            append(' ')
            append("If a post-reward continue/exit choice offers another safe same-flow rewarded ad/video or derived coin step, prefer continuing until reward, cooldown, return, explicit completion, return-step-reserve, max-step, per-run video-limit, or forbidden-continuation evidence closes the run.")
        }
    }
    return TaskRecord(
        taskId = "earnings:${dispatch.executionId}",
        sourceTurnId = "earnings:${dispatch.executionId}",
        phase = TaskPhase.EXECUTING,
        actionCode = ActionCode.NAVIGATE,
        targetType = TargetType.APP,
        targetKey = dispatch.taskKey,
        targetLabel = "${app.displayName}: ${dispatch.displayName}$targetLabelSuffix",
        structuredDetailSlots = TaskDetailSlots(
            common = linkedMapOf(
                CommonDetailSlotKey.CONSTRAINT to commonConstraint
            ),
            domain = domainSlots
        ),
        capabilityStack = CapabilityStack.APP,
        capabilityDomain = CapabilityDomain.ENTERTAINMENT,
        capabilityId = "app.${app.packageName}.open",
        reasonSummary = summary,
        createdAt = now,
        updatedAt = now
    )
}

private fun EarningsDispatchRequest.perExecutionVideoLimit(): Int? {
    return if (isContinuousVideoViewingTask()) WATCH_VIDEOS_PER_EXECUTION_LIMIT else null
}

private fun EarningsDispatchRequest.isContinuousVideoViewingTask(): Boolean {
    val text = listOfNotNull(
        taskKey,
        displayName,
        windowLabel,
        plannedWindowId
    ).joinToString(" ").lowercase(Locale.US)
    val hasVideoSignal = listOf(
        "视频",
        "看视频",
        "刷视频",
        "指定视频",
        "watch_video",
        "watch_videos",
        "watchvideo",
        "video",
        "videos"
    ).any(text::contains)
    if (!hasVideoSignal) {
        return false
    }
    val hasContinuousSignal = category == TaskCategory.STREAK_MULTI_DAY ||
        listOf(
            "连续",
            "指定",
            "30天",
            "30 天",
            "三十天",
            "30_day",
            "30_days",
            "thirty_day",
            "thirty_days",
            "daily_streak",
            "streak",
            "check_in",
            "checkin"
        ).any(text::contains)
    return hasContinuousSignal
}
