package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.AppEarningsScanResult
import com.atombits.pocopaw.earnings.AppScanSnapshot
import com.atombits.pocopaw.earnings.AppScanStatus
import com.atombits.pocopaw.earnings.EntertainmentAppId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultFourAppScanCenterTest {

    @Test
    fun runFullScan_returnsToHostAfterCaptureBeforeRecognition() = runBlocking {
        val events = mutableListOf<String>()
        val first = RecordingScanAdapter(EntertainmentAppId.DOUYIN_LITE, events)
        val second = RecordingScanAdapter(EntertainmentAppId.FANQIE, events)
        val scanCenter = DefaultFourAppScanCenter(
            adapters = listOf(first, second),
            returnToHostApp = { events += "return" }
        )

        scanCenter.runFullScan(PrototypeStoreData(), now = 1234L)

        assertEquals(
            listOf(
                "capture:DOUYIN_LITE",
                "capture:FANQIE",
                "return",
                "recognize:DOUYIN_LITE",
                "recognize:FANQIE"
            ),
            events
        )
    }
}

private class RecordingScanAdapter(
    override val appId: EntertainmentAppId,
    private val events: MutableList<String>
) : AppEarningsScanAdapter {
    override suspend fun capture(
        store: PrototypeStoreData,
        now: Long
    ): AppEarningsCapturedEvidence {
        events += "capture:${appId.name}"
        return AppEarningsCapturedEvidence(
            appId = appId,
            startedAt = now,
            captureFinishedAt = now + 1,
            requests = emptyList(),
            failureReason = "not needed"
        )
    }

    override suspend fun recognize(capturedEvidence: AppEarningsCapturedEvidence): AppEarningsScanResult {
        events += "recognize:${appId.name}"
        return AppEarningsScanResult(
            snapshot = AppScanSnapshot(
                appId = appId,
                startedAt = capturedEvidence.startedAt,
                finishedAt = capturedEvidence.captureFinishedAt,
                status = AppScanStatus.SUCCEEDED
            )
        )
    }
}