package com.atombits.pocopaw

object RuntimeModuleSwitches {
    const val safetyBoundaryEnabled: Boolean = true
    @Volatile
    var proactiveEngineEnabled: Boolean = false

    fun clearProactiveState(
        state: LocalConversationState,
        now: Long = System.currentTimeMillis()
    ): LocalConversationState {
        return state.copy(
            workflowLane = WorkflowLane.PASSIVE,
            stageOwner = StageOwner.USER,
            lastProactiveOpportunitySignal = null,
            pendingProactiveDeliveryPlan = null,
            lastDeliveredProactivePlanFingerprint = null,
            lastDeliveredProactivePlanAt = null,
            proactiveDeliveryCooldownUntil = null,
            lastUpdatedAt = now
        )
    }
}