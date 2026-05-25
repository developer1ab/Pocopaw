package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.CanonicalTraceRawMaterial
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ExecutionEvent
import com.atombits.pocopaw.ExecutionEventPhase
import com.atombits.pocopaw.learning.LearningCurationGateway
import com.atombits.pocopaw.MemoryState
import com.atombits.pocopaw.ProcessLearningMaterial
import com.atombits.pocopaw.PromptCenter
import com.atombits.pocopaw.PromptMessage
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.ProcessCurationPromptSpec
import com.atombits.pocopaw.ProcessExtractionGroupPlan
import com.atombits.pocopaw.ProcessCandidateCurationState
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ReadyProcessAsset
import com.atombits.pocopaw.orGenericProcessScope
import com.atombits.pocopaw.alignProcessAssetBindingsToTraceSteps
import com.atombits.pocopaw.applyProcessShortcutProjection
import com.atombits.pocopaw.buildPayloadPreservingCanonicalTrace
import com.atombits.pocopaw.buildProcessExtractionGroupPlans
import com.atombits.pocopaw.buildTemporaryProcessCandidateName
import com.atombits.pocopaw.buildCanonicalProcessCandidateName
import com.atombits.pocopaw.buildExistingPageEvidenceBundle
import com.atombits.pocopaw.buildExistingReadyAssetBundle
import com.atombits.pocopaw.buildReadyProcessAssetDisplayName
import com.atombits.pocopaw.canonicalizeProcessAppScope
import com.atombits.pocopaw.canonicalizeProcessDomain
import com.atombits.pocopaw.deriveCanonicalProcessScope
import com.atombits.pocopaw.extractStructuredPromptPayloadText
import com.atombits.pocopaw.inferCanonicalProcessDomain
import com.atombits.pocopaw.extractProcessSemantics
import com.atombits.pocopaw.extractCanonicalAcceptanceSignal
import com.atombits.pocopaw.extractCanonicalProcessAssetStepBinding
import com.atombits.pocopaw.extractCanonicalStageName
import com.atombits.pocopaw.inferCanonicalProcessAction
import com.atombits.pocopaw.reduceCanonicalTrace
import com.atombits.pocopaw.toProcessSlotHints
import com.atombits.pocopaw.upsertPageEvidenceAssets
import com.atombits.pocopaw.upsertReadyProcessAsset
import com.atombits.pocopaw.process.reuse.PrototypeStoreProcessAssetRepository
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

private const val processCurationRepairMaxRawChars = 1200
private val allowedProcessCurationDecisions = setOf("replace", "add_variant", "keep")

internal data class ProcessCurationRunOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

internal interface ProcessCurationResolver {
    fun resolve(
        packet: PromptPacket,
        pendingEntry: ProcessAssetEntry,
        traceBundle: CanonicalProcessTraceBundle,
        now: Long = System.currentTimeMillis()
    ): StructuredProcessDraftResult

    fun latestDiagnostics(): List<String> = emptyList()
}

internal object LocalProcessCurationResolver : ProcessCurationResolver {
    private const val localFallbackSummary = "Process curation path: local fallback draft generated."

    override fun resolve(
        packet: PromptPacket,
        pendingEntry: ProcessAssetEntry,
        traceBundle: CanonicalProcessTraceBundle,
        now: Long
    ): StructuredProcessDraftResult {
        return buildFallbackProcessDraft(pendingEntry, traceBundle)
    }

    override fun latestDiagnostics(): List<String> = listOf(localFallbackSummary)
}

internal class SemanticProcessCurationResolver(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient(),
    private val fallbackResolver: ProcessCurationResolver? = null,
    private val isConfiguredOverride: (() -> Boolean)? = null,
    private val requestPromptPacketOverride: ((PromptPacket) -> String)? = null,
    private val requestPromptMessagesOverride: ((List<PromptMessage>, SemanticPrototypeClient.PromptRequestConfig) -> String)? = null
) : ProcessCurationResolver {
    private val gateway by lazy(LazyThreadSafetyMode.NONE) {
        LearningCurationGateway(
            client = client,
            isConfiguredOverride = isConfiguredOverride,
            requestPromptPacketOverride = requestPromptPacketOverride,
            requestPromptMessagesOverride = requestPromptMessagesOverride
        )
    }

    override fun latestDiagnostics(): List<String> = gateway.latestDiagnostics()

    override fun resolve(
        packet: PromptPacket,
        pendingEntry: ProcessAssetEntry,
        traceBundle: CanonicalProcessTraceBundle,
        now: Long
    ): StructuredProcessDraftResult {
        return gateway.resolveProcessCuration(packet, pendingEntry, traceBundle, fallbackResolver, now)
    }
}

internal fun runProcessCurationOnce(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: ProcessCurationResolver = SemanticProcessCurationResolver()
): ProcessCurationRunOutcome {
    val pendingEntry = PrototypeStoreProcessAssetRepository.listPending(store, limit = 1).firstOrNull()
    if (pendingEntry == null) {
        return ProcessCurationRunOutcome(
            updatedStore = store,
            applied = false,
            message = "No pending process asset is available for curation."
        )
    }

    val pendingIndex = store.processAssetEntries.indexOfFirst { entry -> entry.id == pendingEntry.id }
    return runCatching {
        val traceBundle = ProcessTracePreprocessor.preprocess(pendingEntry, now)
        val packet = PromptCenter.buildProcessCurationPacket(
            ProcessCurationPromptSpec(
                task = traceBundle.task,
                appScope = traceBundle.appScope,
                processScope = traceBundle.processScope,
                existingAssetName = pendingEntry.assetName,
                existingDescription = pendingEntry.semanticDescription,
                reviewComment = pendingEntry.reviewComment,
                traceForPrompt = ProcessTracePreprocessor.compactTraceForPrompt(traceBundle),
                existingAssetBundle = null,
                pageEvidenceBundle = buildExistingPageEvidenceBundle(
                    pageEvidenceAssets = store.pageEvidenceAssets,
                    appScope = pendingEntry.appScope,
                    processId = pendingEntry.processScope.orGenericProcessScope()
                )
            )
        )
        val draft = resolver.resolve(packet, pendingEntry, traceBundle, now).withResolvedTraceBundle(traceBundle)
        val applied = applyCuration(
            store = store,
            pendingIndex = pendingIndex,
            pendingEntry = pendingEntry,
            draft = draft,
            now = now
        )
        val diagnostics = resolver.latestDiagnostics()
        val updatedStore = appendProcessCurationExecutionEvents(
            store = applied.updatedStore,
            pendingEntry = pendingEntry,
            diagnostics = diagnostics,
            now = now
        )
        ProcessCurationRunOutcome(
            updatedStore = updatedStore,
            applied = true,
            message = appendProcessCurationDiagnosticMessage(applied.message, diagnostics)
        )
    }.getOrElse { throwable ->
        markCurationFailed(store, pendingEntry, throwable, resolver.latestDiagnostics(), now)
    }
}

internal fun applyProcessExtractionCuration(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: ProcessCurationResolver = SemanticProcessCurationResolver()
): ProcessCurationRunOutcome {
    if (PrototypeStoreProcessAssetRepository.listPending(store, limit = 1).isNotEmpty()) {
        return runProcessCurationOnce(store, now, resolver)
    }

    val queueOutcome = enqueueActionableCurationInputs(store, now)
    if (!queueOutcome.applied) {
        return ProcessCurationRunOutcome(
            updatedStore = queueOutcome.updatedStore,
            applied = false,
            message = queueOutcome.message
        )
    }

    var workingStore = queueOutcome.updatedStore
    queueOutcome.enqueuedEntryIds.forEachIndexed { index, entryId ->
        val pendingEntry = workingStore.processAssetEntries.firstOrNull { entry ->
            entry.id == entryId && entry.assetState == ProcessAssetState.PENDING
        } ?: return@forEachIndexed
        val outcome = runProcessCurationOnce(
            store = workingStore,
            now = now + index + 1,
            resolver = resolver
        )
        if (!outcome.applied) {
            return ProcessCurationRunOutcome(
                updatedStore = workingStore,
                applied = true,
                message = buildUnifiedExtractionSummary(
                    store = workingStore,
                    enqueuedEntryIds = queueOutcome.enqueuedEntryIds,
                    actionableGroupCount = queueOutcome.actionableGroupCount,
                    deferredGroupCount = queueOutcome.deferredGroupCount,
                    inputSourceLabel = queueOutcome.inputSourceLabel
                )
            )
        }
        val resolvedEntry = outcome.updatedStore.processAssetEntries.firstOrNull { entry -> entry.id == pendingEntry.id }
        workingStore = outcome.updatedStore
        if (resolvedEntry?.assetState == ProcessAssetState.PENDING) {
            return@forEachIndexed
        }
    }

    return ProcessCurationRunOutcome(
        updatedStore = workingStore,
        applied = true,
        message = buildUnifiedExtractionSummary(
            store = workingStore,
            enqueuedEntryIds = queueOutcome.enqueuedEntryIds,
            actionableGroupCount = queueOutcome.actionableGroupCount,
            deferredGroupCount = queueOutcome.deferredGroupCount,
            inputSourceLabel = queueOutcome.inputSourceLabel
        )
    )
}

internal fun parseProcessCurationResult(
    raw: String,
    traceBundle: CanonicalProcessTraceBundle
): StructuredProcessDraftResult {
    val (parsed, errors) = parseAndValidateStructuredProcessDraft(raw)
    val validParsed = parsed ?: throw IllegalArgumentException(
        "structured_process_draft contract violation: ${errors.joinToString("; ")}"
    )
    return validParsed.toStructuredProcessDraftResult(traceBundle)
}

private data class AppliedProcessCuration(
    val updatedStore: PrototypeStoreData,
    val message: String
)

private data class PendingRawMaterialQueueOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String,
    val enqueuedEntryIds: List<String> = emptyList(),
    val actionableGroupCount: Int = 0,
    val deferredGroupCount: Int = 0,
    val inputSourceLabel: String = "raw material"
)

private fun enqueueActionableCurationInputs(
    store: PrototypeStoreData,
    now: Long
): PendingRawMaterialQueueOutcome {
    val learningOutcome = enqueueActionableLearningMaterialGroups(store, now)
    return if (learningOutcome.applied) {
        learningOutcome
    } else {
        enqueueActionableRawMaterialGroups(store, now)
    }
}

private fun enqueueActionableRawMaterialGroups(
    store: PrototypeStoreData,
    now: Long
): PendingRawMaterialQueueOutcome {
    val pendingMaterials = store.processExtractionRawMaterials.filterNot { rawMaterial ->
        store.processExtractionConsumedIds.contains(rawMaterial.id)
    }
    if (pendingMaterials.isEmpty()) {
        return PendingRawMaterialQueueOutcome(
            updatedStore = store,
            applied = false,
            message = "No pending process extraction raw material is available.",
            inputSourceLabel = "raw material"
        )
    }

    val groupPlans = buildProcessExtractionGroupPlans(pendingMaterials)
    val actionableGroups = groupPlans.filter { groupPlan -> groupPlan.actionable }
    if (actionableGroups.isEmpty()) {
        return PendingRawMaterialQueueOutcome(
            updatedStore = store,
            applied = false,
            message = "No mature process extraction group is actionable yet.",
            inputSourceLabel = "raw material"
        )
    }

    var workingStore = store
    val nextConsumedIds = store.processExtractionConsumedIds.toMutableList()
    val enqueuedEntryIds = mutableListOf<String>()

    actionableGroups.forEachIndexed { index, groupPlan ->
        val entryNow = now + index
        val queuedEntry = buildPendingEntryFromRawMaterialGroup(
            existingEntries = workingStore.processAssetEntries,
            groupPlan = groupPlan,
            now = entryNow
        )
        workingStore = PrototypeStoreProcessAssetRepository.savePending(
            store = workingStore,
            entry = queuedEntry,
            summary = "Queued ${queuedEntry.assetName} from mature process extraction raw material.",
            now = entryNow
        )
        enqueuedEntryIds += queuedEntry.id
        groupPlan.materials.forEach { rawMaterial ->
            if (!nextConsumedIds.contains(rawMaterial.id)) {
                nextConsumedIds += rawMaterial.id
            }
        }
    }

    val queuedStore = workingStore.copy(
        processExtractionConsumedIds = nextConsumedIds,
        lastProcessCurationSummary = ProcessCurationSummary(
            assetEntryId = enqueuedEntryIds.lastOrNull(),
            assetName = enqueuedEntryIds.lastOrNull()?.let { entryId ->
                workingStore.processAssetEntries.firstOrNull { entry -> entry.id == entryId }?.assetName
            },
            assetState = ProcessAssetState.PENDING,
            summary = buildString {
                append("Queued ")
                append(actionableGroups.size)
                append(" mature process extraction group(s) into pending curation entries.")
            },
            updatedAt = now
        )
    )
    return PendingRawMaterialQueueOutcome(
        updatedStore = queuedStore,
        applied = true,
        message = "Queued ${actionableGroups.size} mature process extraction group(s) for curation.",
        enqueuedEntryIds = enqueuedEntryIds,
        actionableGroupCount = actionableGroups.size,
        deferredGroupCount = groupPlans.size - actionableGroups.size,
        inputSourceLabel = "raw material"
    )
}

private fun enqueueActionableLearningMaterialGroups(
    store: PrototypeStoreData,
    now: Long
): PendingRawMaterialQueueOutcome {
    val pendingLearningMaterials = store.processLearningMaterials.filterNot { material ->
        store.processExtractionConsumedIds.contains(learningMaterialConsumedId(material))
    }
    if (pendingLearningMaterials.isEmpty()) {
        return PendingRawMaterialQueueOutcome(
            updatedStore = store,
            applied = false,
            message = "No pending process learning material is available.",
            inputSourceLabel = "learning material"
        )
    }

    var workingStore = store
    val nextConsumedIds = store.processExtractionConsumedIds.toMutableList()
    val enqueuedEntryIds = mutableListOf<String>()
    pendingLearningMaterials.forEachIndexed { index, material ->
        val entryNow = now + index
        val queuedEntry = buildPendingEntryFromLearningMaterial(
            existingEntries = workingStore.processAssetEntries,
            material = material,
            now = entryNow
        )
        workingStore = PrototypeStoreProcessAssetRepository.savePending(
            store = workingStore,
            entry = queuedEntry,
            summary = "Queued ${queuedEntry.assetName} from process learning material.",
            now = entryNow
        )
        enqueuedEntryIds += queuedEntry.id
        val consumedId = learningMaterialConsumedId(material)
        if (!nextConsumedIds.contains(consumedId)) {
            nextConsumedIds += consumedId
        }
    }

    val queuedStore = workingStore.copy(
        processExtractionConsumedIds = nextConsumedIds,
        lastProcessCurationSummary = ProcessCurationSummary(
            assetEntryId = enqueuedEntryIds.lastOrNull(),
            assetName = enqueuedEntryIds.lastOrNull()?.let { entryId ->
                workingStore.processAssetEntries.firstOrNull { entry -> entry.id == entryId }?.assetName
            },
            assetState = ProcessAssetState.PENDING,
            summary = "Queued ${pendingLearningMaterials.size} process learning material input(s) into pending curation entries.",
            updatedAt = now
        )
    )
    return PendingRawMaterialQueueOutcome(
        updatedStore = queuedStore,
        applied = true,
        message = "Queued ${pendingLearningMaterials.size} process learning material input(s) for curation.",
        enqueuedEntryIds = enqueuedEntryIds,
        actionableGroupCount = pendingLearningMaterials.size,
        deferredGroupCount = 0,
        inputSourceLabel = "learning material"
    )
}

private fun buildPendingEntryFromRawMaterialGroup(
    existingEntries: List<ProcessAssetEntry>,
    groupPlan: ProcessExtractionGroupPlan,
    now: Long
): ProcessAssetEntry {
    val selectedMaterial = groupPlan.selectedMaterial
    val selectedExtraction = extractProcessSemantics(selectedMaterial)
    val traceLines = groupPlan.materials
        .sortedBy { material -> material.createdAt }
        .flatMap(::buildPayloadPreservingTraceLines)
    val acceptanceCriteria = groupPlan.materials.asSequence()
        .flatMap { material ->
            extractProcessSemantics(material, reduceCanonicalTrace(material)).acceptanceCriteria.asSequence()
        }
        .map { criterion -> criterion.trim() }
        .filter { criterion -> criterion.isNotBlank() }
        .distinct()
        .toList()
        .ifEmpty { selectedExtraction.acceptanceCriteria }
    val tempIndex = existingEntries.count { entry ->
        entry.sourceType == ProcessAssetSourceType.TMP &&
            entry.domain.equals(selectedExtraction.domain, ignoreCase = true) &&
            entry.appScope.equals(selectedExtraction.appScope, ignoreCase = true) &&
            entry.processScope.equals(groupPlan.processId, ignoreCase = true)
    } + 1
    val assetName = buildTemporaryProcessCandidateName(
        domain = selectedExtraction.domain,
        appScope = selectedExtraction.appScope,
        processAction = groupPlan.processAction,
        tempIndex = tempIndex
    )
    return ProcessAssetEntry(
        domain = selectedExtraction.domain,
        appScope = selectedExtraction.appScope,
        processScope = groupPlan.processId,
        sourceType = ProcessAssetSourceType.TMP,
        assetName = assetName,
        revision = 1,
        semanticDescription = selectedExtraction.semanticDescription,
        assetState = ProcessAssetState.PENDING,
        assetUpdatedAt = now,
        taskExample = selectedMaterial.objective,
        planningTrace = traceLines.joinToString(separator = "\n"),
        stepCount = traceLines.size,
        successCount = groupPlan.materials.size.coerceAtLeast(1),
        updatedAt = now,
        reviewComment = "Queued from mature process extraction raw material group.",
        businessProcessName = assetName,
        businessAcceptanceCriteria = acceptanceCriteria,
        businessStagesJson = "",
        optimizedProcessTrace = traceLines,
        diffSummary = "Queued from mature process extraction raw material.",
        reliabilityAnalysis = "Pending curation from mature process extraction raw material.",
        reviewDecision = "pending_raw_material",
        reviewConfidence = 0.0,
        readyWeight = 0.0,
        originAssetId = null,
        slotEvidenceSnapshot = selectedMaterial.slotEvidenceSnapshot,
        slotHints = selectedMaterial.slotEvidenceSnapshot?.toProcessSlotHints().orEmpty()
    )
}

private fun buildPendingEntryFromLearningMaterial(
    existingEntries: List<ProcessAssetEntry>,
    material: ProcessLearningMaterial,
    now: Long
): ProcessAssetEntry {
    val domain = canonicalizeProcessDomain(material.domain)
        ?: inferCanonicalProcessDomain(processId = material.processId, objective = material.objective)
    val appScope = canonicalizeProcessAppScope(material.appScope)
    val processAction = material.processAction?.takeIf { value -> value.isNotBlank() }
        ?: inferCanonicalProcessAction(
            processId = material.processId,
            objective = material.objective,
            domain = domain,
            actionHint = material.processAction
        )
    val processScope = deriveCanonicalProcessScope(
        rawProcessId = material.processId,
        objective = material.objective,
        appScope = appScope,
        domain = domain,
        actionHint = processAction
    ) ?: material.processId
    val tempIndex = existingEntries.count { entry ->
        entry.sourceType == ProcessAssetSourceType.TMP &&
            entry.domain.equals(domain, ignoreCase = true) &&
            entry.appScope.equals(appScope, ignoreCase = true) &&
            entry.processScope.equals(processScope, ignoreCase = true)
    } + 1
    val assetName = buildTemporaryProcessCandidateName(
        domain = domain,
        appScope = appScope,
        processAction = processAction,
        tempIndex = tempIndex
    )
    val traceLines = material.stageTransitions.ifEmpty {
        material.exemplarActionSummaries.mapNotNull { exemplar ->
            exemplar.stepType.takeIf { value -> value.isNotBlank() }?.let { stepType ->
                listOf(stepType, "PROCESS_REFERENCE", exemplar.outcomeSignal, exemplar.note)
                    .filterNot { value -> value.isNullOrBlank() }
                    .joinToString(" | ")
            }
        }
    }
    return ProcessAssetEntry(
        domain = domain,
        appScope = appScope,
        processScope = processScope,
        sourceType = ProcessAssetSourceType.TMP,
        assetName = assetName,
        revision = 1,
        semanticDescription = material.objective,
        assetState = ProcessAssetState.PENDING,
        assetUpdatedAt = now,
        taskExample = material.objective,
        planningTrace = traceLines.joinToString(separator = "\n"),
        stepCount = traceLines.size,
        successCount = material.lineageTraceIds.size.coerceAtLeast(1),
        updatedAt = now,
        reviewComment = "Queued from process learning material.",
        businessProcessName = assetName,
        businessAcceptanceCriteria = material.verificationSignals,
        businessStagesJson = "",
        optimizedProcessTrace = traceLines,
        diffSummary = "Queued from process learning material.",
        reliabilityAnalysis = "Pending curation from process learning material.",
        reviewDecision = "pending_learning_material",
        reviewConfidence = 0.0,
        readyWeight = 0.0,
        originAssetId = null,
        slotEvidenceSnapshot = material.slotEvidenceSnapshot,
        slotHints = material.slotHints
    )
}

private fun buildUnifiedExtractionSummary(
    store: PrototypeStoreData,
    enqueuedEntryIds: List<String>,
    actionableGroupCount: Int,
    deferredGroupCount: Int,
    inputSourceLabel: String = "raw material"
): String {
    val processedEntries = store.processAssetEntries.filter { entry -> entry.id in enqueuedEntryIds }
    val readyCount = processedEntries.count { entry -> entry.assetState == ProcessAssetState.READY }
    val failedCount = processedEntries.count { entry -> entry.assetState == ProcessAssetState.FAILED }
    val pendingCount = processedEntries.count { entry -> entry.assetState == ProcessAssetState.PENDING }
    return buildString {
        append("Curated ")
        append(readyCount)
        append(" ready process asset(s) from ")
        append(actionableGroupCount)
        append(' ')
        append(inputSourceLabel)
        append(" input(s).")
        if (failedCount > 0) {
            append(' ')
            append(failedCount)
            append(" group(s) failed curation.")
        }
        if (pendingCount > 0) {
            append(' ')
            append(pendingCount)
            append(" group(s) remain pending.")
        }
        if (deferredGroupCount > 0) {
            append(' ')
            append("Deferred ")
            append(deferredGroupCount)
            append(" immature group(s).")
        }
    }
}

private fun learningMaterialConsumedId(material: ProcessLearningMaterial): String {
    return "learning_material:${material.materialId}"
}

private fun buildFallbackProcessDraft(
    pendingEntry: ProcessAssetEntry,
    traceBundle: CanonicalProcessTraceBundle
): StructuredProcessDraftResult {
    val semanticDescription = pendingEntry.semanticDescription
        .ifBlank { "Reusable process for ${traceBundle.task}" }
    val acceptanceCriteria = pendingEntry.businessAcceptanceCriteria
        .map { criterion -> criterion.trim() }
        .filter { criterion -> criterion.isNotBlank() }
        .ifEmpty {
            traceBundle.verificationSignals.ifEmpty {
                listOf("Task goal is achieved with expected UI verification.")
            }
        }
    return StructuredProcessDraftResult(
        processEnum = traceBundle.processScope.orGenericProcessScope(),
        semanticDescription = semanticDescription,
        processName = buildDefaultProcessName(pendingEntry, traceBundle),
        acceptanceCriteria = acceptanceCriteria,
        stages = buildFallbackBusinessStages(traceBundle, semanticDescription),
        optimizedProcessTrace = traceBundle.canonicalTrace,
        diffSummary = "Locally curated pending ${pendingEntry.sourceType.name.lowercase(Locale.US)} process asset.",
        reliabilityAnalysis = "Local fallback draft generated from canonical trace preprocessing.",
        decision = resolveDefaultDecision(pendingEntry),
        confidence = 0.56,
        traceBundle = traceBundle
    )
}

private fun StructuredProcessDraftResult.withResolvedTraceBundle(
    traceBundle: CanonicalProcessTraceBundle
): StructuredProcessDraftResult {
    return copy(
        processEnum = deriveCanonicalProcessScope(
            rawProcessId = processEnum,
            objective = semanticDescription.ifBlank { traceBundle.task },
            appScope = traceBundle.appScope,
            actionHint = processName
        ) ?: traceBundle.processScope,
        optimizedProcessTrace = optimizedProcessTrace.ifEmpty { traceBundle.canonicalTrace },
        traceBundle = traceBundle
    )
}

private fun applyCuration(
    store: PrototypeStoreData,
    pendingIndex: Int,
    pendingEntry: ProcessAssetEntry,
    draft: StructuredProcessDraftResult,
    now: Long
): AppliedProcessCuration {
    val readyAssetCandidate = buildReadyAssetCandidate(pendingEntry, draft, now)
    val nextReadyAssets = store.readyProcessAssets.toMutableList()
    val storedReadyAsset = upsertReadyProcessAsset(nextReadyAssets, readyAssetCandidate, now)
    val resolvedAssetName = buildReadyProcessAssetDisplayName(storedReadyAsset)
    var workingStore = store
    val supersededIds = resolveSupersededEntryIds(
        entries = store.processAssetEntries,
        pendingIndex = pendingIndex,
        pendingEntry = pendingEntry,
        processId = storedReadyAsset.processId,
        resolvedAssetName = resolvedAssetName
    )
    if (supersededIds.isNotEmpty()) {
        workingStore = PrototypeStoreProcessAssetRepository.supersede(
            store = workingStore,
            entryIds = supersededIds,
            summaryBuilder = { supersededEntry ->
                "Superseded ${supersededEntry.assetName} after promoting ${resolvedAssetName}."
            },
            now = now
        )
    }

    val baseReadyEntry = pendingEntry.copy(
        assetName = resolvedAssetName,
        processScope = storedReadyAsset.processId,
        revision = storedReadyAsset.version,
        semanticDescription = draft.semanticDescription,
        assetState = ProcessAssetState.READY,
        assetUpdatedAt = now,
        taskExample = pendingEntry.taskExample.ifBlank { draft.traceBundle?.task.orEmpty() },
        planningTrace = pendingEntry.planningTrace.ifBlank {
            draft.traceBundle?.canonicalTrace.orEmpty().joinToString(separator = "\n")
        },
        stepCount = storedReadyAsset.exemplarActionSummaries.size.takeIf { count -> count > 0 }
            ?: storedReadyAsset.pageSemanticAnchors.size.takeIf { count -> count > 0 }
            ?: draft.traceBundle?.canonicalTrace?.size
            ?: draft.optimizedProcessTrace.size,
        successCount = pendingEntry.successCount.coerceAtLeast(1),
        updatedAt = now,
        reviewComment = pendingEntry.reviewComment,
        businessProcessName = pendingEntry.businessProcessName.ifBlank { resolvedAssetName },
        businessAcceptanceCriteria = pendingEntry.businessAcceptanceCriteria.ifEmpty {
            draft.traceBundle?.verificationSignals?.ifEmpty {
                listOf("Task goal is achieved with expected UI verification.")
            } ?: listOf("Task goal is achieved with expected UI verification.")
        },
        businessStagesJson = encodeBusinessStagesJson(
            buildFallbackBusinessStages(
                traceBundle = draft.traceBundle ?: CanonicalProcessTraceBundle(),
                semanticDescription = draft.semanticDescription
            )
        ),
        optimizedProcessTrace = draft.traceBundle?.canonicalTrace ?: draft.optimizedProcessTrace,
        diffSummary = "Base curation promoted pending process asset.",
        reliabilityAnalysis = "Base curation completed before review optimization.",
        reviewDecision = resolveDefaultDecision(pendingEntry),
        reviewConfidence = 0.0,
        readyWeight = if (pendingEntry.readyWeight > 0.0) pendingEntry.readyWeight else defaultReadyWeight(pendingEntry),
        originAssetId = pendingEntry.originAssetId
    )
    val baseSummary = buildCurationPromotionSummary(resolvedAssetName, supersededIds.size, null)
    workingStore = PrototypeStoreProcessAssetRepository.applyProcessCuration(
        store = workingStore,
        entryId = pendingEntry.id,
        updatedEntry = baseReadyEntry,
        summary = baseSummary,
        now = now
    )

    val nextPageEvidenceAssets = store.pageEvidenceAssets.toMutableList()
    upsertPageEvidenceAssets(
        nextPageEvidenceAssets = nextPageEvidenceAssets,
        materials = listOf(buildSyntheticRawMaterial(storedReadyAsset, draft, now)),
        appScope = storedReadyAsset.appScope,
        processId = storedReadyAsset.processId,
        now = now
    )
    val baseStore = workingStore.copy(
        readyProcessAssets = nextReadyAssets,
        pageEvidenceAssets = nextPageEvidenceAssets
    )
    val shortcutProjection = applyProcessShortcutProjection(baseStore, now)
    val optimizedEntry = baseReadyEntry.copy(
        reviewComment = draft.reliabilityAnalysis,
        businessProcessName = draft.processName,
        businessAcceptanceCriteria = draft.acceptanceCriteria,
        businessStagesJson = encodeBusinessStagesJson(draft.stages),
        optimizedProcessTrace = draft.optimizedProcessTrace,
        diffSummary = draft.diffSummary,
        reliabilityAnalysis = draft.reliabilityAnalysis,
        reviewDecision = draft.decision,
        reviewConfidence = draft.confidence,
        updatedAt = now,
        assetUpdatedAt = now
    )

    return runCatching {
        val doneSummary = buildCurationPromotionSummary(resolvedAssetName, supersededIds.size, "done")
        val optimizedStore = PrototypeStoreProcessAssetRepository.applyProcessOptimization(
            store = shortcutProjection.updatedStore,
            entryId = pendingEntry.id,
            updatedEntry = optimizedEntry,
            summary = doneSummary,
            now = now
        )
        AppliedProcessCuration(
            updatedStore = optimizedStore,
            message = doneSummary
        )
    }.getOrElse {
        val baseOnlySummary = buildCurationPromotionSummary(resolvedAssetName, supersededIds.size, "done:base_only")
        AppliedProcessCuration(
            updatedStore = shortcutProjection.updatedStore.copy(
                lastProcessCurationSummary = shortcutProjection.updatedStore.lastProcessCurationSummary?.copy(
                    summary = baseOnlySummary,
                    updatedAt = now
                )
            ),
            message = baseOnlySummary
        )
    }
}

private fun buildReadyAssetCandidate(
    pendingEntry: ProcessAssetEntry,
    draft: StructuredProcessDraftResult,
    now: Long
): ReadyProcessAsset {
    val resolvedProcessId = deriveCanonicalProcessScope(
        rawProcessId = draft.processEnum ?: pendingEntry.processScope,
        objective = draft.semanticDescription.ifBlank { pendingEntry.taskExample },
        appScope = pendingEntry.appScope,
        domain = pendingEntry.domain,
        actionHint = draft.processName
    ) ?: draft.traceBundle?.processScope.orGenericProcessScope()
    val resolvedDomain = canonicalizeProcessDomain(pendingEntry.domain) ?: "OTHER"
    val resolvedAppScope = canonicalizeProcessAppScope(
        pendingEntry.appScope.ifBlank { "generic" }
    )
    val optimizedTrace = draft.optimizedProcessTrace
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }
        .ifEmpty { draft.traceBundle?.canonicalTrace.orEmpty() }
    val preferredBindings = optimizedTrace.mapNotNull(::extractCanonicalProcessAssetStepBinding)
    val fallbackBindings = draft.traceBundle?.canonicalTrace
        .orEmpty()
        .mapNotNull(::extractCanonicalProcessAssetStepBinding)
    val referenceEvidenceBindings = selectBestProcessAssetBindings(preferredBindings, fallbackBindings)
    val referenceDraft = draft.structuredReferenceAsset
    val stages = draft.stages.mapNotNull { stage ->
        stage.stageNameNl.trim().takeIf { value -> value.isNotBlank() }
    }.ifEmpty {
        optimizedTrace.mapNotNull(::extractCanonicalStageName).distinct()
    }
    val stageReferences = referenceDraft?.stageReferences
        ?.takeIf { references -> references.isNotEmpty() }
        ?: draft.stages.map { stage ->
            com.atombits.pocopaw.ProcessStageReference(
                stageName = stage.stageNameNl.trim(),
                stageGoal = stage.stageGoalNl.trim(),
                entrySignals = stage.entrySignals,
                exitSignals = stage.exitSignals,
                verificationSignals = stage.exitSignals,
                transitionNotes = stage.transitionConditions
            )
        }.filter { reference ->
            reference.stageName.isNotBlank() ||
                reference.stageGoal.isNotBlank() ||
                reference.verificationSignals.isNotEmpty()
        }
    val pageSemanticAnchors = referenceDraft?.pageSemanticAnchors
        ?.takeIf { anchors -> anchors.isNotEmpty() }
        ?: referenceEvidenceBindings.mapNotNull { binding ->
            val locatorHint = binding.locatorHint?.trim().orEmpty()
            val pageSignature = binding.pageSignature?.trim().orEmpty()
            val note = binding.note?.trim().orEmpty()
            if (locatorHint.isEmpty() && pageSignature.isEmpty() && note.isEmpty()) {
                null
            } else {
                com.atombits.pocopaw.ProcessPageSemanticAnchor(
                    stageName = binding.stepType.takeIf { stepType -> stepType.isNotBlank() },
                    semanticRole = locatorHint.ifBlank { binding.stepType },
                    pageSignature = pageSignature.ifBlank { null },
                    locatorHints = listOfNotNull(locatorHint.takeIf { value -> value.isNotBlank() }),
                    verificationSignals = binding.verificationSignals,
                    notes = listOfNotNull(note.takeIf { value -> value.isNotBlank() })
                )
            }
        }.distinctBy { anchor ->
            listOf(
                anchor.stageName,
                anchor.semanticRole,
                anchor.pageSignature,
                anchor.locatorHints.joinToString(separator = "|"),
                anchor.verificationSignals.joinToString(separator = "|"),
                anchor.notes.joinToString(separator = "|")
            ).joinToString(separator = "||")
        }
    val verificationSignals = (referenceDraft?.verificationSignals.orEmpty() +
        draft.acceptanceCriteria +
        draft.traceBundle?.verificationSignals.orEmpty() +
        stageReferences.flatMap { reference -> reference.verificationSignals } +
        pageSemanticAnchors.flatMap { anchor -> anchor.verificationSignals } +
        referenceEvidenceBindings.flatMap { binding -> binding.verificationSignals })
        .map { signal -> signal.trim() }
        .filter { signal -> signal.isNotBlank() }
        .filterNot { signal ->
            pageSemanticAnchors.any { anchor -> anchor.pageSignature == signal } ||
                referenceEvidenceBindings.any { binding -> binding.pageSignature == signal }
        }
        .distinct()
    val exemplarActionSummaries = referenceDraft?.exemplarActionSummaries
        ?.takeIf { exemplars -> exemplars.isNotEmpty() }
        ?: referenceEvidenceBindings.map { binding ->
            com.atombits.pocopaw.ProcessExemplarActionSummary(
                stageName = binding.stepType.takeIf { stepType -> stepType.isNotBlank() },
                stepType = binding.stepType,
                actionType = binding.actionType,
                outcomeSignal = binding.verificationSignals.firstOrNull(),
                locatorHint = binding.locatorHint,
                pageSignature = binding.pageSignature,
                note = binding.note
            )
        }
    val failurePatterns = referenceDraft?.failurePatterns.orEmpty()
    val generalizationNotes = referenceDraft?.generalizationNotes
        ?.takeIf { notes -> notes.isNotEmpty() }
        ?: listOfNotNull(
            draft.diffSummary.takeIf { summary -> summary.isNotBlank() },
            draft.reliabilityAnalysis.takeIf { analysis -> analysis.isNotBlank() }
        )
    val slotHints = referenceDraft?.slotHints
        ?.takeIf { hints -> hints.isNotEmpty() }
        ?: pendingEntry.slotHints.ifEmpty {
            pendingEntry.slotEvidenceSnapshot?.toProcessSlotHints().orEmpty()
        }
    val referenceWeight = referenceDraft?.referenceWeight
        ?.takeIf { weight -> weight > 0.0 }
        ?: draft.confidence.coerceIn(0.0, 1.0)
    return ReadyProcessAsset(
        processId = resolvedProcessId,
        domain = resolvedDomain,
        appScope = resolvedAppScope,
        semanticDescription = draft.semanticDescription,
        stages = stages,
        acceptanceCriteria = draft.acceptanceCriteria,
        version = 1,
        lineageSourceTraceId = pendingEntry.id,
        lastDerivedAt = now,
        processAction = inferCanonicalProcessAction(
            processId = resolvedProcessId,
            objective = draft.semanticDescription,
            domain = resolvedDomain,
            actionHint = draft.processName
        ),
        stageReferences = stageReferences,
        pageSemanticAnchors = pageSemanticAnchors,
        verificationSignals = verificationSignals,
        exemplarActionSummaries = exemplarActionSummaries,
        failurePatterns = failurePatterns,
        generalizationNotes = generalizationNotes,
        referenceWeight = referenceWeight,
        slotHints = slotHints
    )
}

private fun buildPayloadPreservingTraceLines(rawMaterial: CanonicalTraceRawMaterial): List<String> {
    return buildPayloadPreservingCanonicalTrace(rawMaterial)
}

private fun selectBestProcessAssetBindings(
    preferredBindings: List<com.atombits.pocopaw.ProcessAssetStepBinding>,
    fallbackBindings: List<com.atombits.pocopaw.ProcessAssetStepBinding>
): List<com.atombits.pocopaw.ProcessAssetStepBinding> {
    if (fallbackBindings.isEmpty()) {
        return preferredBindings
    }
    if (preferredBindings.isEmpty()) {
        return fallbackBindings
    }
    return if (scoreExecutableBindings(fallbackBindings) > scoreExecutableBindings(preferredBindings)) {
        fallbackBindings
    } else {
        preferredBindings
    }
}

private fun scoreExecutableBindings(bindings: List<com.atombits.pocopaw.ProcessAssetStepBinding>): Int {
    return bindings.sumOf { binding ->
        when (binding.actionType) {
            com.atombits.pocopaw.VisionActionType.TAP -> if (binding.targetX != null && binding.targetY != null) 3 else 0
            com.atombits.pocopaw.VisionActionType.INPUT -> if (
                !binding.inputText.isNullOrBlank() ||
                binding.note?.contains('"') == true ||
                binding.note?.contains('“') == true ||
                binding.note?.contains('\'') == true
            ) 2 else 0
            com.atombits.pocopaw.VisionActionType.SWIPE -> if (
                binding.swipeFromX != null &&
                binding.swipeFromY != null &&
                binding.swipeToX != null &&
                binding.swipeToY != null
            ) 3 else 0
            com.atombits.pocopaw.VisionActionType.WAIT,
            com.atombits.pocopaw.VisionActionType.BACK,
            com.atombits.pocopaw.VisionActionType.NONE -> 1
        } + if (!binding.pageSignature.isNullOrBlank()) 1 else 0
    }
}

private fun buildSyntheticRawMaterial(
    readyAsset: ReadyProcessAsset,
    draft: StructuredProcessDraftResult,
    now: Long
): CanonicalTraceRawMaterial {
    return CanonicalTraceRawMaterial(
        traceId = readyAsset.lineageSourceTraceId,
        candidateId = readyAsset.lineageSourceTraceId,
        selectedToolId = null,
        processId = readyAsset.processId,
        objective = draft.semanticDescription,
        lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
        steps = draft.optimizedProcessTrace,
        createdAt = now,
        processAction = readyAsset.processAction,
        slotEvidenceSnapshot = null
    )
}

private fun buildSyntheticReadyAssetBindings(
    readyAsset: ReadyProcessAsset
): List<com.atombits.pocopaw.ProcessAssetStepBinding> {
    val exemplarBindings = readyAsset.exemplarActionSummaries.map { exemplar ->
        com.atombits.pocopaw.ProcessAssetStepBinding(
            stepType = exemplar.stepType.ifBlank {
                exemplar.stageName?.takeIf { value -> value.isNotBlank() } ?: "REFERENCE_STEP"
            },
            actionType = exemplar.actionType,
            locatorHint = exemplar.locatorHint,
            inputText = exemplar.note
                ?.takeIf { exemplar.actionType == com.atombits.pocopaw.VisionActionType.INPUT }
                ?.let(::extractSyntheticReferenceInputText),
            verificationSignals = listOfNotNull(exemplar.outcomeSignal),
            pageSignature = exemplar.pageSignature,
            note = exemplar.note
        )
    }.filter { binding ->
        !binding.locatorHint.isNullOrBlank() ||
            !binding.pageSignature.isNullOrBlank() ||
            binding.verificationSignals.isNotEmpty() ||
            !binding.inputText.isNullOrBlank()
    }
    if (exemplarBindings.isNotEmpty()) {
        return exemplarBindings
    }

    val anchorBindings = readyAsset.pageSemanticAnchors.map { anchor ->
        com.atombits.pocopaw.ProcessAssetStepBinding(
            stepType = anchor.stageName?.takeIf { value -> value.isNotBlank() }
                ?: anchor.semanticRole.takeIf { value -> value.isNotBlank() }
                ?: "REFERENCE_STEP",
            actionType = com.atombits.pocopaw.VisionActionType.TAP,
            locatorHint = anchor.locatorHints.firstOrNull()?.takeIf { value -> value.isNotBlank() }
                ?: anchor.semanticRole.takeIf { value -> value.isNotBlank() },
            verificationSignals = anchor.verificationSignals,
            pageSignature = anchor.pageSignature,
            note = anchor.notes.firstOrNull()
        )
    }.filter { binding ->
        !binding.locatorHint.isNullOrBlank() ||
            !binding.pageSignature.isNullOrBlank() ||
            binding.verificationSignals.isNotEmpty()
    }
    if (anchorBindings.isNotEmpty()) {
        return anchorBindings
    }

    return emptyList()
}

private fun extractSyntheticReferenceInputText(note: String): String? {
    val singleQuoted = Regex("'([^']+)'").find(note)?.groupValues?.getOrNull(1)?.trim()
    if (!singleQuoted.isNullOrBlank()) {
        return singleQuoted
    }
    val chineseQuoted = Regex("“([^”]+)”").find(note)?.groupValues?.getOrNull(1)?.trim()
    if (!chineseQuoted.isNullOrBlank()) {
        return chineseQuoted
    }
    return null
}

private fun buildProcessCurationRawMaterialBundle(
    pendingEntry: ProcessAssetEntry,
    traceBundle: CanonicalProcessTraceBundle
): String {
    val resolvedProcessScope = deriveCanonicalProcessScope(
        rawProcessId = pendingEntry.processScope,
        objective = traceBundle.task,
        appScope = pendingEntry.appScope,
        domain = pendingEntry.domain,
        actionHint = pendingEntry.businessProcessName.ifBlank { pendingEntry.assetName }
    ) ?: traceBundle.processScope
    return buildString {
        append("entry_id=")
        append(pendingEntry.id)
        append("; source_type=")
        append(pendingEntry.sourceType.name.lowercase(Locale.US))
        append("; asset_state=")
        append(pendingEntry.assetState.name.lowercase(Locale.US))
        appendLine()
        append("asset_name=")
        append(pendingEntry.assetName)
        append("; process_scope=")
        append(resolvedProcessScope)
        append("; app_scope=")
        append(pendingEntry.appScope)
        append("; domain=")
        append(pendingEntry.domain)
        appendLine()
        append("semantic_description=")
        append(pendingEntry.semanticDescription)
        appendLine()
        append("review_comment=")
        append(pendingEntry.reviewComment)
        appendLine()
        append(ProcessTracePreprocessor.compactTraceForPrompt(traceBundle))
    }
}

private fun buildDefaultProcessName(
    pendingEntry: ProcessAssetEntry,
    traceBundle: CanonicalProcessTraceBundle
): String {
    val resolvedAppScope = pendingEntry.appScope.ifBlank { traceBundle.appScope }.ifBlank { "generic" }
    val resolvedProcessScope = deriveCanonicalProcessScope(
        rawProcessId = pendingEntry.processScope,
        objective = traceBundle.task,
        appScope = resolvedAppScope,
        domain = pendingEntry.domain,
        actionHint = pendingEntry.businessProcessName.ifBlank { pendingEntry.assetName }
    ) ?: traceBundle.processScope.orGenericProcessScope()
    return "$resolvedAppScope ${resolvedProcessScope.replace('_', ' ')} optimized process"
}

private fun buildFallbackBusinessStages(
    traceBundle: CanonicalProcessTraceBundle,
    semanticDescription: String
): List<BusinessProcessStage> {
    return traceBundle.canonicalTrace.mapNotNull { line ->
        val stageName = extractCanonicalStageName(line)
            ?.replace('_', ' ')
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: return@mapNotNull null
        val acceptanceSignal = extractCanonicalAcceptanceSignal(line)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        BusinessProcessStage(
            stageNameNl = stageName,
            stageGoalNl = acceptanceSignal ?: semanticDescription,
            entrySignals = emptyList(),
            exitSignals = listOfNotNull(acceptanceSignal),
            transitionConditions = listOf(line)
        )
    }.distinctBy { stage -> stage.stageNameNl.lowercase(Locale.US) }
}

private fun resolveProcessAction(entry: ProcessAssetEntry): String {
    return inferCanonicalProcessAction(
        processId = entry.processScope,
        objective = entry.semanticDescription.ifBlank { entry.taskExample },
        domain = entry.domain,
        actionHint = entry.businessProcessName.ifBlank { entry.assetName }
    )
}

private fun resolveDefaultDecision(entry: ProcessAssetEntry): String {
    return when (entry.sourceType) {
        ProcessAssetSourceType.CANDIDATE -> "replace"
        ProcessAssetSourceType.TMP -> "add_variant"
        ProcessAssetSourceType.RECORDED -> "keep"
    }
}

private fun defaultReadyWeight(entry: ProcessAssetEntry): Double {
    return when (entry.sourceType) {
        ProcessAssetSourceType.CANDIDATE -> 0.65
        ProcessAssetSourceType.TMP -> 0.45
        ProcessAssetSourceType.RECORDED -> 0.55
    }
}

private fun resolveSupersededEntryIds(
    entries: List<ProcessAssetEntry>,
    pendingIndex: Int,
    pendingEntry: ProcessAssetEntry,
    processId: String,
    resolvedAssetName: String
): List<String> {
    val resolvedPathIndex = extractCanonicalProcessPathIndex(resolvedAssetName)
    val resolvedAction = inferCanonicalProcessAction(
        processId = processId,
        objective = pendingEntry.semanticDescription.ifBlank { pendingEntry.taskExample },
        domain = pendingEntry.domain,
        actionHint = resolvedAssetName
    )
    return entries.indices.mapNotNull { index ->
        if (index == pendingIndex) {
            return@mapNotNull null
        }
        val candidate = entries[index]
        if (candidate.assetState != ProcessAssetState.READY) {
            return@mapNotNull null
        }
        if (!pendingEntry.originAssetId.isNullOrBlank() && candidate.id == pendingEntry.originAssetId) {
            return@mapNotNull candidate.id
        }
        val shouldSupersede = candidate.domain.equals(pendingEntry.domain, ignoreCase = true) &&
            candidate.appScope.equals(pendingEntry.appScope, ignoreCase = true) &&
            resolvedPathIndex != null &&
            extractCanonicalProcessPathIndex(candidate.assetName) == resolvedPathIndex &&
            (
                candidate.processScope.equals(processId, ignoreCase = true) ||
                    resolveProcessAction(candidate) == resolvedAction
                ) &&
            !candidate.assetName.equals(resolvedAssetName, ignoreCase = true)
        if (shouldSupersede) candidate.id else null
    }
}

private fun markCurationFailed(
    store: PrototypeStoreData,
    pendingEntry: ProcessAssetEntry,
    throwable: Throwable,
    diagnostics: List<String>,
    now: Long
): ProcessCurationRunOutcome {
    val failureSummary = throwable.message?.takeIf { message -> message.isNotBlank() }
        ?: "Process curation failed."
    val failureStore = PrototypeStoreProcessAssetRepository.markFailed(
        store = store,
        entryId = pendingEntry.id,
        summary = failureSummary,
        reviewComment = failureSummary,
        now = now
    )
    val updatedStore = appendProcessCurationExecutionEvents(
        store = failureStore,
        pendingEntry = pendingEntry,
        diagnostics = diagnostics,
        now = now
    )
    return ProcessCurationRunOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = appendProcessCurationDiagnosticMessage(
            baseMessage = "Marked ${pendingEntry.assetName} failed after curation error.",
            diagnostics = diagnostics
        )
    )
}

private fun appendProcessCurationExecutionEvents(
    store: PrototypeStoreData,
    pendingEntry: ProcessAssetEntry,
    diagnostics: List<String>,
    now: Long
): PrototypeStoreData {
    if (diagnostics.isEmpty()) {
        return store
    }
    val nextEvents = store.executionEvents.toMutableList().apply {
        diagnostics.distinct().forEachIndexed { index, summary ->
            add(
                ExecutionEvent(
                    candidateId = pendingEntry.id,
                    phase = ExecutionEventPhase.INFO,
                    summary = summary,
                    keyInfo = buildString {
                        append("process_entry=")
                        append(pendingEntry.assetName)
                        append(" | process_scope=")
                        append(pendingEntry.processScope)
                        append(" | app_scope=")
                        append(pendingEntry.appScope)
                    },
                    startedAt = now + index
                )
            )
        }
    }
    return store.copy(executionEvents = nextEvents)
}

private fun appendProcessCurationDiagnosticMessage(
    baseMessage: String,
    diagnostics: List<String>
): String {
    val diagnosticSummary = diagnostics.lastOrNull() ?: return baseMessage
    return "$baseMessage $diagnosticSummary"
}

private fun buildCurationPromotionSummary(
    resolvedAssetName: String,
    supersededCount: Int,
    stage: String?
): String {
    return buildString {
        append("Promoted ")
        append(resolvedAssetName)
        append(" to ready process asset.")
        if (supersededCount > 0) {
            append(' ')
            append("Superseded ")
            append(supersededCount)
            append(" prior ready revision(s).")
        }
        if (stage != null) {
            append(' ')
            append("stage=")
            append(stage)
        }
    }
}

internal fun buildProcessCurationRepairInstruction(
    errors: List<String>,
    previousRawJson: String
): String {
    return buildString {
        appendLine("Previous response violated the structured_process_draft contract.")
        if (errors.isNotEmpty()) {
            appendLine("Violation reasons: ${errors.joinToString("; ")}")
        }
        appendLine("Previous raw JSON: $previousRawJson")
        appendLine("Return strict JSON with all required fields and valid stage arrays.")
    }.trim()
}

internal fun appendProcessCurationRepairInstruction(
    promptMessages: List<PromptMessage>,
    repairInstruction: String
): List<PromptMessage> {
    return promptMessages + PromptMessage(
        role = "user",
        content = buildString {
            appendLine("repair_instruction:")
            appendLine(repairInstruction)
        }.trim()
    )
}

internal fun buildProcessCurationJsonRepairMessages(
    schemaHint: String,
    reasons: List<String>,
    rawText: String
): List<PromptMessage> {
    return listOf(
        PromptMessage(
            role = "system",
            content = "You repair malformed model outputs into strict JSON only. Do not explain. Do not wrap in markdown."
        ),
        PromptMessage(
            role = "user",
            content = buildString {
                appendLine("Fix the malformed payload into valid JSON that matches this schema template exactly:")
                appendLine(schemaHint)
                appendLine("violations: ${reasons.joinToString("; ")}")
                appendLine("malformed_payload:")
                appendLine(rawText.take(processCurationRepairMaxRawChars))
                appendLine("Keep output concise and contract-valid.")
            }.trim()
        )
    )
}

internal fun parseAndValidateStructuredProcessDraft(raw: String): Pair<JsonObject?, List<String>> {
    val payloadText = runCatching { extractStructuredPromptPayloadText(raw) }.getOrElse {
        return null to listOf("invalid_json: ${it.message ?: "unknown"}")
    }
    val parsed = runCatching { JsonParser.parseString(payloadText).asJsonObject }.getOrElse {
        return null to listOf("invalid_json: ${it.message ?: "unknown"}")
    }
    val errors = validateStructuredProcessDraft(parsed)
    return if (errors.isEmpty()) parsed to emptyList() else null to errors
}

private fun validateStructuredProcessDraft(parsed: JsonObject): List<String> {
    return buildList {
        if (parsed.getStringOrNull("process_enum").isNullOrBlank()) {
            add("missing process_enum")
        }
        if (parsed.getStringOrNull("semantic_description").isNullOrBlank()) {
            add("missing semantic_description")
        }
        val optimizedBusinessProcess = parsed.getJsonObjectOrNull("optimized_business_process")
        if (optimizedBusinessProcess == null) {
            add("missing optimized_business_process")
        } else {
            if (optimizedBusinessProcess.getStringOrNull("process_name").isNullOrBlank()) {
                add("missing optimized_business_process.process_name")
            }
            if (optimizedBusinessProcess.getFlexibleStringList("acceptance_criteria").isEmpty()) {
                add("missing optimized_business_process.acceptance_criteria")
            }
            val stages = optimizedBusinessProcess.getJsonArrayOrEmpty("stages")
            if (stages.size() < 3 || stages.size() > 8) {
                add("invalid optimized_business_process.stages")
            }
        }
        if (parsed.getFlexibleStringList("optimized_process_trace").isEmpty()) {
            add("missing optimized_process_trace")
        }
        if (parsed.getStringOrNull("diff_summary").isNullOrBlank()) {
            add("missing diff_summary")
        }
        if (parsed.getStringOrNull("reliability_analysis").isNullOrBlank()) {
            add("missing reliability_analysis")
        }
        val decision = parsed.getStringOrNull("decision")
            ?.trim()
            ?.lowercase(Locale.US)
        if (decision == null || decision !in allowedProcessCurationDecisions) {
            add("invalid decision")
        }
        if (parsed.getDoubleOrNull("confidence") == null) {
            add("missing confidence")
        }
    }
}

internal fun JsonObject.toStructuredProcessDraftResult(
    traceBundle: CanonicalProcessTraceBundle
): StructuredProcessDraftResult {
    val optimizedBusinessProcess = getJsonObjectOrNull("optimized_business_process")
        ?: throw IllegalArgumentException("missing optimized_business_process")
    val structuredReferenceAsset = getJsonObjectOrNull("structured_reference_asset")
        ?.toStructuredProcessReferenceDraftResult()
    return StructuredProcessDraftResult(
        processEnum = getStringOrNull("process_enum")?.trim()?.lowercase(Locale.US),
        semanticDescription = getStringOrNull("semantic_description")?.trim().orEmpty(),
        processName = optimizedBusinessProcess.getStringOrNull("process_name")?.trim().orEmpty(),
        acceptanceCriteria = optimizedBusinessProcess.getFlexibleStringList("acceptance_criteria"),
        stages = optimizedBusinessProcess.getJsonArrayOrEmpty("stages")
            .mapNotNull { item -> item.asJsonObjectOrNull()?.toBusinessProcessStage() },
        optimizedProcessTrace = getFlexibleStringList("optimized_process_trace"),
        diffSummary = getStringOrNull("diff_summary")?.trim().orEmpty(),
        reliabilityAnalysis = getStringOrNull("reliability_analysis")?.trim().orEmpty(),
        decision = getStringOrNull("decision")?.trim()?.lowercase(Locale.US).orEmpty(),
        confidence = getDoubleOrNull("confidence")?.coerceIn(0.0, 1.0) ?: 0.0,
        traceBundle = traceBundle,
        structuredReferenceAsset = structuredReferenceAsset
    )
}

private fun JsonObject.toStructuredProcessReferenceDraftResult(): StructuredProcessReferenceDraftResult {
    return StructuredProcessReferenceDraftResult(
        slotHints = getJsonArrayOrEmpty("slot_hints").mapNotNull { item ->
            item.asJsonObjectOrNull()?.toProcessSlotHintOrNull()
        }
    )
}

private fun JsonObject.toProcessSlotHintOrNull(): com.atombits.pocopaw.ProcessSlotHint? {
    val slotKey = getStringOrNull("slot_key")?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    val hintRole = getStringOrNull("hint_role")
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { value -> value == "PRIMARY_FILTER" || value == "VALUE_PRESERVE" || value == "CONTEXT_HINT" }
        ?: return null
    return com.atombits.pocopaw.ProcessSlotHint(
        slotKey = slotKey,
        hintRole = hintRole,
        exampleValue = getStringOrNull("example_value")?.trim()?.takeIf { value -> value.isNotBlank() }
    )
}

internal fun salvageStructuredProcessDraft(
    rawCandidates: List<String>,
    pendingEntry: ProcessAssetEntry,
    traceBundle: CanonicalProcessTraceBundle,
    violationCodes: List<String>
): StructuredProcessDraftResult {
    val normalizedCandidates = rawCandidates
        .map { rawCandidate -> runCatching { extractStructuredPromptPayloadText(rawCandidate) }.getOrDefault(rawCandidate) }
        .map { candidate -> candidate.trim() }
        .filter { candidate -> candidate.isNotBlank() }
        .sortedByDescending { candidate -> candidate.length }

    val fallbackDraft = buildFallbackProcessDraft(pendingEntry, traceBundle)
    val processEnum = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonStringField(candidate, "process_enum")
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { value -> value.isNotBlank() }
    } ?: fallbackDraft.processEnum.orEmpty()
    val semanticDescription = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonStringField(candidate, "semantic_description")
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    } ?: fallbackDraft.semanticDescription
    val optimizedBusinessProcess = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonObjectField(candidate, "optimized_business_process")
    }
    val stages = optimizedBusinessProcess
        ?.getJsonArrayOrEmpty("stages")
        ?.mapNotNull { item -> item.asJsonObjectOrNull()?.toBusinessProcessStage() }
        .orEmpty()
        .ifEmpty { fallbackDraft.stages }
    val acceptanceCriteria = optimizedBusinessProcess
        ?.getFlexibleStringList("acceptance_criteria")
        .orEmpty()
        .ifEmpty { fallbackDraft.acceptanceCriteria }
    val optimizedProcessTrace = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractProcessTraceLines(candidate).takeIf { lines -> lines.isNotEmpty() }
    } ?: fallbackDraft.optimizedProcessTrace
    val diffSummary = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonStringField(candidate, "diff_summary")
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    } ?: "Recovered structured process draft after contract repair."
    val reliabilityAnalysis = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonStringField(candidate, "reliability_analysis")
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    } ?: buildString {
        append("Recovered minimal structured process draft from malformed model output")
        if (violationCodes.isNotEmpty()) {
            append(": ")
            append(violationCodes.joinToString(", "))
        }
    }.take(220)
    val decision = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonStringField(candidate, "decision")
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { value -> value in allowedProcessCurationDecisions }
    } ?: fallbackDraft.decision
    val confidence = normalizedCandidates.firstNotNullOfOrNull { candidate ->
        extractJsonDoubleField(candidate, "confidence")?.coerceIn(0.0, 1.0)
    } ?: 0.58

    return StructuredProcessDraftResult(
        processEnum = processEnum,
        semanticDescription = semanticDescription,
        processName = optimizedBusinessProcess
            ?.getStringOrNull("process_name")
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallbackDraft.processName,
        acceptanceCriteria = acceptanceCriteria,
        stages = stages,
        optimizedProcessTrace = optimizedProcessTrace,
        diffSummary = diffSummary,
        reliabilityAnalysis = reliabilityAnalysis,
        decision = decision,
        confidence = confidence,
        traceBundle = traceBundle
    )
}

private fun extractProcessTraceLines(raw: String): List<String> {
    return runCatching {
        JsonParser.parseString(raw).asJsonObject.getFlexibleStringList("optimized_process_trace")
    }.getOrElse {
        extractJsonStringField(raw, "optimized_process_trace")
            ?.let(::sanitizeExtractedScalar)
            ?.lineSequence()
            ?.map { line -> line.trim() }
            ?.filter { line -> line.isNotBlank() }
            ?.toList()
            ?: emptyList()
    }
}

private fun sanitizeExtractedScalar(raw: String): String {
    return raw
        .replace("\\r", " ")
        .replace("\\n", "\n")
        .replace("\r", " ")
        .trim()
        .trim('"')
}

private fun extractJsonStringField(raw: String, key: String): String? {
    val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
    return regex.find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::sanitizeExtractedScalar)
        ?.takeIf { value -> value.isNotBlank() }
}

private fun extractJsonDoubleField(raw: String, key: String): Double? {
    val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))")
    return regex.find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
}

private fun extractJsonObjectField(raw: String, key: String): JsonObject? {
    val keyToken = "\"$key\""
    val keyIndex = raw.indexOf(keyToken)
    if (keyIndex < 0) {
        return null
    }
    val colonIndex = raw.indexOf(':', keyIndex + keyToken.length)
    if (colonIndex < 0) {
        return null
    }
    val objectStart = raw.indexOf('{', colonIndex + 1)
    if (objectStart < 0) {
        return null
    }
    var depth = 0
    var inString = false
    var escaped = false
    var index = objectStart
    while (index < raw.length) {
        val char = raw[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return runCatching { JsonParser.parseString(raw.substring(objectStart, index + 1)).asJsonObject }.getOrNull()
                    }
                }
            }
        }
        index += 1
    }
    return null
}

private fun encodeBusinessStagesJson(stages: List<BusinessProcessStage>): String {
    val array = JsonArray()
    stages.forEach { stage ->
        array.add(
            JsonObject().apply {
                addProperty("stage_id", stage.stageId)
                addProperty("stage_name_nl", stage.stageNameNl)
                addProperty("stage_goal_nl", stage.stageGoalNl)
                add("entry_signals", stage.entrySignals.toJsonArray())
                add("exit_signals", stage.exitSignals.toJsonArray())
                add("transition_conditions", stage.transitionConditions.toJsonArray())
            }
        )
    }
    return array.toString()
}

private fun List<String>.toJsonArray(): JsonArray {
    val array = JsonArray()
    forEach { value -> array.add(value) }
    return array
}

private fun extractCanonicalProcessPathIndex(assetName: String): Int? {
    return Regex("-path([1-9][0-9]*)-v[1-9][0-9]*$")
        .find(assetName.lowercase(Locale.US))
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private fun JsonObject.toBusinessProcessStage(): BusinessProcessStage? {
    val stageId = getStringOrNull("stage_id")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
    val stageName = getStringOrNull("stage_name_nl")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?: getStringOrNull("title")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
    val stageGoal = getStringOrNull("stage_goal_nl")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?: getStringOrNull("objective")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
    val entrySignals = getFlexibleStringList("entry_signals")
    val exitSignals = getFlexibleStringList("exit_signals")
    val transitionConditions = getFlexibleStringList("transition_conditions")
    val actions = getFlexibleStringList("actions")
    val acceptanceSignals = getFlexibleStringList("acceptance_signals")
    if (
        stageName == null &&
        stageGoal == null &&
        entrySignals.isEmpty() &&
        exitSignals.isEmpty() &&
        transitionConditions.isEmpty() &&
        actions.isEmpty() &&
        acceptanceSignals.isEmpty()
    ) {
        return null
    }
    return BusinessProcessStage(
        stageId = stageId ?: BusinessProcessStage().stageId,
        stageNameNl = stageName ?: actions.firstOrNull().orEmpty().ifBlank { "stage" },
        stageGoalNl = stageGoal ?: acceptanceSignals.firstOrNull().orEmpty(),
        entrySignals = if (entrySignals.isNotEmpty()) entrySignals.take(4) else actions.take(4),
        exitSignals = if (exitSignals.isNotEmpty()) exitSignals.take(4) else acceptanceSignals.take(4),
        transitionConditions = if (transitionConditions.isNotEmpty()) transitionConditions.take(4) else listOfNotNull(stageGoal).ifEmpty { actions.take(4) }
    )
}

private fun JsonObject.getJsonArrayOrEmpty(memberName: String): JsonArray {
    val value = get(memberName)
    return if (value != null && value.isJsonArray) {
        value.asJsonArray
    } else {
        JsonArray()
    }
}

private fun JsonObject.getJsonObjectOrNull(memberName: String): JsonObject? {
    val value = get(memberName)
    return if (value != null && value.isJsonObject) {
        value.asJsonObject
    } else {
        null
    }
}

private fun JsonObject.getStringOrNull(memberName: String): String? {
    val value = get(memberName) ?: return null
    return if (value.isJsonNull) null else runCatching { value.asString }.getOrNull()
}

private fun JsonObject.getDoubleOrNull(memberName: String): Double? {
    val value = get(memberName) ?: return null
    return if (value.isJsonNull) null else runCatching { value.asDouble }.getOrNull()
}

private fun JsonObject.getFlexibleStringList(memberName: String): List<String> {
    val value = get(memberName) ?: return emptyList()
    return when {
        value.isJsonArray -> value.asJsonArray.mapNotNull { item -> item.asStringOrNull()?.trim()?.takeIf { text -> text.isNotBlank() } }
        value.isJsonNull -> emptyList()
        else -> sanitizeExtractedScalar(runCatching { value.asString }.getOrNull().orEmpty())
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .toList()
    }
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private fun JsonElement.asStringOrNull(): String? {
    return runCatching { asString }.getOrNull()
}
