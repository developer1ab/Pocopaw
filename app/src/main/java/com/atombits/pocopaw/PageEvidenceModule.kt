package com.atombits.pocopaw

data class PageEvidenceObservation(
    val appScope: String,
    val processId: String,
    val pageSignature: String,
    val verificationSignals: List<String> = emptyList(),
    val locatorHints: List<String> = emptyList(),
    val observationCount: Int = 1,
    val lineageSourceTraceIds: List<String> = emptyList(),
    val observedAt: Long = System.currentTimeMillis()
)

fun resolvePreferredPageEvidenceAsset(
    store: PrototypeStoreData,
    selectedToolId: String?,
    processId: String?,
    preferredPageSignature: String? = null
): PageEvidenceAsset? {
    val resolvedProcessId = processId?.takeIf { value -> value.isNotBlank() } ?: return null
    val appScope = extractCanonicalAppScope(selectedToolId)
    return resolvePreferredPageEvidenceAsset(
        pageEvidenceAssets = store.pageEvidenceAssets,
        appScope = appScope,
        processId = resolvedProcessId,
        preferredPageSignature = preferredPageSignature
    )
}

fun resolvePreferredPageEvidenceAsset(
    pageEvidenceAssets: List<PageEvidenceAsset>,
    appScope: String?,
    processId: String?,
    preferredPageSignature: String? = null
): PageEvidenceAsset? {
    val resolvedProcessId = processId?.takeIf { value -> value.isNotBlank() } ?: return null
    val normalizedSignature = preferredPageSignature?.takeIf { value -> value.isNotBlank() }
    return pageEvidenceAssets
        .asSequence()
        .filter { asset -> asset.processId == resolvedProcessId }
        .filter { asset ->
            appScope.isNullOrBlank() || asset.appScope.equals(appScope, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<PageEvidenceAsset> { asset ->
                normalizedSignature != null && asset.pageSignature.equals(normalizedSignature, ignoreCase = true)
            }
                .thenByDescending { asset -> asset.observationCount }
                .thenByDescending { asset -> asset.version }
                .thenByDescending { asset -> asset.lastObservedAt }
        )
        .firstOrNull()
}

fun upsertPageEvidenceObservation(
    nextPageEvidenceAssets: MutableList<PageEvidenceAsset>,
    observation: PageEvidenceObservation,
    now: Long = observation.observedAt
) {
    val normalizedSignature = observation.pageSignature.trim()
    if (normalizedSignature.isEmpty()) {
        return
    }
    val normalizedSignals = observation.verificationSignals
        .map { value -> value.trim() }
        .filter { value -> value.isNotEmpty() }
        .distinct()
    val normalizedHints = observation.locatorHints
        .map { value -> value.trim() }
        .filter { value -> value.isNotEmpty() }
        .distinct()
    val candidateAsset = PageEvidenceAsset(
        evidenceId = "${observation.appScope}:${observation.processId}:$normalizedSignature",
        appScope = observation.appScope,
        processId = observation.processId,
        pageSignature = normalizedSignature,
        verificationSignals = normalizedSignals,
        locatorHints = normalizedHints,
        observationCount = observation.observationCount.coerceAtLeast(1),
        version = 1,
        lineageSourceTraceIds = observation.lineageSourceTraceIds.distinct(),
        lastObservedAt = observation.observedAt
    )
    val existingIndex = nextPageEvidenceAssets.indexOfFirst { asset -> asset.evidenceId == candidateAsset.evidenceId }
    val existingAsset = nextPageEvidenceAssets.getOrNull(existingIndex)
    if (existingAsset == null) {
        nextPageEvidenceAssets.add(candidateAsset.copy(lastObservedAt = maxOf(candidateAsset.lastObservedAt, now)))
    } else {
        val mergedSignals = (existingAsset.verificationSignals + candidateAsset.verificationSignals).distinct()
        val mergedHints = (existingAsset.locatorHints + candidateAsset.locatorHints).distinct()
        val mergedTraceIds = (existingAsset.lineageSourceTraceIds + candidateAsset.lineageSourceTraceIds).distinct()
        val mergedObservationCount = existingAsset.observationCount + candidateAsset.observationCount
        val mergedObservedAt = maxOf(existingAsset.lastObservedAt, candidateAsset.lastObservedAt, now)
        if (buildPageEvidenceObservationFingerprint(existingAsset) == buildPageEvidenceObservationFingerprint(candidateAsset)) {
            nextPageEvidenceAssets[existingIndex] = existingAsset.copy(
                verificationSignals = mergedSignals,
                locatorHints = mergedHints,
                observationCount = mergedObservationCount,
                lineageSourceTraceIds = mergedTraceIds,
                lastObservedAt = mergedObservedAt
            )
        } else {
            nextPageEvidenceAssets[existingIndex] = candidateAsset.copy(
                verificationSignals = mergedSignals,
                locatorHints = mergedHints,
                observationCount = mergedObservationCount,
                version = existingAsset.version + 1,
                lineageSourceTraceIds = mergedTraceIds,
                lastObservedAt = mergedObservedAt
            )
        }
    }
    nextPageEvidenceAssets.sortWith(
        compareBy<PageEvidenceAsset> { asset -> asset.appScope }
            .thenBy { asset -> asset.processId }
            .thenByDescending { asset -> asset.observationCount }
            .thenBy { asset -> asset.pageSignature }
    )
}

fun applyPageEvidenceObservation(
    store: PrototypeStoreData,
    selectedToolId: String?,
    processId: String?,
    pageSignature: String?,
    verificationSignals: List<String> = emptyList(),
    locatorHints: List<String> = emptyList(),
    observationCount: Int = 1,
    lineageSourceTraceIds: List<String> = emptyList(),
    observedAt: Long = System.currentTimeMillis(),
    now: Long = observedAt
): PrototypeStoreData {
    val resolvedProcessId = processId?.takeIf { value -> value.isNotBlank() } ?: return store
    val resolvedPageSignature = pageSignature?.takeIf { value -> value.isNotBlank() } ?: return store
    val resolvedAppScope = extractCanonicalAppScope(selectedToolId) ?: return store
    val nextPageEvidenceAssets = store.pageEvidenceAssets.toMutableList()
    upsertPageEvidenceObservation(
        nextPageEvidenceAssets = nextPageEvidenceAssets,
        observation = PageEvidenceObservation(
            appScope = resolvedAppScope,
            processId = resolvedProcessId,
            pageSignature = resolvedPageSignature,
            verificationSignals = verificationSignals,
            locatorHints = locatorHints,
            observationCount = observationCount,
            lineageSourceTraceIds = lineageSourceTraceIds,
            observedAt = observedAt
        ),
        now = now
    )
    return store.copy(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        executionEvents = store.executionEvents.toMutableList(),
        executionTraces = store.executionTraces.toMutableList(),
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        pageEvidenceAssets = nextPageEvidenceAssets,
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        memoryState = store.memoryState ?: MemoryState()
    )
}

private fun buildPageEvidenceObservationFingerprint(asset: PageEvidenceAsset): String {
    return listOf(
        asset.appScope,
        asset.processId,
        asset.pageSignature,
        asset.verificationSignals.joinToString(","),
        asset.locatorHints.joinToString(",")
    ).joinToString("###")
}