package com.atombits.pocopaw

internal fun PrototypeStoreData.resolveCurrentConversationSlice(): ConversationSlice {
    return currentConversationSlice ?: ConversationSlice(
        messages = messages.toMutableList(),
        snapshots = snapshots.toMutableList()
    )
}

internal fun PrototypeStoreData.resolveConversationMessages(): MutableList<ChatMessage> {
    return currentConversationSlice?.messages ?: messages
}

internal fun PrototypeStoreData.resolveConversationSnapshots(): MutableList<TurnSnapshot> {
    return currentConversationSlice?.snapshots ?: snapshots
}

internal fun PrototypeStoreData.updateCurrentConversationSlice(
    messages: MutableList<ChatMessage> = resolveConversationMessages().toMutableList(),
    snapshots: MutableList<TurnSnapshot> = resolveConversationSnapshots().toMutableList()
): PrototypeStoreData {
    currentConversationSlice = if (messages.isEmpty() && snapshots.isEmpty()) {
        null
    } else {
        ConversationSlice(messages = messages, snapshots = snapshots)
    }
    this.messages.clear()
    this.messages.addAll(messages)
    this.snapshots.clear()
    this.snapshots.addAll(snapshots)
    return this
}

internal fun PrototypeStoreData.syncConversationSliceFromLegacy(): PrototypeStoreData {
    return updateCurrentConversationSlice(
        messages = messages.toMutableList(),
        snapshots = snapshots.toMutableList()
    )
}

internal fun PrototypeStoreData.syncConversationSliceIfPresent(): PrototypeStoreData {
    if (currentConversationSlice != null) {
        currentConversationSlice = ConversationSlice(
            messages = messages.toMutableList(),
            snapshots = snapshots.toMutableList()
        )
    }
    return this
}

internal fun PrototypeStoreData.resolveCurrentIntentSlice(): IntentSlice {
    return currentIntentSlice ?: IntentSlice(
        currentState = currentState,
        semanticRuntimePreferences = semanticRuntimePreferences
    )
}

internal fun PrototypeStoreData.resolveCurrentState(): LocalConversationState {
    return currentIntentSlice?.currentState ?: currentState
}

internal fun PrototypeStoreData.resolveSemanticRuntimePreferences(): SemanticRuntimePreferences? {
    return currentIntentSlice?.semanticRuntimePreferences ?: semanticRuntimePreferences
}

internal fun PrototypeStoreData.updateCurrentIntentSlice(
    currentState: LocalConversationState = resolveCurrentState(),
    semanticRuntimePreferences: SemanticRuntimePreferences? = resolveSemanticRuntimePreferences()
): PrototypeStoreData {
    currentIntentSlice = IntentSlice(
        currentState = currentState,
        semanticRuntimePreferences = semanticRuntimePreferences
    )
    this.currentState = currentState
    this.semanticRuntimePreferences = semanticRuntimePreferences
    return this
}

internal fun PrototypeStoreData.syncIntentSliceFromLegacy(): PrototypeStoreData {
    return updateCurrentIntentSlice(
        currentState = currentState,
        semanticRuntimePreferences = semanticRuntimePreferences
    )
}

internal fun PrototypeStoreData.syncIntentSliceIfPresent(): PrototypeStoreData {
    if (currentIntentSlice != null) {
        currentIntentSlice = IntentSlice(
            currentState = currentState,
            semanticRuntimePreferences = semanticRuntimePreferences
        )
    }
    return this
}

internal fun PrototypeStoreData.resolveCurrentAssetSlice(): AssetSlice {
    return currentAssetSlice ?: AssetSlice(
        processExtractionRawMaterials = processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = readyProcessAssets.toMutableList(),
        processAssetEntries = processAssetEntries.toMutableList(),
        pageEvidenceAssets = pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = processShortcutAtlas.toMutableList(),
        processAssetEvents = processAssetEvents.toMutableList(),
        processExtractionConsumedIds = processExtractionConsumedIds.toMutableList(),
        processLearningMaterials = processLearningMaterials.toMutableList(),
        lastProcessCurationSummary = lastProcessCurationSummary
    )
}

internal fun PrototypeStoreData.resolveProcessExtractionRawMaterials(): MutableList<CanonicalTraceRawMaterial> {
    return currentAssetSlice?.processExtractionRawMaterials ?: processExtractionRawMaterials
}

internal fun PrototypeStoreData.resolveReadyProcessAssets(): MutableList<ReadyProcessAsset> {
    return currentAssetSlice?.readyProcessAssets ?: readyProcessAssets
}

internal fun PrototypeStoreData.resolveProcessAssetEntries(): MutableList<com.atombits.pocopaw.process.curation.ProcessAssetEntry> {
    return currentAssetSlice?.processAssetEntries ?: processAssetEntries
}

internal fun PrototypeStoreData.resolvePageEvidenceAssets(): MutableList<PageEvidenceAsset> {
    return currentAssetSlice?.pageEvidenceAssets ?: pageEvidenceAssets
}

internal fun PrototypeStoreData.resolveProcessShortcutAtlas(): MutableList<ProcessShortcutCandidate> {
    return currentAssetSlice?.processShortcutAtlas ?: processShortcutAtlas
}

internal fun PrototypeStoreData.resolveProcessAssetEvents(): MutableList<com.atombits.pocopaw.process.curation.ProcessAssetEvent> {
    return currentAssetSlice?.processAssetEvents ?: processAssetEvents
}

internal fun PrototypeStoreData.resolveProcessExtractionConsumedIds(): MutableList<String> {
    return currentAssetSlice?.processExtractionConsumedIds ?: processExtractionConsumedIds
}

internal fun PrototypeStoreData.resolveProcessLearningMaterials(): MutableList<ProcessLearningMaterial> {
    return currentAssetSlice?.processLearningMaterials ?: processLearningMaterials
}

internal fun PrototypeStoreData.resolveLastProcessCurationSummary(): com.atombits.pocopaw.process.curation.ProcessCurationSummary? {
    return currentAssetSlice?.lastProcessCurationSummary ?: lastProcessCurationSummary
}

internal fun PrototypeStoreData.updateCurrentAssetSlice(
    processExtractionRawMaterials: MutableList<CanonicalTraceRawMaterial> = resolveProcessExtractionRawMaterials().toMutableList(),
    readyProcessAssets: MutableList<ReadyProcessAsset> = resolveReadyProcessAssets().toMutableList(),
    processAssetEntries: MutableList<com.atombits.pocopaw.process.curation.ProcessAssetEntry> = resolveProcessAssetEntries().toMutableList(),
    pageEvidenceAssets: MutableList<PageEvidenceAsset> = resolvePageEvidenceAssets().toMutableList(),
    processShortcutAtlas: MutableList<ProcessShortcutCandidate> = resolveProcessShortcutAtlas().toMutableList(),
    processAssetEvents: MutableList<com.atombits.pocopaw.process.curation.ProcessAssetEvent> = resolveProcessAssetEvents().toMutableList(),
    processExtractionConsumedIds: MutableList<String> = resolveProcessExtractionConsumedIds().toMutableList(),
    processLearningMaterials: MutableList<ProcessLearningMaterial> = resolveProcessLearningMaterials().toMutableList(),
    lastProcessCurationSummary: com.atombits.pocopaw.process.curation.ProcessCurationSummary? = resolveLastProcessCurationSummary()
): PrototypeStoreData {
    currentAssetSlice = if (
        processExtractionRawMaterials.isEmpty() &&
        readyProcessAssets.isEmpty() &&
        processAssetEntries.isEmpty() &&
        pageEvidenceAssets.isEmpty() &&
        processShortcutAtlas.isEmpty() &&
        processAssetEvents.isEmpty() &&
        processExtractionConsumedIds.isEmpty() &&
        processLearningMaterials.isEmpty() &&
        lastProcessCurationSummary == null
    ) {
        null
    } else {
        AssetSlice(
            processExtractionRawMaterials = processExtractionRawMaterials,
            readyProcessAssets = readyProcessAssets,
            processAssetEntries = processAssetEntries,
            pageEvidenceAssets = pageEvidenceAssets,
            processShortcutAtlas = processShortcutAtlas,
            processAssetEvents = processAssetEvents,
            processExtractionConsumedIds = processExtractionConsumedIds,
            processLearningMaterials = processLearningMaterials,
            lastProcessCurationSummary = lastProcessCurationSummary
        )
    }
    this.processExtractionRawMaterials.clear()
    this.processExtractionRawMaterials.addAll(processExtractionRawMaterials)
    this.readyProcessAssets.clear()
    this.readyProcessAssets.addAll(readyProcessAssets)
    this.processAssetEntries.clear()
    this.processAssetEntries.addAll(processAssetEntries)
    this.pageEvidenceAssets.clear()
    this.pageEvidenceAssets.addAll(pageEvidenceAssets)
    this.processShortcutAtlas.clear()
    this.processShortcutAtlas.addAll(processShortcutAtlas)
    this.processAssetEvents.clear()
    this.processAssetEvents.addAll(processAssetEvents)
    this.processExtractionConsumedIds.clear()
    this.processExtractionConsumedIds.addAll(processExtractionConsumedIds)
    this.processLearningMaterials.clear()
    this.processLearningMaterials.addAll(processLearningMaterials)
    this.lastProcessCurationSummary = lastProcessCurationSummary
    return this
}

internal fun PrototypeStoreData.syncAssetSliceFromLegacy(): PrototypeStoreData {
    return updateCurrentAssetSlice(
        processExtractionRawMaterials = processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = readyProcessAssets.toMutableList(),
        processAssetEntries = processAssetEntries.toMutableList(),
        pageEvidenceAssets = pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = processShortcutAtlas.toMutableList(),
        processAssetEvents = processAssetEvents.toMutableList(),
        processExtractionConsumedIds = processExtractionConsumedIds.toMutableList(),
        processLearningMaterials = processLearningMaterials.toMutableList(),
        lastProcessCurationSummary = lastProcessCurationSummary
    )
}

internal fun PrototypeStoreData.resolveCurrentMemorySlice(): MemorySlice? {
    return currentMemorySlice ?: memoryState?.let(::MemorySlice)
}

internal fun PrototypeStoreData.resolveMemoryState(): MemoryState? {
    return currentMemorySlice?.memoryState ?: memoryState
}

internal fun PrototypeStoreData.updateCurrentMemorySlice(
    memoryState: MemoryState? = resolveMemoryState()
): PrototypeStoreData {
    currentMemorySlice = memoryState?.let(::MemorySlice)
    this.memoryState = memoryState
    return this
}

internal fun PrototypeStoreData.withUpdatedMemoryState(
    memoryState: MemoryState?
): PrototypeStoreData {
    return copy(memoryState = memoryState).syncMemorySliceIfPresent()
}

internal fun PrototypeStoreData.syncMemorySliceFromLegacy(): PrototypeStoreData {
    return updateCurrentMemorySlice(memoryState)
}

internal fun PrototypeStoreData.syncMemorySliceIfPresent(): PrototypeStoreData {
    if (currentMemorySlice != null) {
        currentMemorySlice = memoryState?.let(::MemorySlice)
    }
    return this
}

internal fun PrototypeStoreData.hydrateLegacyRootsFromSlices(): PrototypeStoreData {
    currentConversationSlice?.let { slice ->
        messages.clear()
        messages.addAll(slice.messages)
        snapshots.clear()
        snapshots.addAll(slice.snapshots)
    }
    currentIntentSlice?.let { slice ->
        currentState = slice.currentState
        semanticRuntimePreferences = slice.semanticRuntimePreferences
    }
    currentAssetSlice?.let { slice ->
        processExtractionRawMaterials.clear()
        processExtractionRawMaterials.addAll(slice.processExtractionRawMaterials)
        readyProcessAssets.clear()
        readyProcessAssets.addAll(slice.readyProcessAssets)
        processAssetEntries.clear()
        processAssetEntries.addAll(slice.processAssetEntries)
        pageEvidenceAssets.clear()
        pageEvidenceAssets.addAll(slice.pageEvidenceAssets)
        processShortcutAtlas.clear()
        processShortcutAtlas.addAll(slice.processShortcutAtlas)
        processAssetEvents.clear()
        processAssetEvents.addAll(slice.processAssetEvents)
        processExtractionConsumedIds.clear()
        processExtractionConsumedIds.addAll(slice.processExtractionConsumedIds)
        processLearningMaterials.clear()
        processLearningMaterials.addAll(slice.processLearningMaterials)
        lastProcessCurationSummary = slice.lastProcessCurationSummary
    }
    currentMemorySlice?.let { slice ->
        memoryState = slice.memoryState
    }
    return this
}

internal fun PrototypeStoreData.syncAllSlicesFromLegacy(
    preparedExecutionStart: com.atombits.pocopaw.process.runtime.PreparedExecutionStart? = resolveCurrentPreparedExecutionStart()
): PrototypeStoreData {
    syncConversationSliceFromLegacy()
    syncIntentSliceFromLegacy()
    syncExecutionSliceFromLegacy(preparedExecutionStart)
    syncAssetSliceFromLegacy()
    syncMemorySliceFromLegacy()
    return this
}
