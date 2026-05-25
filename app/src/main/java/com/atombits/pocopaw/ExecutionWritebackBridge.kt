package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ExecutionLearningPipeline
import com.atombits.pocopaw.process.curation.ExecutionOutcomeRecord
import com.atombits.pocopaw.process.runtime.evaluateExecutionCompletionAgainstChecks

data class ExecutionWritebackRecord(
    val lifecycleStatus: ExecutionLifecycleStatus,
    val summary: String,
    val automationResponsePayload: String? = null,
    val appendedSteps: List<ExecutionTraceStep> = emptyList()
)

data class ExecutionWritebackOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

fun applyExecutionWritebackRecord(
    store: PrototypeStoreData,
    writebackRecord: ExecutionWritebackRecord,
    now: Long = System.currentTimeMillis()
): ExecutionWritebackOutcome {
    val runtimeState = store.resolveCurrentExecutionRuntime() ?: return ExecutionWritebackOutcome(
        updatedStore = store,
        applied = false,
        message = UiStrings.resolve(
            R.string.execution_no_running_writeback,
            "No running execution is available for writeback."
        )
    )

    val effectiveWritebackRecord = normalizeTerminalWritebackRecord(
        store = store,
        writebackRecord = writebackRecord
    )

    val mergedTrace = runtimeState.executionTrace.copy(
        steps = runtimeState.executionTrace.steps + effectiveWritebackRecord.appendedSteps
    )
    val mergedResult = runtimeState.executionResult.copy(
        lifecycleStatus = effectiveWritebackRecord.lifecycleStatus,
        summary = effectiveWritebackRecord.summary,
        latestAutomationResponsePayload = effectiveWritebackRecord.automationResponsePayload
            ?: runtimeState.executionResult.latestAutomationResponsePayload,
        occurredAt = now
    )
    val updatedRuntime = runtimeState.copy(
        executionResult = mergedResult,
        executionTrace = mergedTrace
    )
    var workingStore = store.copy(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        executionEvents = store.executionEvents.toMutableList(),
        executionTraces = store.executionTraces.toMutableList().apply {
            val index = indexOfFirst { trace -> trace.traceId == mergedTrace.traceId }
            if (index >= 0) {
                this[index] = mergedTrace
            } else {
                add(mergedTrace)
            }
        },
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        memoryState = store.memoryState ?: MemoryState()
    )
    val resolvedReuseContext = store.resolveCurrentProcessReuseContext()
    val updatedProcessRuntime = projectProcessRuntimeState(
        executionRuntime = updatedRuntime,
        boundaryPacket = workingStore.resolveExecutionBoundaryPacketFor(updatedRuntime),
        reuseContext = resolvedReuseContext,
        previousRuntime = store.resolveCurrentProcessRuntime(),
        now = now
    )
    workingStore.updateCurrentExecutionSession(
        preparedExecutionStart = store.resolveCurrentPreparedExecutionStart(),
        executionRuntime = updatedRuntime,
        processReuseContext = resolvedReuseContext,
        processRuntime = updatedProcessRuntime,
        updatedAt = now
    )
    workingStore.executionEvents.add(
        ExecutionEvent(
            candidateId = runtimeState.candidateId,
            phase = when (effectiveWritebackRecord.lifecycleStatus) {
                ExecutionLifecycleStatus.COMPLETED -> ExecutionEventPhase.COMPLETED
                ExecutionLifecycleStatus.FAILED -> ExecutionEventPhase.FAILED
                ExecutionLifecycleStatus.RUNNING -> ExecutionEventPhase.RUNNING
                ExecutionLifecycleStatus.NOT_STARTED -> ExecutionEventPhase.INFO
            },
            lifecycleStatus = effectiveWritebackRecord.lifecycleStatus,
            summary = effectiveWritebackRecord.summary,
            keyInfo = updatedRuntime.toExecutionKeyInfo(),
            automationResponsePayload = effectiveWritebackRecord.automationResponsePayload,
            startedAt = now
        )
    )
    workingStore = ExecutionLearningPipeline.applyExecutionOutcome(
        store = workingStore,
        outcomeRecord = ExecutionOutcomeRecord(
            executionRuntime = updatedRuntime,
            sourceWritebackRecord = effectiveWritebackRecord,
            occurredAt = now
        ),
        now = now
    ).updatedStore

    return if (effectiveWritebackRecord.lifecycleStatus == ExecutionLifecycleStatus.COMPLETED ||
        effectiveWritebackRecord.lifecycleStatus == ExecutionLifecycleStatus.FAILED
    ) {
        val completion = applyExecutionRuntimeCompletion(
            store = workingStore,
            lifecycleStatus = effectiveWritebackRecord.lifecycleStatus,
            now = now
        )
        ExecutionWritebackOutcome(
            updatedStore = completion.updatedStore,
            applied = completion.applied,
            message = effectiveWritebackRecord.summary
        )
    } else {
        ExecutionWritebackOutcome(
            updatedStore = workingStore,
            applied = true,
            message = effectiveWritebackRecord.summary
        )
    }
}

private fun normalizeTerminalWritebackRecord(
    store: PrototypeStoreData,
    writebackRecord: ExecutionWritebackRecord
): ExecutionWritebackRecord {
    if (writebackRecord.lifecycleStatus != ExecutionLifecycleStatus.COMPLETED) {
        return writebackRecord
    }
    val runtimeState = store.resolveCurrentExecutionRuntime()
    val checks = runtimeState?.let { state ->
        store.resolveExecutionBoundaryPacketFor(state)
    }?.verificationChecks.orEmpty()
    if (checks.isEmpty()) {
        return writebackRecord
    }
    val verificationOutcome = evaluateExecutionCompletionAgainstChecks(
        checks = checks,
        terminalSummary = writebackRecord.summary,
        automationPayload = writebackRecord.automationResponsePayload
    )
    if (verificationOutcome.passed) {
        return writebackRecord
    }
    return writebackRecord.copy(
        lifecycleStatus = ExecutionLifecycleStatus.FAILED,
        summary = verificationOutcome.summary,
        appendedSteps = writebackRecord.appendedSteps + listOf(
            ExecutionTraceStep(
                stepType = "VERIFY",
                groundingMode = "TASK_VERIFY",
                expectedOutcome = "execution_verification_failed",
                fallbackPolicy = "STOP",
                riskLevel = "MEDIUM",
                verificationSignals = verificationOutcome.failedChecks.map { failure ->
                    listOfNotNull(failure.key, failure.expectedValue).joinToString(":")
                },
                continuationMode = "STOP",
                note = verificationOutcome.summary
            )
        )
    )
}