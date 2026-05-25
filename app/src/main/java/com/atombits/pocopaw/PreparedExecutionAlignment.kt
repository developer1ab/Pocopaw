package com.atombits.pocopaw

import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime
import java.util.Locale

internal fun retainPreparedProcessReuseContext(
    previousContext: CandidateProcessReferenceContext?,
    previousBoundaryPacket: TaskExecutionBoundaryPacket?,
    nextBoundaryPacket: TaskExecutionBoundaryPacket?,
    keepsExecutionPreparation: Boolean
): CandidateProcessReferenceContext? {
    val context = previousContext ?: return null
    if (!keepsExecutionPreparation || nextBoundaryPacket == null) {
        return null
    }
    if (!samePreparedExecutionAuthority(previousBoundaryPacket, nextBoundaryPacket)) {
        return null
    }
    return context.takeIf { reuseContext -> reuseContextMatchesPreparedBoundaryPacket(reuseContext, nextBoundaryPacket) }
}

internal fun resolvePreparedProcessReuseContextForStart(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    now: Long = System.currentTimeMillis()
): CandidateProcessReferenceContext? {
    val retainedContext = retainPreparedProcessReuseContext(
        previousContext = store.resolveCurrentProcessReuseContext(),
        previousBoundaryPacket = store.resolveCurrentExecutionBoundaryPacket(),
        nextBoundaryPacket = boundaryPacket,
        keepsExecutionPreparation = boundaryPacket != null
    )
    if (retainedContext != null || boundaryPacket == null) {
        return retainedContext
    }
    return ProcessReuseRuntime.resolve(
        store = store,
        activeCandidate = store.resolveTaskFirstCandidate(),
        boundaryPacket = boundaryPacket,
        previousContext = null,
        now = now
    )?.candidateContext
}

internal fun samePreparedExecutionAuthority(
    previousBoundaryPacket: TaskExecutionBoundaryPacket?,
    nextBoundaryPacket: TaskExecutionBoundaryPacket?
): Boolean {
    val previous = previousBoundaryPacket ?: return false
    val next = nextBoundaryPacket ?: return false
    return previous.taskId == next.taskId && previous.taskUpdatedAt == next.taskUpdatedAt
}

internal fun reuseContextMatchesPreparedBoundaryPacket(
    context: CandidateProcessReferenceContext,
    boundaryPacket: TaskExecutionBoundaryPacket
): Boolean {
    val contextAppScope = context.taskIntent.appScope
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { value -> value.isNotBlank() }
    val contextProcess = context.taskIntent.primaryProcess
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { value -> value.isNotBlank() }
    val boundaryPacketAppScope = canonicalPreparedAppScope(boundaryPacket)
    val boundaryPacketProcess = canonicalPreparedProcessId(boundaryPacket.processId)
    return (contextAppScope == null || boundaryPacketAppScope == null || contextAppScope == boundaryPacketAppScope) &&
        (contextProcess == null || boundaryPacketProcess == null || contextProcess == boundaryPacketProcess)
}

internal fun canonicalPreparedTaskObject(boundaryPacket: TaskExecutionBoundaryPacket): String? {
    return boundaryPacket.objectiveSummary
        .trim()
        .lowercase(Locale.US)
        .takeIf { value -> value.isNotBlank() }
}

internal fun canonicalPreparedAction(boundaryPacket: TaskExecutionBoundaryPacket): String? {
    return boundaryPacket.actionCode.wireName.trim().lowercase(Locale.US).takeIf { value -> value.isNotBlank() }
}

internal fun canonicalPreparedAppScope(boundaryPacket: TaskExecutionBoundaryPacket): String? {
    return extractCanonicalAppScope(boundaryPacket.capabilityId)
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf { value -> value.isNotBlank() }
}

internal fun canonicalPreparedProcessId(value: String?): String? {
    val sanitized = sanitizeCanonicalProcessId(value) ?: return null
    return inferCanonicalProcessAction(
        processId = sanitized,
        objective = sanitized,
        domain = inferCanonicalProcessDomain(processId = sanitized, objective = sanitized),
        actionHint = sanitized
    )
        .trim()
        .lowercase(Locale.US)
        .takeIf { action -> action.isNotBlank() }
}