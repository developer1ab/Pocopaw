package com.atombits.pocopaw.earnings

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.planning.DefaultEarningsPlanCompiler
import com.atombits.pocopaw.earnings.planning.EarningsPlanCompiler
import com.atombits.pocopaw.earnings.runtime.DefaultEarningsExecutionLane
import com.atombits.pocopaw.earnings.runtime.DefaultEarningsWakeScheduler
import com.atombits.pocopaw.earnings.runtime.EarningsExecutionLane
import com.atombits.pocopaw.earnings.runtime.EarningsWakeScheduler
import com.atombits.pocopaw.earnings.scan.FourAppScanCenter
import com.atombits.pocopaw.earnings.ui.DefaultEarningsUiProjectionRenderer
import com.atombits.pocopaw.earnings.ui.EarningsUiProjectionRenderer

data class EarningsHubOperationResult(
    val updatedStore: PrototypeStoreData,
    val summary: String,
    val scanBatchResult: ScanBatchResult? = null,
    val laneTickResult: EarningsLaneTickResult? = null
)

interface EarningsHubOrchestrator {
    suspend fun runFullScan(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    suspend fun runFullScanAndCompilePlan(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    suspend fun compilePlanFromCurrentScan(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    fun setAutomationEnabled(store: PrototypeStoreData, enabled: Boolean, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    suspend fun tick(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    fun cancel(store: PrototypeStoreData, reason: String, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
    fun projectUi(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): EarningsHubOperationResult
}

class DefaultEarningsHubOrchestrator(
    private val scanCenter: FourAppScanCenter? = null,
    private val planCompiler: EarningsPlanCompiler = DefaultEarningsPlanCompiler(),
    private val executionLane: EarningsExecutionLane = DefaultEarningsExecutionLane(),
    private val wakeScheduler: EarningsWakeScheduler = DefaultEarningsWakeScheduler,
    private val projectionRenderer: EarningsUiProjectionRenderer = DefaultEarningsUiProjectionRenderer()
) : EarningsHubOrchestrator {
    override suspend fun runFullScan(store: PrototypeStoreData, now: Long): EarningsHubOperationResult {
        val preparedStore = prepareActiveDay(store, now)
        val configuredScanCenter = scanCenter
        if (configuredScanCenter == null) {
            val updatedStore = preparedStore
                .appendRuntimeDiagnostic("earnings scan center is not configured")
                .project(now)
            return EarningsHubOperationResult(
                updatedStore = updatedStore,
                summary = "earnings scan center is not configured"
            )
        }
        val scanResult = configuredScanCenter.runFullScan(preparedStore, now)
        val projectedStore = scanResult.updatedStore.project(now)
        val projectedScanResult = scanResult.copy(updatedStore = projectedStore)
        return EarningsHubOperationResult(
            updatedStore = projectedStore,
            summary = "scan accepted=${scanResult.acceptedCount}; uncertain=${scanResult.uncertainCount}; rejected=${scanResult.rejectedCount}",
            scanBatchResult = projectedScanResult
        )
    }

    override suspend fun runFullScanAndCompilePlan(store: PrototypeStoreData, now: Long): EarningsHubOperationResult {
        val scanResult = runFullScan(store, now)
        val capturedScanBatch = scanResult.scanBatchResult
        if (capturedScanBatch == null) {
            return scanResult
        }
        val planResult = compilePlanFromCurrentScan(scanResult.updatedStore, now)
        return planResult.copy(
            scanBatchResult = capturedScanBatch,
            summary = "scan accepted=${capturedScanBatch.acceptedCount}; uncertain=${capturedScanBatch.uncertainCount}; rejected=${capturedScanBatch.rejectedCount}; ${planResult.summary}"
        )
    }

    override suspend fun compilePlanFromCurrentScan(store: PrototypeStoreData, now: Long): EarningsHubOperationResult {
        val preparedStore = prepareActiveDay(store, now)
        val hub = preparedStore.earningsHubOrDefault(now)
        val planningState = planCompiler.compile(
            scanState = hub.scanState,
            executionLedgerState = hub.executionLedgerState,
            rewardLedgerState = hub.rewardLedgerState,
            now = now
        )
        val nextWakeAt = listOfNotNull(planningState.nextImportantWakeAt, planningState.nextFillerEligibleAt).minOrNull()
        val updatedHub = hub.copy(
            lastPlanCompiledAt = now,
            planningState = planningState,
            executionLaneState = hub.executionLaneState.copy(nextWakeAt = nextWakeAt),
            diagnosticsState = hub.diagnosticsState.copy(plannerDiagnostics = planningState.plannerDiagnostics)
        )
        val updatedStore = preparedStore.withEarningsHubState(updatedHub).project(now)
        return EarningsHubOperationResult(
            updatedStore = updatedStore,
            summary = "plan important=${planningState.importantScheduleQueue.size}; filler=${planningState.fillerCandidatePool.size}; nextWakeAt=$nextWakeAt"
        )
    }

    override fun setAutomationEnabled(store: PrototypeStoreData, enabled: Boolean, now: Long): EarningsHubOperationResult {
        val preparedStore = prepareActiveDay(store, now)
        val hub = preparedStore.earningsHubOrDefault(now)
        val laneStatus = when {
            enabled && hub.executionLaneState.laneStatus == EarningsLaneStatus.IDLE -> EarningsLaneStatus.WAITING_IMPORTANT_WINDOW
            !enabled -> EarningsLaneStatus.IDLE
            else -> hub.executionLaneState.laneStatus
        }
        val nextWakeAt = if (enabled) wakeScheduler.resolveNextWakeAt(preparedStore, now) else null
        val updatedStore = preparedStore.withEarningsHubState(
            hub.copy(
                enabled = enabled,
                executionLaneState = hub.executionLaneState.copy(
                    laneStatus = laneStatus,
                    nextWakeAt = nextWakeAt,
                    blockReason = null
                )
            )
        ).project(now)
        return EarningsHubOperationResult(
            updatedStore = updatedStore,
            summary = if (enabled) "earnings automation enabled" else "earnings automation disabled"
        )
    }

    override suspend fun tick(store: PrototypeStoreData, now: Long): EarningsHubOperationResult {
        val preparedStore = prepareActiveDay(store, now)
        val tickResult = executionLane.tick(preparedStore, now)
        val projectedStore = tickResult.updatedStore.project(now)
        return EarningsHubOperationResult(
            updatedStore = projectedStore,
            summary = tickResult.summary ?: "earnings lane tick completed",
            laneTickResult = tickResult.copy(updatedStore = projectedStore)
        )
    }

    override fun cancel(store: PrototypeStoreData, reason: String, now: Long): EarningsHubOperationResult {
        val preparedStore = prepareActiveDay(store, now)
        val updatedStore = executionLane.requestCancel(preparedStore, reason, now).project(now)
        return EarningsHubOperationResult(updatedStore = updatedStore, summary = "earnings lane cancellation requested")
    }

    override fun projectUi(store: PrototypeStoreData, now: Long): EarningsHubOperationResult {
        val updatedStore = prepareActiveDay(store, now).project(now)
        return EarningsHubOperationResult(updatedStore = updatedStore, summary = "earnings UI projection refreshed")
    }

    private fun prepareActiveDay(store: PrototypeStoreData, now: Long): PrototypeStoreData {
        val currentDateKey = EarningsDateKeys.forTimestamp(now)
        val hub = store.earningsHubOrDefault(now)
        if (hub.activeDateKey == currentDateKey) {
            return store.withEarningsHubState(hub)
        }
        val resetHub = hub.copy(
            activeDateKey = currentDateKey,
            planningState = EarningsPlanningState(),
            executionLaneState = EarningsExecutionLaneState(),
            executionLedgerState = hub.executionLedgerState.copy(
                todayCompletedOccurrenceIds = emptyList(),
                todayCompletedWindowIdsByScopedTaskKey = emptyMap()
            ),
            diagnosticsState = hub.diagnosticsState.copy(
                runtimeDiagnostics = (hub.diagnosticsState.runtimeDiagnostics + "earnings active date rolled to $currentDateKey").takeLast(100)
            )
        )
        return store.withEarningsHubState(resetHub)
    }

    private fun PrototypeStoreData.project(now: Long): PrototypeStoreData {
        val hub = earningsHubOrDefault(now)
        val projection = projectionRenderer.buildProjection(this, now)
        return withEarningsHubState(hub.copy(uiProjectionState = projection))
    }

    private fun PrototypeStoreData.appendRuntimeDiagnostic(message: String): PrototypeStoreData {
        val hub = earningsHubOrDefault()
        return withEarningsHubState(
            hub.copy(
                diagnosticsState = hub.diagnosticsState.copy(
                    runtimeDiagnostics = (hub.diagnosticsState.runtimeDiagnostics + message).takeLast(100)
                )
            )
        )
    }
}
