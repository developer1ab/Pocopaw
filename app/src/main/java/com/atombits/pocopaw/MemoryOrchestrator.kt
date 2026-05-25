package com.atombits.pocopaw

import java.util.Locale
import java.util.UUID

data class MemoryGroundingRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val candidateId: String,
    val anchorObject: String,
    val focusedObject: String,
    val action: String,
    val sourceType: String = "DIALOGUE",
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class MemoryContinuationRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val candidateId: String,
    val anchorObject: String,
    val focusedObject: String,
    val action: String,
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class MemoryRecentFactRecord(
    val factId: String = UUID.randomUUID().toString(),
    val candidateId: String? = null,
    val factText: String,
    val confidence: Double = 0.8,
    val freshnessHint: String = "RECENT",
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class MemoryPreferenceEvidenceRecord(
    val evidenceId: String = UUID.randomUUID().toString(),
    val domain: String? = null,
    val anchorObject: String? = null,
    val slotKey: String,
    val slotValue: String,
    val polarity: String = "PREFER",
    val confidence: Double = 0.8,
    val sourceType: String = "DIALOGUE",
    val sourceApp: String? = null,
    val freshnessHint: String = "RECENT",
    val observationCount: Int = 1,
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class MemoryHabitRecord(
    val habitId: String = UUID.randomUUID().toString(),
    val habitType: String,
    val timeWindow: String,
    val triggerContext: String,
    val stabilityScore: Double = 0.8,
    val preferredProactiveSignal: String? = null,
    val preferredDeliveryStyle: String? = null,
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class MemoryInteractionStyleRecord(
    val styleId: String = UUID.randomUUID().toString(),
    val styleKey: String,
    val styleValue: String,
    val confidence: Double = 0.8,
    val sourceType: String = "DIALOGUE",
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class PreferenceSummaryCard(
    val cardId: String = UUID.randomUUID().toString(),
    val domain: String,
    val anchorObject: String,
    val summary: String,
    val supportingSourceApps: List<String> = emptyList(),
    val confidence: Double = 0.8,
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class PreferenceFacetFact(
    val id: String,
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val facetKey: String,
    val facetValue: String,
    val polarity: String,
    val confidence: Double,
    val freshnessHint: String,
    val sourceType: String,
    val sourceApp: String? = null,
    val sourceRef: String,
    val lastObservedAt: Long,
    val observationCount: Int,
    val transferabilityTag: String
)

data class PreferenceRecentFact(
    val id: String,
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val summary: String,
    val confidence: Double,
    val sourceType: String,
    val sourceRef: String,
    val lastObservedAt: Long,
    val supportingSourceApps: List<String> = emptyList()
)

data class PreferenceSemanticChunk(
    val id: String,
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val summaryText: String,
    val embedding: List<Double> = emptyList(),
    val confidence: Double,
    val sourceWeight: Double,
    val sourceType: String,
    val sourceRef: String,
    val lastObservedAt: Long,
    val semanticScope: String
)

data class StructuredPreferenceMemoryState(
    val facts: List<PreferenceFacetFact> = emptyList(),
    val recentFacts: List<PreferenceRecentFact> = emptyList(),
    val semanticChunks: List<PreferenceSemanticChunk> = emptyList(),
    val lastUpdatedAt: Long? = null
)

data class InteractionBiasRecord(
    val id: String,
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val signalKey: String,
    val signalValue: String,
    val confidence: Double,
    val freshnessHint: String,
    val sourceType: String,
    val sourceApp: String? = null,
    val sourceRef: String,
    val lastObservedAt: Long,
    val observationCount: Int
)

data class InteractionBiasMemoryState(
    val preferredProcesses: List<InteractionBiasRecord> = emptyList(),
    val preferredPages: List<InteractionBiasRecord> = emptyList(),
    val preferredShortcuts: List<InteractionBiasRecord> = emptyList(),
    val lastUpdatedAt: Long? = null
)

data class DialoguePreferenceBacklogRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val sourceType: String,
    val candidateId: String? = null,
    val anchorObject: String? = null,
    val focusedObject: String? = null,
    val action: String? = null,
    val userMessage: String? = null,
    val assistantReply: String? = null,
    val slotEvidenceSnapshot: TaskSlotEvidenceSnapshot? = null,
    val detailSlots: List<DetailSlot> = emptyList(),
    val semanticSummary: String? = null,
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class DialoguePreferenceBacklogBatch(
    val records: List<DialoguePreferenceBacklogRecord>,
    val bundle: String
)

data class ProactiveOpportunityRecord(
    val opportunityId: String = UUID.randomUUID().toString(),
    val signal: ProactiveOpportunitySignal,
    val title: String,
    val summary: String,
    val anchorObject: String? = null,
    val candidateId: String? = null,
    val sourceType: String = "MEMORY",
    val confidence: Double = 0.8,
    val lastObservedAt: Long = System.currentTimeMillis()
)

enum class ProactiveFeedbackType {
    ACCEPTED,
    IGNORED,
    DISMISSED,
    REJECTED
}

data class ProactiveFeedbackRecord(
    val feedbackId: String = UUID.randomUUID().toString(),
    val planFingerprint: String,
    val feedbackType: ProactiveFeedbackType,
    val recordedAt: Long = System.currentTimeMillis(),
    val cooldownUntil: Long? = null
)

data class PreferenceDiscoveryRuntimeState(
    val lastScheduledAt: Long? = null,
    val nextEligibleScanAt: Long? = null,
    val lastConsumedAt: Long? = null,
    val attemptedSourceApps: List<String> = emptyList(),
    val lastOutcomeMessage: String? = null
)

data class DialoguePreferenceExtractionRuntimeState(
    val lastScheduledAt: Long? = null,
    val nextEligibleExtractionAt: Long? = null,
    val lastConsumedAt: Long? = null,
    val lastOutcomeMessage: String? = null,
    val lastModelResponsePayload: String? = null
)

data class TopicContextRecord(
    val topicId: String,
    val summaryText: String,
    val anchorObject: String? = null,
    val focusedObject: String? = null,
    val action: String? = null,
    val preferredAppScope: String? = null,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val structuredDetailSlots: TaskDetailSlots = TaskDetailSlots(),
    val resolvedSlots: Map<String, String> = emptyMap(),
    val sourceTurnIds: List<String> = emptyList(),
    val matchingHints: List<String> = emptyList(),
    val lastSourceType: String = "DIALOGUE",
    val lastTouchedAt: Long = System.currentTimeMillis()
)

data class TopicContextStore(
    val activeTopicId: String? = null,
    val activeTopicRecord: TopicContextRecord? = null,
    val silentTopicRecords: List<TopicContextRecord> = emptyList(),
    val lastUpdatedAt: Long? = null
)

data class PreferenceProjectionDebugSnapshot(
    val sourceType: String,
    val domain: String,
    val anchorObject: String,
    val factCount: Int,
    val biasCount: Int,
    val recentFactCount: Int,
    val semanticChunkCount: Int,
    val summaryText: String? = null,
    val generatedAt: Long = System.currentTimeMillis()
)

data class PreferenceRecallDebugSnapshot(
    val domain: String,
    val anchorObject: String,
    val directCount: Int,
    val recentCount: Int,
    val semanticCount: Int,
    val neighborCount: Int,
    val derivedCount: Int,
    val siblingExpansionEnabled: Boolean,
    val debugSummary: String,
    val generatedAt: Long = System.currentTimeMillis()
)

enum class PreferenceSlotDecisionType {
    ACCEPTED,
    BLOCKED,
    CONFIRMATION_NEEDED
}

data class PreferenceSlotMappingDecision(
    val slotKey: String,
    val slotValue: String? = null,
    val sourceTier: String? = null,
    val decisionType: PreferenceSlotDecisionType
)

data class PreferenceSlotMappingTrace(
    val domain: String? = null,
    val decisions: List<PreferenceSlotMappingDecision> = emptyList(),
    val siblingExpansionEnabled: Boolean = false,
    val debugSummary: String = "",
    val generatedAt: Long = System.currentTimeMillis()
)

data class PreferenceDebugStore(
    val projectionSnapshots: List<PreferenceProjectionDebugSnapshot> = emptyList(),
    val lastRecallDebugSnapshot: PreferenceRecallDebugSnapshot? = null,
    val lastMappingTrace: PreferenceSlotMappingTrace? = null
)

data class MemoryState(
    val activeGroundingStore: List<MemoryGroundingRecord> = emptyList(),
    val continuationStore: List<MemoryContinuationRecord> = emptyList(),
    val recentFactStore: List<MemoryRecentFactRecord> = emptyList(),
    val structuredPreferenceMemory: StructuredPreferenceMemoryState = StructuredPreferenceMemoryState(),
    val interactionBiasMemory: InteractionBiasMemoryState = InteractionBiasMemoryState(),
    val habitMemoryStore: List<MemoryHabitRecord> = emptyList(),
    val interactionStyleStore: List<MemoryInteractionStyleRecord> = emptyList(),
    val dialoguePreferenceBacklog: List<DialoguePreferenceBacklogRecord> = emptyList(),
    val dialoguePreferenceExtractionRuntime: DialoguePreferenceExtractionRuntimeState = DialoguePreferenceExtractionRuntimeState(),
    val preferenceDiscoveryRuntime: PreferenceDiscoveryRuntimeState = PreferenceDiscoveryRuntimeState(),
    val pendingAppPreferenceScans: List<AppPreferenceScanPayload> = emptyList(),
    val proactiveOpportunityStore: List<ProactiveOpportunityRecord> = emptyList(),
    val proactiveFeedbackStore: List<ProactiveFeedbackRecord> = emptyList(),
    val preferenceDebugStore: PreferenceDebugStore = PreferenceDebugStore(),
    val semanticRecallIndex: List<String> = emptyList(),
    val processCandidateStore: List<ProcessCandidateRecord> = emptyList(),
    val processFeedbackStore: List<ProcessFeedbackRecord> = emptyList(),
    val topicContextStore: TopicContextStore = TopicContextStore()
)

private val interactionBiasPreferenceKeys = setOf(
    "preferred_process_id",
    "preferred_page_signature",
    "preferred_shortcut_screen"
)

internal const val preferenceSummaryProjectionSourceType = "SUMMARY_CARD_PROJECTION"

internal fun InteractionBiasMemoryState.allRecords(): List<InteractionBiasRecord> {
    return preferredProcesses + preferredPages + preferredShortcuts
}

internal fun MemoryState.withProjectedPreferenceRecords(
    records: List<MemoryPreferenceEvidenceRecord>,
    projectedSummaryCards: List<PreferenceSummaryCard> = emptyList()
): MemoryState {
    val normalizedRecords = records.mapNotNull(::normalizeMemoryPreferenceEvidenceRecord)
        .distinctBy { record ->
            listOf(
                record.domain.orEmpty(),
                record.anchorObject.orEmpty(),
                record.slotKey,
                record.slotValue,
                record.polarity,
                record.sourceType,
                record.sourceApp.orEmpty()
            ).joinToString("|")
        }
        .sortedByDescending { record -> record.lastObservedAt }
        .take(48)
    return copy(
        structuredPreferenceMemory = rebuildStructuredPreferenceMemoryState(
            records = normalizedRecords,
            projectedSummaryCards = projectedSummaryCards,
            preservedSemanticChunks = structuredPreferenceMemory.semanticChunks
        ),
        interactionBiasMemory = rebuildInteractionBiasMemoryState(normalizedRecords)
    )
}

internal fun MemoryState.appendProjectedPreferenceRecords(
    records: List<MemoryPreferenceEvidenceRecord>,
    projectedSummaryCards: List<PreferenceSummaryCard> = emptyList(),
    limit: Int = 48
): MemoryState {
    if (records.isEmpty() && projectedSummaryCards.isEmpty()) {
        return this
    }
    val mergedRecords = (records + buildProjectedPreferenceRecords(this))
        .mapNotNull(::normalizeMemoryPreferenceEvidenceRecord)
        .distinctBy { record ->
            listOf(
                record.domain.orEmpty(),
                record.anchorObject.orEmpty(),
                record.slotKey,
                record.slotValue,
                record.polarity,
                record.sourceType,
                record.sourceApp.orEmpty()
            ).joinToString("|")
        }
        .sortedByDescending { record -> record.lastObservedAt }
        .take(limit.coerceAtLeast(1))
    return withProjectedPreferenceRecords(
        records = mergedRecords,
        projectedSummaryCards = projectedSummaryCards
    )
}

internal fun MemoryState.appendPreferenceSignalProjections(
    projections: List<PreferenceMemorySignalProjection>,
    limit: Int = 48
): MemoryState {
    if (projections.isEmpty()) {
        return this
    }
    val mergedFacts = mergePreferenceFacetFacts(
        incomingFacts = projections.flatMap { projection -> projection.preferenceFacts },
        existingFacts = structuredPreferenceMemory.facts,
        limit = limit
    )
    val mergedBiasRecords = mergeInteractionBiasRecords(
        incomingRecords = projections.flatMap { projection -> projection.interactionBiasRecords },
        existingRecords = interactionBiasMemory.allRecords()
    )
    val mergedRecentFacts = (projections.flatMap { projection -> projection.recentFacts } + structuredPreferenceMemory.recentFacts)
        .distinctBy { fact -> listOf(fact.domainRoot, fact.domain, fact.anchorObject, fact.summary, fact.sourceType).joinToString("|") }
        .sortedByDescending { fact -> fact.lastObservedAt }
        .take(24)
    val mergedSemanticChunks = (projections.flatMap { projection -> projection.semanticChunks } + structuredPreferenceMemory.semanticChunks)
        .distinctBy { chunk -> listOf(chunk.domainRoot, chunk.domain, chunk.anchorObject, chunk.summaryText, chunk.sourceType).joinToString("|") }
        .sortedByDescending { chunk -> chunk.lastObservedAt }
        .take(24)
    val lastUpdatedAt = listOfNotNull(
        mergedFacts.maxOfOrNull { fact -> fact.lastObservedAt },
        mergedRecentFacts.maxOfOrNull { fact -> fact.lastObservedAt },
        mergedSemanticChunks.maxOfOrNull { chunk -> chunk.lastObservedAt }
    ).maxOrNull()
    return copy(
        structuredPreferenceMemory = structuredPreferenceMemory.copy(
            facts = mergedFacts,
            recentFacts = mergedRecentFacts,
            semanticChunks = mergedSemanticChunks,
            lastUpdatedAt = lastUpdatedAt
        ),
        interactionBiasMemory = InteractionBiasMemoryState(
            preferredProcesses = mergedBiasRecords.filter { bias -> bias.signalKey == "preferred_process_id" },
            preferredPages = mergedBiasRecords.filter { bias -> bias.signalKey == "preferred_page_signature" },
            preferredShortcuts = mergedBiasRecords.filter { bias -> bias.signalKey == "preferred_shortcut_screen" },
            lastUpdatedAt = mergedBiasRecords.maxOfOrNull { bias -> bias.lastObservedAt }
        ),
        preferenceDebugStore = preferenceDebugStore.appendProjectionSnapshots(
            projections.mapNotNull(::toPreferenceProjectionDebugSnapshot)
        )
    )
}

private fun mergePreferenceFacetFacts(
    incomingFacts: List<PreferenceFacetFact>,
    existingFacts: List<PreferenceFacetFact>,
    limit: Int
): List<PreferenceFacetFact> {
    return (incomingFacts + existingFacts)
        .groupBy(::preferenceFactMergeKey)
        .values
        .map { group ->
            val latest = group.maxByOrNull { fact -> fact.lastObservedAt } ?: error("missing latest fact")
            latest.copy(observationCount = group.sumOf { fact -> fact.observationCount.coerceAtLeast(1) })
        }
        .sortedByDescending { fact -> fact.lastObservedAt }
        .take(limit.coerceAtLeast(1))
}

private fun mergeInteractionBiasRecords(
    incomingRecords: List<InteractionBiasRecord>,
    existingRecords: List<InteractionBiasRecord>
): List<InteractionBiasRecord> {
    return (incomingRecords + existingRecords)
        .groupBy(::interactionBiasMergeKey)
        .values
        .map { group ->
            val latest = group.maxByOrNull { bias -> bias.lastObservedAt } ?: error("missing latest bias")
            latest.copy(observationCount = group.sumOf { bias -> bias.observationCount.coerceAtLeast(1) })
        }
        .sortedByDescending { bias -> bias.lastObservedAt }
        .take(24)
}

private fun preferenceFactMergeKey(fact: PreferenceFacetFact): String {
    return listOf(
        fact.domainRoot,
        fact.domain,
        fact.anchorObject,
        fact.facetKey,
        fact.facetValue,
        fact.polarity,
        fact.sourceType,
        fact.sourceApp.orEmpty()
    ).joinToString("|")
}

private fun interactionBiasMergeKey(record: InteractionBiasRecord): String {
    return listOf(
        record.domainRoot,
        record.domain,
        record.anchorObject,
        record.signalKey,
        record.signalValue,
        record.sourceType,
        record.sourceApp.orEmpty()
    ).joinToString("|")
}

private fun PreferenceDebugStore.appendProjectionSnapshots(
    snapshots: List<PreferenceProjectionDebugSnapshot>
): PreferenceDebugStore {
    if (snapshots.isEmpty()) {
        return this
    }
    return copy(
        projectionSnapshots = (snapshots + projectionSnapshots)
            .distinctBy { snapshot ->
                listOf(
                    snapshot.sourceType,
                    snapshot.domain,
                    snapshot.anchorObject,
                    snapshot.summaryText.orEmpty()
                ).joinToString("|")
            }
            .sortedByDescending { snapshot -> snapshot.generatedAt }
            .take(12)
    )
}

private fun toPreferenceProjectionDebugSnapshot(
    projection: PreferenceMemorySignalProjection
): PreferenceProjectionDebugSnapshot? {
    val sourceType = projection.preferenceFacts.firstOrNull()?.sourceType
        ?: projection.interactionBiasRecords.firstOrNull()?.sourceType
        ?: projection.recentFacts.firstOrNull()?.sourceType
        ?: projection.semanticChunks.firstOrNull()?.sourceType
        ?: return null
    val domain = projection.preferenceFacts.firstOrNull()?.domain
        ?: projection.interactionBiasRecords.firstOrNull()?.domain
        ?: projection.recentFacts.firstOrNull()?.domain
        ?: projection.semanticChunks.firstOrNull()?.domain
        ?: CapabilityDomain.OTHER.wireName
    val anchorObject = projection.preferenceFacts.firstOrNull()?.anchorObject
        ?: projection.interactionBiasRecords.firstOrNull()?.anchorObject
        ?: projection.recentFacts.firstOrNull()?.anchorObject
        ?: projection.semanticChunks.firstOrNull()?.anchorObject
        ?: domain
    val generatedAt = listOfNotNull(
        projection.preferenceFacts.maxOfOrNull { fact -> fact.lastObservedAt },
        projection.interactionBiasRecords.maxOfOrNull { bias -> bias.lastObservedAt },
        projection.recentFacts.maxOfOrNull { fact -> fact.lastObservedAt },
        projection.semanticChunks.maxOfOrNull { chunk -> chunk.lastObservedAt }
    ).maxOrNull() ?: System.currentTimeMillis()
    return PreferenceProjectionDebugSnapshot(
        sourceType = sourceType,
        domain = domain,
        anchorObject = anchorObject,
        factCount = projection.preferenceFacts.size,
        biasCount = projection.interactionBiasRecords.size,
        recentFactCount = projection.recentFacts.size,
        semanticChunkCount = projection.semanticChunks.size,
        summaryText = projection.recentFacts.firstOrNull()?.summary ?: projection.semanticChunks.firstOrNull()?.summaryText,
        generatedAt = generatedAt
    )
}

internal fun PreferenceSummaryCard.asPreferenceEvidenceRecords(
    sourceType: String = "LEGACY_PREFERENCE_SUMMARY"
): List<MemoryPreferenceEvidenceRecord> {
    val anchor = anchorObject.trim().ifBlank { return emptyList() }
    val normalizedDomain = domain.trim().ifBlank { CapabilityDomain.OTHER.wireName }
    return summary.split(';')
        .mapNotNull { segment ->
            val parts = segment.split('=', limit = 2)
            val key = parts.getOrNull(0)?.trim()?.lowercase(Locale.US).orEmpty()
            val value = parts.getOrNull(1)
                ?.trim()
                ?.substringBefore("[")
                ?.trim()
                .orEmpty()
            if (key.isBlank() || value.isBlank()) {
                null
            } else {
                MemoryPreferenceEvidenceRecord(
                    domain = normalizedDomain,
                    anchorObject = anchor,
                    slotKey = key,
                    slotValue = value,
                    polarity = "PREFER",
                    confidence = confidence,
                    sourceType = sourceType,
                    sourceApp = supportingSourceApps.firstOrNull(),
                    freshnessHint = "LONG_TERM",
                    lastObservedAt = lastObservedAt
                )
            }
        }
}

private fun buildProjectedPreferenceRecords(memoryState: MemoryState): List<MemoryPreferenceEvidenceRecord> {
    val factRecords = memoryState.structuredPreferenceMemory.facts.map { fact ->
        MemoryPreferenceEvidenceRecord(
            evidenceId = fact.id,
            domain = fact.domain.uppercase(Locale.US),
            anchorObject = fact.anchorObject,
            slotKey = fact.facetKey,
            slotValue = fact.facetValue,
            polarity = fact.polarity,
            confidence = fact.confidence,
            sourceType = fact.sourceType,
            sourceApp = fact.sourceApp,
            freshnessHint = fact.freshnessHint,
            observationCount = fact.observationCount,
            lastObservedAt = fact.lastObservedAt
        )
    }
    val biasRecords = (
        memoryState.interactionBiasMemory.preferredProcesses +
            memoryState.interactionBiasMemory.preferredPages +
            memoryState.interactionBiasMemory.preferredShortcuts
        ).map { bias ->
            MemoryPreferenceEvidenceRecord(
                evidenceId = bias.id,
                domain = bias.domain.uppercase(Locale.US),
                anchorObject = bias.anchorObject,
                slotKey = bias.signalKey,
                slotValue = bias.signalValue,
                polarity = "PREFER",
                confidence = bias.confidence,
                sourceType = bias.sourceType,
                sourceApp = bias.sourceApp,
                freshnessHint = bias.freshnessHint,
                observationCount = bias.observationCount,
                lastObservedAt = bias.lastObservedAt
            )
        }
    return (factRecords + biasRecords)
        .distinctBy { record ->
            listOf(
                record.domain.orEmpty(),
                record.anchorObject.orEmpty(),
                record.slotKey,
                record.slotValue,
                record.sourceType,
                record.sourceApp.orEmpty()
            ).joinToString("|")
        }
        .sortedByDescending { record -> record.lastObservedAt }
        .take(12)
}

private fun normalizeMemoryPreferenceEvidenceRecord(
    record: MemoryPreferenceEvidenceRecord
): MemoryPreferenceEvidenceRecord? {
    val slotKey = record.slotKey.trim().lowercase(Locale.US)
    val slotValue = record.slotValue.trim()
    if (slotKey.isBlank() || slotValue.isBlank()) {
        return null
    }
    return record.copy(
        domain = record.domain?.trim()?.takeIf { value -> value.isNotBlank() },
        anchorObject = record.anchorObject?.trim()?.takeIf { value -> value.isNotBlank() },
        slotKey = slotKey,
        slotValue = slotValue,
        polarity = record.polarity.trim().ifBlank { "PREFER" },
        sourceType = record.sourceType.trim().ifBlank { "DIALOGUE" },
        sourceApp = record.sourceApp?.trim()?.takeIf { value -> value.isNotBlank() },
        freshnessHint = record.freshnessHint.trim().ifBlank { "RECENT" },
        observationCount = record.observationCount.coerceAtLeast(1)
    )
}

private fun rebuildStructuredPreferenceMemoryState(
    records: List<MemoryPreferenceEvidenceRecord>,
    projectedSummaryCards: List<PreferenceSummaryCard>,
    preservedSemanticChunks: List<PreferenceSemanticChunk>
): StructuredPreferenceMemoryState {
    val facts = records
        .groupBy(::memoryPreferenceFactMergeKey)
        .values
        .mapNotNull { group ->
            val latest = group.maxByOrNull { record -> record.lastObservedAt } ?: return@mapNotNull null
            latest.toPreferenceFacetFact(observationCount = group.sumOf { record -> record.observationCount.coerceAtLeast(1) })
        }
        .sortedByDescending { fact -> fact.lastObservedAt }
        .take(48)
    val derivedRecentFacts = facts.map { fact ->
        PreferenceRecentFact(
            id = fact.id,
            domainRoot = fact.domainRoot,
            domain = fact.domain,
            anchorObject = fact.anchorObject,
            summary = "${fact.facetKey}=${fact.facetValue}",
            confidence = fact.confidence,
            sourceType = fact.sourceType,
            sourceRef = fact.sourceRef,
            lastObservedAt = fact.lastObservedAt,
            supportingSourceApps = listOfNotNull(fact.sourceApp)
        )
    }.take(24)
    val summaryProjectionFacts = projectedSummaryCards.map { card ->
        val capabilityDomain = CapabilityDomain.fromRaw(card.domain)
        val normalizedDomain = capabilityDomain?.wireName
            ?: card.domain.trim().lowercase(Locale.US).ifBlank { CapabilityDomain.OTHER.wireName }
        val normalizedDomainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain)
        PreferenceRecentFact(
            id = card.cardId,
            domainRoot = normalizedDomainRoot,
            domain = normalizedDomain,
            anchorObject = card.anchorObject,
            summary = card.summary,
            confidence = card.confidence,
            sourceType = preferenceSummaryProjectionSourceType,
            sourceRef = card.cardId,
            lastObservedAt = card.lastObservedAt,
            supportingSourceApps = card.supportingSourceApps
        )
    }
    val recentFacts = (summaryProjectionFacts + derivedRecentFacts)
        .distinctBy { fact -> listOf(fact.domainRoot, fact.domain, fact.anchorObject, fact.summary, fact.sourceType).joinToString("|") }
        .sortedByDescending { fact -> fact.lastObservedAt }
        .take(24)
    val lastUpdatedAt = (facts.maxOfOrNull { fact -> fact.lastObservedAt }
        ?: recentFacts.maxOfOrNull { fact -> fact.lastObservedAt }
        ?: preservedSemanticChunks.maxOfOrNull { chunk -> chunk.lastObservedAt })
    return StructuredPreferenceMemoryState(
        facts = facts,
        recentFacts = recentFacts,
        semanticChunks = preservedSemanticChunks,
        lastUpdatedAt = lastUpdatedAt
    )
}

private fun rebuildInteractionBiasMemoryState(
    records: List<MemoryPreferenceEvidenceRecord>
): InteractionBiasMemoryState {
    val biasRecords = records
        .groupBy(::memoryPreferenceBiasMergeKey)
        .values
        .mapNotNull { group ->
            val latest = group.maxByOrNull { record -> record.lastObservedAt } ?: return@mapNotNull null
            latest.toInteractionBiasRecord(observationCount = group.sumOf { record -> record.observationCount.coerceAtLeast(1) })
        }
        .sortedByDescending { bias -> bias.lastObservedAt }
        .take(24)
    return InteractionBiasMemoryState(
        preferredProcesses = biasRecords.filter { bias -> bias.signalKey == "preferred_process_id" },
        preferredPages = biasRecords.filter { bias -> bias.signalKey == "preferred_page_signature" },
        preferredShortcuts = biasRecords.filter { bias -> bias.signalKey == "preferred_shortcut_screen" },
        lastUpdatedAt = biasRecords.maxOfOrNull { bias -> bias.lastObservedAt }
    )
}

private fun memoryPreferenceFactMergeKey(record: MemoryPreferenceEvidenceRecord): String {
    return listOf(
        CapabilityDomainProfileRegistry.domainRoot(CapabilityDomain.fromRaw(record.domain)),
        CapabilityDomain.fromRaw(record.domain)?.wireName ?: record.domain?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { CapabilityDomain.OTHER.wireName },
        record.anchorObject?.trim().orEmpty().ifBlank {
            CapabilityDomain.fromRaw(record.domain)?.wireName ?: record.domain?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { CapabilityDomain.OTHER.wireName }
        },
        record.slotKey.trim().lowercase(Locale.US),
        record.slotValue.trim(),
        record.polarity.trim().uppercase(Locale.US),
        record.freshnessHint.trim().ifBlank { "RECENT" },
        record.sourceType.trim().ifBlank { "DIALOGUE" },
        record.sourceApp?.trim().orEmpty()
    ).joinToString("|")
}

private fun memoryPreferenceBiasMergeKey(record: MemoryPreferenceEvidenceRecord): String {
    return listOf(
        CapabilityDomainProfileRegistry.domainRoot(CapabilityDomain.fromRaw(record.domain)),
        CapabilityDomain.fromRaw(record.domain)?.wireName ?: record.domain?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { CapabilityDomain.OTHER.wireName },
        record.anchorObject?.trim().orEmpty().ifBlank {
            CapabilityDomain.fromRaw(record.domain)?.wireName ?: record.domain?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { CapabilityDomain.OTHER.wireName }
        },
        record.slotKey.trim().lowercase(Locale.US),
        record.slotValue.trim(),
        record.sourceType.trim().ifBlank { "DIALOGUE" },
        record.sourceApp?.trim().orEmpty()
    ).joinToString("|")
}

private fun MemoryPreferenceEvidenceRecord.toPreferenceFacetFact(observationCount: Int = this.observationCount): PreferenceFacetFact? {
    if (slotKey in interactionBiasPreferenceKeys) {
        return null
    }
    val capabilityDomain = CapabilityDomain.fromRaw(domain)
    val normalizedDomain = capabilityDomain?.wireName
        ?: domain?.trim()?.lowercase(Locale.US)
        ?: CapabilityDomain.OTHER.wireName
    val normalizedDomainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain)
    val resolvedAnchor = anchorObject?.trim()?.ifBlank { null } ?: normalizedDomain
    return PreferenceFacetFact(
        id = evidenceId,
        domainRoot = normalizedDomainRoot,
        domain = normalizedDomain,
        anchorObject = resolvedAnchor,
        facetKey = slotKey,
        facetValue = slotValue,
        polarity = polarity.uppercase(Locale.US),
        confidence = confidence.coerceIn(0.0, 1.0),
        freshnessHint = freshnessHint.trim().ifBlank { "RECENT" },
        sourceType = sourceType,
        sourceApp = sourceApp,
        sourceRef = evidenceId,
        lastObservedAt = lastObservedAt,
        observationCount = observationCount.coerceAtLeast(1),
        transferabilityTag = if (sourceType.equals("APP_SCAN", ignoreCase = true)) "APP_SCAN_DIRECT" else "DIRECT"
    )
}

private fun MemoryPreferenceEvidenceRecord.toInteractionBiasRecord(observationCount: Int = this.observationCount): InteractionBiasRecord? {
    if (slotKey !in interactionBiasPreferenceKeys) {
        return null
    }
    val capabilityDomain = CapabilityDomain.fromRaw(domain)
    val normalizedDomain = capabilityDomain?.wireName
        ?: domain?.trim()?.lowercase(Locale.US)
        ?: CapabilityDomain.OTHER.wireName
    val normalizedDomainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain)
    return InteractionBiasRecord(
        id = evidenceId,
        domainRoot = normalizedDomainRoot,
        domain = normalizedDomain,
        anchorObject = anchorObject?.trim()?.ifBlank { null } ?: normalizedDomain,
        signalKey = slotKey,
        signalValue = slotValue,
        confidence = confidence.coerceIn(0.0, 1.0),
        freshnessHint = freshnessHint.trim().ifBlank { "RECENT" },
        sourceType = sourceType,
        sourceApp = sourceApp,
        sourceRef = evidenceId,
        lastObservedAt = lastObservedAt,
        observationCount = observationCount.coerceAtLeast(1)
    )
}

data class MemoryGroundingEvidence(
    val candidateId: String,
    val anchorObject: String,
    val focusedObject: String,
    val action: String,
    val source: String = "snapshot"
)

private fun List<String>.renderEvidenceList(): String {
    return if (isEmpty()) "[]" else joinToString(prefix = "[", postfix = "]")
}

data class MemoryEvidenceBundle(
    val groundingEvidence: List<MemoryGroundingEvidence> = emptyList(),
    val topicContextEvidence: List<String> = emptyList(),
    val recentFacts: List<String> = emptyList(),
    val semanticRecall: List<String> = emptyList(),
    val preferenceRecallBundle: PreferenceRecallBundle? = null,
    val recommendedDetailSlots: RecommendedDetailSlotBundle? = null,
    val habitEvidence: List<String> = emptyList(),
    val styleEvidence: List<String> = emptyList(),
    val confidenceSummary: String
) {
    fun toPromptSection(): String {
        return buildString {
            if (groundingEvidence.isNotEmpty()) {
                appendLine("grounding_evidence:")
                groundingEvidence.forEach { evidence ->
                    appendLine(
                        "- anchor=${evidence.anchorObject}; focus=${evidence.focusedObject}; action=${evidence.action}; source=${evidence.source}"
                    )
                }
            }
            if (topicContextEvidence.isNotEmpty()) {
                if (isNotEmpty()) {
                    appendLine()
                }
                appendLine("topic_context:")
                topicContextEvidence.forEach { evidence ->
                    appendLine("- $evidence")
                }
            }
            if (recentFacts.isNotEmpty()) {
                if (isNotEmpty()) {
                    appendLine()
                }
                appendLine("recent_facts:")
                recentFacts.forEach { fact ->
                    appendLine("- $fact")
                }
            }
            if (isNotEmpty()) {
                appendLine()
            }
            appendLine("semantic_recall=${semanticRecall.renderEvidenceList()}")
            appendLine(renderPreferenceRecallSection(preferenceRecallBundle))
            appendLine(renderRecommendedDetailSlotSection(recommendedDetailSlots))
            appendLine("habit_evidence=${habitEvidence.renderEvidenceList()}")
            appendLine("style_evidence=${styleEvidence.renderEvidenceList()}")
            append("confidence_summary=$confidenceSummary")
        }.trim()
    }
}

private fun renderPreferenceRecallSection(bundle: PreferenceRecallBundle?): String {
    if (bundle == null || bundle.isEmpty()) {
        return "preference_recall_bundle=none"
    }
    val likelyPreferences = bundle.likelyPreferences.map { item ->
        "${item.facetKey}=${item.facetValue}|tier=${item.sourceTier}|confidence=${"%.2f".format(item.confidence)}"
    }
    val avoidances = bundle.avoidances.map { item ->
        "${item.facetKey}=${item.facetValue}|tier=${item.sourceTier}|confidence=${"%.2f".format(item.confidence)}"
    }
    val derivedHypotheses = bundle.derivedHypotheses.map { hypothesis ->
        "${hypothesis.facetKey}=${hypothesis.facetValue}|confidence=${"%.2f".format(hypothesis.confidence)}|reason=${hypothesis.reasonSummary}"
    }
    return buildString {
        append("preference_recall_bundle=")
        append(
            listOf(
                "domain_root=${bundle.domainRoot}",
                "domain=${bundle.domain}",
                "anchor=${bundle.anchorObject}",
                "likely=${likelyPreferences.renderEvidenceList()}",
                "avoid=${avoidances.renderEvidenceList()}",
                "recent=${bundle.recentFacts.renderEvidenceList()}",
                "semantic=${bundle.semanticEvidence.renderEvidenceList()}",
                "neighbor=${bundle.neighborEvidence.renderEvidenceList()}",
                "derived=${derivedHypotheses.renderEvidenceList()}",
                "confidence=${"%.2f".format(bundle.confidence)}",
                "debug=${bundle.debugSummary}"
            ).joinToString("; ")
        )
    }
}

private fun renderRecommendedDetailSlotSection(bundle: RecommendedDetailSlotBundle?): String {
    if (bundle == null || (bundle.recommendedCommonSlots.isEmpty() && bundle.recommendedDomainSlots.isEmpty() && bundle.blockedSlots.isEmpty())) {
        return buildString {
            appendLine("recommended_detail_slots=none")
            appendLine("blocked_slots=[]")
            append("confirmation_needed_slots=[]")
        }
    }
    val commonSlots = bundle.recommendedCommonSlots.values.map { slot ->
        "common.${slot.slotKey}=${slot.slotValue}|tier=${slot.sourceTier}|confidence=${"%.2f".format(slot.confidence)}"
    }
    val domainPrefix = bundle.domain?.wireName ?: CapabilityDomain.OTHER.wireName
    val domainSlots = bundle.recommendedDomainSlots.values.map { slot ->
        "$domainPrefix.${slot.slotKey}=${slot.slotValue}|tier=${slot.sourceTier}|confidence=${"%.2f".format(slot.confidence)}"
    }
    val confirmationNeededSlots = buildList {
        bundle.recommendedCommonSlots.forEach { (slotKey, slot) ->
            if (slot.confirmationNeeded) {
                add("common.$slotKey")
            }
        }
        bundle.recommendedDomainSlots.forEach { (slotKey, slot) ->
            if (slot.confirmationNeeded) {
                add("$domainPrefix.$slotKey")
            }
        }
    }
    return buildString {
        appendLine("recommended_detail_slots=${(commonSlots + domainSlots).renderEvidenceList()}")
        appendLine("blocked_slots=${bundle.blockedSlots.renderEvidenceList()}")
        append("confirmation_needed_slots=${confirmationNeededSlots.renderEvidenceList()}")
    }
}

private fun PreferenceRecallBundle.isEmpty(): Boolean {
    return likelyPreferences.isEmpty() &&
        avoidances.isEmpty() &&
        recentFacts.isEmpty() &&
        semanticEvidence.isEmpty() &&
        neighborEvidence.isEmpty() &&
        derivedHypotheses.isEmpty()
}

object MemoryOrchestrator {
    private val lowPreferenceSignalDomains = setOf(
        CapabilityDomain.SYSTEM_CONTROL,
        CapabilityDomain.COMMUNICATION
    )

    private val explicitPreferenceMarkers = listOf(
        "喜欢",
        "偏好",
        "更喜欢",
        "不喜欢",
        "讨厌",
        "常常",
        "经常",
        "通常",
        "每次",
        "prefer",
        "preference",
        "avoid",
        "usually",
        "always"
    )

    fun recordPreferenceEvidence(
        store: PrototypeStoreData,
        records: List<MemoryPreferenceEvidenceRecord>
    ): MemoryState {
        val currentState = currentMemoryState(store)
        if (records.isEmpty()) {
            return currentState
        }
        return currentState.appendProjectedPreferenceRecords(records, limit = 12)
    }

    fun recordHabitEvidence(
        store: PrototypeStoreData,
        records: List<MemoryHabitRecord>
    ): MemoryState {
        val currentState = currentMemoryState(store)
        if (records.isEmpty()) {
            return currentState
        }
        val merged = (records + currentState.habitMemoryStore)
            .distinctBy { record ->
                listOf(record.habitType, record.timeWindow, record.triggerContext).joinToString("|")
            }
            .sortedByDescending { record -> record.lastObservedAt }
            .take(12)
        return currentState.copy(habitMemoryStore = merged)
    }

    fun recordInteractionStyleEvidence(
        store: PrototypeStoreData,
        records: List<MemoryInteractionStyleRecord>
    ): MemoryState {
        val currentState = currentMemoryState(store)
        if (records.isEmpty()) {
            return currentState
        }
        val merged = (records + currentState.interactionStyleStore)
            .distinctBy { record -> listOf(record.styleKey, record.styleValue).joinToString("|") }
            .sortedByDescending { record -> record.lastObservedAt }
            .take(12)
        return currentState.copy(interactionStyleStore = merged)
    }

    fun recordPassiveTurn(
        store: PrototypeStoreData,
        activeCandidate: IntentCandidate?,
        semanticSummary: String,
        userMessage: String = "",
        assistantReply: String = "",
        now: Long
    ): MemoryState {
        val currentState = currentMemoryState(store)
        val candidate = activeCandidate
        val groundingRecord = candidate?.let {
            MemoryGroundingRecord(
                candidateId = it.id,
                anchorObject = it.anchorObject,
                focusedObject = it.focusedObject,
                action = it.action,
                lastObservedAt = now
            )
        }
        val continuationRecord = candidate?.let {
            MemoryContinuationRecord(
                candidateId = it.id,
                anchorObject = it.anchorObject,
                focusedObject = it.focusedObject,
                action = it.action,
                lastObservedAt = now
            )
        }
        val semanticRecallIndex = semanticSummary
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { summary -> listOf(summary) + currentState.semanticRecallIndex }
            ?.distinct()
            ?.take(12)
            ?: currentState.semanticRecallIndex
        val backlogRecord = DialoguePreferenceBacklogRecord(
            sourceType = "DIALOGUE",
            candidateId = candidate?.id,
            anchorObject = candidate?.anchorObject,
            focusedObject = candidate?.focusedObject,
            action = candidate?.action,
            userMessage = userMessage.trim().ifBlank { null },
            assistantReply = assistantReply.trim().ifBlank { null },
            slotEvidenceSnapshot = store.resolveCurrentTaskSlotEvidenceSnapshot(),
            detailSlots = candidate?.detailSlots.orEmpty(),
            semanticSummary = semanticSummary.trim().ifBlank { null },
            lastObservedAt = now
        )
        val mergedPreferenceBacklog = (listOf(backlogRecord) + currentState.dialoguePreferenceBacklog)
            .distinctBy { record -> record.recordId }
            .sortedByDescending { record -> record.lastObservedAt }
            .take(20)
        val updatedTopicContextStore = currentState.updateTopicContextStore(
            store = store,
            activeCandidate = candidate,
            summaryText = semanticSummary,
            userMessage = userMessage,
            assistantReply = assistantReply,
            sourceType = "DIALOGUE",
            now = now
        )

        return currentState.copy(
            activeGroundingStore = listOfNotNull(groundingRecord) + currentState.activeGroundingStore
                .filterNot { record -> record.candidateId == candidate?.id }
                .take(5),
            continuationStore = listOfNotNull(continuationRecord) + currentState.continuationStore
                .filterNot { record -> record.candidateId == candidate?.id }
                .take(11),
            dialoguePreferenceBacklog = mergedPreferenceBacklog,
            semanticRecallIndex = semanticRecallIndex,
            topicContextStore = updatedTopicContextStore
        )
    }

    fun recordExecutionStart(
        store: PrototypeStoreData,
        candidateId: String?,
        summary: String,
        now: Long
    ): MemoryState {
        val currentState = currentMemoryState(store)
        val factText = summary.trim()
        if (factText.isBlank()) {
            return currentState
        }
        val recentFact = MemoryRecentFactRecord(
            candidateId = candidateId,
            factText = factText,
            lastObservedAt = now
        )
        val resolvedCandidate = resolveMemoryCandidate(store, candidateId)
        val derivedHabitRecords = resolvedCandidate?.let { candidate ->
            listOf(
                MemoryHabitRecord(
                    habitType = candidate.action.uppercase(),
                    timeWindow = deriveMemoryTimeWindow(now),
                    triggerContext = candidate.anchorObject.ifBlank { candidate.focusedObject },
                    stabilityScore = if (candidate.canStartExecution) 0.9 else 0.8,
                    preferredProactiveSignal = ProactiveOpportunitySignal.PREPARE_OPPORTUNITY.name,
                    lastObservedAt = now
                )
            )
        }.orEmpty()
        val backlogRecord = DialoguePreferenceBacklogRecord(
            sourceType = "EXECUTION",
            candidateId = resolvedCandidate?.id,
            anchorObject = resolvedCandidate?.anchorObject,
            focusedObject = resolvedCandidate?.focusedObject,
            action = resolvedCandidate?.action,
            assistantReply = factText,
            slotEvidenceSnapshot = store.resolveCurrentTaskSlotEvidenceSnapshot(),
            detailSlots = resolvedCandidate?.detailSlots.orEmpty(),
            semanticSummary = factText,
            lastObservedAt = now
        )
        val mergedHabits = (derivedHabitRecords + currentState.habitMemoryStore)
            .distinctBy { record -> listOf(record.habitType, record.timeWindow, record.triggerContext).joinToString("|") }
            .sortedByDescending { record -> record.lastObservedAt }
            .take(12)
        val mergedBacklog = (listOf(backlogRecord) + currentState.dialoguePreferenceBacklog)
            .distinctBy { record -> record.recordId }
            .sortedByDescending { record -> record.lastObservedAt }
            .take(20)
        val updatedTopicContextStore = currentState.updateTopicContextStore(
            store = store,
            activeCandidate = resolvedCandidate,
            summaryText = factText,
            assistantReply = factText,
            sourceType = "EXECUTION",
            now = now
        )
        return currentState.copy(
            recentFactStore = listOf(recentFact) + currentState.recentFactStore
                .filterNot { fact -> fact.factText == factText && fact.candidateId == candidateId }
                .take(11),
            habitMemoryStore = mergedHabits,
            dialoguePreferenceBacklog = mergedBacklog,
            topicContextStore = updatedTopicContextStore
        )
    }

    fun buildDialoguePreferenceBacklogBundle(store: PrototypeStoreData): String? {
        return buildDialoguePreferenceBacklogBatch(store)?.bundle
    }

    fun buildOfflineDialoguePreferenceMemoryBundle(
        store: PrototypeStoreData,
        limit: Int = 6
    ): String {
        val memoryState = currentMemoryState(store)
        val topicContextLines = buildOfflineTopicContextEvidence(memoryState, limit)
        val preferenceFactLines = memoryState.structuredPreferenceMemory.facts
            .sortedByDescending { fact -> fact.lastObservedAt }
            .take(limit)
            .map { fact ->
                buildString {
                    append("domain=")
                    append(fact.domain)
                    append("; anchor=")
                    append(fact.anchorObject.ifBlank { "*" })
                    append("; facet=")
                    append(fact.facetKey)
                    append("; value=")
                    append(fact.facetValue)
                    append("; polarity=")
                    append(fact.polarity)
                }
            }
        val interactionBiasLines = memoryState.interactionBiasMemory.allRecords()
            .sortedByDescending { record -> record.lastObservedAt }
            .take(limit)
            .map { record ->
                buildString {
                    append("domain=")
                    append(record.domain)
                    append("; anchor=")
                    append(record.anchorObject.ifBlank { "*" })
                    append("; signal=")
                    append(record.signalKey)
                    append("; value=")
                    append(record.signalValue)
                }
            }
        val recentPreferenceLines = memoryState.structuredPreferenceMemory.recentFacts
            .filterNot { fact -> fact.sourceType == preferenceSummaryProjectionSourceType }
            .sortedByDescending { fact -> fact.lastObservedAt }
            .take(limit)
            .map { fact ->
                val sources = fact.supportingSourceApps.joinToString(",").ifBlank { "-" }
                "anchor=${fact.anchorObject}; summary=${fact.summary}; sources=$sources"
            }
        val habitLines = memoryState.habitMemoryStore
            .sortedByDescending { record -> record.lastObservedAt }
            .take(limit)
            .map { record ->
                "habit=${record.habitType}; time=${record.timeWindow}; trigger=${record.triggerContext}; delivery=${record.preferredDeliveryStyle.orEmpty()}"
            }
        val semanticRecallLines = memoryState.semanticRecallIndex
            .take(limit)
        val styleLines = memoryState.interactionStyleStore
            .sortedByDescending { record -> record.lastObservedAt }
            .take(limit)
            .map { record ->
                "style=${record.styleKey}; value=${record.styleValue}; confidence=${String.format("%.2f", record.confidence)}"
            }
        return buildString {
            appendLine("preference_facts:")
            if (preferenceFactLines.isEmpty()) {
                appendLine("- none")
            } else {
                preferenceFactLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("interaction_bias_signals:")
            if (interactionBiasLines.isEmpty()) {
                appendLine("- none")
            } else {
                interactionBiasLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("preference_recent_facts:")
            if (recentPreferenceLines.isEmpty()) {
                appendLine("- none")
            } else {
                recentPreferenceLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("topic_context:")
            if (topicContextLines.isEmpty()) {
                appendLine("- none")
            } else {
                topicContextLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("habit_evidence:")
            if (habitLines.isEmpty()) {
                appendLine("- none")
            } else {
                habitLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("semantic_recall:")
            if (semanticRecallLines.isEmpty()) {
                appendLine("- none")
            } else {
                semanticRecallLines.forEach { line -> appendLine("- $line") }
            }
            appendLine()
            appendLine("style_evidence:")
            if (styleLines.isEmpty()) {
                appendLine("- none")
            } else {
                styleLines.forEach { line -> appendLine("- $line") }
            }
        }.trim()
    }

    fun buildDialoguePreferenceBacklogBatch(
        store: PrototypeStoreData,
        limit: Int = 12
    ): DialoguePreferenceBacklogBatch? {
        val memoryState = currentMemoryState(store)
        val records = memoryState.dialoguePreferenceBacklog
            .map { record -> record to record.preferenceExtractionRank() }
            .filter { (_, rank) -> rank > 0 }
            .sortedWith(
                compareByDescending<Pair<DialoguePreferenceBacklogRecord, Int>> { (_, rank) -> rank }
                    .thenByDescending { (record, _) -> record.lastObservedAt }
            )
            .take(limit)
            .map { (record, _) -> record }
            .sortedByDescending { record -> record.lastObservedAt }
        if (records.isEmpty()) {
            return null
        }
        val lines = records
            .map { record ->
                buildString {
                    append("source=")
                    append(record.sourceType)
                    record.anchorObject?.takeIf { value -> value.isNotBlank() }?.let { value ->
                        append("; anchor=")
                        append(value)
                    }
                    record.action?.takeIf { value -> value.isNotBlank() }?.let { value ->
                        append("; action=")
                        append(value)
                    }
                    record.userMessage?.let { value ->
                        append("; user=")
                        append(value)
                    }
                    record.slotEvidenceSnapshot?.let { snapshot ->
                        appendLine()
                        append("authoritative_slot_source=")
                        append(snapshot.sourceLevel)
                        appendLine()
                        append("structured_detail_slots.common=")
                        append(snapshot.structuredCommonSummary())
                        appendLine()
                        append("structured_detail_slots.domain=")
                        append(snapshot.structuredDomainSummary())
                        appendLine()
                        append("authoritative_resolved_slots=")
                        append(snapshot.resolvedSlotSummary())
                    }
                    if (record.detailSlots.isNotEmpty()) {
                        appendLine()
                        append("legacy_slots=")
                        append(record.detailSlots.joinToString(",") { slot -> "${slot.key.wireName}=${slot.value}" })
                    }
                }.trim()
            }
        return DialoguePreferenceBacklogBatch(
            records = records,
            bundle = lines.joinToString("\n\n")
        )
    }

    private fun DialoguePreferenceBacklogRecord.preferenceExtractionRank(): Int {
        val evidenceText = listOfNotNull(userMessage, semanticSummary)
            .joinToString(" ")
            .lowercase()
        val topicValues = preferenceTopicValues()
        val hasAlignedTopic = topicValues.any { topic -> evidenceText.contains(topic.lowercase()) }
        val hasExplicitPreferenceSignal = explicitPreferenceMarkers.any { marker -> evidenceText.contains(marker) }
        var rank = 0
        if (sourceType.equals("DIALOGUE", ignoreCase = true) && !userMessage.isNullOrBlank()) {
            rank += 2
        }
        if (sourceType.equals("EXECUTION", ignoreCase = true) && !assistantReply.isNullOrBlank()) {
            rank += 1
        }
        if (hasExplicitPreferenceSignal) {
            rank += 8
        }
        if (hasAlignedTopic) {
            rank += 8
        }
        if (detailSlots.isNotEmpty() && hasAlignedTopic) {
            rank += 2
        }
        if (slotEvidenceSnapshot?.capabilityDomain in lowPreferenceSignalDomains) {
            rank -= 10
        }
        if (topicValues.isNotEmpty() && !hasAlignedTopic) {
            rank -= 7
        }
        return rank
    }

    private fun DialoguePreferenceBacklogRecord.preferenceTopicValues(): List<String> {
        val snapshot = slotEvidenceSnapshot
        return buildList {
            add(anchorObject)
            add(focusedObject)
            add(snapshot?.targetKey)
            add(snapshot?.targetLabel)
            add(snapshot?.resolvedSlots?.get("target_object"))
            addAll(detailSlots.map { slot -> slot.value })
        }.mapNotNull { value -> value?.trim()?.takeIf { it.length >= 2 } }
            .distinct()
    }

    fun applyOfflineDialoguePreferenceExtraction(
        store: PrototypeStoreData,
        extractionResult: OfflineDialoguePreferenceExtractionResult,
        consumedBacklogRecordIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): MemoryState {
        var nextState = currentMemoryState(store)
        if (extractionResult.preferenceFacts.isNotEmpty() || extractionResult.interactionBiasSignals.isNotEmpty()) {
            val dialogueRecords = buildDialoguePreferenceRecords(extractionResult, now)
            nextState = nextState.appendPreferenceSignalProjections(
                dialogueRecords.map(PreferenceMemorySignalProjector::project)
            )
        }
        if (extractionResult.habitEvidence.isNotEmpty()) {
            nextState = recordHabitEvidence(
                store.copy(memoryState = nextState),
                extractionResult.habitEvidence.map { record ->
                    record.copy(lastObservedAt = now)
                }
            )
        }
        if (extractionResult.styleEvidence.isNotEmpty()) {
            nextState = recordInteractionStyleEvidence(
                store.copy(memoryState = nextState),
                extractionResult.styleEvidence.map { record ->
                    record.copy(lastObservedAt = now)
                }
            )
        }
        val consumedIdSet = consumedBacklogRecordIds.toSet()
        return nextState.copy(
            dialoguePreferenceBacklog = nextState.dialoguePreferenceBacklog.filterNot { record ->
                record.recordId in consumedIdSet
            }
        )
    }

    fun buildPassiveEvidence(
        userMessage: String,
        store: PrototypeStoreData
    ): MemoryEvidenceBundle? {
        val memoryState = currentMemoryState(store)
        val resolvedState = store.resolveCurrentState()
        val knownLocalCandidateIds = (
            store.currentDialogueCandidates() + resolvedState.dormantHistoricalCandidates
        ).map { it.id }.toSet()
        val groundingCandidates = (memoryState.activeGroundingStore.map { record ->
            IntentCandidate(
                id = record.candidateId,
                anchorObject = record.anchorObject,
                focusedObject = record.focusedObject,
                action = record.action,
                readiness = CandidateReadiness.ACCUMULATING,
                confidence = 0.0,
                evidence = "memory",
                rationale = "memory_grounding"
            )
        } + memoryState.continuationStore.map { record ->
            IntentCandidate(
                id = record.candidateId,
                anchorObject = record.anchorObject,
                focusedObject = record.focusedObject,
                action = record.action,
                readiness = CandidateReadiness.ACCUMULATING,
                confidence = 0.0,
                evidence = "memory",
                rationale = "memory_continuation"
            )
        }).distinctBy { candidate -> candidate.id }

        val recalledGroundingIds = collectExplicitReMentionedCandidateIds(
            userMessage,
            groundingCandidates.filterNot { candidate -> candidate.id in knownLocalCandidateIds }
        )

        val groundingEvidence = groundingCandidates
            .filterNot { candidate -> candidate.id in knownLocalCandidateIds }
            .filter { candidate -> candidate.id in recalledGroundingIds }
            .take(3)
            .map { candidate ->
                MemoryGroundingEvidence(
                    candidateId = candidate.id,
                    anchorObject = candidate.anchorObject,
                    focusedObject = candidate.focusedObject,
                    action = candidate.action
                )
            }

        val recentFacts = memoryState.recentFactStore
            .filter { fact ->
                fact.factText.isNotBlank() &&
                    fact.candidateId != null &&
                    groundingEvidence.any { evidence -> evidence.candidateId == fact.candidateId }
            }
            .sortedByDescending { fact -> fact.lastObservedAt }
            .map { fact -> fact.factText.trim() }
            .distinct()
            .take(3)
        val normalizedUserMessage = normalizeMemoryText(userMessage)
        val groundingTerms = groundingEvidence.flatMap { evidence ->
            listOf(evidence.anchorObject, evidence.focusedObject)
        }.map(::normalizeMemoryText).filter { value -> value.length >= 2 }
        val semanticRecall = memoryState.semanticRecallIndex
            .filter { summary ->
                val normalizedSummary = normalizeMemoryText(summary)
                normalizedSummary.isNotBlank() && (
                    normalizedUserMessage.takeIf { it.length >= 2 }?.let(normalizedSummary::contains) == true ||
                        groundingTerms.any { term -> term.length >= 2 && normalizedSummary.contains(term) }
                    )
            }
            .take(3)
        val topicContextEvidence = buildPassiveTopicContextEvidence(
            memoryState = memoryState,
            normalizedUserMessage = normalizedUserMessage,
            groundingTerms = groundingTerms,
            hasLiveTaskAuthority = resolvedState.currentTaskRecord != null || resolvedState.currentTaskDraft != null
        )
        val preferenceRecallRequest = buildPassivePreferenceRecallRequest(
            userMessage = userMessage,
            store = store,
            groundingEvidence = groundingEvidence
        )
        val preferenceRecallBundle = preferenceRecallRequest?.let { request ->
            PreferenceRecallResolver.resolve(memoryState, request)
        }
        val recommendedDetailSlots = if (preferenceRecallRequest != null && preferenceRecallBundle != null) {
            PreferenceSlotRecommendationEngine.recommend(preferenceRecallRequest, preferenceRecallBundle)
        } else {
            null
        }
        if (preferenceRecallRequest != null && preferenceRecallBundle != null && recommendedDetailSlots != null) {
            store.memoryState = memoryState.recordPreferenceDebugSnapshot(
                recallBundle = preferenceRecallBundle,
                mappingTrace = recommendedDetailSlots.toPreferenceSlotMappingTrace()
            )
        }
        val visiblePreferenceRecallBundle = preferenceRecallBundle?.takeUnless(PreferenceRecallBundle::isEmpty)
        val habitEvidence = memoryState.habitMemoryStore
            .filter { record ->
                val normalizedTriggerContext = normalizeMemoryText(record.triggerContext)
                val normalizedHabitType = normalizeMemoryText(record.habitType)
                val matchesUserMessage =
                    normalizedTriggerContext.takeIf { it.length >= 2 }?.let(normalizedUserMessage::contains) == true ||
                        normalizedHabitType.takeIf { it.length >= 2 }?.let(normalizedUserMessage::contains) == true
                val matchesGrounding = groundingTerms.any { term ->
                    term.contains(normalizedTriggerContext) || normalizedTriggerContext.contains(term) ||
                        term.contains(normalizedHabitType) || normalizedHabitType.contains(term)
                }
                matchesUserMessage || matchesGrounding
            }
            .sortedByDescending { record -> record.lastObservedAt }
            .map { record ->
                "habit_type=${record.habitType}; time_window=${record.timeWindow}; trigger=${record.triggerContext}"
            }
            .distinct()
            .take(2)
        val styleEvidence = memoryState.interactionStyleStore
            .sortedByDescending { record -> record.lastObservedAt }
            .map { record -> "${record.styleKey}=${record.styleValue}" }
            .distinct()
            .take(2)

        if (
            groundingEvidence.isEmpty() &&
            topicContextEvidence.isEmpty() &&
            recentFacts.isEmpty() &&
            semanticRecall.isEmpty() &&
            visiblePreferenceRecallBundle == null &&
            habitEvidence.isEmpty() &&
            styleEvidence.isEmpty()
        ) {
            return null
        }

        return MemoryEvidenceBundle(
            groundingEvidence = groundingEvidence,
            topicContextEvidence = topicContextEvidence,
            recentFacts = recentFacts,
            semanticRecall = semanticRecall,
            preferenceRecallBundle = visiblePreferenceRecallBundle,
            recommendedDetailSlots = recommendedDetailSlots,
            habitEvidence = habitEvidence,
            styleEvidence = styleEvidence,
            confidenceSummary = "grounding:${groundingEvidence.size};topic_context:${topicContextEvidence.size};recent_facts:${recentFacts.size};semantic_recall:${semanticRecall.size};preference_recall:${if (visiblePreferenceRecallBundle == null) 0 else 1};recommended_slots:${recommendedDetailSlots?.let { bundle -> bundle.recommendedCommonSlots.size + bundle.recommendedDomainSlots.size } ?: 0}"
        )
    }

    private fun buildPassiveTopicContextEvidence(
        memoryState: MemoryState,
        normalizedUserMessage: String,
        groundingTerms: List<String>,
        hasLiveTaskAuthority: Boolean
    ): List<String> {
        val activeTopicRecord = memoryState.topicContextStore.activeTopicRecord
        return buildList {
            activeTopicRecord
                ?.takeIf { record -> !hasLiveTaskAuthority || topicContextMatches(record, normalizedUserMessage, groundingTerms) }
                ?.let { record ->
                    add(record.toPassiveTopicContextEvidenceLine(kind = "active"))
                }
            memoryState.topicContextStore.silentTopicRecords
                .filter { record -> topicContextMatches(record, normalizedUserMessage, groundingTerms) }
                .take(2)
                .forEach { record ->
                    add(record.toPassiveTopicContextEvidenceLine(kind = "silent"))
                }
        }
            .distinct()
            .take(3)
    }

    private fun buildOfflineTopicContextEvidence(
        memoryState: MemoryState,
        limit: Int
    ): List<String> {
        return buildList {
            memoryState.topicContextStore.activeTopicRecord?.let { record ->
                add(record.toPassiveTopicContextEvidenceLine(kind = "active"))
            }
            memoryState.topicContextStore.silentTopicRecords
                .sortedByDescending { record -> record.lastTouchedAt }
                .forEach { record ->
                    add(record.toPassiveTopicContextEvidenceLine(kind = "silent"))
                }
        }
            .distinct()
            .take(limit.coerceAtLeast(1))
    }

    private fun topicContextMatches(
        record: TopicContextRecord,
        normalizedUserMessage: String,
        groundingTerms: List<String>
    ): Boolean {
        if (normalizedUserMessage.isBlank() && groundingTerms.isEmpty()) {
            return false
        }
        val normalizedHints = buildList {
            add(record.summaryText)
            record.focusedObject?.let(::add)
            record.anchorObject?.let(::add)
            record.action?.let(::add)
            record.preferredAppScope?.let(::add)
            addAll(record.matchingHints)
        }
            .map(::normalizeMemoryText)
            .filter { value -> value.length >= 2 }
            .distinct()
        return normalizedHints.any { hint ->
            normalizedUserMessage.takeIf { value -> value.length >= 2 }?.contains(hint) == true ||
                groundingTerms.any { term -> term.length >= 2 && (term.contains(hint) || hint.contains(term)) }
        }
    }

    private fun TopicContextRecord.toPassiveTopicContextEvidenceLine(kind: String): String {
        return buildString {
            append("type=")
            append(kind)
            append("; topic_id=")
            append(topicId)
            append("; summary=")
            append(summaryText.sanitizeMemoryEvidenceValue())
            append("; focus=")
            append(focusedObject.orEmpty().ifBlank { "-" }.sanitizeMemoryEvidenceValue())
            append("; anchor=")
            append(anchorObject.orEmpty().ifBlank { "-" }.sanitizeMemoryEvidenceValue())
            append("; action=")
            append(action.orEmpty().ifBlank { "-" }.sanitizeMemoryEvidenceValue())
            append("; app_scope=")
            append(preferredAppScope.orEmpty().ifBlank { "-" }.sanitizeMemoryEvidenceValue())
            append("; source=")
            append(lastSourceType.sanitizeMemoryEvidenceValue())
            append("; common_slots=")
            append(structuredDetailSlots.toTopicCommonSummary(capabilityDomain))
            append("; domain_slots=")
            append(structuredDetailSlots.toTopicDomainSummary(capabilityDomain))
            append("; resolved_slots=")
            append(resolvedSlots.toTopicResolvedSlotSummary())
            append("; source_turn_ids=")
            append(sourceTurnIds.toTopicEvidenceListSummary())
            append("; matching_hints=")
            append(matchingHints.toTopicEvidenceListSummary())
            append("; last_touched_at=")
            append(lastTouchedAt)
        }
    }

    private fun String.sanitizeMemoryEvidenceValue(): String {
        return replace('|', '/').replace(';', ',').replace('\n', ' ').trim()
    }

    private fun TaskDetailSlots.toTopicCommonSummary(capabilityDomain: CapabilityDomain?): String {
        val normalized = normalize(capabilityDomain)
        return normalized.common.entries
            .joinToString(",") { (key, value) -> "${key.wireName}=${value.sanitizeMemoryEvidenceValue()}" }
            .ifBlank { "none" }
    }

    private fun TaskDetailSlots.toTopicDomainSummary(capabilityDomain: CapabilityDomain?): String {
        val normalized = normalize(capabilityDomain)
        return normalized.domain.entries
            .joinToString(",") { (key, value) -> "${key.sanitizeMemoryEvidenceValue()}=${value.sanitizeMemoryEvidenceValue()}" }
            .ifBlank { "none" }
    }

    private fun Map<String, String>.toTopicResolvedSlotSummary(): String {
        if (isEmpty()) {
            return "none"
        }
        return entries.joinToString(",") { (key, value) ->
            "${key.sanitizeMemoryEvidenceValue()}=${value.sanitizeMemoryEvidenceValue()}"
        }
    }

    private fun List<String>.toTopicEvidenceListSummary(): String {
        return map { value -> value.sanitizeMemoryEvidenceValue() }
            .filter { value -> value.isNotBlank() }
            .joinToString(",")
            .ifBlank { "none" }
    }

    private fun buildPassivePreferenceRecallRequest(
        userMessage: String,
        store: PrototypeStoreData,
        groundingEvidence: List<MemoryGroundingEvidence>
    ): PreferenceRecallRequest? {
        val memoryState = currentMemoryState(store)
        val activeTopicRecord = memoryState.topicContextStore.activeTopicRecord
        val resolvedState = store.resolveCurrentState()
        val boundaryPacket = store.resolveCurrentExecutionBoundaryPacket()
        val taskSlotSnapshot = store.resolveCurrentTaskSlotEvidenceSnapshot()
        val taskRecord = resolvedState.currentTaskRecord
        val taskDraft = resolvedState.currentTaskDraft
        val capabilityDomain = boundaryPacket?.capabilityDomain
            ?: taskSlotSnapshot?.capabilityDomain
            ?: taskRecord?.capabilityDomain
            ?: taskDraft?.capabilityDomain
            ?: activeTopicRecord?.capabilityDomain
            ?: return null
        val anchorObject = boundaryPacket?.targetLabel?.takeIf { value -> value.isNotBlank() }
            ?: boundaryPacket?.targetKey?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetLabel?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetKey?.takeIf { value -> value.isNotBlank() }
            ?: taskRecord?.displayTarget()?.takeIf { value -> value.isNotBlank() }
            ?: taskDraft?.displayTarget()?.takeIf { value -> value.isNotBlank() }
            ?: activeTopicRecord?.focusedObject?.takeIf { value -> value.isNotBlank() }
            ?: activeTopicRecord?.anchorObject?.takeIf { value -> value.isNotBlank() }
            ?: groundingEvidence.firstOrNull()?.focusedObject?.takeIf { value -> value.isNotBlank() }
            ?: groundingEvidence.firstOrNull()?.anchorObject?.takeIf { value -> value.isNotBlank() }
            ?: return null
        val structuredSlots = taskSlotSnapshot?.structuredDetailSlots
            ?: boundaryPacket?.structuredDetailSlots
            ?: taskRecord?.structuredDetailSlots
            ?: taskDraft?.structuredDetailSlots
            ?: activeTopicRecord?.structuredDetailSlots
            ?: TaskDetailSlots()
        val resolvedSlots = taskSlotSnapshot?.resolvedSlots
            ?: boundaryPacket?.resolvedSlots
            ?: activeTopicRecord?.resolvedSlots?.takeIf { slots -> slots.isNotEmpty() }
            ?: structuredSlots.toNamespacedResolvedSlots(capabilityDomain)
        val activeTopicSummary = listOfNotNull(
            boundaryPacket?.reasonSummary,
            taskRecord?.reasonSummary,
            taskDraft?.reasonSummary,
            activeTopicRecord?.summaryText
        ).firstOrNull { value -> value.isNotBlank() }
        return PreferenceRecallRequest(
            domainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain),
            domain = capabilityDomain.wireName,
            anchorObject = anchorObject,
            focusedObject = groundingEvidence.firstOrNull()?.focusedObject,
            userQuery = userMessage,
            activeTopicSummary = activeTopicSummary,
            currentStructuredSlots = structuredSlots,
            currentResolvedSlots = resolvedSlots,
            allowSiblingExpansion = true
        )
    }

    private fun currentMemoryState(store: PrototypeStoreData): MemoryState {
        val currentState = store.memoryState
        return if (currentState == null || shouldSeedFromLegacy(store, currentState)) {
            legacyMemoryState(store)
        } else {
            currentState
        }
    }

    private fun shouldSeedFromLegacy(
        store: PrototypeStoreData,
        memoryState: MemoryState
    ): Boolean {
        val hasFormalData = memoryState.activeGroundingStore.isNotEmpty() ||
            memoryState.continuationStore.isNotEmpty() ||
            memoryState.recentFactStore.isNotEmpty() ||
            memoryState.structuredPreferenceMemory.facts.isNotEmpty() ||
            memoryState.structuredPreferenceMemory.recentFacts.isNotEmpty() ||
            memoryState.structuredPreferenceMemory.semanticChunks.isNotEmpty() ||
            memoryState.interactionBiasMemory.preferredProcesses.isNotEmpty() ||
            memoryState.interactionBiasMemory.preferredPages.isNotEmpty() ||
            memoryState.interactionBiasMemory.preferredShortcuts.isNotEmpty() ||
            memoryState.habitMemoryStore.isNotEmpty() ||
            memoryState.interactionStyleStore.isNotEmpty() ||
            memoryState.dialoguePreferenceBacklog.isNotEmpty() ||
            memoryState.proactiveOpportunityStore.isNotEmpty() ||
            memoryState.processFeedbackStore.isNotEmpty() ||
            memoryState.topicContextStore.activeTopicRecord != null ||
            memoryState.topicContextStore.silentTopicRecords.isNotEmpty() ||
            memoryState.semanticRecallIndex.isNotEmpty() ||
            store.processAssetEntries.isNotEmpty() ||
            store.readyProcessAssets.isNotEmpty()
        if (hasFormalData) {
            return false
        }
        return store.snapshots.isNotEmpty() || store.executionEvents.isNotEmpty()
    }

    private fun normalizeMemoryText(value: String): String {
        return value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }

    private fun legacyMemoryState(store: PrototypeStoreData): MemoryState {
        val persistedCandidates = store.snapshots
            .asReversed()
            .flatMap { snapshot -> snapshot.candidates }
            .distinctBy { candidate -> candidate.id }
        val activeGroundingStore = persistedCandidates.take(6).map { candidate ->
            MemoryGroundingRecord(
                candidateId = candidate.id,
                anchorObject = candidate.anchorObject,
                focusedObject = candidate.focusedObject,
                action = candidate.action,
                sourceType = "SNAPSHOT",
                lastObservedAt = store.snapshots
                    .lastOrNull { snapshot -> snapshot.candidates.any { it.id == candidate.id } }
                    ?.persistedAt
                    ?: System.currentTimeMillis()
            )
        }
        val continuationStore = activeGroundingStore.map { record ->
            MemoryContinuationRecord(
                candidateId = record.candidateId,
                anchorObject = record.anchorObject,
                focusedObject = record.focusedObject,
                action = record.action,
                lastObservedAt = record.lastObservedAt
            )
        }
        val recentFactStore = store.executionEvents
            .asReversed()
            .take(12)
            .map { event ->
                MemoryRecentFactRecord(
                    candidateId = event.candidateId,
                    factText = event.summary,
                    lastObservedAt = event.startedAt
                )
            }
        return MemoryState(
            activeGroundingStore = activeGroundingStore,
            continuationStore = continuationStore,
            recentFactStore = recentFactStore
        )
    }

    private fun resolveMemoryCandidate(store: PrototypeStoreData, candidateId: String?): IntentCandidate? {
        val localCandidates = store.currentDialogueCandidates()
        if (candidateId != null) {
            localCandidates.firstOrNull { candidate -> candidate.id == candidateId }?.let { return it }
        }
        store.resolveTaskFirstCandidate()?.let { return it }
        return store.snapshots.asReversed()
            .asSequence()
            .flatMap { snapshot -> snapshot.candidates.asSequence() }
            .firstOrNull { candidate -> candidateId == null || candidate.id == candidateId }
    }

    private fun deriveMemoryTimeWindow(now: Long): String {
        val hourOfDay = ((now / 3_600_000L) % 24).toInt()
        return when (hourOfDay) {
            in 5..10 -> "morning"
            in 11..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "night"
        }
    }

    private fun MemoryState.updateTopicContextStore(
        store: PrototypeStoreData,
        activeCandidate: IntentCandidate? = null,
        summaryText: String = "",
        userMessage: String = "",
        assistantReply: String = "",
        sourceType: String,
        now: Long
    ): TopicContextStore {
        val existingStore = topicContextStore
        val previousActive = existingStore.activeTopicRecord
        val resolvedState = store.resolveCurrentState()
        val taskSlotSnapshot = store.resolveCurrentTaskSlotEvidenceSnapshot()
        val preferredAppScope = listOfNotNull(
            taskSlotSnapshot?.capabilityId?.let(::extractCanonicalAppScope),
            resolvedState.currentTaskRecord?.resolvePreferredAppScope(),
            resolvedState.currentTaskDraft?.capabilityId?.let(::extractCanonicalAppScope),
            previousActive?.preferredAppScope
        ).firstOrNull { value -> value.isNotBlank() }
        val capabilityDomain = taskSlotSnapshot?.capabilityDomain
            ?: resolvedState.currentTaskRecord?.capabilityDomain
            ?: resolvedState.currentTaskDraft?.capabilityDomain
            ?: previousActive?.capabilityDomain
        val anchorObject = activeCandidate?.anchorObject?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetLabel?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetKey?.takeIf { value -> value.isNotBlank() }
            ?: resolvedState.currentTaskRecord?.displayTarget()?.takeIf { value -> value.isNotBlank() }
            ?: resolvedState.currentTaskDraft?.displayTarget()?.takeIf { value -> value.isNotBlank() }
            ?: previousActive?.anchorObject
        val focusedObject = activeCandidate?.focusedObject?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetLabel?.takeIf { value -> value.isNotBlank() }
            ?: taskSlotSnapshot?.targetKey?.takeIf { value -> value.isNotBlank() }
            ?: previousActive?.focusedObject
            ?: anchorObject
        val action = activeCandidate?.action?.takeIf { value -> value.isNotBlank() }
            ?: resolvedState.currentTaskRecord?.actionCode?.wireName?.takeIf { value -> value.isNotBlank() }
            ?: resolvedState.currentTaskDraft?.actionCode?.wireName?.takeIf { value -> value.isNotBlank() }
            ?: previousActive?.action
        val structuredDetailSlots = taskSlotSnapshot?.structuredDetailSlots
            ?: previousActive?.structuredDetailSlots
            ?: TaskDetailSlots()
        val resolvedSlots = taskSlotSnapshot?.resolvedSlots?.takeIf { slots -> slots.isNotEmpty() }
            ?: previousActive?.resolvedSlots
            ?: emptyMap()
        val normalizedSummary = summaryText.trim().ifBlank {
            previousActive?.summaryText.orEmpty()
        }.ifBlank {
            focusedObject.orEmpty()
        }.ifBlank {
            anchorObject.orEmpty()
        }.ifBlank {
            userMessage.trim()
        }.ifBlank {
            assistantReply.trim()
        }
        if (
            normalizedSummary.isBlank() &&
            anchorObject.isNullOrBlank() &&
            focusedObject.isNullOrBlank() &&
            action.isNullOrBlank()
        ) {
            return existingStore
        }
        val topicId = stableTopicContextId(
            anchorObject = anchorObject,
            focusedObject = focusedObject,
            action = action,
            preferredAppScope = preferredAppScope,
            capabilityDomain = capabilityDomain?.wireName,
            resolvedSlots = resolvedSlots,
            fallbackSummary = normalizedSummary
        )
        val sameActiveTopic = previousActive?.topicId == topicId
        val sourceTurnIds = buildList {
            if (sameActiveTopic) {
                addAll(previousActive?.sourceTurnIds.orEmpty())
            }
            store.messages.asReversed().firstOrNull { message ->
                message.role == MessageRole.USER && userMessage.isNotBlank() && message.content.trim() == userMessage.trim()
            }?.id?.let(::add)
            store.messages.asReversed().firstOrNull { message ->
                message.role == MessageRole.ASSISTANT && assistantReply.isNotBlank() && message.content.trim() == assistantReply.trim()
            }?.id?.let(::add)
        }.distinct().takeLast(6)
        val matchingHints = buildList {
            if (sameActiveTopic) {
                addAll(previousActive?.matchingHints.orEmpty())
            }
            listOf(
                normalizedSummary,
                userMessage.trim(),
                assistantReply.trim(),
                anchorObject.orEmpty(),
                focusedObject.orEmpty(),
                action.orEmpty(),
                preferredAppScope.orEmpty()
            ).forEach { value ->
                value.takeIf { text -> text.isNotBlank() }?.let(::add)
            }
            resolvedSlots.values.forEach { value ->
                value.takeIf { text -> text.isNotBlank() }?.let(::add)
            }
        }.distinct().take(8)
        val activeTopicRecord = TopicContextRecord(
            topicId = topicId,
            summaryText = normalizedSummary,
            anchorObject = anchorObject,
            focusedObject = focusedObject,
            action = action,
            preferredAppScope = preferredAppScope,
            capabilityDomain = capabilityDomain,
            capabilityId = taskSlotSnapshot?.capabilityId ?: previousActive?.capabilityId,
            structuredDetailSlots = structuredDetailSlots,
            resolvedSlots = resolvedSlots,
            sourceTurnIds = sourceTurnIds,
            matchingHints = matchingHints,
            lastSourceType = sourceType,
            lastTouchedAt = now
        )
        val silentTopicRecords = buildList {
            if (!sameActiveTopic) {
                previousActive?.let(::add)
            }
            addAll(existingStore.silentTopicRecords.filterNot { record -> record.topicId == topicId })
        }.sortedByDescending { record -> record.lastTouchedAt }
            .distinctBy { record -> record.topicId }
            .take(6)
        return TopicContextStore(
            activeTopicId = activeTopicRecord.topicId,
            activeTopicRecord = activeTopicRecord,
            silentTopicRecords = silentTopicRecords,
            lastUpdatedAt = now
        )
    }

    private fun stableTopicContextId(
        anchorObject: String?,
        focusedObject: String?,
        action: String?,
        preferredAppScope: String?,
        capabilityDomain: String?,
        resolvedSlots: Map<String, String>,
        fallbackSummary: String
    ): String {
        val fingerprint = listOf(
            capabilityDomain.orEmpty().trim().lowercase(Locale.US),
            preferredAppScope.orEmpty().trim().lowercase(Locale.US),
            action.orEmpty().trim().lowercase(Locale.US),
            anchorObject.orEmpty().trim().lowercase(Locale.US),
            focusedObject.orEmpty().trim().lowercase(Locale.US),
            resolvedSlots.entries
                .sortedBy { (slotKey, _) -> slotKey }
                .joinToString("|") { (slotKey, slotValue) ->
                    "${slotKey.trim().lowercase(Locale.US)}=${slotValue.trim().lowercase(Locale.US)}"
                },
            fallbackSummary.trim().lowercase(Locale.US)
        ).joinToString("###")
        return UUID.nameUUIDFromBytes(fingerprint.toByteArray()).toString()
    }
}

internal fun MemoryState.recordPreferenceDebugSnapshot(
    recallBundle: PreferenceRecallBundle,
    mappingTrace: PreferenceSlotMappingTrace
): MemoryState {
    return copy(
        preferenceDebugStore = preferenceDebugStore.copy(
            lastRecallDebugSnapshot = recallBundle.toPreferenceRecallDebugSnapshot(),
            lastMappingTrace = mappingTrace
        )
    )
}

internal fun PreferenceRecallBundle.toPreferenceRecallDebugSnapshot(
    generatedAt: Long = System.currentTimeMillis()
): PreferenceRecallDebugSnapshot {
    return PreferenceRecallDebugSnapshot(
        domain = domain,
        anchorObject = anchorObject,
        directCount = likelyPreferences.size + avoidances.size,
        recentCount = recentFacts.size,
        semanticCount = semanticEvidence.size,
        neighborCount = neighborEvidence.size,
        derivedCount = derivedHypotheses.size,
        siblingExpansionEnabled = neighborEvidence.isNotEmpty() || derivedHypotheses.isNotEmpty(),
        debugSummary = debugSummary,
        generatedAt = generatedAt
    )
}

internal fun RecommendedDetailSlotBundle.toPreferenceSlotMappingTrace(
    generatedAt: Long = System.currentTimeMillis()
): PreferenceSlotMappingTrace {
    val domainPrefix = domain?.wireName ?: CapabilityDomain.OTHER.wireName
    val decisions = buildList {
        recommendedCommonSlots.forEach { (slotKey, slot) ->
            add(
                PreferenceSlotMappingDecision(
                    slotKey = "common.$slotKey",
                    slotValue = slot.slotValue,
                    sourceTier = slot.sourceTier,
                    decisionType = if (slot.confirmationNeeded) {
                        PreferenceSlotDecisionType.CONFIRMATION_NEEDED
                    } else {
                        PreferenceSlotDecisionType.ACCEPTED
                    }
                )
            )
        }
        recommendedDomainSlots.forEach { (slotKey, slot) ->
            add(
                PreferenceSlotMappingDecision(
                    slotKey = "$domainPrefix.$slotKey",
                    slotValue = slot.slotValue,
                    sourceTier = slot.sourceTier,
                    decisionType = if (slot.confirmationNeeded) {
                        PreferenceSlotDecisionType.CONFIRMATION_NEEDED
                    } else {
                        PreferenceSlotDecisionType.ACCEPTED
                    }
                )
            )
        }
        blockedSlots.forEach { slotKey ->
            add(
                PreferenceSlotMappingDecision(
                    slotKey = slotKey,
                    decisionType = PreferenceSlotDecisionType.BLOCKED
                )
            )
        }
    }
    return PreferenceSlotMappingTrace(
        domain = domain?.wireName,
        decisions = decisions,
        siblingExpansionEnabled = decisions.any { decision ->
            decision.sourceTier?.equals("DERIVED", ignoreCase = true) == true
        },
        debugSummary = debugSummary,
        generatedAt = generatedAt
    )
}

internal fun PreferenceDebugStore.latestProjectionSnapshot(sourceType: String): PreferenceProjectionDebugSnapshot? {
    return projectionSnapshots.firstOrNull { snapshot ->
        snapshot.sourceType.equals(sourceType, ignoreCase = true)
    }
}

internal fun PreferenceProjectionDebugSnapshot.summaryLine(): String {
    return buildString {
        append(sourceType)
        append(":")
        append(domain)
        append(":")
        append(anchorObject)
        append(";facts=")
        append(factCount)
        append(";bias=")
        append(biasCount)
        append(";recent=")
        append(recentFactCount)
        append(";semantic=")
        append(semanticChunkCount)
        summaryText?.takeIf { text -> text.isNotBlank() }?.let { text ->
            append(";summary=")
            append(text)
        }
    }
}

internal fun PreferenceRecallDebugSnapshot.summaryLine(): String {
    return "domain=$domain; anchor=$anchorObject; direct=$directCount; recent=$recentCount; semantic=$semanticCount; neighbor=$neighborCount; derived=$derivedCount; sibling=$siblingExpansionEnabled"
}

internal fun PreferenceSlotMappingTrace.summaryLine(): String {
    val acceptedCount = decisions.count { decision -> decision.decisionType == PreferenceSlotDecisionType.ACCEPTED }
    val blockedCount = decisions.count { decision -> decision.decisionType == PreferenceSlotDecisionType.BLOCKED }
    val confirmationCount = decisions.count { decision -> decision.decisionType == PreferenceSlotDecisionType.CONFIRMATION_NEEDED }
    val tierSummary = decisions
        .mapNotNull { decision ->
            val tier = decision.sourceTier ?: return@mapNotNull null
            "${decision.slotKey}:$tier"
        }
        .joinToString(",")
        .ifBlank { "none" }
    return "domain=${domain.orEmpty().ifBlank { "none" }}; accepted=$acceptedCount; blocked=$blockedCount; confirmation=$confirmationCount; sibling=$siblingExpansionEnabled; tiers=$tierSummary"
}
