package com.atombits.pocopaw

internal data class PreferenceRecommendationResolution(
    val request: PreferenceRecallRequest,
    val recallBundle: PreferenceRecallBundle,
    val recommendedDetailSlots: RecommendedDetailSlotBundle
)

internal fun PrototypeStoreData.resolveDirectPreferenceRecommendedSlotValues(
    boundaryPacket: TaskExecutionBoundaryPacket? = resolveCurrentExecutionBoundaryPacket(),
    userQuery: String = boundaryPacket?.planSummary.orEmpty()
): Map<String, String> {
    val recommendationContext = resolvePreferenceRecommendationResolution(
        boundaryPacket = boundaryPacket,
        userQuery = userQuery
    ) ?: return emptyMap()
    return recommendationContext.recommendedDetailSlots.directRecommendedSlotValues()
}

internal fun PrototypeStoreData.resolvePreferenceRecommendationContext(
    boundaryPacket: TaskExecutionBoundaryPacket? = resolveCurrentExecutionBoundaryPacket(),
    userQuery: String = boundaryPacket?.planSummary.orEmpty()
): RecommendedDetailSlotBundle? {
    return resolvePreferenceRecommendationResolution(boundaryPacket, userQuery)?.recommendedDetailSlots
}

internal fun PrototypeStoreData.resolvePreferenceRecommendationResolution(
    boundaryPacket: TaskExecutionBoundaryPacket? = resolveCurrentExecutionBoundaryPacket(),
    userQuery: String = boundaryPacket?.planSummary.orEmpty()
): PreferenceRecommendationResolution? {
    val request = buildPreferenceRecommendationRequest(
        store = this,
        boundaryPacket = boundaryPacket,
        userQuery = userQuery
    ) ?: return null
    val recallBundle = PreferenceRecallResolver.resolve(memoryState ?: MemoryState(), request)
    val recommendedDetailSlots = PreferenceSlotRecommendationEngine.recommend(request, recallBundle)
    memoryState = (memoryState ?: MemoryState()).recordPreferenceDebugSnapshot(
        recallBundle = recallBundle,
        mappingTrace = recommendedDetailSlots.toPreferenceSlotMappingTrace()
    )
    return PreferenceRecommendationResolution(
        request = request,
        recallBundle = recallBundle,
        recommendedDetailSlots = recommendedDetailSlots
    )
}

private fun buildPreferenceRecommendationRequest(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    userQuery: String
): PreferenceRecallRequest? {
    val resolvedBoundaryPacket = boundaryPacket ?: store.resolveCurrentExecutionBoundaryPacket()
    val resolvedState = store.resolveCurrentState()
    val taskSlotSnapshot = resolvedBoundaryPacket?.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")
        ?: store.resolveCurrentTaskSlotEvidenceSnapshot()
    val taskRecord = resolvedState.currentTaskRecord
    val taskDraft = resolvedState.currentTaskDraft
    val capabilityDomain = resolvedBoundaryPacket?.capabilityDomain
        ?: taskSlotSnapshot?.capabilityDomain
        ?: taskRecord?.capabilityDomain
        ?: taskDraft?.capabilityDomain
        ?: return null
    val anchorObject = resolvedBoundaryPacket?.targetLabel?.takeIf(String::isNotBlank)
        ?: resolvedBoundaryPacket?.targetKey?.takeIf(String::isNotBlank)
        ?: taskSlotSnapshot?.targetLabel?.takeIf(String::isNotBlank)
        ?: taskSlotSnapshot?.targetKey?.takeIf(String::isNotBlank)
        ?: taskRecord?.displayTarget().orEmpty().takeIf(String::isNotBlank)
        ?: taskDraft?.displayTarget()?.takeIf(String::isNotBlank)
        ?: return null
    val structuredSlots = taskSlotSnapshot?.structuredDetailSlots
        ?: resolvedBoundaryPacket?.structuredDetailSlots
        ?: taskRecord?.structuredDetailSlots
        ?: taskDraft?.structuredDetailSlots
        ?: TaskDetailSlots()
    val resolvedSlots = taskSlotSnapshot?.resolvedSlots
        ?: resolvedBoundaryPacket?.resolvedSlots
        ?: structuredSlots.toNamespacedResolvedSlots(capabilityDomain)
    val resolvedUserQuery = userQuery.trim().ifBlank {
        listOfNotNull(
            resolvedBoundaryPacket?.planSummary,
            resolvedBoundaryPacket?.objectiveSummary,
            taskRecord?.displayPlanSummary(),
            taskDraft?.displayPlanSummary()
        ).firstOrNull { value -> value.isNotBlank() } ?: anchorObject
    }
    val activeTopicSummary = listOfNotNull(
        resolvedBoundaryPacket?.reasonSummary,
        taskRecord?.reasonSummary,
        taskDraft?.reasonSummary
    ).firstOrNull { value -> value.isNotBlank() }
    val focusedObject = taskSlotSnapshot?.targetLabel?.takeIf(String::isNotBlank)
        ?: taskSlotSnapshot?.targetKey?.takeIf(String::isNotBlank)
    return PreferenceRecallRequest(
        domainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain),
        domain = capabilityDomain.wireName,
        anchorObject = anchorObject,
        focusedObject = focusedObject,
        userQuery = resolvedUserQuery,
        activeTopicSummary = activeTopicSummary,
        currentStructuredSlots = structuredSlots,
        currentResolvedSlots = resolvedSlots,
        allowSiblingExpansion = true
    )
}

internal fun RecommendedDetailSlotBundle.directRecommendedSlotValues(): Map<String, String> {
    val domainPrefix = domain?.wireName ?: CapabilityDomain.OTHER.wireName
    return linkedMapOf<String, String>().apply {
        recommendedCommonSlots.forEach { (slotKey, slot) ->
            if (slot.isDirectReusableRecommendation()) {
                put("common.$slotKey", slot.slotValue)
            }
        }
        recommendedDomainSlots.forEach { (slotKey, slot) ->
            if (slot.isDirectReusableRecommendation()) {
                put("$domainPrefix.$slotKey", slot.slotValue)
            }
        }
    }
}

private fun RecommendedDetailSlot.isDirectReusableRecommendation(): Boolean {
    return !confirmationNeeded && sourceTier.equals("DIRECT", ignoreCase = true)
}