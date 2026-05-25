package com.atombits.pocopaw

internal fun resolveStoreAwareExecutionBoundaryPacket(
    store: PrototypeStoreData,
    currentState: LocalConversationState = store.resolveCurrentState()
): TaskExecutionBoundaryPacket? {
    val currentTaskRecord = currentState.currentTaskRecord ?: return store.resolveCurrentExecutionBoundaryPacket()
    val preparedStart = store.resolveCurrentPreparedExecutionStart()
    val runtimeState = store.resolveCurrentExecutionRuntime()
    val samePreparedTask = preparedStart?.taskId == currentTaskRecord.taskId &&
        preparedStart.taskUpdatedAt == currentTaskRecord.updatedAt
    val sameRuntimeTask = runtimeState?.taskId == currentTaskRecord.taskId
    return currentTaskRecord.toTaskExecutionBoundaryPacket(
        verificationChecks = when {
            sameRuntimeTask -> runtimeState?.verificationChecks.orEmpty()
            samePreparedTask -> preparedStart?.verificationChecks.orEmpty()
            else -> emptyList()
        }
    )
}
