package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessLearningWritebackBridge
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext

data class ExecutionRuntimeStartResult(
    val accepted: Boolean,
    val boundaryPacket: TaskExecutionBoundaryPacket? = null,
    val executionResult: ExecutionResult? = null,
    val executionTrace: ExecutionTrace? = null,
    val rejectionReason: String? = null
)

data class ExecutionStartOutcome(
    val updatedStore: PrototypeStoreData,
    val started: Boolean,
    val message: String
)

data class ExecutionCompletionOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

object ExecutionRuntimeOrchestrator {

    fun start(
        candidateId: String?,
        boundaryPacket: TaskExecutionBoundaryPacket,
        now: Long = System.currentTimeMillis(),
        summary: String = "Execution engine started",
        routeInfo: String? = null,
        routeEntryType: ExecutionRouteEntryType? = null
    ): ExecutionRuntimeStartResult {
        val objectiveSummary = boundaryPacket.objectiveSummary
        if (objectiveSummary.isBlank()) {
            return ExecutionRuntimeStartResult(
                accepted = false,
                rejectionReason = UiStrings.resolve(
                    R.string.execution_draft_requires_objective,
                    "Execution draft must include a non-empty objective before runtime can begin."
                )
            )
        }

        val executionResult = ExecutionResult(
            candidateId = candidateId,
            selectedToolId = boundaryPacket.capabilityId,
            selectedProcessId = boundaryPacket.processId,
            lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
            summary = summary,
            occurredAt = now,
            routeInfo = routeInfo,
            routeEntryType = routeEntryType
        )
        val executionTrace = ExecutionTrace(
            candidateId = candidateId,
            selectedToolId = boundaryPacket.capabilityId,
            processId = boundaryPacket.processId,
            steps = listOf(
                ExecutionTraceStep(
                    stepType = "START",
                    groundingMode = if (boundaryPacket.processId.isNullOrBlank()) {
                        "SHORTCUT"
                    } else {
                        "PROCESS_REFERENCE"
                    },
                    expectedOutcome = "runtime_started",
                    fallbackPolicy = "STOP",
                    riskLevel = if (boundaryPacket.confirmRequirement == ConfirmRequirement.NONE) {
                        "LOW"
                    } else {
                        "MEDIUM"
                    },
                    note = boundaryPacket.reasonSummary?.takeIf { value -> value.isNotBlank() } ?: objectiveSummary
                )
            ),
            startedAt = now
        )

        return ExecutionRuntimeStartResult(
            accepted = true,
            boundaryPacket = boundaryPacket,
            executionResult = executionResult,
            executionTrace = executionTrace
        )
    }
}

fun applyExecutionRuntimeCompletion(
    store: PrototypeStoreData,
    lifecycleStatus: ExecutionLifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
    now: Long = System.currentTimeMillis()
): ExecutionCompletionOutcome {
    val runtimeState = store.resolveCurrentExecutionRuntime() ?: return ExecutionCompletionOutcome(
        updatedStore = store,
        applied = false,
        message = UiStrings.resolve(
            R.string.execution_no_running_to_complete,
            "No running execution is available to complete."
        )
    )
    val boundaryPacket = store.resolveExecutionBoundaryPacketFor(runtimeState)

    val summary = when (lifecycleStatus) {
        ExecutionLifecycleStatus.COMPLETED -> runtimeState.executionResult.summary.ifBlank {
            UiStrings.resolve(R.string.execution_completed_locally, "Execution completed locally")
        }
        ExecutionLifecycleStatus.FAILED -> runtimeState.executionResult.summary.ifBlank {
            UiStrings.resolve(R.string.execution_failed_locally, "Execution failed locally")
        }
        else -> runtimeState.executionResult.summary
    }

    val outcomeMessage = buildExecutionOutcomeConversationMessage(
        boundaryPacket = boundaryPacket,
        runtimeState = runtimeState,
        lifecycleStatus = lifecycleStatus,
        summary = summary
    )
    val systemIntentExecution = isSystemIntentExecution(runtimeState)

    val lifecycleAdjustedStore = if (lifecycleStatus == ExecutionLifecycleStatus.FAILED && !systemIntentExecution) {
        ProcessLearningWritebackBridge.applyGuidedRepeatFailure(
            store = store,
            now = now
        )
    } else {
        store
    }

    val systemIntentContinuationRecovery = if (
        systemIntentExecution &&
        (lifecycleStatus == ExecutionLifecycleStatus.COMPLETED || lifecycleStatus == ExecutionLifecycleStatus.FAILED)
    ) {
        buildSystemIntentContinuationRecovery(
            boundaryPacket = boundaryPacket,
            runtimeState = runtimeState,
            lifecycleStatus = lifecycleStatus,
            summary = summary,
            now = now
        )
    } else {
        null
    }

    val currentProcessRuntime = lifecycleAdjustedStore.resolveCurrentProcessRuntime()
    val terminalExecutionReturn = lifecycleStatus == ExecutionLifecycleStatus.COMPLETED ||
        lifecycleStatus == ExecutionLifecycleStatus.FAILED
    val nextCurrentState = lifecycleAdjustedStore.resolveCurrentState().copy(
        stage = ConversationStage.ACCUMULATING,
        workflowLane = WorkflowLane.PASSIVE,
        stageOwner = StageOwner.USER,
        lastPassiveUserTransitionIntent = PassiveUserTransitionIntent.SAME_TOPIC_ACCUMULATE,
        lastPassiveUserProgressSignal = UserProgressSignal.RETURN_TO_ACCUMULATING,
        currentPhase = CurrentPhase.ACCUMULATION,
        userRequestSemantic = UserRequestSemantic.START_ACCUMULATING,
        stageTransitionRecommendation = StageTransitionRecommendation.SHOULD_ENTER_ACCUMULATING,
        lastProactiveOpportunitySignal = null,
        currentSemanticIntentState = if (terminalExecutionReturn) null else lifecycleAdjustedStore.resolveCurrentState().currentSemanticIntentState,
        currentTaskDraft = if (terminalExecutionReturn) null else lifecycleAdjustedStore.resolveCurrentState().currentTaskDraft,
        currentTaskRecord = if (terminalExecutionReturn) null else lifecycleAdjustedStore.resolveCurrentState().currentTaskRecord,
        activeCandidateId = if (terminalExecutionReturn) null else lifecycleAdjustedStore.resolveCurrentState().activeCandidateId,
        currentDialogueCandidates = if (terminalExecutionReturn) emptyList() else lifecycleAdjustedStore.resolveCurrentState().currentDialogueCandidates,
        pendingProactiveDeliveryPlan = null,
        executionStartedAt = null,
        pendingExecutionRecovery = systemIntentContinuationRecovery,
        pendingProcessFeedbackDraft = null,
        lastUpdatedAt = now
    )

    val updatedStore = lifecycleAdjustedStore.copy(
        messages = lifecycleAdjustedStore.messages.toMutableList().apply {
            add(
                ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = outcomeMessage,
                    timestamp = now,
                    stage = ConversationStage.ACCUMULATING
                )
            )
        },
        latestCompletedProcessReviewContext = if (
            lifecycleStatus == ExecutionLifecycleStatus.COMPLETED &&
            !systemIntentExecution
        ) {
            ProcessReviewContext(
                processAssetEntryId = currentProcessRuntime?.matchedReadyAssetId,
                processAssetName = currentProcessRuntime?.matchedReadyAssetName,
                finalUserSummary = summary,
                verificationSummary = runtimeState.executionResult.routeInfo,
                reviewedAt = now,
                taskId = runtimeState.taskId,
                routeDecisionHistory = runtimeState.routeDecisionHistorySnapshot()
            )
        } else {
            null
        },
        pendingProcessRecoveryContext = if (lifecycleStatus == ExecutionLifecycleStatus.FAILED && !systemIntentExecution) {
            ProcessRecoveryContext(
                processAssetEntryId = currentProcessRuntime?.matchedReadyAssetId,
                processAssetName = currentProcessRuntime?.matchedReadyAssetName,
                objective = boundaryPacket?.objectiveSummary ?: runtimeState.executionResult.summary,
                blockedContext = summary,
                recoveryAction = "request_human_guidance",
                selectedToolId = runtimeState.executionResult.selectedToolId
                    ?: runtimeState.executionTrace.selectedToolId
                    ?: runtimeState.capabilityId
                    ?: boundaryPacket?.capabilityId,
                selectedProcessId = runtimeState.executionResult.selectedProcessId
                    ?: runtimeState.executionTrace.processId
                    ?: runtimeState.processId
                    ?: boundaryPacket?.processId,
                sourceTraceId = runtimeState.executionTrace.traceId,
                retryBudget = currentProcessRuntime?.retryBudget ?: 0,
                createdAt = now,
                taskId = runtimeState.taskId,
                routeDecisionHistory = runtimeState.routeDecisionHistorySnapshot()
            )
        } else {
            null
        },
        currentState = nextCurrentState
    )
    updatedStore.updateCurrentExecutionSession(
        preparedExecutionStart = null,
        executionRuntime = null,
        processReuseContext = null,
        processRuntime = null,
        updatedAt = now
    )
    updatedStore.syncConversationSliceIfPresent()
    updatedStore.syncIntentSliceIfPresent()

    return ExecutionCompletionOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = summary
    )
}

private fun buildExecutionOutcomeConversationMessage(
    boundaryPacket: TaskExecutionBoundaryPacket?,
    runtimeState: ExecutionRuntimeState,
    lifecycleStatus: ExecutionLifecycleStatus,
    summary: String
): String {
    val objective = boundaryPacket?.objectiveSummary?.takeIf { value -> value.isNotBlank() }
        ?: runtimeState.executionResult.summary.ifBlank {
            UiStrings.resolve(R.string.execution_current_task_fallback, "the current task")
        }
    val systemIntentExecution = isSystemIntentExecution(runtimeState)
    return when (lifecycleStatus) {
        ExecutionLifecycleStatus.COMPLETED -> {
            if (systemIntentExecution) {
                UiStrings.resolve(
                    R.string.execution_completed_system_intent_message,
                    "Execution completed for %1\$s. %2\$s is back in chat. Share what to do next and %2\$s will replan with your guidance.",
                    objective,
                    currentAssistantDisplayName()
                )
            } else {
                UiStrings.resolve(
                    R.string.execution_completed_message,
                    "Execution completed for %1\$s. Review the flow with thumbs up/down or keep chatting to refine it.",
                    objective
                )
            }
        }

        ExecutionLifecycleStatus.FAILED -> {
            if (systemIntentExecution) {
                UiStrings.resolve(
                    R.string.execution_failed_system_intent_message,
                    "Execution failed for %1\$s. %2\$s is back in chat. Describe how to adjust the flow and %2\$s will replan with your guidance. Summary: %3\$s",
                    objective,
                    currentAssistantDisplayName(),
                    summary
                )
            } else {
                UiStrings.resolve(
                    R.string.execution_failed_message,
                    "Execution failed for %1\$s. Describe how to adjust the flow and %2\$s will retry with your guidance. Summary: %3\$s",
                    objective,
                    currentAssistantDisplayName(),
                    summary
                )
            }
        }

        else -> summary
    }
}

private fun buildSystemIntentContinuationRecovery(
    boundaryPacket: TaskExecutionBoundaryPacket?,
    runtimeState: ExecutionRuntimeState,
    lifecycleStatus: ExecutionLifecycleStatus,
    summary: String,
    now: Long
): ProcessRecoveryContext {
    val recoveryAction = if (lifecycleStatus == ExecutionLifecycleStatus.FAILED) {
        "request_human_guidance_after_failure"
    } else {
        "request_next_step_guidance"
    }
    return ProcessRecoveryContext(
        processAssetEntryId = null,
        processAssetName = null,
        objective = boundaryPacket?.objectiveSummary ?: runtimeState.executionResult.summary,
        blockedContext = summary,
        recoveryAction = recoveryAction,
        selectedToolId = runtimeState.executionResult.selectedToolId
            ?: runtimeState.executionTrace.selectedToolId
            ?: runtimeState.capabilityId
            ?: boundaryPacket?.capabilityId,
        selectedProcessId = runtimeState.executionResult.selectedProcessId
            ?: runtimeState.executionTrace.processId
            ?: runtimeState.processId
            ?: boundaryPacket?.processId,
        sourceTraceId = runtimeState.executionTrace.traceId,
        retryBudget = 0,
        createdAt = now,
        awaitingUserGuidance = true,
        guidanceReceivedAt = null,
        taskId = runtimeState.taskId,
        routeDecisionHistory = runtimeState.routeDecisionHistorySnapshot()
    )
}