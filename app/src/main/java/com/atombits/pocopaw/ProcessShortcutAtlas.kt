package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessAssetState

fun resolveProcessShortcutCandidate(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): ProcessShortcutCandidate? {
    val selectedProcessId = boundaryPacket.processId?.takeIf { it.isNotBlank() } ?: return null
    val explicitAppScope = extractCanonicalAppScope(boundaryPacket.capabilityId)
    val requestedAction = resolveCanonicalBoundaryPacketProcessAction(boundaryPacket)
    val preferredScreens = collectPreferredShortcutScreens(store, selectedProcessId, requestedAction)
    val taskSlotValues = boundaryPacket.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")?.resolvedSlots.orEmpty()
    val recommendedSlotValues = store.resolveDirectPreferenceRecommendedSlotValues(
        boundaryPacket = boundaryPacket,
        userQuery = boundaryPacket.planSummary
    )
    val scopedCandidates = store.processShortcutAtlas
        .asSequence()
        .filter { candidate ->
            explicitAppScope == null || candidate.appScope.equals(explicitAppScope, ignoreCase = true)
        }
        .toList()
    val exactMatches = scopedCandidates.filter { candidate -> candidate.processId == selectedProcessId }
    val fallbackMatches = if (exactMatches.isEmpty() && requestedAction != null) {
        scopedCandidates.filter { candidate -> candidate.resolvedProcessAction() == requestedAction }
    } else {
        emptyList()
    }
    return exactMatches.ifEmpty { fallbackMatches }
        .asSequence()
        .map { candidate ->
            candidate to findPreferredReusableProcessAssetRecord(
                store = store,
                appScope = candidate.appScope,
                processAction = candidate.resolvedProcessAction(),
                pathIndex = candidate.pathIndex
            )
        }
        .filter { (_, assetRecord) ->
            assetRecord?.assetState != ProcessAssetState.FAILED &&
                assetRecord?.assetState != ProcessAssetState.SUPERSEDED
        }
        .sortedWith(
            compareByDescending<Pair<ProcessShortcutCandidate, ReusableProcessAssetRecord?>> { (_, assetRecord) ->
                assetRecord?.assetState == ProcessAssetState.READY
            }
                .thenBy { (candidate, _) -> countMissingShortcutPrimaryFilters(candidate, taskSlotValues) }
                .thenByDescending { (candidate, _) -> countShortcutSlotOverlap(candidate, taskSlotValues) }
                .thenByDescending { (candidate, _) -> countShortcutValuePreserveMatches(candidate, taskSlotValues) }
                .thenByDescending { (candidate, _) -> countShortcutExactValueMatches(candidate, taskSlotValues) }
                .thenByDescending { (candidate, _) ->
                    countRecommendedShortcutPrimaryFilterMatches(candidate, taskSlotValues, recommendedSlotValues)
                }
                .thenByDescending { (candidate, _) ->
                    preferredScreens.contains(candidate.screenSignature)
                }
                .thenBy { (candidate, _) ->
                    preferredScreens.indexOf(candidate.screenSignature).let { index -> if (index >= 0) index else Int.MAX_VALUE }
                }
                .thenByDescending { (_, assetRecord) -> assetRecord?.updatedAt ?: Long.MIN_VALUE }
                .thenByDescending { (_, assetRecord) -> assetRecord?.successCount ?: Int.MIN_VALUE }
                .thenByDescending { (_, assetRecord) -> assetRecord?.revision ?: Int.MIN_VALUE }
                .thenByDescending { (candidate, _) -> candidate.stabilityScore }
                .thenByDescending { (candidate, _) -> candidate.version }
                .thenByDescending { (candidate, _) -> candidate.lastDerivedAt }
        )
        .firstOrNull()
        ?.first
}

private fun countShortcutSlotOverlap(
    candidate: ProcessShortcutCandidate,
    taskSlotValues: Map<String, String>
): Int {
    if (candidate.slotHints.isEmpty() || taskSlotValues.isEmpty()) {
        return 0
    }
    return candidate.slotHints
        .distinctBy { hint -> listOf(hint.slotKey, hint.hintRole).joinToString("|") }
        .count { hint -> taskSlotValues.containsKey(hint.slotKey) }
}

private fun countShortcutValuePreserveMatches(
    candidate: ProcessShortcutCandidate,
    taskSlotValues: Map<String, String>
): Int {
    if (candidate.slotHints.isEmpty() || taskSlotValues.isEmpty()) {
        return 0
    }
    return candidate.slotHints.count { hint ->
        hint.hintRole == "VALUE_PRESERVE" && taskSlotValues.containsKey(hint.slotKey)
    }
}

private fun countShortcutExactValueMatches(
    candidate: ProcessShortcutCandidate,
    taskSlotValues: Map<String, String>
): Int {
    if (candidate.slotHints.isEmpty() || taskSlotValues.isEmpty()) {
        return 0
    }
    return candidate.slotHints.count { hint ->
        val taskValue = taskSlotValues[hint.slotKey]?.trim().orEmpty()
        val exampleValue = hint.exampleValue?.trim().orEmpty()
        taskValue.isNotBlank() && exampleValue.isNotBlank() && taskValue.equals(exampleValue, ignoreCase = true)
    }
}

private fun countMissingShortcutPrimaryFilters(
    candidate: ProcessShortcutCandidate,
    taskSlotValues: Map<String, String>
): Int {
    if (candidate.slotHints.isEmpty()) {
        return 0
    }
    return candidate.slotHints.count { hint ->
        hint.hintRole == "PRIMARY_FILTER" && !taskSlotValues.containsKey(hint.slotKey)
    }
}

private fun countRecommendedShortcutPrimaryFilterMatches(
    candidate: ProcessShortcutCandidate,
    taskSlotValues: Map<String, String>,
    recommendedSlotValues: Map<String, String>
): Int {
    if (candidate.slotHints.isEmpty() || recommendedSlotValues.isEmpty()) {
        return 0
    }
    return candidate.slotHints.fold(0) { score, hint ->
        val recommendedValue = recommendedSlotValues[hint.slotKey]?.trim().orEmpty()
        val exampleValue = hint.exampleValue?.trim().orEmpty()
        score + when {
            hint.hintRole != "PRIMARY_FILTER" -> 0
            taskSlotValues.containsKey(hint.slotKey) -> 0
            recommendedValue.isBlank() -> 0
            exampleValue.isNotBlank() && recommendedValue.equals(exampleValue, ignoreCase = true) -> 2
            else -> 1
        }
    }
}

private fun collectPreferredShortcutScreens(
    store: PrototypeStoreData,
    selectedProcessId: String,
    processAction: String?
): List<String> {
    val memoryState = store.memoryState ?: return emptyList()
    val evidenceScreens = (memoryState.interactionBiasMemory.preferredShortcuts + memoryState.interactionBiasMemory.preferredPages)
        .asSequence()
        .filter { record ->
            (record.anchorObject == selectedProcessId ||
                (processAction != null && record.anchorObject == processAction))
        }
        .map { record -> record.signalValue }
        .filter { value -> value.isNotBlank() }
        .toList()
    return evidenceScreens.distinct()
}