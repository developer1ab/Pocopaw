package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.AppScanStatus
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionRequest
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionResult
import com.atombits.pocopaw.earnings.EntertainmentAppId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionBackedAppEarningsScanAdapterTest {

    @Test
    fun recognize_preservesExplicitCaptureFailureReason() = runBlocking {
        val adapter = RecognitionBackedAppEarningsScanAdapter(
            appId = EntertainmentAppId.DOUYIN_LITE,
            evidenceProvider = FixedEvidenceProvider(
                EarningsScreenEvidenceCapture(failureReason = "accessibility service not connected")
            ),
            recognizer = NoOpRecognizer
        )

        val capturedEvidence = adapter.capture(PrototypeStoreData(), now = 1000L)
        val result = adapter.recognize(capturedEvidence)

        assertEquals(AppScanStatus.FAILED, result.snapshot.status)
        assertEquals("accessibility service not connected", result.snapshot.failureReason)
        assertEquals(0, result.rawItems.size)
    }
}

private class FixedEvidenceProvider(
    private val capture: EarningsScreenEvidenceCapture
) : EarningsScreenEvidenceProvider {
    override suspend fun captureAll(
        appId: EntertainmentAppId,
        store: PrototypeStoreData,
        now: Long
    ): EarningsScreenEvidenceCapture = capture
}

private object NoOpRecognizer : VisionEarningsScreenRecognizer {
    override suspend fun recognize(request: EarningsScreenRecognitionRequest): EarningsScreenRecognitionResult {
        return EarningsScreenRecognitionResult(appId = request.appId)
    }
}