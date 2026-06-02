package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.FillerCandidateRecord
import com.atombits.pocopaw.earnings.RawScanItem
import java.util.Locale

interface FillerPostRunCooldownObserver {
    suspend fun observe(
        store: PrototypeStoreData,
        candidate: FillerCandidateRecord,
        executionId: String,
        terminalReason: String,
        finishedAt: Long
    ): List<FillerCooldownObservation>
}

data class FillerCooldownObservation(
    val appId: EntertainmentAppId,
    val taskKey: String? = null,
    val displayName: String? = null,
    val observedAt: Long,
    val cooldownUntil: Long,
    val source: FillerCooldownObservationSource,
    val evidenceSummary: String? = null
)

enum class FillerCooldownObservationSource(val priority: Int) {
    TERMINAL_REASON(10),
    EXECUTION_EVENT(20),
    POST_RUN_PAGE(30)
}

object ExecutionEventFillerPostRunCooldownObserver : FillerPostRunCooldownObserver {
    override suspend fun observe(
        store: PrototypeStoreData,
        candidate: FillerCandidateRecord,
        executionId: String,
        terminalReason: String,
        finishedAt: Long
    ): List<FillerCooldownObservation> {
        return buildList {
            buildCooldownObservation(
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                observedAt = finishedAt,
                evidenceText = terminalReason,
                source = FillerCooldownObservationSource.TERMINAL_REASON
            )?.let(::add)

            val executionEvidence = buildString {
                store.executionEvents
                    .asSequence()
                    .filter { event -> event.keyInfo?.contains("task=earnings:$executionId") == true }
                    .forEach { event ->
                        appendLine(event.summary)
                        event.automationResponsePayload?.let(::appendLine)
                    }
            }
            buildCooldownObservation(
                appId = candidate.appId,
                taskKey = candidate.taskKey,
                displayName = candidate.displayName,
                observedAt = finishedAt,
                evidenceText = executionEvidence,
                source = FillerCooldownObservationSource.EXECUTION_EVENT
            )?.let(::add)
        }
    }
}

class CompositeFillerPostRunCooldownObserver(
    private val observers: List<FillerPostRunCooldownObserver>
) : FillerPostRunCooldownObserver {
    override suspend fun observe(
        store: PrototypeStoreData,
        candidate: FillerCandidateRecord,
        executionId: String,
        terminalReason: String,
        finishedAt: Long
    ): List<FillerCooldownObservation> {
        return observers.flatMap { observer ->
            observer.observe(
                store = store,
                candidate = candidate,
                executionId = executionId,
                terminalReason = terminalReason,
                finishedAt = finishedAt
            )
        }
    }
}

internal fun RawScanItem.toFillerCooldownObservation(
    candidate: FillerCandidateRecord,
    observedAt: Long,
    source: FillerCooldownObservationSource = FillerCooldownObservationSource.POST_RUN_PAGE
): FillerCooldownObservation? {
    if (!matchesFillerCandidate(candidate)) {
        return null
    }
    val evidenceText = listOfNotNull(
        visibleTitle,
        rewardText,
        scheduleText,
        actionText,
        screenContext,
        evidenceText,
        semanticMatchReason
    ).joinToString(separator = "\n")
    return buildCooldownObservation(
        appId = appId,
        taskKey = modelTaskKey ?: candidate.taskKey,
        displayName = visibleTitle,
        observedAt = observedAt,
        evidenceText = evidenceText,
        source = source
    )
}

internal fun buildFillerCooldownObservationFromPageText(
    candidate: FillerCandidateRecord,
    observedAt: Long,
    pageText: String,
    source: FillerCooldownObservationSource = FillerCooldownObservationSource.POST_RUN_PAGE
): FillerCooldownObservation? {
    val evidenceText = pageText.snippetAround(candidate.displayName, radius = 220) ?: return null
    return buildCooldownObservation(
        appId = candidate.appId,
        taskKey = candidate.taskKey,
        displayName = candidate.displayName,
        observedAt = observedAt,
        evidenceText = evidenceText,
        source = source
    )
}

internal fun buildCooldownObservation(
    appId: EntertainmentAppId,
    taskKey: String?,
    displayName: String?,
    observedAt: Long,
    evidenceText: String,
    source: FillerCooldownObservationSource
): FillerCooldownObservation? {
    val normalizedEvidence = evidenceText.trim().takeIf { value -> value.isNotBlank() } ?: return null
    val cooldownDelayMs = FillerCooldownTextParser.extractCooldownDelayMs(normalizedEvidence) ?: return null
    return FillerCooldownObservation(
        appId = appId,
        taskKey = taskKey,
        displayName = displayName,
        observedAt = observedAt,
        cooldownUntil = observedAt + cooldownDelayMs,
        source = source,
        evidenceSummary = normalizedEvidence.replace(Regex("\\s+"), " ").take(240)
    )
}

internal object FillerCooldownTextParser {
    fun extractCooldownDelayMs(text: String): Long? {
        return listOfNotNull(
            extractDurationMs(text, Regex("""(?i)(\d{1,3})\s*h(?:ours?|rs?)?\s*(?:(\d{1,3})\s*m(?:in(?:ute)?s?)?)?"""), hourGroup = 1, minuteGroup = 2),
            extractDurationMs(text, Regex("""(\d{1,2})\s*小时\s*(?:(\d{1,3})\s*分(?:钟)?)?"""), hourGroup = 1, minuteGroup = 2),
            extractDurationMs(text, Regex("""(?i)(\d{1,3})\s*m(?:in(?:ute)?s?)?\s*(?:(\d{1,2})\s*s(?:ec(?:ond)?s?)?)?"""), minuteGroup = 1, secondGroup = 2),
            extractDurationMs(text, Regex("""(?i)(\d{1,3})\s*(?:minutes?|mins?)\s*(?:(\d{1,2})\s*(?:seconds?|secs?))?"""), minuteGroup = 1, secondGroup = 2),
            extractDurationMs(text, Regex("""(\d{1,3})\s*分(?:钟)?\s*(?:(\d{1,2})\s*秒)?"""), minuteGroup = 1, secondGroup = 2),
            extractColonCountdownMs(text)
        ).minOrNull()
    }

    private fun extractDurationMs(
        text: String,
        pattern: Regex,
        hourGroup: Int? = null,
        minuteGroup: Int? = null,
        secondGroup: Int? = null
    ): Long? {
        return pattern.findAll(text)
            .mapNotNull { match ->
                val hours = hourGroup?.let { group -> match.groupValues.getOrNull(group)?.toLongOrNull() } ?: 0L
                val minutes = minuteGroup?.let { group -> match.groupValues.getOrNull(group)?.toLongOrNull() } ?: 0L
                val seconds = secondGroup?.let { group -> match.groupValues.getOrNull(group)?.toLongOrNull() } ?: 0L
                ((hours * 60L * 60L) + (minutes * 60L) + seconds)
                    .takeIf { totalSeconds -> totalSeconds > 0L }
                    ?.times(1_000L)
            }
            .minOrNull()
    }

    private fun extractColonCountdownMs(text: String): Long? {
        val pattern = Regex("""(?<!\d)(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?!\d)""")
        return pattern.findAll(text)
            .mapNotNull { match ->
                if (isLikelyClockWindow(text, match.range)) {
                    return@mapNotNull null
                }
                val first = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val thirdText = match.groupValues.getOrNull(3).orEmpty()
                val totalSeconds = if (thirdText.isBlank()) {
                    first * 60L + second
                } else {
                    val third = thirdText.toLongOrNull() ?: return@mapNotNull null
                    first * 60L * 60L + second * 60L + third
                }
                totalSeconds.takeIf { value -> value in 1L..86_400L }?.times(1_000L)
            }
            .minOrNull()
    }

    private fun isLikelyClockWindow(text: String, range: IntRange): Boolean {
        val contextStart = (range.first - 12).coerceAtLeast(0)
        val contextEnd = (range.last + 13).coerceAtMost(text.length)
        val context = text.substring(contextStart, contextEnd)
        return Regex("""\d{1,2}:[0-5]\d\s*[-~至到]\s*\d{1,2}:[0-5]\d""").containsMatchIn(context)
    }
}

internal fun FillerCooldownObservation.matchesCandidate(record: FillerCandidateRecord): Boolean {
    if (appId != record.appId) {
        return false
    }
    if (!taskKey.isNullOrBlank() && taskKey == record.taskKey) {
        return true
    }
    val observationName = displayName?.normalizedFillerDisplayName().orEmpty()
    return observationName.isNotBlank() && observationName == record.displayName.normalizedFillerDisplayName()
}

internal fun RawScanItem.matchesFillerCandidate(candidate: FillerCandidateRecord): Boolean {
    if (appId != candidate.appId) {
        return false
    }
    if (!modelTaskKey.isNullOrBlank() && modelTaskKey == candidate.taskKey) {
        return true
    }
    return visibleTitle.normalizedFillerDisplayName() == candidate.displayName.normalizedFillerDisplayName()
}

internal fun String.normalizedFillerDisplayName(): String {
    return trim().lowercase(Locale.US).replace(Regex("\\s+"), "")
}

private fun String.snippetAround(target: String, radius: Int): String? {
    val targetText = target.trim().takeIf { value -> value.isNotBlank() } ?: return null
    val index = indexOf(targetText, ignoreCase = true).takeIf { value -> value >= 0 } ?: return null
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + targetText.length + radius).coerceAtMost(length)
    return substring(start, end)
}