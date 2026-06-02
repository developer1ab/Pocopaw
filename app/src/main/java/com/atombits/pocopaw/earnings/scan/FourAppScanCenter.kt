package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.AppScanStatus
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.ScanBatchResult
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.withEarningsHubState
import java.util.UUID

interface FourAppScanCenter {
    suspend fun runFullScan(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): ScanBatchResult
}

class DefaultFourAppScanCenter(
    private val adapters: List<AppEarningsScanAdapter>,
    private val normalizer: DefaultOpportunityNormalizer = DefaultOpportunityNormalizer(),
    private val returnToHostApp: suspend () -> Unit = {}
) : FourAppScanCenter {
    override suspend fun runFullScan(store: PrototypeStoreData, now: Long): ScanBatchResult {
        val batchId = UUID.randomUUID().toString()
        val capturedEvidence = try {
            adapters.map { adapter -> adapter to adapter.capture(store, now) }
        } finally {
            returnToHostApp()
        }
        val scanResults = capturedEvidence.map { (adapter, evidence) ->
            adapter.recognize(evidence)
        }
        val normalization = normalizer.normalizeBatch(scanResults.flatMap { result -> result.rawItems })
        val snapshots = scanResults.map { result ->
            val acceptedCount = normalization.acceptedOpportunities.count { opportunity -> opportunity.appId == result.snapshot.appId }
            result.snapshot.copy(
                acceptedCount = acceptedCount,
                uncertainCount = normalization.uncertainOpportunities.count { opportunity -> opportunity.appId == result.snapshot.appId },
                rejectedCount = (result.snapshot.rawItemCount - acceptedCount).coerceAtLeast(0),
                status = if (result.snapshot.failureReason == null) AppScanStatus.SUCCEEDED else result.snapshot.status
            )
        }
        val hub = store.earningsHubOrDefault(now)
        val scanState = FourAppScanState(
            currentBatchId = null,
            lastCompletedBatchId = batchId,
            appSnapshots = snapshots,
            acceptedOpportunities = normalization.acceptedOpportunities,
            uncertainOpportunities = normalization.uncertainOpportunities,
            rejectedItemCount = normalization.rejectedCount,
            lastScanSummary = "accepted=${normalization.acceptedOpportunities.size}; rejected=${normalization.rejectedCount}"
        )
        val updatedHub: EarningsHubState = hub.copy(
            lastFullScanAt = now,
            scanState = scanState,
            diagnosticsState = hub.diagnosticsState.copy(
                runtimeDiagnostics = buildScanRuntimeDiagnostics(snapshots)
            )
        )
        val updatedStore = store.withEarningsHubState(updatedHub)
        return ScanBatchResult(
            updatedStore = updatedStore,
            batchId = batchId,
            acceptedCount = normalization.acceptedOpportunities.size,
            uncertainCount = normalization.uncertainOpportunities.size,
            rejectedCount = normalization.rejectedCount
        )
    }

    private fun buildScanRuntimeDiagnostics(snapshots: List<com.atombits.pocopaw.earnings.AppScanSnapshot>): List<String> {
        return snapshots.mapNotNull { snapshot ->
            val reason = snapshot.failureReason?.trim()?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            "${snapshot.appId.stableKey}: $reason"
        }
    }
}
