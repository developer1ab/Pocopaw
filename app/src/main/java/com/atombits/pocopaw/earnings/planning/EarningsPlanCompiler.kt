package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.earnings.EarningsPlanningState
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.EarningsManualTaskBlacklist
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.ImportantOccurrence
import com.atombits.pocopaw.earnings.PlannerDiagnosticItem
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity
import java.util.Locale
import java.util.UUID

interface EarningsPlanCompiler {
    suspend fun compile(
        scanState: FourAppScanState,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): EarningsPlanningState
}

class DefaultEarningsPlanCompiler(
    private val planAdviceProvider: EarningsPlanAdviceProvider = ModelEarningsPlanAdviceProvider(),
    private val importantTaskScheduler: ImportantTaskScheduler = DefaultImportantTaskScheduler,
    private val fillerRotationController: FillerRotationController = DefaultFillerRotationController
) : EarningsPlanCompiler {
    override suspend fun compile(
        scanState: FourAppScanState,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): EarningsPlanningState {
        val opportunities = scanState.acceptedOpportunities
        val admissionResults = opportunities.map { opportunity -> opportunity to evaluatePlanningAdmission(opportunity) }
        val plannableOpportunities = admissionResults
            .filter { (_, admission) -> admission.accepted }
            .map { (opportunity, _) -> opportunity }
        val admissionDiagnostics = admissionResults
            .filterNot { (_, admission) -> admission.accepted }
            .map { (opportunity, admission) ->
                PlannerDiagnosticItem(
                    code = "PLANNING_OPPORTUNITY_REJECTED",
                    message = "${opportunity.taskKey}: ${admission.reason}",
                    severity = "INFO"
                )
            }
        val localDiagnostics = admissionDiagnostics + buildLocalDiagnostics(plannableOpportunities)
        if (plannableOpportunities.isEmpty()) {
            return emptyPlanningState(now, localDiagnostics)
        }

        val planningScanState = scanState.copy(acceptedOpportunities = plannableOpportunities)
        val adviceResult = planAdviceProvider.requestAdvice(planningScanState, executionLedgerState, rewardLedgerState, now)
        val advice = adviceResult.advice ?: return emptyPlanningState(
            now = now,
            diagnostics = localDiagnostics + adviceResult.diagnostics + PlannerDiagnosticItem(
                code = "MODEL_PLANNER_ADVICE_REQUIRED",
                message = "Plan was not compiled because model scheduling advice is required by design and was unavailable.",
                severity = "ERROR"
            )
        )

        val expectedImportantSchedule = importantTaskScheduler.buildSchedule(plannableOpportunities, executionLedgerState, rewardLedgerState, now)
        val coverageDiagnostics = buildAdviceCoverageDiagnostics(plannableOpportunities, expectedImportantSchedule, advice)
        val importantSchedule = importantTaskScheduler.buildSchedule(plannableOpportunities, executionLedgerState, rewardLedgerState, now, advice)
        val policy = fillerRotationController.buildPolicy(plannableOpportunities, executionLedgerState, rewardLedgerState, now, advice)
        val fillerPool = buildFillerCandidatePool(plannableOpportunities, executionLedgerState, rewardLedgerState, now, advice)
        val diagnostics = localDiagnostics + adviceResult.diagnostics + coverageDiagnostics + buildAdviceRejectionDiagnostics(advice)
        return EarningsPlanningState(
            planId = UUID.randomUUID().toString(),
            compiledAt = now,
            importantScheduleQueue = importantSchedule,
            fillerCandidatePool = fillerPool,
            fillerRotationPolicy = policy,
            nextImportantWakeAt = importantSchedule.filter { occurrence -> occurrence.plannedRunAt >= now }.minOfOrNull { occurrence -> occurrence.plannedRunAt },
            nextFillerEligibleAt = fillerPool.mapNotNull { candidate -> candidate.nextEligibleAt }.minOrNull(),
            plannerDiagnostics = diagnostics
        )
    }

    private fun emptyPlanningState(now: Long, diagnostics: List<PlannerDiagnosticItem>): EarningsPlanningState {
        return EarningsPlanningState(
            planId = UUID.randomUUID().toString(),
            compiledAt = now,
            plannerDiagnostics = diagnostics
        )
    }

    private fun buildLocalDiagnostics(opportunities: List<TaskOpportunity>): List<PlannerDiagnosticItem> {
        return buildList {
            if (opportunities.isEmpty()) {
                add(PlannerDiagnosticItem("NO_ACCEPTED_OPPORTUNITIES", "No accepted earnings opportunities are available."))
            }
            opportunities
                .filter { opportunity -> opportunity.category == TaskCategory.DAILY_WINDOWED_REPEAT && opportunity.timeWindowHints.isEmpty() }
                .forEach { opportunity ->
                    add(PlannerDiagnosticItem("WINDOWED_TASK_WITHOUT_WINDOW", "${opportunity.taskKey} has no stable window and was not expanded."))
                }
        }
    }

    private fun buildFillerCandidatePool(
        opportunities: List<TaskOpportunity>,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long,
        advice: EarningsPlanAdvice
    ): List<FillerCandidateRecord> {
        val advisedAppRank = advice.fillerAdvice.candidateAppOrder
            .mapIndexed { index, appId -> appId to index }
            .toMap()
        val advisedTaskRank = advice.fillerAdvice.candidateTaskOrder
            .mapIndexed { index, taskAdvice -> taskAdvice.appId.stableKey + "|" + taskAdvice.taskKey to (taskAdvice.priorityRank ?: index + 1) }
            .toMap()
        return opportunities
            .filter { opportunity -> opportunity.category == TaskCategory.FILLER_REPEATABLE_DECAY }
            .map { opportunity ->
                FillerCandidateRecord(
                    appId = opportunity.appId,
                    taskKey = opportunity.taskKey,
                    displayName = opportunity.displayName,
                    nextEligibleAt = resolveFillerNextEligibleAt(opportunity, executionLedgerState, rewardLedgerState, now),
                    recentRewardPerMinute = rewardLedgerState.recentFillerRewardVelocityByApp[opportunity.appId.stableKey],
                    recentFailureRate = resolveRecentFailureRate(opportunity, executionLedgerState),
                    estimatedDurationSeconds = opportunity.estimatedDurationSeconds
                )
            }
            .sortedWith(
                compareBy<FillerCandidateRecord> { candidate -> advisedAppRank[candidate.appId] ?: Int.MAX_VALUE }
                    .thenBy { candidate -> advisedTaskRank[candidate.appId.stableKey + "|" + candidate.taskKey] ?: Int.MAX_VALUE }
                    .thenBy { candidate -> candidate.displayName }
            )
    }

    private fun resolveFillerNextEligibleAt(
        opportunity: TaskOpportunity,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): Long {
        val cooldownMs = opportunity.cooldownHintMinutes?.takeIf { minutes -> minutes > 0 }?.toLong()?.times(60_000L)
            ?: return now
        val lastExecutionAt = executionLedgerState.entries
            .filter { entry -> entry.appId == opportunity.appId && entry.taskKey == opportunity.taskKey }
            .map { entry -> entry.finishedAt ?: entry.startedAt }
            .maxOrNull()
        val lastRewardAt = rewardLedgerState.entries
            .filter { entry -> entry.appId == opportunity.appId && entry.taskKey == opportunity.taskKey }
            .maxOfOrNull { entry -> entry.finishedAt }
        val lastFinishedAt = listOfNotNull(lastExecutionAt, lastRewardAt).maxOrNull() ?: return now
        return (lastFinishedAt + cooldownMs).coerceAtLeast(now)
    }

    private fun resolveRecentFailureRate(
        opportunity: TaskOpportunity,
        executionLedgerState: ExecutionLedgerState
    ): Double? {
        val recentTerminalEntries = executionLedgerState.entries
            .filter { entry -> entry.appId == opportunity.appId && entry.taskKey == opportunity.taskKey }
            .filter { entry -> entry.status != ExecutionLedgerStatus.STARTED }
            .takeLast(8)
        if (recentTerminalEntries.isEmpty()) {
            return null
        }
        val failures = recentTerminalEntries.count { entry -> entry.status == ExecutionLedgerStatus.FAILED }
        return failures.toDouble() / recentTerminalEntries.size.toDouble()
    }

    private fun buildAdviceCoverageDiagnostics(
        opportunities: List<TaskOpportunity>,
        expectedImportantSchedule: List<ImportantOccurrence>,
        advice: EarningsPlanAdvice
    ): List<PlannerDiagnosticItem> {
        val advisedFillerKeys = advice.fillerAdvice.candidateTaskOrder
            .map { item -> item.appId.stableKey + "|" + item.taskKey }
            .toSet()
        val expectedFillerKeys = opportunities
            .filter { opportunity -> opportunity.category == TaskCategory.FILLER_REPEATABLE_DECAY }
            .map { opportunity -> opportunity.appId.stableKey + "|" + opportunity.taskKey }
            .toSet()
        return buildList {
            expectedImportantSchedule
                .filterNot { occurrence -> advice.importantAdvice.any { item -> item.matches(occurrence) } }
                .forEach { occurrence ->
                    add(
                        PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_MISSING_IMPORTANT_ADVICE",
                            message = "Model advice omitted ${occurrence.taskKey} (${occurrence.plannedWindowId}); local authority scheduled it from accepted scan state.",
                            severity = "WARN"
                        )
                    )
                }
            expectedFillerKeys
                .filterNot { key -> advisedFillerKeys.contains(key) }
                .forEach { key ->
                    add(
                        PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_MISSING_FILLER_ADVICE",
                            message = "Model advice omitted filler $key; local authority kept it in the accepted filler pool.",
                            severity = "WARN"
                        )
                    )
                }
        }
    }

    private fun buildAdviceRejectionDiagnostics(advice: EarningsPlanAdvice): List<PlannerDiagnosticItem> {
        return advice.rejectedAdvice.map { rejected ->
            PlannerDiagnosticItem(
                code = "MODEL_PLANNER_REJECTED_ADVICE",
                message = listOfNotNull(rejected.appId?.stableKey, rejected.taskKey, rejected.reason).joinToString(": "),
                severity = "INFO"
            )
        }
    }

    private fun EarningsImportantTaskAdvice.matches(occurrence: ImportantOccurrence): Boolean {
        if (appId != occurrence.appId || taskKey != occurrence.taskKey) {
            return false
        }
        return plannedWindowId == null || plannedWindowId == occurrence.plannedWindowId
    }

    private fun evaluatePlanningAdmission(opportunity: TaskOpportunity): PlanningAdmissionDecision {
        val action = opportunity.actionText.orEmpty().trim()
        val text = listOfNotNull(
            opportunity.displayName,
            opportunity.subtitle,
            opportunity.rewardText,
            opportunity.actionText,
            opportunity.repeatRule,
            opportunity.streakRule,
            opportunity.rawTextSnapshot
        ).joinToString(" ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.US)
        EarningsManualTaskBlacklist.matchingPhrase(text)?.let { matchedPhrase ->
            return PlanningAdmissionDecision(false, "manual task blacklist: $matchedPhrase")
        }
        if (action.containsAny(planningCompletedActionTerms)) {
            return PlanningAdmissionDecision(false, "already completed or not available today")
        }
        if (action.containsAny(planningDownloadActionTerms) || text.containsAny(planningDownloadActionTerms)) {
            return PlanningAdmissionDecision(false, "download or install task is not auto-executable")
        }
        if (text.containsAny(planningPurchaseTerms)) {
            return PlanningAdmissionDecision(false, "purchase, order, coupon, subsidy, or cash task is not auto-executable")
        }
        if (text.containsAny(planningSocialOrPrivacyTerms)) {
            return PlanningAdmissionDecision(false, "social/contact task is not auto-executable")
        }
        return PlanningAdmissionDecision(true)
    }

    private data class PlanningAdmissionDecision(
        val accepted: Boolean,
        val reason: String = "accepted"
    )
}

private val planningCompletedActionTerms = listOf("已完成", "已打卡", "明日再来", "明天再来", "明日可领", "明天领")
private val planningDownloadActionTerms = listOf("去下载", "下载", "安装")
private val planningPurchaseTerms = listOf("下单", "订单", "支付", "付款", "购买", "消费", "提现", "现金", "红包", "券", "补贴", "车主", "地铁", "团购")
private val planningSocialOrPrivacyTerms = listOf("通讯录", "联系人", "好友", "朋友", "邀请", "送金币")

private fun String.containsAny(terms: List<String>): Boolean {
    return terms.any { term -> contains(term, ignoreCase = true) }
}
