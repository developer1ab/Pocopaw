package com.atombits.pocopaw

import java.nio.charset.StandardCharsets
import java.util.UUID

data class PreferenceFacetSeed(
    val facetKey: String,
    val facetValue: String,
    val polarity: String = "PREFER",
    val confidence: Double = 0.8,
    val freshnessHint: String = "RECENT",
    val sourceApp: String? = null,
    val supportingSourceApps: List<String> = emptyList(),
    val promotionSignals: List<String> = emptyList()
)

data class AppOrderHistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceApp: String,
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val normalizedTitle: String,
    val summaryText: String,
    val observedAt: Long,
    val facetSeeds: List<PreferenceFacetSeed>
)

data class DialoguePreferenceRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceType: String = "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION",
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val normalizedTitle: String,
    val summaryText: String,
    val observedAt: Long,
    val facetSeeds: List<PreferenceFacetSeed>
)

internal data class PreferenceMemorySignalProjection(
    val preferenceFacts: List<PreferenceFacetFact> = emptyList(),
    val recentFacts: List<PreferenceRecentFact> = emptyList(),
    val semanticChunks: List<PreferenceSemanticChunk> = emptyList(),
    val interactionBiasRecords: List<InteractionBiasRecord> = emptyList()
) {
    fun toMemoryPreferenceEvidenceRecords(): List<MemoryPreferenceEvidenceRecord> {
        return preferenceFacts.map { fact ->
            MemoryPreferenceEvidenceRecord(
                evidenceId = fact.id,
                domain = fact.domain,
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
        } + interactionBiasRecords.map { record ->
            MemoryPreferenceEvidenceRecord(
                evidenceId = record.id,
                domain = record.domain,
                anchorObject = record.anchorObject,
                slotKey = record.signalKey,
                slotValue = record.signalValue,
                polarity = "PREFER",
                confidence = record.confidence,
                sourceType = record.sourceType,
                sourceApp = record.sourceApp,
                freshnessHint = record.freshnessHint,
                observationCount = record.observationCount,
                lastObservedAt = record.lastObservedAt
            )
        }
    }
}

internal object PreferenceMemorySignalProjector {
    fun project(record: AppOrderHistoryRecord): PreferenceMemorySignalProjection {
        return projectRecord(
            recordId = record.id,
            sourceType = "APP_SCAN",
            sourceApp = record.sourceApp,
            domainRoot = record.domainRoot,
            domain = record.domain,
            anchorObject = record.anchorObject,
            normalizedTitle = record.normalizedTitle,
            summaryText = record.summaryText,
            observedAt = record.observedAt,
            facetSeeds = record.facetSeeds
        )
    }

    fun project(record: DialoguePreferenceRecord): PreferenceMemorySignalProjection {
        return projectRecord(
            recordId = record.id,
            sourceType = record.sourceType,
            sourceApp = null,
            domainRoot = record.domainRoot,
            domain = record.domain,
            anchorObject = record.anchorObject,
            normalizedTitle = record.normalizedTitle,
            summaryText = record.summaryText,
            observedAt = record.observedAt,
            facetSeeds = record.facetSeeds
        )
    }

    private fun projectRecord(
        recordId: String,
        sourceType: String,
        sourceApp: String?,
        domainRoot: String,
        domain: String,
        anchorObject: String,
        normalizedTitle: String,
        summaryText: String,
        observedAt: Long,
        facetSeeds: List<PreferenceFacetSeed>
    ): PreferenceMemorySignalProjection {
        val capabilityDomain = CapabilityDomain.fromRaw(domain)
        val normalizedDomain = capabilityDomain?.wireName
            ?: domain.trim().lowercase().ifBlank { CapabilityDomain.OTHER.wireName }
        val normalizedDomainRoot = domainRoot.trim().ifBlank {
            CapabilityDomainProfileRegistry.domainRoot(capabilityDomain)
        }
        val resolvedAnchor = anchorObject.trim().ifBlank {
            normalizedTitle.trim().ifBlank { normalizedDomain }
        }
        val normalizedSourceApp = sourceApp?.trim()?.takeIf { value -> value.isNotBlank() }
        val normalizedSeeds = facetSeeds.mapNotNull { seed ->
            val seedKey = seed.facetKey.trim().lowercase()
            val seedValue = seed.facetValue.trim()
            if (seedKey.isBlank() || seedValue.isBlank()) {
                null
            } else {
                seed.copy(
                    facetKey = seedKey,
                    facetValue = seedValue,
                    polarity = seed.polarity.trim().ifBlank { "PREFER" },
                    freshnessHint = seed.freshnessHint.trim().ifBlank { "RECENT" },
                    sourceApp = seed.sourceApp?.trim()?.takeIf { value -> value.isNotBlank() },
                    supportingSourceApps = seed.supportingSourceApps
                        .mapNotNull { value -> value.trim().takeIf { candidate -> candidate.isNotBlank() } }
                        .distinct()
                )
            }
        }
        val supportingSourceApps = (normalizedSeeds.flatMap { seed -> seed.supportingSourceApps } + listOfNotNull(normalizedSourceApp))
            .distinct()
        val projectionConfidence = normalizedSeeds.maxOfOrNull { seed -> seed.confidence.coerceIn(0.0, 1.0) } ?: 0.8
        val preferenceFacts = normalizedSeeds
            .filterNot { seed -> seed.facetKey in projectorInteractionBiasKeys }
            .mapIndexed { index, seed ->
                PreferenceFacetFact(
                    id = stableProjectionId(recordId, "fact:$index:${seed.facetKey}:${seed.facetValue}"),
                    domainRoot = normalizedDomainRoot,
                    domain = normalizedDomain,
                    anchorObject = resolvedAnchor,
                    facetKey = seed.facetKey,
                    facetValue = seed.facetValue,
                    polarity = seed.polarity.uppercase(),
                    confidence = seed.confidence.coerceIn(0.0, 1.0),
                    freshnessHint = seed.freshnessHint,
                    sourceType = sourceType,
                    sourceApp = seed.sourceApp ?: normalizedSourceApp,
                    sourceRef = recordId,
                    lastObservedAt = observedAt,
                    observationCount = 1,
                    transferabilityTag = if (seed.facetKey in CapabilityDomainProfileRegistry.transferableFacetKeys(capabilityDomain)) {
                        "TRANSFERABLE"
                    } else if (sourceType.equals("APP_SCAN", ignoreCase = true)) {
                        "APP_SCAN_DIRECT"
                    } else {
                        "DIRECT"
                    }
                )
            }
        val interactionBiasRecords = normalizedSeeds
            .filter { seed -> seed.facetKey in projectorInteractionBiasKeys }
            .mapIndexed { index, seed ->
                InteractionBiasRecord(
                    id = stableProjectionId(recordId, "bias:$index:${seed.facetKey}:${seed.facetValue}"),
                    domainRoot = normalizedDomainRoot,
                    domain = normalizedDomain,
                    anchorObject = resolvedAnchor,
                    signalKey = seed.facetKey,
                    signalValue = seed.facetValue,
                    confidence = seed.confidence.coerceIn(0.0, 1.0),
                    freshnessHint = seed.freshnessHint,
                    sourceType = sourceType,
                    sourceApp = seed.sourceApp ?: normalizedSourceApp,
                    sourceRef = recordId,
                    lastObservedAt = observedAt,
                    observationCount = 1
                )
            }
        val normalizedSummaryText = summaryText.trim().ifBlank {
            normalizedSeeds.joinToString("; ") { seed ->
                buildString {
                    append(seed.facetKey)
                    append('=')
                    append(seed.facetValue)
                    if (seed.promotionSignals.isNotEmpty()) {
                        append(" [")
                        append(seed.promotionSignals.joinToString(","))
                        append(']')
                    }
                }
            }
        }
        val recentFacts = normalizedSummaryText.takeIf { value -> value.isNotBlank() }?.let { summary ->
            listOf(
                PreferenceRecentFact(
                    id = stableProjectionId(recordId, "recent:$summary"),
                    domainRoot = normalizedDomainRoot,
                    domain = normalizedDomain,
                    anchorObject = resolvedAnchor,
                    summary = summary,
                    confidence = projectionConfidence,
                    sourceType = sourceType,
                    sourceRef = recordId,
                    lastObservedAt = observedAt,
                    supportingSourceApps = supportingSourceApps
                )
            )
        }.orEmpty()
        val semanticScope = CapabilityDomainProfileRegistry.semanticExpansionScope(capabilityDomain)
            .firstOrNull()
            ?: normalizedDomainRoot
        val semanticChunks = normalizedSummaryText.takeIf { value -> value.isNotBlank() }?.let { summary ->
            listOf(
                PreferenceSemanticChunk(
                    id = stableProjectionId(recordId, "semantic:$summary"),
                    domainRoot = normalizedDomainRoot,
                    domain = normalizedDomain,
                    anchorObject = resolvedAnchor,
                    summaryText = summary,
                    confidence = projectionConfidence,
                    sourceWeight = projectionConfidence,
                    sourceType = sourceType,
                    sourceRef = recordId,
                    lastObservedAt = observedAt,
                    semanticScope = semanticScope
                )
            )
        }.orEmpty()
        return PreferenceMemorySignalProjection(
            preferenceFacts = preferenceFacts,
            recentFacts = recentFacts,
            semanticChunks = semanticChunks,
            interactionBiasRecords = interactionBiasRecords
        )
    }
}

internal fun buildDialoguePreferenceRecords(
    extractionResult: OfflineDialoguePreferenceExtractionResult,
    observedAt: Long,
    sourceType: String = "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION"
): List<DialoguePreferenceRecord> {
    data class DialogueSeedCarrier(
        val domain: String,
        val anchorObject: String,
        val seed: PreferenceFacetSeed
    )

    val carriers = extractionResult.preferenceFacts.map { fact ->
        val capabilityDomain = CapabilityDomain.fromRaw(fact.domain)
        val normalizedDomain = capabilityDomain?.wireName
            ?: fact.domain?.trim()?.lowercase().orEmpty().ifBlank { CapabilityDomain.OTHER.wireName }
        val anchorObject = fact.anchorObject?.trim().orEmpty().ifBlank { normalizedDomain }
        DialogueSeedCarrier(
            domain = normalizedDomain,
            anchorObject = anchorObject,
            seed = PreferenceFacetSeed(
                facetKey = fact.facetKey,
                facetValue = fact.facetValue,
                polarity = fact.polarity,
                confidence = fact.confidence,
                freshnessHint = fact.freshnessHint
            )
        )
    } + extractionResult.interactionBiasSignals.map { signal ->
        val capabilityDomain = CapabilityDomain.fromRaw(signal.domain)
        val normalizedDomain = capabilityDomain?.wireName
            ?: signal.domain?.trim()?.lowercase().orEmpty().ifBlank { CapabilityDomain.OTHER.wireName }
        val anchorObject = signal.anchorObject?.trim().orEmpty().ifBlank { normalizedDomain }
        DialogueSeedCarrier(
            domain = normalizedDomain,
            anchorObject = anchorObject,
            seed = PreferenceFacetSeed(
                facetKey = signal.signalKey,
                facetValue = signal.signalValue,
                confidence = signal.confidence,
                freshnessHint = signal.freshnessHint
            )
        )
    }

    return carriers
        .groupBy { carrier -> listOf(carrier.domain, carrier.anchorObject).joinToString("###") }
        .values
        .mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val capabilityDomain = CapabilityDomain.fromRaw(first.domain)
            val summaryText = group.joinToString("; ") { carrier ->
                "${carrier.seed.facetKey}=${carrier.seed.facetValue}"
            }
            DialoguePreferenceRecord(
                sourceType = sourceType,
                domainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain),
                domain = first.domain,
                anchorObject = first.anchorObject,
                normalizedTitle = first.anchorObject,
                summaryText = summaryText,
                observedAt = observedAt,
                facetSeeds = group.map { carrier -> carrier.seed }
            )
        }
}

private val projectorInteractionBiasKeys = setOf(
    "preferred_process_id",
    "preferred_page_signature",
    "preferred_shortcut_screen"
)

private fun stableProjectionId(recordId: String, suffix: String): String {
    return UUID.nameUUIDFromBytes("$recordId|$suffix".toByteArray(StandardCharsets.UTF_8)).toString()
}