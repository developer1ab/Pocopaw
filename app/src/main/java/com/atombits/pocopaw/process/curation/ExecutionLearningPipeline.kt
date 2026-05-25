package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.CanonicalTraceRawMaterial
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ExecutionRuntimeState
import com.atombits.pocopaw.ExecutionWritebackRecord
import com.atombits.pocopaw.ProcessExtractionWritebackBridge
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.isSystemIntentExecution
import com.atombits.pocopaw.resolveExecutionBoundaryPacketFor

data class ExecutionOutcomeRecord(
    val executionRuntime: ExecutionRuntimeState,
    val sourceWritebackRecord: ExecutionWritebackRecord? = null,
    val occurredAt: Long = System.currentTimeMillis()
)

data class ExecutionLearningOutcome(
    val updatedStore: PrototypeStoreData,
    val rawMaterial: CanonicalTraceRawMaterial?,
    val applied: Boolean
)

internal object ExecutionLearningPipeline {

    fun applyExecutionOutcome(
        store: PrototypeStoreData,
        outcomeRecord: ExecutionOutcomeRecord,
        now: Long = System.currentTimeMillis()
    ): ExecutionLearningOutcome {
        val runtimeState = outcomeRecord.executionRuntime
        if (runtimeState.executionResult.lifecycleStatus != ExecutionLifecycleStatus.COMPLETED) {
            return ExecutionLearningOutcome(
                updatedStore = store,
                rawMaterial = null,
                applied = false
            )
        }
        if (isSystemIntentExecution(runtimeState)) {
            return ExecutionLearningOutcome(
                updatedStore = store,
                rawMaterial = null,
                applied = false
            )
        }
        val boundaryPacket = store.resolveExecutionBoundaryPacketFor(runtimeState)
            ?: return ExecutionLearningOutcome(
            updatedStore = store,
            rawMaterial = null,
            applied = false
        )

        val rawMaterial = ProcessExtractionWritebackBridge.buildCanonicalTraceMaterial(
            boundaryPacket = boundaryPacket,
            executionResult = runtimeState.executionResult,
            executionTrace = runtimeState.executionTrace,
            now = now
        )
        val learningSeedStore = rawMaterial?.let { material -> upsertRawMaterial(store, material) } ?: store
        val updatedStore = ProcessLearningWritebackBridge.applyCompletedExecution(
            store = learningSeedStore,
            outcomeRecord = outcomeRecord,
            rawMaterial = rawMaterial,
            now = now
        )
        return ExecutionLearningOutcome(
            updatedStore = updatedStore,
            rawMaterial = rawMaterial,
            applied = updatedStore !== store
        )
    }

    fun runCuration(
        store: PrototypeStoreData,
        now: Long = System.currentTimeMillis(),
        resolver: ProcessCurationResolver = SemanticProcessCurationResolver()
    ): ProcessCurationRunOutcome {
        return applyProcessExtractionCuration(
            store = store,
            now = now,
            resolver = resolver
        )
    }

    private fun upsertRawMaterial(
        store: PrototypeStoreData,
        rawMaterial: CanonicalTraceRawMaterial
    ): PrototypeStoreData {
        val nextRawMaterials = store.processExtractionRawMaterials.toMutableList()
        val existingIndex = nextRawMaterials.indexOfFirst { material -> material.traceId == rawMaterial.traceId }
        if (existingIndex >= 0) {
            nextRawMaterials[existingIndex] = rawMaterial
        } else {
            nextRawMaterials.add(rawMaterial)
        }
        return store.copy(processExtractionRawMaterials = nextRawMaterials)
    }
}
