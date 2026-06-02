package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.AppEarningsScanResult
import com.atombits.pocopaw.earnings.AppScanSnapshot
import com.atombits.pocopaw.earnings.AppScanStatus
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionRequest
import com.atombits.pocopaw.earnings.EntertainmentAppId

data class AppEarningsCapturedEvidence(
    val appId: EntertainmentAppId,
    val startedAt: Long,
    val captureFinishedAt: Long,
    val requests: List<EarningsScreenRecognitionRequest>,
    val failureReason: String? = null
)

data class EarningsScreenEvidenceCapture(
    val requests: List<EarningsScreenRecognitionRequest> = emptyList(),
    val failureReason: String? = null
)

interface AppEarningsScanAdapter {
    val appId: EntertainmentAppId
    suspend fun capture(store: PrototypeStoreData, now: Long): AppEarningsCapturedEvidence
    suspend fun recognize(capturedEvidence: AppEarningsCapturedEvidence): AppEarningsScanResult
}

interface EarningsScreenEvidenceProvider {
    suspend fun captureAll(appId: EntertainmentAppId, store: PrototypeStoreData, now: Long): EarningsScreenEvidenceCapture
}

class RecognitionBackedAppEarningsScanAdapter(
    override val appId: EntertainmentAppId,
    private val evidenceProvider: EarningsScreenEvidenceProvider,
    private val recognizer: VisionEarningsScreenRecognizer
) : AppEarningsScanAdapter {
    override suspend fun capture(store: PrototypeStoreData, now: Long): AppEarningsCapturedEvidence {
        val startedAt = now
        val capture = evidenceProvider.captureAll(appId, store, now)
        val requests = capture.requests
        return AppEarningsCapturedEvidence(
            appId = appId,
            startedAt = startedAt,
            captureFinishedAt = System.currentTimeMillis(),
            requests = requests,
            failureReason = capture.failureReason?.trim()?.takeIf { value -> value.isNotBlank() }
                ?: if (requests.isEmpty()) "screen evidence unavailable" else null
        )
    }

    override suspend fun recognize(capturedEvidence: AppEarningsCapturedEvidence): AppEarningsScanResult {
        val captureFailureReason = capturedEvidence.failureReason
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: if (capturedEvidence.requests.isEmpty()) {
                "screen evidence unavailable"
            } else {
                null
            }
        if (captureFailureReason != null) {
            return AppEarningsScanResult(
                snapshot = AppScanSnapshot(
                    appId = capturedEvidence.appId,
                    startedAt = capturedEvidence.startedAt,
                    finishedAt = capturedEvidence.captureFinishedAt,
                    status = AppScanStatus.FAILED,
                    failureReason = captureFailureReason
                )
            )
        }
        val results = capturedEvidence.requests.map { request -> recognizer.recognize(request) }
        val successfulResults = results.filter { result -> result.failureReason == null }
        val mergedRawItems = successfulResults.flatMap { result -> result.rawItems }
        val failureReason = if (successfulResults.isEmpty()) {
            results.mapNotNull { result -> result.failureReason?.trim()?.takeIf { value -> value.isNotBlank() } }
                .distinct()
                .joinToString(" | ")
                .takeIf { value -> value.isNotBlank() }
        } else {
            null
        }
        val status = if (successfulResults.isNotEmpty()) AppScanStatus.SUCCEEDED else AppScanStatus.FAILED
        return AppEarningsScanResult(
            snapshot = AppScanSnapshot(
                appId = capturedEvidence.appId,
                startedAt = capturedEvidence.startedAt,
                finishedAt = System.currentTimeMillis(),
                status = status,
                rawItemCount = mergedRawItems.size,
                failureReason = failureReason
            ),
            rawItems = mergedRawItems
        )
    }
}
