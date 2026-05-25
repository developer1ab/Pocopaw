package com.atombits.pocopaw

import com.atombits.pocopaw.learning.LearningCurationGateway
import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class OfflineDialoguePreferenceFact(
    val domain: String? = null,
    val anchorObject: String? = null,
    val facetKey: String,
    val facetValue: String,
    val polarity: String = "PREFER",
    val confidence: Double = 0.8,
    val freshnessHint: String = "RECENT"
)

data class OfflineDialogueInteractionBiasSignal(
    val domain: String? = null,
    val anchorObject: String? = null,
    val signalKey: String,
    val signalValue: String,
    val confidence: Double = 0.8,
    val freshnessHint: String = "RECENT"
)

data class OfflineDialoguePreferenceExtractionResult(
    val preferenceFacts: List<OfflineDialoguePreferenceFact>,
    val interactionBiasSignals: List<OfflineDialogueInteractionBiasSignal>,
    val habitEvidence: List<MemoryHabitRecord>,
    val styleEvidence: List<MemoryInteractionStyleRecord>
)

data class OfflineDialoguePreferenceProjectionOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

data class OfflineDialoguePreferenceExtractionResolveOutcome(
    val extractionResult: OfflineDialoguePreferenceExtractionResult?,
    val rawResponse: String? = null
)

private const val defaultOfflineDialoguePreferenceMinimumBacklogSize = 2
private val offlineDialogueInteractionBiasKeys = setOf(
    "preferred_process_id",
    "preferred_page_signature",
    "preferred_shortcut_screen"
)

interface OfflineDialoguePreferenceExtractionResolver {
    fun resolve(
        packet: PromptPacket,
        backlogBatch: DialoguePreferenceBacklogBatch,
        store: PrototypeStoreData,
        now: Long
    ): OfflineDialoguePreferenceExtractionResolveOutcome?
}

class SemanticOfflineDialoguePreferenceExtractionResolver(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient()
) : OfflineDialoguePreferenceExtractionResolver {
    private val gateway by lazy(LazyThreadSafetyMode.NONE) {
        LearningCurationGateway(client = client)
    }

    override fun resolve(
        packet: PromptPacket,
        backlogBatch: DialoguePreferenceBacklogBatch,
        store: PrototypeStoreData,
        now: Long
    ): OfflineDialoguePreferenceExtractionResolveOutcome? {
        return gateway.resolveOfflineDialoguePreferenceExtraction(packet, backlogBatch, store, now)
    }
}

fun applyOfflineDialoguePreferenceExtractionProjection(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: OfflineDialoguePreferenceExtractionResolver = SemanticOfflineDialoguePreferenceExtractionResolver()
): OfflineDialoguePreferenceProjectionOutcome {
    val backlogBatch = MemoryOrchestrator.buildDialoguePreferenceBacklogBatch(store)
        ?: return OfflineDialoguePreferenceProjectionOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.dialogue_preference_backlog_unavailable,
                "No dialogue preference backlog is available."
            )
        )
    val packet = PromptCenter.buildOfflineDialoguePreferenceExtractionPacket(
        OfflineDialoguePreferencePromptSpec(
            backlogBundle = backlogBatch.bundle,
            memoryBundle = MemoryOrchestrator.buildOfflineDialoguePreferenceMemoryBundle(store)
        )
    )
    val resolveOutcome = resolver.resolve(
        packet = packet,
        backlogBatch = backlogBatch,
        store = store,
        now = now
    ) ?: return OfflineDialoguePreferenceProjectionOutcome(
        updatedStore = withPreferenceExtractionDiagnostics(
            store = store,
            outcomeMessage = UiStrings.resolve(
                R.string.dialogue_preference_resolver_no_result,
                "Offline dialogue preference extraction resolver did not return a result."
            ),
            rawResponse = null
        ),
        applied = false,
        message = UiStrings.resolve(
            R.string.dialogue_preference_resolver_no_result,
            "Offline dialogue preference extraction resolver did not return a result."
        )
    )
    val extractionResult = resolveOutcome.extractionResult ?: return OfflineDialoguePreferenceProjectionOutcome(
        updatedStore = withPreferenceExtractionDiagnostics(
            store = store,
            outcomeMessage = UiStrings.resolve(
                R.string.dialogue_preference_resolver_no_result,
                "Offline dialogue preference extraction resolver did not return a result."
            ),
            rawResponse = resolveOutcome.rawResponse
        ),
        applied = false,
        message = UiStrings.resolve(
            R.string.dialogue_preference_resolver_no_result,
            "Offline dialogue preference extraction resolver did not return a result."
        )
    )
    if (extractionResult.preferenceFacts.isEmpty() &&
        extractionResult.interactionBiasSignals.isEmpty() &&
        extractionResult.habitEvidence.isEmpty() &&
        extractionResult.styleEvidence.isEmpty()
    ) {
        return OfflineDialoguePreferenceProjectionOutcome(
            updatedStore = withPreferenceExtractionDiagnostics(
                store = store,
                outcomeMessage = UiStrings.resolve(
                    R.string.dialogue_preference_no_stable_evidence,
                    "Offline dialogue preference extraction did not produce stable evidence; backlog was kept."
                ),
                rawResponse = resolveOutcome.rawResponse
            ),
            applied = false,
            message = UiStrings.resolve(
                R.string.dialogue_preference_no_stable_evidence,
                "Offline dialogue preference extraction did not produce stable evidence; backlog was kept."
            )
        )
    }
    val updatedMemoryState = MemoryOrchestrator.applyOfflineDialoguePreferenceExtraction(
        store = store,
        extractionResult = extractionResult,
        consumedBacklogRecordIds = backlogBatch.records.map { record -> record.recordId },
        now = now
    )
    return OfflineDialoguePreferenceProjectionOutcome(
        updatedStore = withPreferenceExtractionDiagnostics(
            store = store.withUpdatedMemoryState(updatedMemoryState),
            outcomeMessage = UiStrings.resolve(
                R.string.dialogue_preference_applied,
                "Applied offline dialogue preference extraction for %1\$d backlog record(s).",
                backlogBatch.records.size
            ),
            rawResponse = resolveOutcome.rawResponse
        ),
        applied = true,
        message = UiStrings.resolve(
            R.string.dialogue_preference_applied,
            "Applied offline dialogue preference extraction for %1\$d backlog record(s).",
            backlogBatch.records.size
        )
    )
}

fun applyScheduledOfflineDialoguePreferenceExtractionProjection(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: OfflineDialoguePreferenceExtractionResolver = SemanticOfflineDialoguePreferenceExtractionResolver(),
    minimumBacklogSize: Int = defaultOfflineDialoguePreferenceMinimumBacklogSize
): OfflineDialoguePreferenceProjectionOutcome {
    val memoryState = store.memoryState ?: MemoryState()
    val effectiveMinimumBacklogSize = minimumBacklogSize.coerceAtLeast(1)
    if (memoryState.dialoguePreferenceBacklog.size < effectiveMinimumBacklogSize) {
        return OfflineDialoguePreferenceProjectionOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.dialogue_preference_threshold_not_reached,
                "Dialogue preference backlog has not reached extraction threshold."
            )
        )
    }

    val runtime = memoryState.dialoguePreferenceExtractionRuntime
    val scheduledRuntime = runtime.copy(
        lastScheduledAt = now,
        nextEligibleExtractionAt = null
    )
    val scheduledStore = store.withUpdatedMemoryState(
        memoryState.copy(dialoguePreferenceExtractionRuntime = scheduledRuntime)
    )
    val outcome = applyOfflineDialoguePreferenceExtractionProjection(
        store = scheduledStore,
        now = now,
        resolver = resolver
    )
    val outcomeMemoryState = outcome.updatedStore.memoryState ?: MemoryState()
    val updatedRuntime = outcomeMemoryState.dialoguePreferenceExtractionRuntime.copy(
        lastScheduledAt = now,
        nextEligibleExtractionAt = null,
        lastConsumedAt = if (outcome.applied) now else outcomeMemoryState.dialoguePreferenceExtractionRuntime.lastConsumedAt,
        lastOutcomeMessage = outcomeMemoryState.dialoguePreferenceExtractionRuntime.lastOutcomeMessage,
        lastModelResponsePayload = outcomeMemoryState.dialoguePreferenceExtractionRuntime.lastModelResponsePayload
    )
    return outcome.copy(
        updatedStore = outcome.updatedStore.withUpdatedMemoryState(
            outcomeMemoryState.copy(dialoguePreferenceExtractionRuntime = updatedRuntime)
        )
    )
}

fun parseOfflineDialoguePreferenceExtractionResult(
    raw: String,
    sourceType: String = "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION"
): OfflineDialoguePreferenceExtractionResult {
    val payload = parseStructuredPromptPayloadObject(raw)
    val structuredPreferenceFacts = payload.getJsonArrayOrEmpty("preference_facts").mapNotNull { item ->
        item.asJsonObjectOrNull()?.toOfflineDialoguePreferenceFact()
    }
    val interactionBiasSignals = payload.getJsonArrayOrEmpty("interaction_bias_signals").mapNotNull { item ->
        item.asJsonObjectOrNull()?.toOfflineDialogueInteractionBiasSignal()
    }
    val legacyPreferenceEvidence = payload.getJsonArrayOrEmpty("preference_evidence").mapNotNull { item ->
        item.asJsonObjectOrNull()?.toLegacyOfflineDialoguePreferenceSignal()
    }
    val legacyPreferenceFacts = legacyPreferenceEvidence
        .filterNot { signal -> signal.slotKey in offlineDialogueInteractionBiasKeys }
        .map { signal ->
            OfflineDialoguePreferenceFact(
                domain = signal.domain,
                anchorObject = signal.anchorObject,
                facetKey = signal.slotKey,
                facetValue = signal.slotValue,
                polarity = signal.polarity,
                confidence = signal.confidence,
                freshnessHint = signal.freshnessHint
            )
        }
    val legacyInteractionBiasSignals = legacyPreferenceEvidence
        .filter { signal -> signal.slotKey in offlineDialogueInteractionBiasKeys }
        .map { signal ->
            OfflineDialogueInteractionBiasSignal(
                domain = signal.domain,
                anchorObject = signal.anchorObject,
                signalKey = signal.slotKey,
                signalValue = signal.slotValue,
                confidence = signal.confidence,
                freshnessHint = signal.freshnessHint
            )
        }
    return OfflineDialoguePreferenceExtractionResult(
        preferenceFacts = (structuredPreferenceFacts + legacyPreferenceFacts)
            .distinctBy { fact ->
                listOf(
                    fact.domain.orEmpty(),
                    fact.anchorObject.orEmpty(),
                    fact.facetKey,
                    fact.facetValue,
                    fact.polarity,
                    fact.freshnessHint
                ).joinToString("|")
            },
        interactionBiasSignals = (interactionBiasSignals + legacyInteractionBiasSignals)
            .distinctBy { signal ->
                listOf(
                    signal.domain.orEmpty(),
                    signal.anchorObject.orEmpty(),
                    signal.signalKey,
                    signal.signalValue,
                    signal.freshnessHint
                ).joinToString("|")
            },
        habitEvidence = payload.getJsonArrayOrEmpty("habit_evidence").mapNotNull { item ->
            item.asJsonObjectOrNull()?.toHabitRecord()
        },
        styleEvidence = payload.getJsonArrayOrEmpty("style_evidence").mapNotNull { item ->
            item.asJsonObjectOrNull()?.toInteractionStyleRecord(sourceType)
        }
    )
}

private data class LegacyOfflineDialoguePreferenceSignal(
    val domain: String? = null,
    val anchorObject: String? = null,
    val slotKey: String,
    val slotValue: String,
    val polarity: String,
    val confidence: Double,
    val freshnessHint: String
)

private fun JsonObject.toOfflineDialoguePreferenceFact(): OfflineDialoguePreferenceFact? {
    val facetKey = getAliasedString("facet_key", "slot_key")?.trim().orEmpty()
    val facetValue = getAliasedString("facet_value", "slot_value")?.trim().orEmpty()
    if (facetKey.isBlank() || facetValue.isBlank()) {
        return null
    }
    return OfflineDialoguePreferenceFact(
        domain = getStringOrNull("domain")?.trim()?.ifBlank { null },
        anchorObject = getAliasedString("anchor_object", "anchor")?.trim()?.ifBlank { null },
        facetKey = facetKey,
        facetValue = facetValue,
        polarity = getStringOrNull("polarity")?.trim()?.ifBlank { "PREFER" } ?: "PREFER",
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        freshnessHint = getStringOrNull("freshness_hint")?.trim()?.ifBlank { "RECENT" } ?: "RECENT"
    )
}

private fun JsonObject.toOfflineDialogueInteractionBiasSignal(): OfflineDialogueInteractionBiasSignal? {
    val signalKey = getAliasedString("signal_key", "slot_key")?.trim().orEmpty()
    val signalValue = getAliasedString("signal_value", "slot_value")?.trim().orEmpty()
    if (signalKey.isBlank() || signalValue.isBlank()) {
        return null
    }
    return OfflineDialogueInteractionBiasSignal(
        domain = getStringOrNull("domain")?.trim()?.ifBlank { null },
        anchorObject = getAliasedString("anchor_object", "anchor")?.trim()?.ifBlank { null },
        signalKey = signalKey,
        signalValue = signalValue,
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        freshnessHint = getStringOrNull("freshness_hint")?.trim()?.ifBlank { "RECENT" } ?: "RECENT"
    )
}

private fun JsonObject.toLegacyOfflineDialoguePreferenceSignal(): LegacyOfflineDialoguePreferenceSignal? {
    val slotKey = getAliasedString("slot_key", "slot")?.trim().orEmpty()
    val slotValue = getAliasedString("slot_value", "value")?.trim().orEmpty()
    if (slotKey.isBlank() || slotValue.isBlank()) {
        return null
    }
    return LegacyOfflineDialoguePreferenceSignal(
        domain = getStringOrNull("domain")?.trim()?.ifBlank { null },
        anchorObject = getAliasedString("anchor_object", "anchor")?.trim()?.ifBlank { null },
        slotKey = slotKey,
        slotValue = slotValue,
        polarity = getStringOrNull("polarity")?.trim()?.ifBlank { "PREFER" } ?: "PREFER",
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        freshnessHint = getStringOrNull("freshness_hint")?.trim()?.ifBlank { "RECENT" } ?: "RECENT"
    )
}

private fun JsonObject.toHabitRecord(): MemoryHabitRecord? {
    val habitType = getStringOrNull("habit_type")?.trim().orEmpty()
    val timeWindow = getStringOrNull("time_window")?.trim().orEmpty()
    val triggerContext = getStringOrNull("trigger_context")?.trim().orEmpty()
    if (habitType.isBlank() || timeWindow.isBlank() || triggerContext.isBlank()) {
        return null
    }
    return MemoryHabitRecord(
        habitType = habitType,
        timeWindow = timeWindow,
        triggerContext = triggerContext,
        stabilityScore = getDoubleOrDefault("stability_score", 0.8).coerceIn(0.0, 1.0),
        preferredProactiveSignal = getStringOrNull("preferred_proactive_signal")?.trim()?.ifBlank { null },
        preferredDeliveryStyle = getStringOrNull("preferred_delivery_style")?.trim()?.ifBlank { null },
        lastObservedAt = System.currentTimeMillis()
    )
}

private fun JsonObject.toInteractionStyleRecord(sourceType: String): MemoryInteractionStyleRecord? {
    val styleKey = getStringOrNull("style_key")?.trim().orEmpty()
    val styleValue = getStringOrNull("style_value")?.trim().orEmpty()
    if (styleKey.isBlank() || styleValue.isBlank()) {
        return null
    }
    return MemoryInteractionStyleRecord(
        styleKey = styleKey,
        styleValue = styleValue,
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        sourceType = sourceType
    )
}

private fun JsonObject.getJsonArrayOrEmpty(memberName: String): JsonArray {
    val value = get(memberName)
    return if (value != null && value.isJsonArray) {
        value.asJsonArray
    } else {
        JsonArray()
    }
}

private fun JsonObject.getStringOrNull(memberName: String): String? {
    val value = get(memberName) ?: return null
    return if (value.isJsonNull) null else runCatching { value.asString }.getOrNull()
}

private fun JsonObject.getAliasedString(primaryName: String, aliasName: String): String? {
    return getStringOrNull(primaryName) ?: getStringOrNull(aliasName)
}

private fun JsonObject.getDoubleOrDefault(memberName: String, defaultValue: Double): Double {
    val value = get(memberName) ?: return defaultValue
    return if (value.isJsonNull) {
        defaultValue
    } else {
        runCatching { value.asDouble }.getOrDefault(defaultValue)
    }
}

private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private fun withPreferenceExtractionDiagnostics(
    store: PrototypeStoreData,
    outcomeMessage: String,
    rawResponse: String?
): PrototypeStoreData {
    val memoryState = store.memoryState ?: MemoryState()
    return store.withUpdatedMemoryState(
        memoryState.copy(
            dialoguePreferenceExtractionRuntime = memoryState.dialoguePreferenceExtractionRuntime.copy(
                lastOutcomeMessage = outcomeMessage,
                lastModelResponsePayload = rawResponse?.trim()?.ifBlank { null }
            )
        )
    )
}