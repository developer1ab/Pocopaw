package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessLearningWritebackBridge
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext

const val EXECUTION_RECOVERY_TIMEOUT_MS: Long = 30_000L

data class ExecutionRecoveryTimeoutOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

fun markPendingProcessRecoveryGuidanceReceived(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis()
): PrototypeStoreData {
    val recovery = store.pendingProcessRecoveryContext ?: return store
    if (!recovery.awaitingUserGuidance) {
        return store
    }
    val nextCurrentState = store.resolveCurrentState().copy(lastUpdatedAt = now)
    return store.copy(
        currentState = nextCurrentState,
        pendingProcessRecoveryContext = recovery.copy(
            awaitingUserGuidance = false,
            guidanceReceivedAt = now
        )
    ).syncIntentSliceIfPresent()
}

fun expirePendingProcessRecoveryIfNeeded(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis()
): ExecutionRecoveryTimeoutOutcome {
    val recovery = store.pendingProcessRecoveryContext ?: return ExecutionRecoveryTimeoutOutcome(
        updatedStore = store,
        applied = false,
        message = UiStrings.resolve(
            R.string.execution_recovery_none_pending,
            "No pending execution recovery is waiting for guidance."
        )
    )
    if (!recovery.awaitingUserGuidance) {
        return ExecutionRecoveryTimeoutOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.execution_recovery_no_longer_waiting,
                "Execution recovery is no longer waiting for guidance."
            )
        )
    }
    val expiresAt = recovery.createdAt + EXECUTION_RECOVERY_TIMEOUT_MS
    if (now < expiresAt) {
        return ExecutionRecoveryTimeoutOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.execution_recovery_within_window,
                "Execution recovery is still within the guidance window."
            )
        )
    }

    val lifecycleAdjustedStore = ProcessLearningWritebackBridge.applyRecoveryTimeoutFailure(
        store = store,
        now = now
    )
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
        pendingProactiveDeliveryPlan = null,
        executionStartedAt = null,
        pendingProcessFeedbackDraft = null,
        lastUpdatedAt = now
    )
    val updatedStore = lifecycleAdjustedStore.copy(
        messages = store.messages.toMutableList().apply {
            add(
                ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = buildExecutionRecoveryTimeoutMessage(recovery),
                    timestamp = now,
                    stage = ConversationStage.ACCUMULATING
                )
            )
        },
        currentState = nextCurrentState,
        pendingProcessRecoveryContext = null
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
    updatedStore.syncMemorySliceIfPresent()

    return ExecutionRecoveryTimeoutOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = UiStrings.resolve(
            R.string.execution_recovery_timeout_outcome,
            "No guidance was received within 30 seconds. Returned to idle and archived the failed flow."
        )
    )
}

private fun buildExecutionRecoveryTimeoutMessage(recovery: ProcessRecoveryContext): String {
    val objective = recovery.objective.ifBlank {
        UiStrings.resolve(R.string.execution_current_task_fallback, "the current task")
    }
    return UiStrings.resolve(
        R.string.execution_recovery_timeout_system_message,
        "Execution for %1\$s did not receive new guidance within 30 seconds. %2\$s has returned to idle and archived the failed flow under failed flows in Settings.",
        objective,
        currentAssistantDisplayName()
    )
}