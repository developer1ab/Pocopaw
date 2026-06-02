package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.LocalScreenCaptureCoordinator
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ScreenCaptureCoordinator
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionRequest
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.scan.DefaultVisionEarningsScreenRecognizer
import com.atombits.pocopaw.earnings.scan.VisionEarningsScreenRecognizer
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidFillerPostRunCooldownObserver(
    private val recognizer: VisionEarningsScreenRecognizer = DefaultVisionEarningsScreenRecognizer(),
    private val screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
    private val serviceProvider: () -> PrototypeAccessibilityService? = { PrototypeAccessibilityService.instance },
    private val captureTimeoutMs: Long = 5_000L
) : FillerPostRunCooldownObserver {
    override suspend fun observe(
        store: PrototypeStoreData,
        candidate: FillerCandidateRecord,
        executionId: String,
        terminalReason: String,
        finishedAt: Long
    ): List<FillerCooldownObservation> {
        val accessibilitySnapshot = serviceProvider()?.captureScreenTextSnapshot()
        val packageName = accessibilitySnapshot?.packageName
        if (!packageName.isNullOrBlank() && packageName != candidate.appId.packageName) {
            return emptyList()
        }
        val screenCapture = withContext(Dispatchers.IO) {
            screenCaptureCoordinator.captureSnapshot(timeoutMs = captureTimeoutMs)
        }
        val screenText = accessibilitySnapshot?.joinedText.orEmpty()
        val screenshotDataUrl = screenCapture?.imageDataUrl?.takeIf { value -> value.isNotBlank() }
        if (screenText.isBlank() && screenshotDataUrl == null) {
            return emptyList()
        }
        val observedAt = System.currentTimeMillis().takeIf { value -> value > finishedAt } ?: finishedAt
        val request = EarningsScreenRecognitionRequest(
            appId = candidate.appId,
            capturedAt = observedAt,
            screenText = screenText,
            screenshotDataUrl = screenshotDataUrl,
            accessibilityPackageName = accessibilitySnapshot?.packageName,
            sourceScreenSignature = accessibilitySnapshot?.sourceScreenSignature
        )
        val textObservation = buildFillerCooldownObservationFromPageText(
            candidate = candidate,
            observedAt = observedAt,
            pageText = screenText
        )
        val recognizedItems = recognizer.recognize(request)
            .takeIf { result -> result.failureReason == null }
            ?.rawItems
            .orEmpty()
        val modelObservations = recognizedItems.mapNotNull { rawItem ->
            rawItem.toFillerCooldownObservation(
                candidate = candidate,
                observedAt = observedAt
            )
        }
        return (modelObservations + listOfNotNull(textObservation))
            .distinctBy { observation ->
                listOf(
                    observation.appId.stableKey,
                    observation.taskKey.orEmpty(),
                    observation.displayName.orEmpty().normalizedFillerDisplayName(),
                    observation.cooldownUntil.toString(),
                    observation.source.name
                ).joinToString("|")
            }
    }
}