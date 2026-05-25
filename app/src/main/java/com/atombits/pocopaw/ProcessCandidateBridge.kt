package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessAssetEvent
import com.atombits.pocopaw.process.curation.ProcessAssetEventType
import com.atombits.pocopaw.process.curation.ProcessAssetSourceType
import com.atombits.pocopaw.process.curation.ProcessAssetState
import com.atombits.pocopaw.process.curation.ProcessCurationSummary
import com.atombits.pocopaw.process.reuse.PrototypeStoreProcessAssetRepository
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun currentProcessCandidates(store: PrototypeStoreData): List<ProcessCandidateRecord> {
    return store.resolveMemoryState()?.processCandidateStore.orEmpty()
}

fun currentProcessFeedback(store: PrototypeStoreData): List<ProcessFeedbackRecord> {
    return store.resolveMemoryState()?.processFeedbackStore.orEmpty()
}

fun resolveVisibleProcessReviewContext(store: PrototypeStoreData): ProcessReviewContext? {
    return store.resolveCurrentState().pendingProcessFeedbackDraft?.reviewContext
        ?: store.resolveLatestCompletedProcessReviewContext()
}

data class ProcessFeedbackOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

fun buildProcessExtractionAssetSummary(store: PrototypeStoreData): String {
    val processAssetEntries = store.resolveProcessAssetEntries()
    val pageEvidenceAssets = store.resolvePageEvidenceAssets()
    val extractedNames = processAssetEntries
        .asSequence()
        .filter { entry -> entry.assetState == ProcessAssetState.READY }
        .sortedWith(
            compareByDescending<ProcessAssetEntry> { entry -> maxOf(entry.updatedAt, entry.assetUpdatedAt) }
                .thenBy { entry ->
                    entry.assetName
                        .ifBlank { entry.businessProcessName }
                        .ifBlank { entry.processScope }
                        .lowercase(Locale.US)
                }
        )
        .map(::buildProcessAssetEntryDisplayName)
        .toList()
    return buildString {
        append(
            UiStrings.resolve(
                R.string.process_extraction_asset_summary,
                "%1\$d extracted, %2\$d page evidence item(s)",
                extractedNames.size,
                pageEvidenceAssets.size
            )
        )
        appendLine()
        append(UiStrings.resolve(R.string.process_extracted_flow_names, "Extracted flow names:"))
        appendLine()
        append(formatProcessDisplayNames(extractedNames))
    }
}

fun buildProcessExtractionSettingsSummary(store: PrototypeStoreData): String {
    val processAssetEntries = store.resolveProcessAssetEntries()
    val processShortcutAtlas = store.resolveProcessShortcutAtlas()
    val counts = processAssetEntries
        .groupingBy { entry -> entry.assetState }
        .eachCount()
    val pendingNames = buildPendingProcessDisplayNames(store)
    val readyEntries = processAssetEntries
        .asSequence()
        .filter { entry -> entry.assetState == ProcessAssetState.READY }
        .sortedWith(
            compareByDescending<ProcessAssetEntry> { entry -> maxOf(entry.updatedAt, entry.assetUpdatedAt) }
                .thenBy { entry ->
                    entry.assetName
                        .ifBlank { entry.businessProcessName }
                        .ifBlank { entry.processScope }
                        .lowercase(Locale.US)
                }
        )
        .toList()
    val extractedNames = readyEntries.map(::buildProcessAssetEntryDisplayName)
    val lastExtractedAt = readyEntries.maxOfOrNull { entry -> maxOf(entry.updatedAt, entry.assetUpdatedAt) }
    return buildString {
        append(
            UiStrings.resolve(
                R.string.process_extraction_settings_pending,
                "%1\$d pending",
                pendingNames.size
            )
        )
        appendLine()
        append(UiStrings.resolve(R.string.process_extraction_process_names, "process names:"))
        appendLine()
        append(formatProcessDisplayNames(pendingNames))
        appendLine()
        appendLine()
        append(
            UiStrings.resolve(
                R.string.process_extraction_settings_extracted,
                "%1\$d extracted",
                extractedNames.size
            )
        )
        appendLine()
        append(formatProcessDisplayNames(extractedNames))
        appendLine()
        appendLine()
        append(
            UiStrings.resolve(
                R.string.process_extraction_settings_ready_failed_superseded_shortcut,
                "%1\$d ready. %2\$d failed. %3\$d superseded. %4\$d shortcut",
                counts[ProcessAssetState.READY] ?: 0,
                counts[ProcessAssetState.FAILED] ?: 0,
                counts[ProcessAssetState.SUPERSEDED] ?: 0,
                processShortcutAtlas.size
            )
        )
        appendLine()
        append(
            UiStrings.resolve(
                R.string.process_extraction_last_extracted,
                "last extracted: %1\$s",
                lastExtractedAt?.let(::formatProcessExtractionTimestamp)
                    ?: UiStrings.resolve(R.string.process_extraction_none, "none")
            )
        )
    }
}

fun buildProcessCandidateCurationSummary(store: PrototypeStoreData): String {
    val processAssetEntries = store.resolveProcessAssetEntries()
    val processShortcutAtlas = store.resolveProcessShortcutAtlas()
    val counts = processAssetEntries
        .groupingBy { entry -> entry.assetState }
        .eachCount()
    val pendingNames = buildPendingProcessDisplayNames(store)
    return buildString {
        append(
            UiStrings.resolve(
                R.string.process_curation_summary_pending_extraction,
                "%1\$d pending extraction",
                pendingNames.size
            )
        )
        appendLine()
        append(UiStrings.resolve(R.string.process_curation_pending_flow_names, "Pending flow names:"))
        appendLine()
        append(formatProcessDisplayNames(pendingNames))
        appendLine()
        append(
            UiStrings.resolve(
                R.string.process_curation_summary_ready_failed_superseded_shortcuts,
                "%1\$d ready, %2\$d failed, %3\$d superseded, %4\$d shortcut(s)",
                counts[ProcessAssetState.READY] ?: 0,
                counts[ProcessAssetState.FAILED] ?: 0,
                counts[ProcessAssetState.SUPERSEDED] ?: 0,
                processShortcutAtlas.size
            )
        )
    }
}

private fun formatProcessDisplayNames(names: List<String>): String {
    if (names.isEmpty()) {
        return UiStrings.resolve(R.string.process_extraction_none_bullet, "- none")
    }
    return names.joinToString(separator = "\n") { name -> "- $name" }
}

private fun formatProcessExtractionTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun buildPendingProcessDisplayNames(store: PrototypeStoreData): List<String> {
    return store.resolveProcessAssetEntries()
        .asSequence()
        .filter { entry -> entry.assetState == ProcessAssetState.PENDING }
        .map { entry ->
            PendingProcessDisplayRow(
                displayName = buildProcessAssetEntryDisplayName(entry),
                updatedAt = maxOf(entry.updatedAt, entry.assetUpdatedAt)
            )
        }
        .filter { row -> row.displayName.isNotBlank() }
        .sortedWith(
            compareByDescending<PendingProcessDisplayRow> { row -> row.updatedAt }
                .thenBy { row -> row.displayName.lowercase(Locale.US) }
        )
        .map { row -> row.displayName }
        .toList()
}

private data class PendingProcessDisplayRow(
    val displayName: String,
    val updatedAt: Long
)

private fun buildProcessAssetEntryDisplayName(entry: ProcessAssetEntry): String {
    return entry.assetName
        .ifBlank { entry.businessProcessName }
        .ifBlank { entry.processScope }
}

internal data class ReusableProcessAssetRecord(
    val assetName: String,
    val assetState: ProcessAssetState,
    val updatedAt: Long,
    val successCount: Int,
    val revision: Int
)

internal fun findPreferredReusableProcessAssetRecord(
    store: PrototypeStoreData,
    appScope: String,
    processAction: String,
    pathIndex: Int?
): ReusableProcessAssetRecord? {
    val normalizedPathIndex = normalizeReusableAssetPathIndex(pathIndex)
    return store.processAssetEntries
        .asSequence()
        .filter { entry ->
            entry.assetState != ProcessAssetState.RECORDED &&
                entry.appScope.equals(appScope, ignoreCase = true) &&
                resolveReusableProcessAction(entry) == processAction &&
                resolveReusablePathIndex(entry) == normalizedPathIndex
        }
        .sortedWith(
            compareByDescending<ProcessAssetEntry> { entry -> entry.assetState == ProcessAssetState.READY }
                .thenByDescending { entry -> maxOf(entry.updatedAt, entry.assetUpdatedAt) }
                .thenByDescending { entry -> entry.successCount }
                .thenByDescending { entry -> entry.revision }
        )
        .map { entry ->
            ReusableProcessAssetRecord(
                assetName = buildProcessAssetEntryDisplayName(entry),
                assetState = entry.assetState,
                updatedAt = maxOf(entry.updatedAt, entry.assetUpdatedAt),
                successCount = entry.successCount,
                revision = entry.revision
            )
        }
        .firstOrNull()
}

internal fun findReusableProcessAssetRecordName(
    store: PrototypeStoreData,
    appScope: String,
    processAction: String,
    pathIndex: Int?
): String? {
    return findPreferredReusableProcessAssetRecord(
        store = store,
        appScope = appScope,
        processAction = processAction,
        pathIndex = pathIndex
    )?.assetName
}

private fun resolveReusableProcessAction(entry: ProcessAssetEntry): String {
    return inferCanonicalProcessAction(
        processId = entry.processScope,
        objective = entry.semanticDescription.ifBlank { entry.taskExample }.ifBlank { entry.assetName },
        domain = entry.domain,
        actionHint = entry.businessProcessName.ifBlank { entry.assetName }
    )
}

private fun resolveReusablePathIndex(entry: ProcessAssetEntry): Int {
    return extractCanonicalProcessPathIndex(entry.assetName)?.coerceAtLeast(1) ?: 1
}

private fun normalizeReusableAssetPathIndex(pathIndex: Int?): Int {
    return pathIndex?.coerceAtLeast(1) ?: 1
}

fun armProcessFeedbackDraft(
    store: PrototypeStoreData,
    feedbackType: ProcessFeedbackType,
    now: Long = System.currentTimeMillis()
): ProcessFeedbackOutcome {
    val reviewContext = store.latestCompletedProcessReviewContext
        ?: return ProcessFeedbackOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_feedback_no_review_waiting,
                "No completed process review is waiting for feedback."
            )
        )
    val nextCurrentState = store.resolveCurrentState().copy(
        pendingProcessFeedbackDraft = PendingProcessFeedbackDraft(
            feedbackType = feedbackType,
            reviewContext = reviewContext,
            createdAt = now
        ),
        lastUpdatedAt = now
    )
    val updatedStore = store.copy(
        currentState = nextCurrentState
    )
    updatedStore.syncIntentSliceIfPresent()
    val message = when (feedbackType) {
        ProcessFeedbackType.THUMBS_UP -> UiStrings.resolve(
            R.string.process_feedback_armed_positive,
            "Feedback armed. Add a short note for what worked well."
        )
        ProcessFeedbackType.THUMBS_DOWN -> UiStrings.resolve(
            R.string.process_feedback_armed_negative,
            "Feedback armed. Describe what should change before this flow is reused."
        )
    }
    return ProcessFeedbackOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = message
    )
}

fun applyProcessFeedback(
    store: PrototypeStoreData,
    comment: String,
    now: Long = System.currentTimeMillis()
): ProcessFeedbackOutcome {
    val draft = store.resolveCurrentState().pendingProcessFeedbackDraft
        ?: return ProcessFeedbackOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_feedback_no_draft_waiting,
                "No process feedback draft is waiting for input."
            )
        )
    return applySubmittedProcessFeedback(
        store = store,
        reviewContext = draft.reviewContext,
        feedbackType = draft.feedbackType,
        comment = comment,
        now = now
    )
}

fun submitProcessFeedback(
    store: PrototypeStoreData,
    feedbackType: ProcessFeedbackType,
    comment: String,
    now: Long = System.currentTimeMillis()
): ProcessFeedbackOutcome {
    val reviewContext = store.latestCompletedProcessReviewContext
        ?: return ProcessFeedbackOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_feedback_no_review_waiting,
                "No completed process review is waiting for feedback."
            )
        )
    return applySubmittedProcessFeedback(
        store = store,
        reviewContext = reviewContext,
        feedbackType = feedbackType,
        comment = comment,
        now = now
    )
}

private fun applySubmittedProcessFeedback(
    store: PrototypeStoreData,
    reviewContext: ProcessReviewContext,
    feedbackType: ProcessFeedbackType,
    comment: String,
    now: Long
): ProcessFeedbackOutcome {
    val trimmedComment = comment.trim()

    val feedbackTarget = resolveProcessFeedbackTarget(store, reviewContext)
        ?: return ProcessFeedbackOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_feedback_review_mismatch,
                "The completed process review no longer matches a reusable process asset."
            )
        )
    val processAssetFeedback = applyProcessAssetFeedback(
        store = store,
        feedbackTarget = feedbackTarget,
        feedbackType = feedbackType,
        comment = trimmedComment,
        now = now
    )
    val memoryState = store.resolveMemoryState() ?: MemoryState()
    val nextCandidateStore = memoryState.processCandidateStore.toMutableList()
    val matchingRecord = findMatchingProcessCandidateRecord(
        records = nextCandidateStore,
        processId = feedbackTarget.processId,
        appScope = feedbackTarget.appScope,
        sourceTraceId = feedbackTarget.sourceTraceId,
        recordName = feedbackTarget.candidateRecordName
    )
    if (matchingRecord != null) {
        val matchingRecordIndex = nextCandidateStore.indexOfFirst { record ->
            record.recordId == matchingRecord.recordId
        }
        val record = nextCandidateStore[matchingRecordIndex]
        val queuedPendingEntry = processAssetFeedback.queuedPendingEntry
        nextCandidateStore[matchingRecordIndex] = record.copy(
            recordName = queuedPendingEntry?.assetName ?: record.recordName,
            curationState = when {
                queuedPendingEntry != null -> ProcessCandidateCurationState.PENDING
                feedbackTarget.entry.assetState == ProcessAssetState.PENDING && trimmedComment.isNotBlank() -> {
                    ProcessCandidateCurationState.PENDING
                }

                else -> record.curationState
            },
            revision = queuedPendingEntry?.revision ?: record.revision,
            successCount = incrementFeedbackSuccessCount(record.successCount, feedbackType),
            reviewComment = mergeReviewComment(record.reviewComment, trimmedComment),
            updatedAt = now
        )
    }

    val feedbackRecord = ProcessFeedbackRecord(
        processId = feedbackTarget.processId,
        appScope = feedbackTarget.appScope,
        feedbackType = feedbackType,
        comment = trimmedComment,
        recordName = feedbackTarget.candidateRecordName,
        sourceTraceId = feedbackTarget.sourceTraceId,
        recordedAt = now
    )
    val storedMessage = buildProcessFeedbackStoredMessage(
        feedbackTarget = feedbackTarget,
        feedbackType = feedbackType,
        queuedCandidateName = processAssetFeedback.queuedCandidateName
    )
    val assetStore = processAssetFeedback.updatedStore
    val nextCurrentState = assetStore.resolveCurrentState().copy(
        pendingProcessFeedbackDraft = null,
        lastUpdatedAt = now
    )
    val updatedStore = assetStore.copy(
        messages = store.messages.toMutableList().apply {
            add(
                ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = storedMessage,
                    timestamp = now,
                    stage = ConversationStage.ACCUMULATING
                )
            )
        },
        latestCompletedProcessReviewContext = null,
        currentState = nextCurrentState,
        memoryState = memoryState.copy(
            processCandidateStore = nextCandidateStore.sortedByDescending { record -> record.updatedAt },
            processFeedbackStore = listOf(feedbackRecord) + memoryState.processFeedbackStore
        )
    )
    updatedStore.updateCurrentExecutionSlice(
        latestCompletedProcessReviewContext = null
    )
    updatedStore.syncConversationSliceIfPresent()
    updatedStore.syncIntentSliceIfPresent()
    updatedStore.syncMemorySliceIfPresent()
    return ProcessFeedbackOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = UiStrings.resolve(
            R.string.process_feedback_stored,
            "Stored process feedback for %1\$s.",
            feedbackTarget.objective.ifBlank {
                UiStrings.resolve(R.string.process_feedback_objective_fallback, "the completed flow")
            }
        )
    )
}

private fun applyProcessAssetFeedback(
    store: PrototypeStoreData,
    feedbackTarget: ProcessFeedbackTarget,
    feedbackType: ProcessFeedbackType,
    comment: String,
    now: Long
): ProcessAssetFeedbackResult {
    val matchedEntry = feedbackTarget.entry
    val mergedComment = mergeReviewComment(matchedEntry.reviewComment, comment)
    var workingStore = PrototypeStoreProcessAssetRepository.applyProcessOptimization(
        store = store,
        entryId = matchedEntry.id,
        updatedEntry = matchedEntry.copy(
            reviewComment = mergedComment,
            successCount = incrementFeedbackSuccessCount(matchedEntry.successCount, feedbackType),
            readyWeight = adjustFeedbackReadyWeight(matchedEntry.readyWeight, feedbackType),
            updatedAt = now,
            assetUpdatedAt = now
        ),
        summary = UiStrings.resolve(
            R.string.process_feedback_optimization_stored,
            "Stored %1\$s feedback for %2\$s.",
            feedbackType.name.lowercase(Locale.US),
            matchedEntry.assetName
        ),
        now = now
    ).copy(lastProcessCurationSummary = store.lastProcessCurationSummary)

    var queuedCandidateName: String? = null
    var queuedPendingEntry: ProcessAssetEntry? = null
    val originEntry = matchedEntry.originAssetId?.let { originId ->
        workingStore.resolveProcessAssetEntries().firstOrNull { entry -> entry.id == originId }
    }
    if (originEntry != null) {
        workingStore = PrototypeStoreProcessAssetRepository.applyProcessOptimization(
            store = workingStore,
            entryId = originEntry.id,
            updatedEntry = originEntry.copy(
                successCount = incrementFeedbackSuccessCount(originEntry.successCount, feedbackType),
                readyWeight = adjustFeedbackReadyWeight(originEntry.readyWeight, feedbackType),
                updatedAt = now,
                assetUpdatedAt = now
            ),
            summary = if (feedbackType == ProcessFeedbackType.THUMBS_UP) {
                UiStrings.resolve(
                    R.string.process_feedback_lineage_reinforced,
                    "Reinforced ready lineage for %1\$s.",
                    originEntry.assetName
                )
            } else {
                UiStrings.resolve(
                    R.string.process_feedback_lineage_lowered,
                    "Lowered ready lineage preference for %1\$s.",
                    originEntry.assetName
                )
            },
            now = now
        )
    }

    if (comment.isNotBlank() && matchedEntry.assetState == ProcessAssetState.RECORDED) {
        val refreshedRecordedEntry = PrototypeStoreProcessAssetRepository.getById(workingStore, matchedEntry.id)
            ?: matchedEntry
        val pendingCandidate = buildPendingCandidateFromRecordedEntry(
            recordedEntry = refreshedRecordedEntry,
            objective = feedbackTarget.objective,
            now = now
        )
        val existingCandidate = workingStore.resolveProcessAssetEntries().firstOrNull { entry ->
            entry.sourceType == ProcessAssetSourceType.CANDIDATE &&
                entry.assetState == ProcessAssetState.PENDING &&
                entry.originAssetId == pendingCandidate.originAssetId &&
                entry.revision == pendingCandidate.revision
        }
        workingStore = if (existingCandidate != null) {
            val updatedCandidate = pendingCandidate.copy(
                id = existingCandidate.id,
                reviewComment = mergeReviewComment(existingCandidate.reviewComment, pendingCandidate.reviewComment),
                updatedAt = now,
                assetUpdatedAt = now
            )
            queuedCandidateName = updatedCandidate.assetName
            queuedPendingEntry = updatedCandidate
            PrototypeStoreProcessAssetRepository.applyProcessOptimization(
                store = workingStore,
                entryId = existingCandidate.id,
                updatedEntry = updatedCandidate,
                summary = UiStrings.resolve(
                    R.string.process_feedback_queued_pending_candidate,
                    "Queued pending candidate %1\$s from recorded feedback.",
                    updatedCandidate.assetName
                ),
                now = now
            )
        } else {
            queuedCandidateName = pendingCandidate.assetName
            queuedPendingEntry = pendingCandidate
            PrototypeStoreProcessAssetRepository.savePending(
                store = workingStore,
                entry = pendingCandidate,
                summary = UiStrings.resolve(
                    R.string.process_feedback_queued_pending_candidate,
                    "Queued pending candidate %1\$s from recorded feedback.",
                    pendingCandidate.assetName
                ),
                now = now
            )
        }
    }

    return ProcessAssetFeedbackResult(
        updatedStore = workingStore,
        queuedCandidateName = queuedCandidateName,
        queuedPendingEntry = queuedPendingEntry
    )
}

private fun findMatchingProcessCandidateRecord(
    records: List<ProcessCandidateRecord>,
    processId: String,
    appScope: String,
    sourceTraceId: String?,
    recordName: String?
): ProcessCandidateRecord? {
    return records.firstOrNull { record ->
        !sourceTraceId.isNullOrBlank() && record.sourceTraceId == sourceTraceId
    } ?: records.firstOrNull { record ->
        !recordName.isNullOrBlank() && record.recordName == recordName
    } ?: records.firstOrNull { record ->
        record.processId == processId &&
            record.appScope.equals(appScope, ignoreCase = true) &&
            record.curationState != ProcessCandidateCurationState.SUPERSEDED
    }
}

private fun buildProcessFeedbackStoredMessage(
    feedbackTarget: ProcessFeedbackTarget,
    feedbackType: ProcessFeedbackType,
    queuedCandidateName: String? = null
): String {
    val objective = feedbackTarget.objective.ifBlank {
        UiStrings.resolve(R.string.process_feedback_objective_fallback, "the completed flow")
    }
    val feedbackLabel = when (feedbackType) {
        ProcessFeedbackType.THUMBS_UP -> UiStrings.resolve(
            R.string.process_feedback_label_positive,
            "thumbs up"
        )
        ProcessFeedbackType.THUMBS_DOWN -> UiStrings.resolve(
            R.string.process_feedback_label_negative,
            "thumbs down"
        )
    }
    return queuedCandidateName?.let { candidateName ->
        UiStrings.resolve(
            R.string.process_feedback_stored_system_with_candidate,
            "Stored %1\$s feedback for %2\$s. %3\$s will use it in later process refinement. Queued candidate revision %4\$s.",
            feedbackLabel,
            objective,
            currentAssistantDisplayName(),
            candidateName
        )
    } ?: UiStrings.resolve(
        R.string.process_feedback_stored_system,
        "Stored %1\$s feedback for %2\$s. %3\$s will use it in later process refinement.",
        feedbackLabel,
        objective,
        currentAssistantDisplayName()
    )
}

private fun mergeReviewComment(existingComment: String, incomingComment: String): String {
    if (incomingComment.isBlank()) {
        return existingComment
    }
    if (existingComment.isBlank()) {
        return incomingComment
    }
    if (existingComment.contains(incomingComment)) {
        return existingComment
    }
    if (incomingComment.contains(existingComment)) {
        return incomingComment
    }
    return buildString {
        append(existingComment.trimEnd())
        appendLine()
        append(incomingComment)
    }
}

private fun incrementFeedbackSuccessCount(successCount: Int, feedbackType: ProcessFeedbackType): Int {
    return if (feedbackType == ProcessFeedbackType.THUMBS_UP) {
        successCount + 1
    } else {
        successCount
    }
}

private fun adjustFeedbackReadyWeight(readyWeight: Double, feedbackType: ProcessFeedbackType): Double {
    val delta = when (feedbackType) {
        ProcessFeedbackType.THUMBS_UP -> 0.1
        ProcessFeedbackType.THUMBS_DOWN -> -0.1
    }
    return (readyWeight + delta).coerceIn(0.0, 1.0)
}

private fun buildPendingCandidateFromRecordedEntry(
    recordedEntry: ProcessAssetEntry,
    objective: String,
    now: Long
): ProcessAssetEntry {
    val resolvedPathIndex = extractCanonicalProcessPathIndex(recordedEntry.assetName) ?: 1
    val nextRevision = recordedEntry.revision.coerceAtLeast(1) + 1
    val resolvedProcessAction = inferCanonicalProcessAction(
        processId = recordedEntry.processScope,
        objective = recordedEntry.semanticDescription.ifBlank { objective },
        domain = recordedEntry.domain,
        actionHint = recordedEntry.businessProcessName
    )
    val candidateName = buildCanonicalProcessCandidateName(
        domain = recordedEntry.domain,
        appScope = recordedEntry.appScope,
        processAction = resolvedProcessAction,
        pathIndex = resolvedPathIndex,
        version = nextRevision
    )
    return ProcessAssetEntry(
        domain = recordedEntry.domain,
        appScope = recordedEntry.appScope,
        processScope = recordedEntry.processScope,
        sourceType = ProcessAssetSourceType.CANDIDATE,
        assetName = candidateName,
        revision = nextRevision,
        semanticDescription = recordedEntry.semanticDescription,
        assetState = ProcessAssetState.PENDING,
        assetUpdatedAt = now,
        taskExample = recordedEntry.taskExample,
        planningTrace = recordedEntry.planningTrace,
        stepCount = recordedEntry.stepCount,
        successCount = recordedEntry.successCount,
        updatedAt = now,
        reviewComment = recordedEntry.reviewComment,
        businessProcessName = recordedEntry.businessProcessName,
        businessAcceptanceCriteria = recordedEntry.businessAcceptanceCriteria,
        businessStagesJson = recordedEntry.businessStagesJson,
        optimizedProcessTrace = recordedEntry.optimizedProcessTrace,
        diffSummary = "Created candidate revision from recorded execution feedback.",
        reliabilityAnalysis = "Pending curation after user feedback.",
        reviewDecision = "pending_candidate",
        reviewConfidence = recordedEntry.reviewConfidence,
        readyWeight = recordedEntry.readyWeight,
        originAssetId = recordedEntry.originAssetId ?: recordedEntry.id
    )
}

private fun extractCanonicalProcessPathIndex(assetName: String): Int? {
    return Regex("-path([1-9][0-9]*)-v[1-9][0-9]*$")
        .find(assetName.lowercase(Locale.US))
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private data class ProcessAssetFeedbackResult(
    val updatedStore: PrototypeStoreData,
    val queuedCandidateName: String? = null,
    val queuedPendingEntry: ProcessAssetEntry? = null
)

private data class ProcessFeedbackTarget(
    val entry: ProcessAssetEntry,
    val objective: String,
    val processId: String,
    val appScope: String,
    val sourceTraceId: String?,
    val candidateRecordName: String?
)

private fun resolveProcessFeedbackTarget(
    store: PrototypeStoreData,
    reviewContext: ProcessReviewContext
): ProcessFeedbackTarget? {
    val entry = resolveReviewedProcessAssetEntry(store, reviewContext) ?: return null
    val processId = entry.processScope.orGenericProcessScope()
    val appScope = entry.appScope.ifBlank { inferProcessAppScope(null, processId) }
    val candidateRecordName = reviewContext.processAssetName?.takeIf { assetName -> assetName.isNotBlank() }
        ?: entry.assetName.takeIf { assetName -> assetName.isNotBlank() }
    val legacySourceTraceId = entry.originAssetId ?: reviewContext.processAssetEntryId
    val candidateRecord = findMatchingProcessCandidateRecord(
        records = currentProcessCandidates(store),
        processId = processId,
        appScope = appScope,
        sourceTraceId = legacySourceTraceId,
        recordName = candidateRecordName
    )
    return ProcessFeedbackTarget(
        entry = entry,
        objective = reviewContext.finalUserSummary?.takeIf { summary -> summary.isNotBlank() } ?: entry.taskExample,
        processId = processId,
        appScope = appScope,
        sourceTraceId = candidateRecord?.sourceTraceId,
        candidateRecordName = candidateRecord?.recordName ?: candidateRecordName
    )
}

private fun resolveReviewedProcessAssetEntry(
    store: PrototypeStoreData,
    reviewContext: ProcessReviewContext
): ProcessAssetEntry? {
    val entries = store.resolveProcessAssetEntries()
    return reviewContext.processAssetEntryId?.let { entryId ->
        entries.firstOrNull { entry -> entry.id == entryId }
    } ?: reviewContext.processAssetName?.let { assetName ->
        entries.firstOrNull { entry -> entry.assetName == assetName }
    }
}

internal fun buildCanonicalProcessCandidateName(
    domain: String,
    appScope: String,
    processAction: String,
    pathIndex: Int,
    version: Int
): String {
    return buildString {
        append(normalizeProcessNameSegment(domain))
        append('-')
        append(normalizeProcessNameSegment(appScope))
        append('-')
        append(normalizeProcessNameSegment(processAction))
        append("-path")
        append(pathIndex.coerceAtLeast(1))
        append("-v")
        append(version.coerceAtLeast(1))
    }
}

internal fun buildTemporaryProcessCandidateName(
    domain: String,
    appScope: String,
    processAction: String,
    tempIndex: Int
): String {
    return buildString {
        append(normalizeProcessNameSegment(domain))
        append('-')
        append(normalizeProcessNameSegment(appScope))
        append('-')
        append(normalizeProcessNameSegment(processAction))
        append("-temp")
        append(tempIndex.coerceAtLeast(1))
    }
}

internal fun buildReadyProcessAssetDisplayName(asset: ReadyProcessAsset): String {
    return buildCanonicalProcessCandidateName(
        domain = asset.domain,
        appScope = asset.appScope,
        processAction = asset.resolvedProcessAction(),
        pathIndex = asset.pathIndex.coerceAtLeast(1),
        version = asset.version
    )
}

internal fun buildProcessCandidateDisplayName(record: ProcessCandidateRecord): String {
    return when (record.namingStage) {
        ProcessCandidateNamingStage.CANONICAL -> buildCanonicalProcessCandidateName(
            domain = record.domain,
            appScope = record.appScope,
            processAction = record.resolvedProcessAction(),
            pathIndex = record.pathIndex.coerceAtLeast(1),
            version = record.revision
        )

        ProcessCandidateNamingStage.TEMPORARY -> buildTemporaryProcessCandidateName(
            domain = record.domain,
            appScope = record.appScope,
            processAction = record.resolvedProcessAction(),
            tempIndex = extractTemporaryProcessCandidateIndex(record.recordName) ?: 1
        )
    }
}

internal fun extractTemporaryProcessCandidateIndex(recordName: String): Int? {
    return Regex("(?:^|[-_])(?:tmp|temp)([1-9][0-9]*)(?:$|[-_])")
        .find(recordName.lowercase(Locale.US))
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private fun inferProcessAppScope(selectedToolId: String?, processId: String): String {
    return resolveCanonicalAppId(selectedToolId)
        ?: resolveCanonicalAppId(processId)
        ?: "generic"
}

private fun inferProcessDomain(processId: String, objective: String): String {
    val normalized = "$processId $objective".lowercase(Locale.US)
    return if (
        normalized.contains("buy") ||
        normalized.contains("cart") ||
        normalized.contains("order") ||
        normalized.contains("coupon") ||
        normalized.contains("refund") ||
        normalized.contains("return") ||
        normalized.contains("rating") ||
        normalized.contains("shop") ||
        normalized.contains("买") ||
        normalized.contains("购物") ||
        normalized.contains("下单")
    ) {
        "SHOPPING"
    } else {
        "GENERIC"
    }
}

private fun normalizeProcessNameToken(value: String): String {
    return value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "generic" }
}

private fun normalizeProcessNameSegment(value: String): String {
    return value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "generic" }
}