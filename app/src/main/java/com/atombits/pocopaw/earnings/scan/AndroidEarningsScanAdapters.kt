package com.atombits.pocopaw.earnings.scan

import android.content.Context
import android.content.Intent
import com.atombits.pocopaw.LocalScreenCaptureCoordinator
import com.atombits.pocopaw.MainActivity
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ScreenCaptureCoordinator
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionRequest
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.launchAppViaPackageManager
import com.atombits.pocopaw.service.AccessibilityScreenTextSnapshot
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface EarningsChannelNavigator {
    suspend fun navigateToEarningsChannel(appId: EntertainmentAppId): EarningsChannelNavigationResult
}

data class EarningsChannelNavigationResult(
    val entered: Boolean,
    val failureReason: String? = null
)

private const val FAILURE_ACCESSIBILITY_NOT_CONNECTED = "accessibility service not connected"
private const val FAILURE_TARGET_APP_LAUNCH_FAILED = "target app launch failed"
private const val FAILURE_EARNINGS_NAVIGATION_PROFILE_UNAVAILABLE = "earnings navigation profile unavailable"
private const val FAILURE_EARNINGS_NAVIGATION_TAP_FAILED = "earnings channel navigation tap failed"
private const val FAILURE_EARNINGS_CHANNEL_NOT_REACHED = "earnings channel not reached"

class AndroidEarningsChannelNavigator(
    private val serviceProvider: () -> PrototypeAccessibilityService? = { PrototypeAccessibilityService.instance },
    private val pause: suspend (Long) -> Unit = { durationMs -> delay(durationMs) }
) : EarningsChannelNavigator {
    override suspend fun navigateToEarningsChannel(appId: EntertainmentAppId): EarningsChannelNavigationResult {
        pause(APP_SETTLE_DELAY_MS)
        val service = serviceProvider() ?: return EarningsChannelNavigationResult(
            entered = false,
            failureReason = FAILURE_ACCESSIBILITY_NOT_CONNECTED
        )
        if (isEarningsChannel(appId, service.captureScreenTextSnapshot())) {
            return EarningsChannelNavigationResult(entered = true)
        }
        val profile = navigationProfile(appId) ?: return EarningsChannelNavigationResult(
            entered = false,
            failureReason = FAILURE_EARNINGS_NAVIGATION_PROFILE_UNAVAILABLE
        )
        for (step in profile.steps) {
            val currentService = serviceProvider() ?: return EarningsChannelNavigationResult(
                entered = false,
                failureReason = FAILURE_ACCESSIBILITY_NOT_CONNECTED
            )
            val tapped = currentService.tap(step.normalizedX, step.normalizedY) == true
            if (!tapped) {
                return EarningsChannelNavigationResult(
                    entered = false,
                    failureReason = FAILURE_EARNINGS_NAVIGATION_TAP_FAILED
                )
            }
            pause(step.settleDelayMs)
            if (isEarningsChannel(appId, serviceProvider()?.captureScreenTextSnapshot())) {
                return EarningsChannelNavigationResult(entered = true)
            }
        }
        return EarningsChannelNavigationResult(
            entered = false,
            failureReason = FAILURE_EARNINGS_CHANNEL_NOT_REACHED
        )
    }

    private fun navigationProfile(appId: EntertainmentAppId): EarningsNavigationProfile? {
        return when (appId) {
            EntertainmentAppId.DOUYIN_LITE -> EarningsNavigationProfile(
                steps = listOf(
                    EarningsNavigationStep(normalizedX = 0.50f, normalizedY = 0.956f),
                    EarningsNavigationStep(normalizedX = 0.50f, normalizedY = 0.93f)
                )
            )
            else -> null
        }
    }

    private fun isEarningsChannel(appId: EntertainmentAppId, snapshot: AccessibilityScreenTextSnapshot?): Boolean {
        if (snapshot?.packageName != appId.packageName) {
            return false
        }
        val text = snapshot.joinedText
        if (text.isBlank()) {
            return false
        }
        return when (appId) {
            EntertainmentAppId.DOUYIN_LITE -> containsEnoughSignals(
                text = text,
                requiredAny = listOf("金币", "任务", "签到", "宝箱", "赚钱", "赚"),
                strongPhrases = listOf("金币任务", "赚钱任务", "做任务赚金币", "金币收益", "去赚钱")
            )
            else -> false
        }
    }

    private fun containsEnoughSignals(
        text: String,
        requiredAny: List<String>,
        strongPhrases: List<String>
    ): Boolean {
        if (strongPhrases.any { phrase -> text.contains(phrase) }) {
            return true
        }
        return requiredAny.count { signal -> text.contains(signal) } >= 2
    }

    companion object {
        private const val APP_SETTLE_DELAY_MS = 1_500L
    }
}

private data class EarningsNavigationProfile(
    val steps: List<EarningsNavigationStep>
)

private data class EarningsNavigationStep(
    val normalizedX: Float,
    val normalizedY: Float,
    val settleDelayMs: Long = 2_500L
)

class AndroidEarningsScreenEvidenceProvider(
    context: Context,
    private val screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
    private val appLauncher: (Context, String) -> Boolean = ::launchAppViaPackageManager,
    private val channelNavigator: EarningsChannelNavigator = AndroidEarningsChannelNavigator(),
    private val pageSwiper: suspend () -> Boolean = {
        withContext(Dispatchers.Main) {
            PrototypeAccessibilityService.instance?.swipe(
                fromX = 0.5f,
                fromY = 0.82f,
                toX = 0.5f,
                toY = 0.22f,
                durationMs = 420L
            ) == true
        }
    },
    private val pageCount: Int = 5,
    private val settleDelayMs: Long = 3_500L,
    private val pageSettleDelayMs: Long = 1_400L,
    private val captureTimeoutMs: Long = 5_000L
) : EarningsScreenEvidenceProvider {
    private val appContext = context.applicationContext

    override suspend fun captureAll(
        appId: EntertainmentAppId,
        store: PrototypeStoreData,
        now: Long
    ): EarningsScreenEvidenceCapture {
        if (PrototypeAccessibilityService.instance == null) {
            return EarningsScreenEvidenceCapture(failureReason = FAILURE_ACCESSIBILITY_NOT_CONNECTED)
        }
        val launched = withContext(Dispatchers.Main) {
            appLauncher(appContext, appId.packageName)
        }
        if (!launched) {
            return EarningsScreenEvidenceCapture(failureReason = FAILURE_TARGET_APP_LAUNCH_FAILED)
        }
        if (PrototypeAccessibilityService.instance == null) {
            return EarningsScreenEvidenceCapture(failureReason = FAILURE_ACCESSIBILITY_NOT_CONNECTED)
        }
        val navigationResult = channelNavigator.navigateToEarningsChannel(appId)
        if (!navigationResult.entered) {
            return EarningsScreenEvidenceCapture(
                failureReason = navigationResult.failureReason?.trim()?.takeIf { value -> value.isNotBlank() }
                    ?: FAILURE_EARNINGS_CHANNEL_NOT_REACHED
            )
        }
        delay(settleDelayMs)
        val pageRequests = mutableListOf<EarningsScreenRecognitionRequest>()
        val captureTotal = pageCount.coerceAtLeast(1)
        repeat(captureTotal) { index ->
            val request = captureCurrentPage(appId, now)
            if (request == null) {
                return EarningsScreenEvidenceCapture(
                    requests = pageRequests,
                    failureReason = if (pageRequests.isEmpty()) "screen evidence unavailable" else null
                )
            }
            pageRequests += request
            if (index >= captureTotal - 1) {
                return@repeat
            }
            val scrolled = pageSwiper()
            if (!scrolled) {
                return EarningsScreenEvidenceCapture(requests = pageRequests)
            }
            delay(pageSettleDelayMs)
        }
        return EarningsScreenEvidenceCapture(requests = pageRequests)
    }

    private suspend fun captureCurrentPage(
        appId: EntertainmentAppId,
        now: Long
    ): EarningsScreenRecognitionRequest? {
        val accessibilitySnapshot = PrototypeAccessibilityService.instance?.captureScreenTextSnapshot()
        val screenCapture = withContext(Dispatchers.IO) {
            screenCaptureCoordinator.captureSnapshot(timeoutMs = captureTimeoutMs)
        }
        val screenText = accessibilitySnapshot?.joinedText.orEmpty()
        val screenshotDataUrl = screenCapture?.imageDataUrl?.takeIf { value -> value.isNotBlank() }
        if (screenText.isBlank() && screenshotDataUrl == null) {
            return null
        }
        val capturedAt = System.currentTimeMillis().takeIf { value -> value > now } ?: now
        return EarningsScreenRecognitionRequest(
            appId = appId,
            capturedAt = capturedAt,
            screenText = screenText,
            screenshotDataUrl = screenshotDataUrl,
            accessibilityPackageName = accessibilitySnapshot?.packageName,
            sourceScreenSignature = accessibilitySnapshot?.sourceScreenSignature
        )
    }
}

fun buildAndroidFourAppScanCenter(
    context: Context,
    recognizer: VisionEarningsScreenRecognizer = DefaultVisionEarningsScreenRecognizer(),
    evidenceProvider: EarningsScreenEvidenceProvider = AndroidEarningsScreenEvidenceProvider(context)
): DefaultFourAppScanCenter {
    val appContext = context.applicationContext
    val adapters = EntertainmentAppId.defaultOrder().map { appId ->
        RecognitionBackedAppEarningsScanAdapter(
            appId = appId,
            evidenceProvider = evidenceProvider,
            recognizer = recognizer
        )
    }
    return DefaultFourAppScanCenter(
        adapters = adapters,
        returnToHostApp = {
            withContext(Dispatchers.Main) {
                launchPocopawMainActivity(PrototypeAccessibilityService.instance ?: appContext)
            }
        }
    )
}

private fun launchPocopawMainActivity(context: Context): Boolean {
    return runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        true
    }.getOrDefault(false)
}
