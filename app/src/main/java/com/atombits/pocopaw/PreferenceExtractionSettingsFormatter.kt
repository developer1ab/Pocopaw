package com.atombits.pocopaw

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val offlineDialoguePreferenceSourceType = "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION"
private const val defaultPreferenceExtractionListLimit = 4

internal data class PreferenceExtractionDisplayState(
    val pendingCount: Int,
    val extractedCount: Int,
    val pendingItems: List<String>,
    val extractedItems: List<String>,
    val nextEligibleExtractionAt: Long?,
    val lastConsumedAt: Long?,
    val lastOutcomeMessage: String?,
    val lastModelResponsePreview: String?
)

private data class TimestampedPreferenceExtractionLine(
    val timestamp: Long,
    val content: String
)

internal fun buildPreferenceExtractionDisplayState(
    memoryState: MemoryState,
    listLimit: Int = defaultPreferenceExtractionListLimit
): PreferenceExtractionDisplayState {
    val effectiveLimit = listLimit.coerceAtLeast(1)
    val pendingRecords = memoryState.dialoguePreferenceBacklog
        .sortedByDescending { record -> record.lastObservedAt }
    val extractedPreferenceLines = (
        memoryState.structuredPreferenceMemory.facts
            .filter { fact -> fact.sourceType.equals(offlineDialoguePreferenceSourceType, ignoreCase = true) }
            .map { fact ->
                TimestampedPreferenceExtractionLine(
                    timestamp = fact.lastObservedAt,
                    content = buildPreferenceExtractionBullet(
                        buildString {
                            append(
                                fact.anchorObject.ifBlank {
                                    UiStrings.resolve(
                                        R.string.preference_extraction_anchor_fallback,
                                        "preference"
                                    )
                                }
                            )
                            append(": ")
                            append(fact.facetKey)
                            append("=")
                            append(fact.facetValue)
                        }
                    )
                )
            } + memoryState.interactionBiasMemory.allRecords()
            .filter { record -> record.sourceType.equals(offlineDialoguePreferenceSourceType, ignoreCase = true) }
            .map { record ->
                TimestampedPreferenceExtractionLine(
                    timestamp = record.lastObservedAt,
                    content = buildPreferenceExtractionBullet(
                        buildString {
                            append(
                                record.anchorObject.ifBlank {
                                    UiStrings.resolve(
                                        R.string.preference_extraction_anchor_fallback,
                                        "preference"
                                    )
                                }
                            )
                            append(": ")
                            append(record.signalKey)
                            append("=")
                            append(record.signalValue)
                        }
                    )
                )
            }
        )
        .map { line ->
            TimestampedPreferenceExtractionLine(
                timestamp = line.timestamp,
                content = line.content
            )
        }
    val extractedStyleLines = memoryState.interactionStyleStore
        .filter { record -> record.sourceType.equals(offlineDialoguePreferenceSourceType, ignoreCase = true) }
        .map { record ->
            TimestampedPreferenceExtractionLine(
                timestamp = record.lastObservedAt,
                content = buildPreferenceExtractionBullet(
                    UiStrings.resolve(
                        R.string.preference_extraction_style_entry,
                        "%1\$s: %2\$s=%3\$s",
                        UiStrings.resolve(R.string.preference_extraction_style_prefix, "style"),
                        record.styleKey,
                        record.styleValue
                    )
                )
            )
        }
    val extractedItems = (extractedPreferenceLines + extractedStyleLines)
        .sortedByDescending { line -> line.timestamp }
        .map { line -> line.content }

    return PreferenceExtractionDisplayState(
        pendingCount = pendingRecords.size,
        extractedCount = extractedItems.size,
        pendingItems = truncatePreferenceExtractionItems(
            pendingRecords.map(::formatPendingPreferenceExtractionRecord),
            effectiveLimit
        ),
        extractedItems = truncatePreferenceExtractionItems(extractedItems, effectiveLimit),
        nextEligibleExtractionAt = memoryState.dialoguePreferenceExtractionRuntime.nextEligibleExtractionAt,
        lastConsumedAt = memoryState.dialoguePreferenceExtractionRuntime.lastConsumedAt,
        lastOutcomeMessage = memoryState.dialoguePreferenceExtractionRuntime.lastOutcomeMessage
            ?.takeIf { message -> message.isNotBlank() },
        lastModelResponsePreview = memoryState.dialoguePreferenceExtractionRuntime.lastModelResponsePayload
            ?.takeIf { payload -> payload.isNotBlank() }
            ?.let(::formatPreferenceExtractionRawPayloadPreview)
    )
}

fun buildPreferenceExtractionStatusSummary(
    context: Context,
    memoryState: MemoryState,
    listLimit: Int = defaultPreferenceExtractionListLimit
): String {
    val displayState = buildPreferenceExtractionDisplayState(memoryState, listLimit)
    val updatedAtText = displayState.lastConsumedAt?.let(::formatPreferenceExtractionTimestamp)
        ?: context.getString(R.string.settings_not_updated_yet)
    val cooldownSummary = displayState.nextEligibleExtractionAt?.let { timestamp ->
        if (displayState.pendingCount > 0 && System.currentTimeMillis() < timestamp) {
            context.getString(
                R.string.preference_extraction_cooldown_summary,
                formatPreferenceExtractionTimestamp(timestamp)
            )
        } else {
            context.getString(R.string.preference_extraction_ready_summary)
        }
    } ?: context.getString(R.string.preference_extraction_ready_summary)

    return buildString {
        append(context.getString(R.string.preference_extraction_pending_count_summary, displayState.pendingCount))
        appendLine()
        append(context.getString(R.string.preference_extraction_extracted_count_summary, displayState.extractedCount))
        appendLine()
        append(context.getString(R.string.settings_last_updated_summary, updatedAtText))
        appendLine()
        append(cooldownSummary)
        displayState.lastOutcomeMessage?.let { message ->
            appendLine()
            append(context.getString(R.string.preference_extraction_last_result_summary, message))
        }
        displayState.lastModelResponsePreview?.let { preview ->
            appendLine()
            append(context.getString(R.string.preference_extraction_last_raw_response_summary, preview))
        }
        buildPreferenceDebugSummaryLines(memoryState, projectionSourceType = "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION").forEach { line ->
            appendLine()
            append(line)
        }
        appendLine()
        append(context.getString(R.string.preference_extraction_pending_list_title))
        appendLine()
        append(displayState.pendingItems.ifEmpty { listOf(context.getString(R.string.settings_empty_list)) }.joinToString("\n"))
        appendLine()
        append(context.getString(R.string.preference_extraction_extracted_list_title))
        appendLine()
        append(displayState.extractedItems.ifEmpty { listOf(context.getString(R.string.settings_empty_list)) }.joinToString("\n"))
    }
}

internal fun formatPreferenceExtractionTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

internal fun formatPreferenceExtractionRawPayloadPreview(rawPayload: String, maxLength: Int = 220): String {
    return clipPreferenceExtractionText(
        rawPayload.replace('\n', ' ').replace("\r", " "),
        maxLength = maxLength
    )
}

private fun formatPendingPreferenceExtractionRecord(record: DialoguePreferenceBacklogRecord): String {
    val anchor = record.anchorObject
        ?.takeIf { value -> value.isNotBlank() }
        ?: record.focusedObject?.takeIf { value -> value.isNotBlank() }
        ?: record.action?.takeIf { value -> value.isNotBlank() }
        ?: record.sourceType.lowercase(Locale.getDefault())
    val summary = record.userMessage?.takeIf { value -> value.isNotBlank() }
    val slotSummary = if (record.detailSlots.isEmpty()) {
        null
    } else {
        record.detailSlots.take(3).joinToString(", ") { slot -> "${slot.key.wireName}=${slot.value}" }
    }
    return buildPreferenceExtractionBullet(
        buildString {
            append(anchor)
            summary?.let { value ->
                append(": ")
                append(clipPreferenceExtractionText(value))
            }
            if (summary == null && slotSummary != null) {
                append(": ")
                append(slotSummary)
            }
        }
    )
}

private fun buildPreferenceExtractionBullet(value: String): String {
    return "- ${clipPreferenceExtractionText(value)}"
}

private fun clipPreferenceExtractionText(value: String, maxLength: Int = 60): String {
    val trimmed = value.trim()
    if (trimmed.length <= maxLength) {
        return trimmed
    }
    return trimmed.take(maxLength - 3).trimEnd() + "..."
}

private fun truncatePreferenceExtractionItems(items: List<String>, limit: Int): List<String> {
    if (items.size <= limit) {
        return items
    }
    val remaining = items.size - limit
    return items.take(limit) + buildPreferenceExtractionBullet("+${remaining} more")
}