package com.atombits.pocopaw

import java.util.Locale

data class PreferenceRecallRequest(
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val focusedObject: String? = null,
    val userQuery: String,
    val activeTopicSummary: String? = null,
    val currentStructuredSlots: TaskDetailSlots = TaskDetailSlots(),
    val currentResolvedSlots: Map<String, String> = emptyMap(),
    val allowSiblingExpansion: Boolean = false
)

data class PreferenceEvidenceItem(
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val facetKey: String,
    val facetValue: String,
    val polarity: String,
    val confidence: Double,
    val sourceTier: String,
    val sourceRefs: List<String>,
    val reasonSummary: String
)

data class DerivedPreferenceHypothesis(
    val facetKey: String,
    val facetValue: String,
    val confidence: Double,
    val sourceTier: String,
    val sourceRefs: List<String>,
    val reasonSummary: String,
    val confirmationNeeded: Boolean
)

data class PreferenceRecallBundle(
    val domainRoot: String,
    val domain: String,
    val anchorObject: String,
    val likelyPreferences: List<PreferenceEvidenceItem>,
    val avoidances: List<PreferenceEvidenceItem>,
    val recentFacts: List<String>,
    val semanticEvidence: List<String>,
    val neighborEvidence: List<String>,
    val derivedHypotheses: List<DerivedPreferenceHypothesis> = emptyList(),
    val confidence: Double,
    val debugSummary: String
)

data class RecommendedDetailSlot(
    val slotKey: String,
    val slotValue: String,
    val confidence: Double,
    val sourceTier: String,
    val sourceRefs: List<String>,
    val reasonSummary: String,
    val confirmationNeeded: Boolean
)

data class RecommendedDetailSlotBundle(
    val domain: CapabilityDomain?,
    val recommendedCommonSlots: Map<String, RecommendedDetailSlot>,
    val recommendedDomainSlots: Map<String, RecommendedDetailSlot>,
    val explicitUserSlots: Map<String, String>,
    val blockedSlots: List<String>,
    val debugSummary: String
)

private const val directRecallSourceTier = "DIRECT"
private const val derivedRecallSourceTier = "DERIVED"

internal data class PreferenceInferenceExpansionResult(
    val neighborEvidence: List<String> = emptyList(),
    val derivedHypotheses: List<DerivedPreferenceHypothesis> = emptyList(),
    val debugSummary: String = "neighbor=0;derived=0"
)

internal object PreferenceRecallResolver {
    fun resolve(
        memoryState: MemoryState,
        request: PreferenceRecallRequest,
        limit: Int = 4
    ): PreferenceRecallBundle {
        val effectiveLimit = limit.coerceAtLeast(1)
        val requestDomain = resolvePreferenceRecallDomain(request.domain)
        val requestDomainRoot = request.domainRoot.trim().ifBlank {
            CapabilityDomainProfileRegistry.domainRoot(requestDomain)
        }
        val directFacts = memoryState.structuredPreferenceMemory.facts
            .filter { fact -> fact.domain == requestDomain.wireName }
            .filter { fact -> fact.matchesDirectRecallRequest(request) }
            .sortedWith(
                compareByDescending<PreferenceFacetFact> { fact -> directPreferenceSourcePriority(fact.sourceType) }
                    .thenByDescending { fact -> fact.confidence }
                    .thenByDescending { fact -> fact.lastObservedAt }
            )
            .distinctBy(::directPreferenceConflictKey)
            .take(effectiveLimit)

        val likelyPreferences = directFacts
            .filterNot(::isAvoidanceFact)
            .map { fact -> fact.toEvidenceItem(sourceTier = directRecallSourceTier) }
        val avoidances = directFacts
            .filter(::isAvoidanceFact)
            .map { fact -> fact.toEvidenceItem(sourceTier = directRecallSourceTier) }

        val recentFacts = memoryState.structuredPreferenceMemory.recentFacts
            .filter { fact -> fact.sourceType != preferenceSummaryProjectionSourceType }
            .filter { fact -> fact.domain == requestDomain.wireName }
            .filter { fact -> fact.matchesDirectRecallRequest(request) }
            .sortedByDescending { fact -> fact.lastObservedAt }
            .map { fact -> fact.summary }
            .distinct()
            .take(effectiveLimit)

        val semanticEvidence = memoryState.structuredPreferenceMemory.semanticChunks
            .filter { chunk -> chunk.domain == requestDomain.wireName }
            .filter { chunk -> chunk.matchesDirectRecallRequest(request) }
            .sortedWith(compareByDescending<PreferenceSemanticChunk> { chunk -> chunk.confidence }.thenByDescending { chunk -> chunk.lastObservedAt })
            .map { chunk -> chunk.summaryText }
            .distinct()
            .take(effectiveLimit)

        val expansion = if (request.allowSiblingExpansion && likelyPreferences.isEmpty() && avoidances.isEmpty()) {
            PreferenceInferenceExpander.expand(memoryState, request)
        } else {
            PreferenceInferenceExpansionResult(debugSummary = "neighbor=0;derived=0")
        }

        val confidenceSignals = likelyPreferences.map { item -> item.confidence } +
            avoidances.map { item -> item.confidence } +
            expansion.derivedHypotheses.map { hypothesis -> hypothesis.confidence }
        val confidence = confidenceSignals.ifEmpty { listOf(0.0) }
            .average()
            .coerceIn(0.0, 0.95)

        return PreferenceRecallBundle(
            domainRoot = requestDomainRoot,
            domain = requestDomain.wireName,
            anchorObject = request.anchorObject,
            likelyPreferences = likelyPreferences,
            avoidances = avoidances,
            recentFacts = recentFacts,
            semanticEvidence = semanticEvidence,
            neighborEvidence = expansion.neighborEvidence,
            derivedHypotheses = expansion.derivedHypotheses,
            confidence = confidence,
            debugSummary = buildString {
                append("direct=")
                append(likelyPreferences.size + avoidances.size)
                append(";recent=")
                append(recentFacts.size)
                append(";semantic=")
                append(semanticEvidence.size)
                append(';')
                append(expansion.debugSummary)
            }
        )
    }
}

internal object PreferenceInferenceExpander {
    fun expand(
        memoryState: MemoryState,
        request: PreferenceRecallRequest,
        limit: Int = 3
    ): PreferenceInferenceExpansionResult {
        val effectiveLimit = limit.coerceAtLeast(1)
        val requestDomain = resolvePreferenceRecallDomain(request.domain)
        val requestDomainRoot = request.domainRoot.trim().ifBlank {
            CapabilityDomainProfileRegistry.domainRoot(requestDomain)
        }
        val explicitUserSlots = collectExplicitRecallSlots(request, requestDomain)
        val transferableFacetKeys = CapabilityDomainProfileRegistry.transferableFacetKeys(requestDomain)
        val allowedSemanticScopes = CapabilityDomainProfileRegistry.semanticExpansionScope(requestDomain)
            .mapNotNull { scope ->
                scope.trim().lowercase(Locale.US).takeIf { value -> value.isNotBlank() }
            }
            .toSet()
        if (transferableFacetKeys.isEmpty()) {
            return PreferenceInferenceExpansionResult(debugSummary = "neighbor=0;derived=0")
        }
        val neighborFacts = memoryState.structuredPreferenceMemory.facts
            .filter { fact -> fact.domainRoot == requestDomainRoot }
            .filterNot { fact -> fact.domain == requestDomain.wireName }
            .filter { fact -> fact.transferabilityTag.equals("TRANSFERABLE", ignoreCase = true) }
            .filter { fact -> fact.facetKey in transferableFacetKeys }
            .filter { fact -> CapabilityDomainProfileRegistry.facetSlotMapping(requestDomain, fact.facetKey) != null }
            .filter { fact -> !conflictsWithExplicitUserSlot(requestDomain, explicitUserSlots, fact) }
            .sortedWith(
                compareByDescending<PreferenceFacetFact> { fact -> fact.confidence }
                    .thenBy { fact ->
                        CapabilityDomainProfileRegistry.siblingDistance(requestDomain, CapabilityDomain.fromRaw(fact.domain)) ?: Int.MAX_VALUE
                    }
                    .thenByDescending { fact -> fact.lastObservedAt }
            )
            .take(effectiveLimit)
        val neighborSemanticChunks = memoryState.structuredPreferenceMemory.semanticChunks
            .filter { chunk -> chunk.domainRoot == requestDomainRoot }
            .filterNot { chunk -> chunk.domain == requestDomain.wireName }
            .filter { chunk ->
                val normalizedScope = chunk.semanticScope.trim().lowercase(Locale.US)
                normalizedScope.isNotBlank() && normalizedScope in allowedSemanticScopes
            }
            .sortedWith(
                compareByDescending<PreferenceSemanticChunk> { chunk -> chunk.confidence }
                    .thenBy { chunk ->
                        CapabilityDomainProfileRegistry.siblingDistance(requestDomain, CapabilityDomain.fromRaw(chunk.domain)) ?: Int.MAX_VALUE
                    }
                    .thenByDescending { chunk -> chunk.lastObservedAt }
            )
            .take(effectiveLimit)
        if (neighborFacts.isEmpty() && neighborSemanticChunks.isEmpty()) {
            return PreferenceInferenceExpansionResult(debugSummary = "neighbor=0;derived=0")
        }
        val neighborEvidence = neighborFacts.map { fact ->
            "neighbor_domain=${fact.domain}; anchor=${fact.anchorObject}; ${fact.facetKey}=${fact.facetValue}; confidence=${"%.2f".format(fact.confidence)}"
        } + neighborSemanticChunks.map { chunk ->
            "neighbor_semantic_domain=${chunk.domain}; anchor=${chunk.anchorObject}; scope=${chunk.semanticScope}; summary=${chunk.summaryText}; confidence=${"%.2f".format(chunk.confidence)}"
        }
        val derivedHypotheses = neighborFacts.mapNotNull { fact ->
            CapabilityDomainProfileRegistry.facetSlotMapping(requestDomain, fact.facetKey)?.let { mapping ->
                DerivedPreferenceHypothesis(
                    facetKey = fact.facetKey,
                    facetValue = fact.facetValue,
                    confidence = (fact.confidence * 0.75).coerceAtMost(0.72),
                    sourceTier = derivedRecallSourceTier,
                    sourceRefs = listOf(fact.sourceRef, mapping.namespacedKey(requestDomain)),
                    reasonSummary = "derived from sibling ${fact.domain}.${fact.facetKey}",
                    confirmationNeeded = true
                )
            }
        }
        return PreferenceInferenceExpansionResult(
            neighborEvidence = neighborEvidence,
            derivedHypotheses = derivedHypotheses,
            debugSummary = "neighbor=${neighborEvidence.size};derived=${derivedHypotheses.size}"
        )
    }
}

internal object PreferenceSlotRecommendationEngine {
    fun recommend(
        request: PreferenceRecallRequest,
        recallBundle: PreferenceRecallBundle
    ): RecommendedDetailSlotBundle {
        val requestDomain = resolvePreferenceRecallDomain(request.domain)
        val explicitUserSlots = collectExplicitRecallSlots(request, requestDomain)
        val blockedSlots = explicitUserSlots.keys.sorted()
        val commonSlots = linkedMapOf<String, RecommendedDetailSlot>()
        val domainSlots = linkedMapOf<String, RecommendedDetailSlot>()

        recallBundle.likelyPreferences.forEach { item ->
            addRecommendationCandidate(
                requestDomain = requestDomain,
                facetKey = item.facetKey,
                facetValue = item.facetValue,
                confidence = item.confidence,
                sourceTier = item.sourceTier,
                sourceRefs = item.sourceRefs,
                reasonSummary = item.reasonSummary,
                confirmationNeeded = false,
                explicitUserSlots = explicitUserSlots,
                commonSlots = commonSlots,
                domainSlots = domainSlots
            )
        }
        recallBundle.derivedHypotheses.forEach { hypothesis ->
            addRecommendationCandidate(
                requestDomain = requestDomain,
                facetKey = hypothesis.facetKey,
                facetValue = hypothesis.facetValue,
                confidence = hypothesis.confidence,
                sourceTier = hypothesis.sourceTier,
                sourceRefs = hypothesis.sourceRefs,
                reasonSummary = hypothesis.reasonSummary,
                confirmationNeeded = hypothesis.confirmationNeeded,
                explicitUserSlots = explicitUserSlots,
                commonSlots = commonSlots,
                domainSlots = domainSlots
            )
        }

        return RecommendedDetailSlotBundle(
            domain = requestDomain,
            recommendedCommonSlots = commonSlots,
            recommendedDomainSlots = domainSlots,
            explicitUserSlots = explicitUserSlots,
            blockedSlots = blockedSlots,
            debugSummary = "common=${commonSlots.size};domain=${domainSlots.size};blocked=${blockedSlots.size};recall=${recallBundle.debugSummary}"
        )
    }
}

private fun addRecommendationCandidate(
    requestDomain: CapabilityDomain,
    facetKey: String,
    facetValue: String,
    confidence: Double,
    sourceTier: String,
    sourceRefs: List<String>,
    reasonSummary: String,
    confirmationNeeded: Boolean,
    explicitUserSlots: Map<String, String>,
    commonSlots: MutableMap<String, RecommendedDetailSlot>,
    domainSlots: MutableMap<String, RecommendedDetailSlot>
) {
    val mapping = CapabilityDomainProfileRegistry.facetSlotMapping(requestDomain, facetKey) ?: return
    val namespacedKey = mapping.namespacedKey(requestDomain)
    if (explicitUserSlots.containsKey(namespacedKey)) {
        return
    }
    val recommendation = RecommendedDetailSlot(
        slotKey = mapping.commonSlotKey?.wireName ?: mapping.domainSlotKey.orEmpty(),
        slotValue = facetValue,
        confidence = confidence.coerceIn(0.0, 0.95),
        sourceTier = sourceTier,
        sourceRefs = sourceRefs,
        reasonSummary = reasonSummary,
        confirmationNeeded = confirmationNeeded
    )
    if (mapping.commonSlotKey != null) {
        mergeRecommendedSlot(commonSlots, mapping.commonSlotKey.wireName, recommendation)
    } else {
        mergeRecommendedSlot(domainSlots, mapping.domainSlotKey.orEmpty(), recommendation)
    }
}

private fun mergeRecommendedSlot(
    target: MutableMap<String, RecommendedDetailSlot>,
    key: String,
    candidate: RecommendedDetailSlot
) {
    val existing = target[key]
    if (
        existing == null ||
        candidate.confidence > existing.confidence ||
        (candidate.confidence == existing.confidence && !candidate.confirmationNeeded && existing.confirmationNeeded)
    ) {
        target[key] = candidate
    }
}

private fun collectExplicitRecallSlots(
    request: PreferenceRecallRequest,
    requestDomain: CapabilityDomain
): Map<String, String> {
    return (request.currentStructuredSlots.toNamespacedResolvedSlots(requestDomain) + request.currentResolvedSlots)
        .mapNotNull { (key, value) ->
            val normalizedValue = value.trim()
            if (normalizedValue.isBlank()) {
                null
            } else {
                key to normalizedValue
            }
        }
        .toMap(linkedMapOf())
}

private fun conflictsWithExplicitUserSlot(
    requestDomain: CapabilityDomain,
    explicitUserSlots: Map<String, String>,
    fact: PreferenceFacetFact
): Boolean {
    val mapping = CapabilityDomainProfileRegistry.facetSlotMapping(requestDomain, fact.facetKey) ?: return false
    val explicitValue = explicitUserSlots[mapping.namespacedKey(requestDomain)] ?: return false
    return !explicitValue.equals(fact.facetValue, ignoreCase = true)
}

private fun isAvoidanceFact(fact: PreferenceFacetFact): Boolean {
    val normalizedPolarity = fact.polarity.trim().uppercase(Locale.US)
    return normalizedPolarity == "AVOID" || normalizedPolarity == "DISLIKE" || normalizedPolarity == "NEGATIVE"
}

private fun PreferenceFacetFact.toEvidenceItem(sourceTier: String): PreferenceEvidenceItem {
    return PreferenceEvidenceItem(
        domainRoot = domainRoot,
        domain = domain,
        anchorObject = anchorObject,
        facetKey = facetKey,
        facetValue = facetValue,
        polarity = polarity,
        confidence = confidence,
        sourceTier = sourceTier,
        sourceRefs = listOf(sourceRef),
        reasonSummary = "${sourceType.lowercase(Locale.US)}:${facetKey}"
    )
}

private fun PreferenceFacetFact.matchesDirectRecallRequest(request: PreferenceRecallRequest): Boolean {
    val normalizedAnchor = normalizePreferenceRecallText(anchorObject)
    val normalizedValue = normalizePreferenceRecallText(facetValue)
    val normalizedRequestAnchor = normalizePreferenceRecallText(request.anchorObject)
    val normalizedFocusedObject = normalizePreferenceRecallText(request.focusedObject.orEmpty())
    val normalizedUserQuery = normalizePreferenceRecallText(request.userQuery)
    val normalizedTopic = normalizePreferenceRecallText(request.activeTopicSummary.orEmpty())
    return normalizedAnchor == normalizedRequestAnchor ||
        (normalizedFocusedObject.isNotBlank() && normalizedAnchor == normalizedFocusedObject) ||
        (normalizedAnchor.isNotBlank() && normalizedUserQuery.contains(normalizedAnchor)) ||
        (normalizedValue.isNotBlank() && normalizedUserQuery.contains(normalizedValue)) ||
        (normalizedAnchor.isNotBlank() && normalizedTopic.contains(normalizedAnchor)) ||
        (normalizedValue.isNotBlank() && normalizedTopic.contains(normalizedValue))
}

private fun directPreferenceSourcePriority(sourceType: String): Int {
    return when {
        sourceType.equals("APP_SCAN", ignoreCase = true) -> 2
        sourceType.equals("OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION", ignoreCase = true) -> 1
        else -> 0
    }
}

private fun directPreferenceConflictKey(fact: PreferenceFacetFact): String {
    val polarityBucket = if (isAvoidanceFact(fact)) "avoid" else "prefer"
    return listOf(fact.facetKey, polarityBucket).joinToString("|")
}

private fun PreferenceRecentFact.matchesDirectRecallRequest(request: PreferenceRecallRequest): Boolean {
    val normalizedAnchor = normalizePreferenceRecallText(anchorObject)
    val normalizedSummary = normalizePreferenceRecallText(summary)
    val normalizedRequestAnchor = normalizePreferenceRecallText(request.anchorObject)
    val normalizedFocusedObject = normalizePreferenceRecallText(request.focusedObject.orEmpty())
    val normalizedUserQuery = normalizePreferenceRecallText(request.userQuery)
    return normalizedAnchor == normalizedRequestAnchor ||
        (normalizedFocusedObject.isNotBlank() && normalizedAnchor == normalizedFocusedObject) ||
        (normalizedAnchor.isNotBlank() && normalizedUserQuery.contains(normalizedAnchor)) ||
        (normalizedSummary.isNotBlank() && normalizedUserQuery.contains(normalizedSummary))
}

private fun PreferenceSemanticChunk.matchesDirectRecallRequest(request: PreferenceRecallRequest): Boolean {
    val normalizedAnchor = normalizePreferenceRecallText(anchorObject)
    val normalizedSummary = normalizePreferenceRecallText(summaryText)
    val normalizedRequestAnchor = normalizePreferenceRecallText(request.anchorObject)
    val normalizedFocusedObject = normalizePreferenceRecallText(request.focusedObject.orEmpty())
    val normalizedUserQuery = normalizePreferenceRecallText(request.userQuery)
    return normalizedAnchor == normalizedRequestAnchor ||
        (normalizedFocusedObject.isNotBlank() && normalizedAnchor == normalizedFocusedObject) ||
        (normalizedAnchor.isNotBlank() && normalizedUserQuery.contains(normalizedAnchor)) ||
        (normalizedSummary.isNotBlank() && normalizedUserQuery.contains(normalizedSummary))
}

private fun normalizePreferenceRecallText(value: String): String {
    return value.lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]+"), "")
}

private fun resolvePreferenceRecallDomain(rawDomain: String): CapabilityDomain {
    return CapabilityDomain.fromRaw(rawDomain) ?: CapabilityDomain.OTHER
}