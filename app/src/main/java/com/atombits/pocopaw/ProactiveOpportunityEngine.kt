package com.atombits.pocopaw

fun applyProactiveOpportunityRefresh(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis()
): PrototypeStoreData {
    if (!RuntimeModuleSwitches.proactiveEngineEnabled) {
        val memoryState = store.memoryState ?: MemoryState()
        val updatedStore = store.copy(
            currentState = RuntimeModuleSwitches.clearProactiveState(store.resolveCurrentState(), now),
            memoryState = memoryState.copy(proactiveOpportunityStore = emptyList())
        )
        updatedStore.syncIntentSliceIfPresent()
        updatedStore.syncMemorySliceIfPresent()
        return updatedStore
    }

    val memoryState = store.memoryState ?: MemoryState()
    val habitDrivenOpportunities = memoryState.habitMemoryStore.map { habit ->
        val signal = ProactiveOpportunitySignal.fromRaw(habit.preferredProactiveSignal)
            ?: if (habit.stabilityScore >= 0.85) {
                ProactiveOpportunitySignal.PREPARE_OPPORTUNITY
            } else {
                ProactiveOpportunitySignal.OBSERVE_OPPORTUNITY
            }
        ProactiveOpportunityRecord(
            signal = signal,
            title = "${habit.triggerContext} ${habit.habitType.lowercase()}",
            summary = "habit_type=${habit.habitType}; time_window=${habit.timeWindow}; trigger=${habit.triggerContext}",
            anchorObject = habit.triggerContext,
            sourceType = "HABIT",
            confidence = habit.stabilityScore,
            lastObservedAt = now
        )
    }
    val preferenceDrivenOpportunities = if (habitDrivenOpportunities.isEmpty()) {
        val evidenceOpportunities = (
            memoryState.structuredPreferenceMemory.facts.map { fact ->
                ProactiveOpportunityRecord(
                    signal = ProactiveOpportunitySignal.OBSERVE_OPPORTUNITY,
                    title = "${fact.anchorObject.ifBlank { "preference" }} revisit",
                    summary = "preference=${fact.facetKey}:${fact.facetValue}; polarity=${fact.polarity}",
                    anchorObject = fact.anchorObject,
                    sourceType = "PREFERENCE",
                    confidence = fact.confidence,
                    lastObservedAt = maxOf(fact.lastObservedAt, now)
                )
            } + memoryState.interactionBiasMemory.allRecords().map { record ->
                ProactiveOpportunityRecord(
                    signal = ProactiveOpportunitySignal.OBSERVE_OPPORTUNITY,
                    title = "${record.anchorObject.ifBlank { "preference" }} revisit",
                    summary = "preference=${record.signalKey}:${record.signalValue}",
                    anchorObject = record.anchorObject,
                    sourceType = "PREFERENCE",
                    confidence = record.confidence,
                    lastObservedAt = maxOf(record.lastObservedAt, now)
                )
            }
        )
            .sortedByDescending { record -> record.lastObservedAt }
            .take(2)
        evidenceOpportunities
    } else {
        emptyList()
    }
    val proactiveOpportunityStore = (habitDrivenOpportunities + preferenceDrivenOpportunities)
        .distinctBy { record -> listOf(record.signal.name, record.anchorObject.orEmpty(), record.title).joinToString("|") }
        .sortedByDescending { record -> record.lastObservedAt }
        .take(6)

    val updatedStore = store.copy(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        executionEvents = store.executionEvents.toMutableList(),
        executionTraces = store.executionTraces.toMutableList(),
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        currentState = store.resolveCurrentState().copy(
            lastProactiveOpportunitySignal = proactiveOpportunityStore.firstOrNull()?.signal,
            lastUpdatedAt = now
        ),
        memoryState = memoryState.copy(proactiveOpportunityStore = proactiveOpportunityStore)
    )
    updatedStore.syncIntentSliceIfPresent()
    updatedStore.syncMemorySliceIfPresent()
    return updatedStore
}

fun buildProactiveOpportunityBundle(store: PrototypeStoreData): String? {
    val memoryState = store.memoryState ?: return null
    val lines = memoryState.proactiveOpportunityStore
        .sortedByDescending { record -> record.lastObservedAt }
        .take(4)
        .map { record ->
            "signal=${record.signal.name}; title=${record.title}; summary=${record.summary}; anchor=${record.anchorObject.orEmpty().ifBlank { "-" }}"
        }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}