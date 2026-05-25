package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.CanonicalTraceRawMaterial
import com.atombits.pocopaw.PageEvidenceAsset
import com.atombits.pocopaw.ProcessLearningMaterial
import com.atombits.pocopaw.ProcessShortcutCandidate
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ReadyProcessAsset
import com.atombits.pocopaw.resolveLastProcessCurationSummary
import com.atombits.pocopaw.resolvePageEvidenceAssets
import com.atombits.pocopaw.resolveProcessAssetEntries
import com.atombits.pocopaw.resolveProcessAssetEvents
import com.atombits.pocopaw.resolveProcessExtractionConsumedIds
import com.atombits.pocopaw.resolveProcessExtractionRawMaterials
import com.atombits.pocopaw.resolveProcessLearningMaterials
import com.atombits.pocopaw.resolveProcessShortcutAtlas
import com.atombits.pocopaw.resolveReadyProcessAssets
import com.atombits.pocopaw.syncAssetSliceFromLegacy
import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessAssetEvent
import com.atombits.pocopaw.process.curation.ProcessAssetEventType
import com.atombits.pocopaw.process.curation.ProcessAssetState
import com.atombits.pocopaw.process.curation.ProcessCurationSummary

internal interface ProcessAssetRepository {
    fun listReady(
        store: PrototypeStoreData,
        limit: Int = Int.MAX_VALUE,
        appScope: String? = null,
        processScope: String? = null
    ): List<ProcessAssetEntry>

    fun listPending(
        store: PrototypeStoreData,
        limit: Int = Int.MAX_VALUE
    ): List<ProcessAssetEntry>

    fun getById(
        store: PrototypeStoreData,
        id: String
    ): ProcessAssetEntry?

    fun savePending(
        store: PrototypeStoreData,
        entry: ProcessAssetEntry,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun saveRecorded(
        store: PrototypeStoreData,
        entry: ProcessAssetEntry,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun applyProcessCuration(
        store: PrototypeStoreData,
        entryId: String,
        updatedEntry: ProcessAssetEntry,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun applyProcessOptimization(
        store: PrototypeStoreData,
        entryId: String,
        updatedEntry: ProcessAssetEntry,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun markFailed(
        store: PrototypeStoreData,
        entryId: String,
        summary: String,
        reviewComment: String? = null,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun supersede(
        store: PrototypeStoreData,
        entryIds: List<String>,
        summaryBuilder: (ProcessAssetEntry) -> String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData

    fun adjustReadyWeight(
        store: PrototypeStoreData,
        entryId: String,
        delta: Double,
        summary: String,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData
}

internal object PrototypeStoreProcessAssetRepository : ProcessAssetRepository {

    override fun listReady(
        store: PrototypeStoreData,
        limit: Int,
        appScope: String?,
        processScope: String?
    ): List<ProcessAssetEntry> {
        return store.resolveProcessAssetEntries().asSequence()
            .filter { entry -> entry.assetState == ProcessAssetState.READY }
            .filter { entry -> appScope.isNullOrBlank() || entry.appScope.equals(appScope, ignoreCase = true) }
            .filter { entry -> processScope.isNullOrBlank() || entry.processScope.equals(processScope, ignoreCase = true) }
            .sortedByDescending { entry -> entry.updatedAt }
            .take(limit)
            .toList()
    }

    override fun listPending(
        store: PrototypeStoreData,
        limit: Int
    ): List<ProcessAssetEntry> {
        return store.resolveProcessAssetEntries().asSequence()
            .filter { entry -> entry.assetState == ProcessAssetState.PENDING }
            .sortedByDescending { entry -> entry.updatedAt }
            .take(limit)
            .toList()
    }

    override fun getById(
        store: PrototypeStoreData,
        id: String
    ): ProcessAssetEntry? {
        return store.resolveProcessAssetEntries().firstOrNull { entry -> entry.id == id }
    }

    override fun savePending(
        store: PrototypeStoreData,
        entry: ProcessAssetEntry,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        return saveCreatedEntry(store, entry, summary, now)
    }

    override fun saveRecorded(
        store: PrototypeStoreData,
        entry: ProcessAssetEntry,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        return saveCreatedEntry(store, entry, summary, now)
    }

    override fun applyProcessCuration(
        store: PrototypeStoreData,
        entryId: String,
        updatedEntry: ProcessAssetEntry,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        return updateExistingEntry(
            store = store,
            entryId = entryId,
            updatedEntry = updatedEntry,
            eventType = ProcessAssetEventType.PROMOTED_READY,
            summary = summary,
            now = now
        )
    }

    override fun applyProcessOptimization(
        store: PrototypeStoreData,
        entryId: String,
        updatedEntry: ProcessAssetEntry,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        return updateExistingEntry(
            store = store,
            entryId = entryId,
            updatedEntry = updatedEntry,
            eventType = ProcessAssetEventType.UPDATED,
            summary = summary,
            now = now
        )
    }

    override fun markFailed(
        store: PrototypeStoreData,
        entryId: String,
        summary: String,
        reviewComment: String?,
        now: Long
    ): PrototypeStoreData {
        val existingEntry = getById(store, entryId) ?: return store
        val failedEntry = existingEntry.copy(
            assetState = ProcessAssetState.FAILED,
            updatedAt = now,
            assetUpdatedAt = now,
            reviewComment = reviewComment ?: existingEntry.reviewComment,
            reliabilityAnalysis = summary,
            reviewDecision = "failed"
        )
        return updateExistingEntry(
            store = store,
            entryId = entryId,
            updatedEntry = failedEntry,
            eventType = ProcessAssetEventType.MARKED_FAILED,
            summary = summary,
            now = now
        )
    }

    override fun supersede(
        store: PrototypeStoreData,
        entryIds: List<String>,
        summaryBuilder: (ProcessAssetEntry) -> String,
        now: Long
    ): PrototypeStoreData {
        if (entryIds.isEmpty()) {
            return store
        }
        val nextEntries = store.resolveProcessAssetEntries().toMutableList()
        val nextEvents = store.resolveProcessAssetEvents().toMutableList()
        var lastSummary: ProcessCurationSummary? = store.resolveLastProcessCurationSummary()
        entryIds.forEach { entryId ->
            val index = nextEntries.indexOfFirst { entry -> entry.id == entryId }
            if (index < 0) {
                return@forEach
            }
            val currentEntry = nextEntries[index]
            val updatedEntry = currentEntry.copy(
                assetState = ProcessAssetState.SUPERSEDED,
                updatedAt = now,
                assetUpdatedAt = now
            )
            val summary = summaryBuilder(currentEntry)
            nextEntries[index] = updatedEntry
            nextEvents += ProcessAssetEvent(
                assetEntryId = updatedEntry.id,
                assetName = updatedEntry.assetName,
                eventType = ProcessAssetEventType.SUPERSEDED,
                summary = summary,
                createdAt = now
            )
            lastSummary = ProcessCurationSummary(
                assetEntryId = updatedEntry.id,
                assetName = updatedEntry.assetName,
                assetState = updatedEntry.assetState,
                summary = summary,
                updatedAt = now
            )
        }
        return storeWithSyncedAssetSlice(
            store = store,
            processAssetEntries = nextEntries,
            processAssetEvents = nextEvents,
            lastProcessCurationSummary = lastSummary
        )
    }

    override fun adjustReadyWeight(
        store: PrototypeStoreData,
        entryId: String,
        delta: Double,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        val existingEntry = getById(store, entryId) ?: return store
        val updatedEntry = existingEntry.copy(
            readyWeight = (existingEntry.readyWeight + delta).coerceIn(0.0, 1.0),
            updatedAt = now,
            assetUpdatedAt = now
        )
        return updateExistingEntry(
            store = store,
            entryId = entryId,
            updatedEntry = updatedEntry,
            eventType = ProcessAssetEventType.UPDATED,
            summary = summary,
            now = now
        )
    }

    private fun saveCreatedEntry(
        store: PrototypeStoreData,
        entry: ProcessAssetEntry,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        val nextEntries = store.resolveProcessAssetEntries().toMutableList().apply {
            add(entry)
        }
        val nextEvents = store.resolveProcessAssetEvents().toMutableList().apply {
            add(
                ProcessAssetEvent(
                    assetEntryId = entry.id,
                    assetName = entry.assetName,
                    eventType = ProcessAssetEventType.CREATED,
                    summary = summary,
                    createdAt = now
                )
            )
        }
        return storeWithSyncedAssetSlice(
            store = store,
            processAssetEntries = nextEntries,
            processAssetEvents = nextEvents,
            lastProcessCurationSummary = ProcessCurationSummary(
                assetEntryId = entry.id,
                assetName = entry.assetName,
                assetState = entry.assetState,
                summary = summary,
                updatedAt = now
            )
        )
    }

    private fun updateExistingEntry(
        store: PrototypeStoreData,
        entryId: String,
        updatedEntry: ProcessAssetEntry,
        eventType: ProcessAssetEventType,
        summary: String,
        now: Long
    ): PrototypeStoreData {
        val nextEntries = store.resolveProcessAssetEntries().toMutableList()
        val entryIndex = nextEntries.indexOfFirst { entry -> entry.id == entryId }
        if (entryIndex < 0) {
            return store
        }
        nextEntries[entryIndex] = updatedEntry
        val nextEvents = store.resolveProcessAssetEvents().toMutableList().apply {
            add(
                ProcessAssetEvent(
                    assetEntryId = updatedEntry.id,
                    assetName = updatedEntry.assetName,
                    eventType = eventType,
                    summary = summary,
                    createdAt = now
                )
            )
        }
        return storeWithSyncedAssetSlice(
            store = store,
            processAssetEntries = nextEntries,
            processAssetEvents = nextEvents,
            lastProcessCurationSummary = ProcessCurationSummary(
                assetEntryId = updatedEntry.id,
                assetName = updatedEntry.assetName,
                assetState = updatedEntry.assetState,
                summary = summary,
                updatedAt = now
            )
        )
    }

    private fun storeWithSyncedAssetSlice(
        store: PrototypeStoreData,
        processAssetEntries: MutableList<ProcessAssetEntry>,
        processAssetEvents: MutableList<ProcessAssetEvent>,
        lastProcessCurationSummary: ProcessCurationSummary?,
        processExtractionRawMaterials: MutableList<CanonicalTraceRawMaterial> = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets: MutableList<ReadyProcessAsset> = store.readyProcessAssets.toMutableList(),
        pageEvidenceAssets: MutableList<PageEvidenceAsset> = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas: MutableList<ProcessShortcutCandidate> = store.processShortcutAtlas.toMutableList(),
        processExtractionConsumedIds: MutableList<String> = store.processExtractionConsumedIds.toMutableList(),
        processLearningMaterials: MutableList<ProcessLearningMaterial> = store.processLearningMaterials.toMutableList()
    ): PrototypeStoreData {
        return store.copy(
            currentAssetSlice = store.currentAssetSlice,
            processExtractionRawMaterials = processExtractionRawMaterials,
            readyProcessAssets = readyProcessAssets,
            processAssetEntries = processAssetEntries,
            pageEvidenceAssets = pageEvidenceAssets,
            processShortcutAtlas = processShortcutAtlas,
            processAssetEvents = processAssetEvents,
            processExtractionConsumedIds = processExtractionConsumedIds,
            processLearningMaterials = processLearningMaterials,
            lastProcessCurationSummary = lastProcessCurationSummary
        ).syncAssetSliceFromLegacy()
    }
}