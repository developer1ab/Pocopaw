package com.atombits.pocopaw

import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.runtime.PreparedExecutionStart
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState

internal fun PrototypeStoreData.resolveCurrentExecutionSession(): ExecutionSession? {
    currentExecutionSlice?.executionSession?.let { return it }
    if (currentExecutionRuntime == null && currentProcessRuntime == null) {
        return null
    }
    val currentTaskRecord = resolveCurrentState().currentTaskRecord
    val synthesizedBoundaryPacket = resolveExecutionBoundaryPacketFor(
        executionRuntime = currentExecutionRuntime,
        taskRecord = currentTaskRecord
    ) ?: currentTaskRecord?.toTaskExecutionBoundaryPacket()
    return ExecutionSession(
        executionRuntime = currentExecutionRuntime,
        boundaryPacket = synthesizedBoundaryPacket,
        processRuntime = currentProcessRuntime,
        updatedAt = currentProcessRuntime?.updatedAt
            ?: currentExecutionRuntime?.startedAt
            ?: System.currentTimeMillis()
    )
}

internal fun PrototypeStoreData.resolveCurrentExecutionRuntime(): ExecutionRuntimeState? {
    return currentExecutionSlice?.executionSession?.executionRuntime ?: currentExecutionRuntime
}

internal fun PrototypeStoreData.resolveCurrentProcessReuseContext(): CandidateProcessReferenceContext? {
    return currentExecutionSlice?.executionSession?.processReuseContext
}

internal fun PrototypeStoreData.resolveCurrentProcessRuntime(): ProcessRuntimeState? {
    return currentExecutionSlice?.executionSession?.processRuntime ?: currentProcessRuntime
}

internal fun PrototypeStoreData.resolveExecutionBoundaryPacketSnapshot(
    preparedExecutionStart: PreparedExecutionStart? = resolveCurrentPreparedExecutionStart(),
    executionRuntime: ExecutionRuntimeState? = resolveCurrentExecutionRuntime(),
    taskRecord: TaskRecord? = resolveCurrentState().currentTaskRecord,
    fallbackBoundaryPacket: TaskExecutionBoundaryPacket? = currentExecutionSlice?.executionSession?.boundaryPacket
): TaskExecutionBoundaryPacket? {
    return fallbackBoundaryPacket
        ?: resolveExecutionBoundaryPacketFor(
            executionRuntime = executionRuntime,
            taskRecord = taskRecord
        )
        ?: resolveExecutionBoundaryPacketFor(
            preparedExecutionStart = preparedExecutionStart,
            taskRecord = taskRecord
        )
        ?: taskRecord?.toTaskExecutionBoundaryPacket()
}

private fun PreparedExecutionStart.safeVerificationChecks(): List<ExecutionCheck> {
    return runCatching { verificationChecks }.getOrNull().orEmpty()
}

private fun ExecutionRuntimeState.safeVerificationChecks(): List<ExecutionCheck> {
    return runCatching { verificationChecks }.getOrNull().orEmpty()
}

internal fun PrototypeStoreData.resolveExecutionBoundaryPacketFor(
    preparedExecutionStart: PreparedExecutionStart?,
    taskRecord: TaskRecord? = resolveCurrentState().currentTaskRecord
): TaskExecutionBoundaryPacket? {
    val currentTaskRecord = taskRecord ?: return null
    val preparedStart = preparedExecutionStart ?: return null
    if (preparedStart.taskId != currentTaskRecord.taskId || preparedStart.taskUpdatedAt != currentTaskRecord.updatedAt) {
        return null
    }
    return currentTaskRecord.toTaskExecutionBoundaryPacket(
        verificationChecks = preparedStart.safeVerificationChecks()
    )
}

internal fun PrototypeStoreData.resolveExecutionBoundaryPacketFor(
    executionRuntime: ExecutionRuntimeState?,
    taskRecord: TaskRecord? = resolveCurrentState().currentTaskRecord
): TaskExecutionBoundaryPacket? {
    val currentTaskRecord = taskRecord ?: return null
    val runtimeState = executionRuntime ?: return null
    if (runtimeState.taskId != currentTaskRecord.taskId) {
        return null
    }
    return currentTaskRecord.toTaskExecutionBoundaryPacket(
        verificationChecks = runtimeState.safeVerificationChecks()
    )
}

private fun parsePreparedExecutionRouteInfo(routeInfo: String?): Map<String, String> {
    return routeInfo.orEmpty()
        .split("|")
        .mapNotNull { rawSegment ->
            val segment = rawSegment.trim()
            val separatorIndex = segment.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex >= segment.lastIndex) {
                return@mapNotNull null
            }
            val key = segment.substring(0, separatorIndex).trim()
            val value = segment.substring(separatorIndex + 1).trim()
            if (key.isBlank() || value.isBlank()) {
                null
            } else {
                key to value
            }
        }
        .toMap()
}

internal fun PrototypeStoreData.resolveCurrentExecutionBoundaryPacket(): TaskExecutionBoundaryPacket? {
    return resolveExecutionBoundaryPacketSnapshot()
}

internal fun PrototypeStoreData.resolveExecutionEvents(): MutableList<ExecutionEvent> {
    return currentExecutionSlice?.executionEvents ?: executionEvents
}

internal fun PrototypeStoreData.resolveExecutionTraces(): MutableList<ExecutionTrace> {
    return currentExecutionSlice?.executionTraces ?: executionTraces
}

internal fun PrototypeStoreData.resolveLatestCompletedProcessReviewContext(): ProcessReviewContext? {
    return currentExecutionSlice?.latestCompletedProcessReviewContext ?: latestCompletedProcessReviewContext
}

internal fun PrototypeStoreData.resolvePendingProcessRecoveryContext(): ProcessRecoveryContext? {
    return currentExecutionSlice?.pendingProcessRecoveryContext ?: pendingProcessRecoveryContext
}

internal fun PrototypeStoreData.resolveCurrentPreparedExecutionStart(): PreparedExecutionStart? {
    return currentExecutionSlice?.preparedExecutionStart
}

internal fun PrototypeStoreData.updateCurrentExecutionSession(
    preparedExecutionStart: PreparedExecutionStart? = resolveCurrentPreparedExecutionStart(),
    executionRuntime: ExecutionRuntimeState? = resolveCurrentExecutionRuntime(),
    boundaryPacket: TaskExecutionBoundaryPacket? = resolveExecutionBoundaryPacketSnapshot(
        preparedExecutionStart = preparedExecutionStart,
        executionRuntime = executionRuntime
    ),
    processReuseContext: CandidateProcessReferenceContext? = resolveCurrentProcessReuseContext(),
    processRuntime: ProcessRuntimeState? = resolveCurrentProcessRuntime(),
    updatedAt: Long = System.currentTimeMillis()
): PrototypeStoreData {
    val executionSession = if (
        executionRuntime == null &&
        boundaryPacket == null &&
        processReuseContext == null &&
        processRuntime == null
    ) {
        null
    } else {
        ExecutionSession(
            executionRuntime = executionRuntime,
            boundaryPacket = boundaryPacket,
            processReuseContext = processReuseContext,
            processRuntime = processRuntime,
            updatedAt = updatedAt
        )
    }
    return updateCurrentExecutionSlice(
        preparedExecutionStart = preparedExecutionStart,
        executionSession = executionSession,
        executionEvents = this.executionEvents.toMutableList(),
        executionTraces = this.executionTraces.toMutableList(),
        latestCompletedProcessReviewContext = latestCompletedProcessReviewContext,
        pendingProcessRecoveryContext = pendingProcessRecoveryContext
    )
}

internal fun PrototypeStoreData.updateCurrentExecutionSlice(
    preparedExecutionStart: PreparedExecutionStart? = resolveCurrentPreparedExecutionStart(),
    executionSession: ExecutionSession? = resolveCurrentExecutionSession(),
    executionEvents: MutableList<ExecutionEvent> = resolveExecutionEvents().toMutableList(),
    executionTraces: MutableList<ExecutionTrace> = resolveExecutionTraces().toMutableList(),
    latestCompletedProcessReviewContext: ProcessReviewContext? = resolveLatestCompletedProcessReviewContext(),
    pendingProcessRecoveryContext: ProcessRecoveryContext? = resolvePendingProcessRecoveryContext()
): PrototypeStoreData {
    currentExecutionSlice = if (
        preparedExecutionStart == null &&
        executionSession == null &&
        executionEvents.isEmpty() &&
        executionTraces.isEmpty() &&
        latestCompletedProcessReviewContext == null &&
        pendingProcessRecoveryContext == null
    ) {
        null
    } else {
        ExecutionSlice(
            preparedExecutionStart = preparedExecutionStart,
            executionSession = executionSession,
            executionEvents = executionEvents,
            executionTraces = executionTraces,
            latestCompletedProcessReviewContext = latestCompletedProcessReviewContext,
            pendingProcessRecoveryContext = pendingProcessRecoveryContext
        )
    }
    currentExecutionRuntime = null
    currentProcessRuntime = null
    this.executionEvents.clear()
    this.executionEvents.addAll(executionEvents)
    this.executionTraces.clear()
    this.executionTraces.addAll(executionTraces)
    this.latestCompletedProcessReviewContext = latestCompletedProcessReviewContext
    this.pendingProcessRecoveryContext = pendingProcessRecoveryContext
    return this
}

internal fun PrototypeStoreData.syncExecutionSliceFromLegacy(
    preparedExecutionStart: PreparedExecutionStart? = resolveCurrentPreparedExecutionStart()
): PrototypeStoreData {
    return updateCurrentExecutionSlice(
        preparedExecutionStart = preparedExecutionStart,
        executionSession = resolveCurrentExecutionSession(),
        executionEvents = resolveExecutionEvents().toMutableList(),
        executionTraces = resolveExecutionTraces().toMutableList(),
        latestCompletedProcessReviewContext = resolveLatestCompletedProcessReviewContext(),
        pendingProcessRecoveryContext = resolvePendingProcessRecoveryContext()
    )
}