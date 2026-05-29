package com.atombits.pocopaw

import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.reuse.ProcessGuidanceLayer
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState
import com.atombits.pocopaw.process.runtime.ProcessRuntimeStatus
import java.util.UUID

private const val defaultProcessRuntimeMaxSteps = 100

internal fun projectProcessRuntimeState(
    executionRuntime: ExecutionRuntimeState?,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    reuseContext: CandidateProcessReferenceContext?,
    previousRuntime: ProcessRuntimeState? = null,
    now: Long = System.currentTimeMillis()
): ProcessRuntimeState? {
    if (executionRuntime == null && boundaryPacket == null) {
        return null
    }

    val guidanceLayer = ProcessReuseRuntime.buildGuidanceLayer(
        taskIntent = reuseContext?.taskIntent,
        boundaryPacket = boundaryPacket,
        now = now
    )
    val preferredReference = ProcessReuseRuntime.resolvePreferredReference(
        references = reuseContext?.candidateReferences.orEmpty(),
        boundaryPacket = boundaryPacket
    )

    executionRuntime?.let { runtime ->
        val lifecycleStatus = runtime.executionResult.lifecycleStatus
        val lastStep = runtime.executionTrace.steps.lastOrNull()
        return ProcessRuntimeState(
            runtimeId = previousRuntime?.runtimeId ?: UUID.randomUUID().toString(),
            taskContextId = previousRuntime?.taskContextId ?: "ctx_$now",
            status = when (lifecycleStatus) {
                ExecutionLifecycleStatus.NOT_STARTED -> ProcessRuntimeStatus.READY
                ExecutionLifecycleStatus.RUNNING -> ProcessRuntimeStatus.RUNNING
                ExecutionLifecycleStatus.COMPLETED -> ProcessRuntimeStatus.COMPLETED
                ExecutionLifecycleStatus.FAILED -> ProcessRuntimeStatus.FAILED
            },
            currentStep = runtime.executionTrace.steps.size,
            maxSteps = defaultProcessRuntimeMaxSteps,
            lastActionFingerprint = lastStep?.let(::buildExecutionTraceFingerprint),
            sameActionRepeatCount = computeSameActionRepeatCount(runtime.executionTrace.steps),
            matchedReadyAssetId = preferredReference?.assetId,
            matchedReadyAssetName = preferredReference?.assetName,
            processGuidanceLayerSummary = buildProcessGuidanceLayerSummary(guidanceLayer),
            candidateReferenceIds = reuseContext?.candidateReferences?.map { reference -> reference.assetId }
                ?: previousRuntime?.candidateReferenceIds
                ?: emptyList(),
            blockedContext = if (lifecycleStatus == ExecutionLifecycleStatus.FAILED) {
                runtime.executionResult.summary
            } else {
                null
            },
            recoveryAction = if (lifecycleStatus == ExecutionLifecycleStatus.FAILED) {
                "request_human_guidance"
            } else {
                null
            },
            retryBudget = previousRuntime?.retryBudget ?: 0,
            finalUserSummary = if (
                lifecycleStatus == ExecutionLifecycleStatus.COMPLETED ||
                lifecycleStatus == ExecutionLifecycleStatus.FAILED
            ) {
                runtime.executionResult.summary
            } else {
                null
            },
            startedAt = previousRuntime?.startedAt ?: runtime.startedAt,
            updatedAt = now
        )
    }

    boundaryPacket ?: return null
    return ProcessRuntimeState(
        runtimeId = previousRuntime?.runtimeId ?: UUID.randomUUID().toString(),
        taskContextId = previousRuntime?.taskContextId ?: "ctx_$now",
        status = ProcessRuntimeStatus.READY,
        currentStep = 0,
        maxSteps = defaultProcessRuntimeMaxSteps,
        lastActionFingerprint = null,
        sameActionRepeatCount = 0,
        matchedReadyAssetId = preferredReference?.assetId,
        matchedReadyAssetName = preferredReference?.assetName,
        processGuidanceLayerSummary = buildProcessGuidanceLayerSummary(guidanceLayer),
        candidateReferenceIds = reuseContext?.candidateReferences?.map { reference -> reference.assetId }.orEmpty(),
        blockedContext = null,
        recoveryAction = null,
        retryBudget = previousRuntime?.retryBudget ?: 0,
        finalUserSummary = null,
        startedAt = previousRuntime?.startedAt ?: now,
        updatedAt = now
    )
}

private fun buildProcessGuidanceLayerSummary(guidanceLayer: ProcessGuidanceLayer?): String? {
    if (guidanceLayer == null) {
        return null
    }
    return buildList {
        guidanceLayer.domainScope?.let { value -> add("domain=$value") }
        guidanceLayer.appScope?.let { value -> add("app=$value") }
        guidanceLayer.processScope?.let { value -> add("process=$value") }
        addAll(guidanceLayer.guidanceLines.take(2))
        addAll(guidanceLayer.appSpecificCautions.take(1))
    }.takeIf { values -> values.isNotEmpty() }?.joinToString(" | ")
}

private fun buildExecutionTraceFingerprint(step: ExecutionTraceStep): String {
    return listOf(
        step.stepType,
        step.actionType?.name.orEmpty(),
        step.locatorHint.orEmpty(),
        step.pageSignature.orEmpty(),
        step.expectedOutcome
    ).joinToString("|")
}

private fun computeSameActionRepeatCount(steps: List<ExecutionTraceStep>): Int {
    val lastStep = steps.lastOrNull() ?: return 0
    val lastFingerprint = buildExecutionTraceFingerprint(lastStep)
    var count = 0
    for (index in steps.indices.reversed()) {
        val fingerprint = buildExecutionTraceFingerprint(steps[index])
        if (fingerprint != lastFingerprint) {
            break
        }
        count += 1
    }
    return count
}
