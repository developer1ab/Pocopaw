package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.CanonicalTraceRawMaterial
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ExecutionResult
import com.atombits.pocopaw.ExecutionRouteEntryType
import com.atombits.pocopaw.ExecutionTraceStep
import com.atombits.pocopaw.ProcessExemplarActionSummary
import com.atombits.pocopaw.ProcessFailurePattern
import com.atombits.pocopaw.ProcessLearningMaterial
import com.atombits.pocopaw.ProcessPageSemanticAnchor
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ReadyProcessAsset
import com.atombits.pocopaw.buildPayloadPreservingCanonicalTrace
import com.atombits.pocopaw.buildReadyProcessAssetDisplayName
import com.atombits.pocopaw.buildTemporaryProcessCandidateName
import com.atombits.pocopaw.canonicalizeProcessAppScope
import com.atombits.pocopaw.canonicalizeProcessAction
import com.atombits.pocopaw.deriveCanonicalProcessScope
import com.atombits.pocopaw.extractCanonicalAppScope
import com.atombits.pocopaw.extractCanonicalProcessAssetStepBinding
import com.atombits.pocopaw.inferCanonicalProcessAction
import com.atombits.pocopaw.inferCanonicalProcessDomain
import com.atombits.pocopaw.resolveCurrentPreparedExecutionStart
import com.atombits.pocopaw.resolveCurrentExecutionRuntime
import com.atombits.pocopaw.resolveCurrentProcessRuntime
import com.atombits.pocopaw.resolveExecutionBoundaryPacketFor
import com.atombits.pocopaw.sanitizeCanonicalProcessId
import com.atombits.pocopaw.toProcessSlotHints
import com.atombits.pocopaw.updateCurrentExecutionSession
import com.atombits.pocopaw.process.reuse.PrototypeStoreProcessAssetRepository
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.google.gson.Gson
import java.util.Locale

private typealias CanonicalTraceRawMaterialBinding = com.atombits.pocopaw.ProcessAssetStepBinding

internal object ProcessLearningWritebackBridge {

    private val gson = Gson()
    private const val readyWeightFailurePenalty = 0.1

    fun applyCompletedExecution(
        store: PrototypeStoreData,
        rawMaterial: CanonicalTraceRawMaterial?,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData {
        val runtimeState = store.resolveCurrentExecutionRuntime() ?: return store
        return applyCompletedExecution(
            store = store,
            outcomeRecord = ExecutionOutcomeRecord(
                executionRuntime = runtimeState,
                occurredAt = now
            ),
            rawMaterial = rawMaterial,
            now = now
        )
    }

    fun applyCompletedExecution(
        store: PrototypeStoreData,
        outcomeRecord: ExecutionOutcomeRecord,
        rawMaterial: CanonicalTraceRawMaterial?,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData {
        if (outcomeRecord.executionRuntime.executionResult.lifecycleStatus != ExecutionLifecycleStatus.COMPLETED) {
            return store
        }

        val learningInput = buildLearningInput(store, outcomeRecord, rawMaterial) ?: return store
        val createdEntry = if (learningInput.executedRoute == ExecutedLearningRoute.PROCESS_REFERENCE) {
            buildRecordedEntry(store, outcomeRecord, learningInput, now)
        } else {
            buildPendingTmpEntry(store, learningInput, now)
        }
        val summary = when (createdEntry.assetState) {
            ProcessAssetState.RECORDED -> "Recorded execution copy stored for ${createdEntry.assetName}."
            ProcessAssetState.PENDING -> "Pending tmp process asset stored for ${createdEntry.assetName}."
            else -> "Process asset stored for ${createdEntry.assetName}."
        }

        val assetStore = when (createdEntry.assetState) {
            ProcessAssetState.RECORDED -> PrototypeStoreProcessAssetRepository.saveRecorded(
                store = store,
                entry = createdEntry,
                summary = summary,
                now = now
            )

            else -> PrototypeStoreProcessAssetRepository.savePending(
                store = store,
                entry = createdEntry,
                summary = summary,
                now = now
            )
        }
        val learningMaterial = buildLearningMaterial(outcomeRecord, learningInput, rawMaterial, now)
        val nextLearningMaterials = assetStore.processLearningMaterials.toMutableList().apply {
            val existingIndex = indexOfFirst { material -> material.traceId == learningMaterial.traceId }
            if (existingIndex >= 0) {
                this[existingIndex] = learningMaterial
            } else {
                add(learningMaterial)
            }
        }
        return assetStore.copy(
            processLearningMaterials = nextLearningMaterials
        ).updateCurrentExecutionSession(
            preparedExecutionStart = assetStore.resolveCurrentPreparedExecutionStart(),
            executionRuntime = assetStore.resolveCurrentExecutionRuntime(),
            processReuseContext = null,
            processRuntime = assetStore.resolveCurrentProcessRuntime()?.copy(
                matchedReadyAssetId = createdEntry.id,
                matchedReadyAssetName = createdEntry.assetName,
                updatedAt = now
            ),
            updatedAt = now
        )
    }

    fun applyRecoveryTimeoutFailure(
        store: PrototypeStoreData,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData {
        val recoveryContext = store.pendingProcessRecoveryContext ?: return store
        val readyIndex = findReadyAssetEntryIndex(
            entries = store.processAssetEntries,
            recoveryContext = recoveryContext
        ) ?: return store
        val readyEntry = store.processAssetEntries[readyIndex]
        return PrototypeStoreProcessAssetRepository.adjustReadyWeight(
            store = store,
            entryId = readyEntry.id,
            delta = -readyWeightFailurePenalty,
            summary = com.atombits.pocopaw.UiStrings.resolve(
                com.atombits.pocopaw.R.string.process_learning_recovery_timeout_penalty,
                "Lowered ready weight for %1\$s after recovery guidance timed out.",
                readyEntry.assetName
            ),
            now = now
        )
    }

    fun applyGuidedRepeatFailure(
        store: PrototypeStoreData,
        now: Long = System.currentTimeMillis()
    ): PrototypeStoreData {
        val previousRecovery = store.pendingProcessRecoveryContext ?: return store
        val currentReadyId = store.resolveCurrentProcessRuntime()?.matchedReadyAssetId
        val currentReadyName = store.resolveCurrentProcessRuntime()?.matchedReadyAssetName
        if (!matchesRecoveryAnchor(previousRecovery, currentReadyId, currentReadyName)) {
            return store
        }
        val readyIndex = findReadyAssetEntryIndex(
            entries = store.processAssetEntries,
            recoveryContext = previousRecovery,
            fallbackAssetId = currentReadyId,
            fallbackAssetName = currentReadyName
        ) ?: return store
        val readyEntry = store.processAssetEntries[readyIndex]
        return PrototypeStoreProcessAssetRepository.markFailed(
            store = store,
            entryId = readyEntry.id,
            summary = com.atombits.pocopaw.UiStrings.resolve(
                com.atombits.pocopaw.R.string.process_learning_guided_retry_failed,
                "Marked %1\$s failed after guided retry also failed.",
                readyEntry.assetName
            ),
            reviewComment = store.resolveCurrentExecutionRuntime()?.executionResult?.summary
                ?.takeIf { summary -> summary.isNotBlank() }
                ?: readyEntry.reviewComment,
            now = now
        )
    }

    private fun buildLearningInput(
        store: PrototypeStoreData,
        outcomeRecord: ExecutionOutcomeRecord,
        rawMaterial: CanonicalTraceRawMaterial?
    ): LearningInput? {
        val runtimeState = outcomeRecord.executionRuntime
        val boundaryPacket = store.resolveExecutionBoundaryPacketFor(runtimeState)
        val objective = boundaryPacket?.objectiveSummary?.takeIf { value -> value.isNotBlank() }
            ?: runtimeState.executionResult.summary.takeIf { value -> value.isNotBlank() }
            ?: rawMaterial?.objective
            ?: return null
        val selectedToolId = runtimeState.executionResult.selectedToolId
            ?: boundaryPacket?.capabilityId
            ?: rawMaterial?.selectedToolId
        val appScope = extractCanonicalAppScope(boundaryPacket?.capabilityId)?.takeIf { value -> value.isNotBlank() }
            ?: extractCanonicalAppScope(
                selectedToolId
            )
            ?: resolveReadyAssetMatch(store, runtimeState.currentProcessAssetIdHint(), runtimeState.currentProcessAssetNameHint())?.readyAsset?.appScope
            ?: "generic"
        val matchedReady = resolveReadyAssetMatch(
            store = store,
            matchedAssetId = runtimeState.currentProcessAssetIdHint(),
            matchedAssetName = runtimeState.currentProcessAssetNameHint()
        )
        val routeProcessScope = sequenceOf(
            rawMaterial?.processId,
            boundaryPacket?.processId,
            outcomeRecord.executionRuntime.executionResult.selectedProcessId,
            outcomeRecord.executionRuntime.executionTrace.processId
        ).mapNotNull(::sanitizeCanonicalProcessId)
            .firstOrNull()
        val derivedProcessScope = deriveCanonicalProcessScope(
            rawProcessId = null,
            objective = objective,
            appScope = appScope,
            domain = inferCanonicalProcessDomain(
                processId = routeProcessScope.orEmpty(),
                objective = objective,
                selectedToolId = selectedToolId
            ),
            actionHint = boundaryPacket?.actionCode?.wireName,
            selectedToolId = selectedToolId
        )
        val currentCanonicalProcessScope = derivedProcessScope
            ?: routeProcessScope
            ?: return null
        val domain = inferCanonicalProcessDomain(
            processId = currentCanonicalProcessScope,
            objective = objective,
            selectedToolId = selectedToolId
        )
        val executedRoute = resolveExecutedLearningRoute(outcomeRecord.executionRuntime.executionResult)
        val currentCanonicalProcessAction = inferCanonicalProcessAction(
            processId = currentCanonicalProcessScope,
            objective = objective,
            domain = domain,
            actionHint = boundaryPacket?.actionCode?.wireName
        )
        val shouldRetainRecordedLineage = executedRoute == ExecutedLearningRoute.PROCESS_REFERENCE &&
            matchedReady != null &&
            readyAssetMatchesCurrentCanonicalIdentity(
                matchedReady = matchedReady,
                appScope = appScope,
                processAction = currentCanonicalProcessAction
            )
        val resolvedProcessScope = if (executedRoute == ExecutedLearningRoute.PROCESS_REFERENCE && !shouldRetainRecordedLineage) {
            currentCanonicalProcessScope
        } else {
            resolveLearningProcessScope(
                routeProcessScope = routeProcessScope,
                currentCanonicalProcessScope = currentCanonicalProcessScope,
                appScope = appScope
            )
        }
        val processAction = inferCanonicalProcessAction(
            processId = resolvedProcessScope,
            objective = objective,
            domain = domain,
            actionHint = boundaryPacket?.actionCode?.wireName
        )
        val traceLines = rawMaterial?.let(::buildPayloadPreservingCanonicalTrace)?.takeIf { steps -> steps.isNotEmpty() }
            ?: runtimeState.executionTrace.steps.map(::formatTraceStep)
        val acceptanceCriteria = collectAcceptanceCriteria(runtimeState.executionTrace.steps, rawMaterial)
        val semanticDescription = objective
        return LearningInput(
            processScope = resolvedProcessScope,
            objective = objective,
            semanticDescription = semanticDescription,
            appScope = appScope,
            domain = domain,
            processAction = processAction,
            traceLines = traceLines,
            acceptanceCriteria = acceptanceCriteria,
            route = if (shouldRetainRecordedLineage) ExecutedLearningRoute.PROCESS_REFERENCE else ExecutedLearningRoute.EXPLORATORY,
            matchedReady = matchedReady.takeIf { shouldRetainRecordedLineage }
        )
    }

    private fun buildPendingTmpEntry(
        store: PrototypeStoreData,
        input: LearningInput,
        now: Long
    ): ProcessAssetEntry {
        val tempIndex = store.processAssetEntries.count { entry ->
            entry.sourceType == ProcessAssetSourceType.TMP &&
                entry.domain.equals(input.domain, ignoreCase = true) &&
                entry.appScope.equals(input.appScope, ignoreCase = true) &&
                entry.processScope.equals(input.processScope, ignoreCase = true)
        } + 1
        val assetName = buildTemporaryProcessCandidateName(
            domain = input.domain,
            appScope = input.appScope,
            processAction = input.processAction,
            tempIndex = tempIndex
        )
        return ProcessAssetEntry(
            domain = input.domain,
            appScope = input.appScope,
            processScope = input.processScope,
            sourceType = ProcessAssetSourceType.TMP,
            assetName = assetName,
            revision = 1,
            semanticDescription = input.semanticDescription,
            assetState = ProcessAssetState.PENDING,
            assetUpdatedAt = now,
            taskExample = input.objective,
            planningTrace = input.traceLines.joinToString(separator = "\n"),
            stepCount = input.traceLines.size,
            successCount = 1,
            updatedAt = now,
            reviewComment = "",
            businessProcessName = assetName,
            businessAcceptanceCriteria = input.acceptanceCriteria,
            businessStagesJson = "",
            optimizedProcessTrace = input.traceLines,
            diffSummary = "Created from successful exploratory execution.",
            reliabilityAnalysis = "Pending curation.",
            reviewDecision = "pending_tmp",
            reviewConfidence = 0.0,
            readyWeight = 0.0,
            originAssetId = null
        )
    }

    private fun buildRecordedEntry(
        store: PrototypeStoreData,
        outcomeRecord: ExecutionOutcomeRecord,
        input: LearningInput,
        now: Long
    ): ProcessAssetEntry {
        val matchedReady = input.matchedReady
        val matchedEntry = matchedReady?.entry
        val matchedReadyAsset = matchedReady?.readyAsset
        val assetName = matchedEntry?.assetName
            ?: matchedReadyAsset?.let(::buildReadyProcessAssetDisplayName)
            ?: store.resolveCurrentProcessRuntime()?.matchedReadyAssetName
            ?: outcomeRecord.executionRuntime.currentProcessAssetNameHint()
            ?: buildTemporaryProcessCandidateName(
                domain = input.domain,
                appScope = input.appScope,
                processAction = input.processAction,
                tempIndex = 1
            )
        val acceptanceCriteria = matchedEntry?.businessAcceptanceCriteria
            ?.takeIf { criteria -> criteria.isNotEmpty() }
            ?: matchedReadyAsset?.acceptanceCriteria?.takeIf { criteria -> criteria.isNotEmpty() }
            ?: input.acceptanceCriteria
        val businessStagesJson = matchedEntry?.businessStagesJson
            ?.takeIf { json -> json.isNotBlank() }
            ?: matchedReadyAsset?.let(::serializeReadyAssetStages)
            ?: ""
        return ProcessAssetEntry(
            domain = matchedEntry?.domain ?: matchedReadyAsset?.domain ?: input.domain,
            appScope = matchedEntry?.appScope ?: matchedReadyAsset?.appScope ?: input.appScope,
            processScope = matchedEntry?.processScope ?: input.processScope,
            sourceType = ProcessAssetSourceType.RECORDED,
            assetName = assetName,
            revision = matchedEntry?.revision ?: matchedReadyAsset?.version ?: 1,
            semanticDescription = matchedEntry?.semanticDescription
                ?.takeIf { description -> description.isNotBlank() }
                ?: matchedReadyAsset?.semanticDescription?.takeIf { description -> description.isNotBlank() }
                ?: input.semanticDescription,
            assetState = ProcessAssetState.RECORDED,
            assetUpdatedAt = now,
            taskExample = input.objective,
            planningTrace = input.traceLines.joinToString(separator = "\n"),
            stepCount = input.traceLines.size,
            successCount = 1,
            updatedAt = now,
            reviewComment = "",
            businessProcessName = matchedEntry?.businessProcessName
                ?.takeIf { name -> name.isNotBlank() }
                ?: assetName,
            businessAcceptanceCriteria = acceptanceCriteria,
            businessStagesJson = businessStagesJson,
            optimizedProcessTrace = input.traceLines,
            diffSummary = "Created recorded execution copy from reusable process route.",
            reliabilityAnalysis = "Captured after successful reusable execution.",
            reviewDecision = "recorded_execution",
            reviewConfidence = 0.0,
            readyWeight = matchedEntry?.readyWeight ?: 0.0,
            originAssetId = matchedReady?.originAssetId
        )
    }

    private fun buildLearningMaterial(
        outcomeRecord: ExecutionOutcomeRecord,
        input: LearningInput,
        rawMaterial: CanonicalTraceRawMaterial?,
        now: Long
    ): ProcessLearningMaterial {
        val traceId = rawMaterial?.traceId ?: outcomeRecord.executionRuntime.executionTrace.traceId
        val traceSteps = outcomeRecord.executionRuntime.executionTrace.steps
        val bindings = resolveLearningBindings(rawMaterial)
        val pageSemanticAnchors = buildLearningPageSemanticAnchors(traceSteps, bindings)
        val exemplarActionSummaries = buildLearningExemplarActionSummaries(traceSteps, bindings)
        val verificationSignals = buildLearningVerificationSignals(
            acceptanceCriteria = input.acceptanceCriteria,
            traceSteps = traceSteps,
            bindings = bindings
        )
        return ProcessLearningMaterial(
            traceId = traceId,
            processId = input.processScope,
            appScope = input.appScope,
            domain = input.domain,
            objective = input.objective,
            stageTransitions = input.traceLines,
            pageSemanticAnchors = pageSemanticAnchors,
            verificationSignals = verificationSignals,
            exemplarActionSummaries = exemplarActionSummaries,
            failurePatterns = listOf(
                ProcessFailurePattern(
                    failureMode = "guided_repeat_failure_pending_learning",
                    recoveryHints = listOf("prefer current screenshot grounding over replay")
                )
            ).takeIf { input.executedRoute == ExecutedLearningRoute.PROCESS_REFERENCE }
                ?: emptyList(),
            finalBusinessOutcome = verificationSignals.lastOrNull() ?: input.acceptanceCriteria.lastOrNull(),
            lineageTraceIds = listOfNotNull(rawMaterial?.traceId, outcomeRecord.executionRuntime.executionTrace.traceId).distinct(),
            createdAt = now,
            processAction = input.processAction,
            slotEvidenceSnapshot = rawMaterial?.slotEvidenceSnapshot,
            slotHints = rawMaterial?.slotEvidenceSnapshot?.toProcessSlotHints().orEmpty()
        )
    }

    private fun resolveLearningBindings(
        rawMaterial: CanonicalTraceRawMaterial?
    ): List<CanonicalTraceRawMaterialBinding> {
        if (rawMaterial == null) {
            return emptyList()
        }
        return buildPayloadPreservingCanonicalTrace(rawMaterial)
            .mapNotNull(::extractCanonicalProcessAssetStepBinding)
    }

    private fun buildLearningPageSemanticAnchors(
        traceSteps: List<ExecutionTraceStep>,
        bindings: List<CanonicalTraceRawMaterialBinding>
    ): List<ProcessPageSemanticAnchor> {
        val traceAnchors = traceSteps.mapIndexedNotNull { index, step ->
            if (!stepHasLearningStructure(step)) {
                null
            } else {
                ProcessPageSemanticAnchor(
                    anchorId = "trace_anchor_${index + 1}",
                    stageName = step.stepType.takeIf { value -> value.isNotBlank() },
                    semanticRole = step.locatorHint ?: step.stepType.lowercase(),
                    pageSignature = step.pageSignature,
                    locatorHints = listOfNotNull(step.locatorHint),
                    verificationSignals = step.verificationSignals,
                    notes = listOfNotNull(step.note)
                )
            }
        }
        if (traceAnchors.isNotEmpty()) {
            return traceAnchors
        }
        return bindings.filter(::bindingHasLearningPayload).mapIndexed { index, binding ->
            ProcessPageSemanticAnchor(
                anchorId = "anchor_${index + 1}",
                semanticRole = binding.locatorHint ?: binding.stepType.lowercase(),
                pageSignature = binding.pageSignature,
                locatorHints = listOfNotNull(binding.locatorHint),
                verificationSignals = binding.verificationSignals,
                notes = listOfNotNull(binding.note)
            )
        }
    }

    private fun buildLearningExemplarActionSummaries(
        traceSteps: List<ExecutionTraceStep>,
        bindings: List<CanonicalTraceRawMaterialBinding>
    ): List<ProcessExemplarActionSummary> {
        val traceExemplars = traceSteps.mapIndexedNotNull { index, step ->
            val actionType = step.actionType ?: return@mapIndexedNotNull null
            ProcessExemplarActionSummary(
                exemplarId = "trace_exemplar_${index + 1}",
                stageName = step.stepType.takeIf { value -> value.isNotBlank() },
                stepType = step.stepType,
                actionType = actionType,
                outcomeSignal = step.verificationSignals.firstOrNull() ?: step.expectedOutcome,
                locatorHint = step.locatorHint,
                pageSignature = step.pageSignature,
                note = normalizeLearningNote(step.note, step.inputText, actionType)
            )
        }
        if (traceExemplars.isNotEmpty()) {
            return traceExemplars
        }
        return bindings.filter(::bindingHasLearningPayload).mapIndexed { index, binding ->
            ProcessExemplarActionSummary(
                exemplarId = "exemplar_${index + 1}",
                stepType = binding.stepType,
                actionType = binding.actionType,
                outcomeSignal = binding.verificationSignals.firstOrNull(),
                locatorHint = binding.locatorHint,
                pageSignature = binding.pageSignature,
                note = normalizeLearningNote(binding.note, binding.inputText, binding.actionType)
            )
        }
    }

    private fun buildLearningVerificationSignals(
        acceptanceCriteria: List<String>,
        traceSteps: List<ExecutionTraceStep>,
        bindings: List<CanonicalTraceRawMaterialBinding>
    ): List<String> {
        return (traceSteps.flatMap { step -> step.verificationSignals + listOf(step.expectedOutcome) } +
            acceptanceCriteria +
            bindings.flatMap { binding -> binding.verificationSignals })
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { signal -> signal == "runtime_started" }
            .distinct()
            .take(12)
    }

    private fun normalizeLearningNote(
        note: String?,
        inputText: String?,
        actionType: com.atombits.pocopaw.VisionActionType
    ): String? {
        if (actionType == com.atombits.pocopaw.VisionActionType.INPUT && !inputText.isNullOrBlank()) {
            return "Input '$inputText'"
        }
        return note
    }

    private fun bindingHasLearningPayload(binding: CanonicalTraceRawMaterialBinding): Boolean {
        return !binding.locatorHint.isNullOrBlank() ||
            binding.targetX != null ||
            binding.targetY != null ||
            !binding.inputText.isNullOrBlank() ||
            binding.swipeFromX != null ||
            binding.swipeFromY != null ||
            binding.swipeToX != null ||
            binding.swipeToY != null ||
            binding.actionDurationMs != null ||
            !binding.pageSignature.isNullOrBlank()
    }

    private fun stepHasLearningStructure(step: ExecutionTraceStep): Boolean {
        return step.actionType != null ||
            !step.locatorHint.isNullOrBlank() ||
            !step.pageSignature.isNullOrBlank() ||
            step.targetX != null ||
            step.targetY != null ||
            !step.inputText.isNullOrBlank() ||
            step.swipeFromX != null ||
            step.swipeFromY != null ||
            step.swipeToX != null ||
            step.swipeToY != null ||
            step.actionDurationMs != null ||
            step.verificationSignals.isNotEmpty() ||
            (!step.note.isNullOrBlank() && step.note.contains('='))
    }

    private fun collectAcceptanceCriteria(
        steps: List<ExecutionTraceStep>,
        rawMaterial: CanonicalTraceRawMaterial?
    ): List<String> {
        val verificationSignals = (steps.flatMap { step -> step.verificationSignals } +
            resolveLearningBindings(rawMaterial).flatMap { binding -> binding.verificationSignals })
            .map(String::trim)
            .filter(String::isNotBlank)
        if (verificationSignals.isNotEmpty()) {
            return verificationSignals.distinct().take(4)
        }
        return steps.mapNotNull { step ->
            step.expectedOutcome
                .trim()
                .takeIf { outcome -> outcome.isNotBlank() && outcome != "runtime_started" }
        }.distinct().take(4)
    }

    private fun resolveExecutedLearningRoute(executionResult: ExecutionResult): ExecutedLearningRoute {
        return when (executionResult.routeEntryType) {
            ExecutionRouteEntryType.PROCESS_REFERENCE -> ExecutedLearningRoute.PROCESS_REFERENCE
            ExecutionRouteEntryType.SHORTCUT,
            ExecutionRouteEntryType.EXPLORATORY,
            ExecutionRouteEntryType.SYSTEM_INTENT -> ExecutedLearningRoute.EXPLORATORY
            null -> {
                val route = executionResult.routeInfo.orEmpty()
                    .split('|')
                    .map { segment -> segment.trim() }
                    .firstOrNull { segment -> segment.startsWith("route=") }
                    ?.substringAfter('=')
                    ?.trim()
                when (route) {
                    "process_reference" -> ExecutedLearningRoute.PROCESS_REFERENCE
                    else -> ExecutedLearningRoute.EXPLORATORY
                }
            }
        }
    }

    private fun resolveReadyAssetMatch(
        store: PrototypeStoreData,
        matchedAssetId: String?,
        matchedAssetName: String?
    ): ReadyAssetMatch? {
        store.processAssetEntries.firstOrNull { entry ->
            entry.id == matchedAssetId && entry.assetState == ProcessAssetState.READY
        }?.let { entry ->
            return ReadyAssetMatch(entry = entry, readyAsset = null, originAssetId = entry.id)
        }

        val readyProcessId = matchedAssetId
            ?.takeIf { assetId -> assetId.startsWith("ready_asset:") }
            ?.substringAfter(':')
        val readyAsset = store.readyProcessAssets
            .asSequence()
            .filter { asset ->
                (readyProcessId != null && asset.processId == readyProcessId) ||
                    (!matchedAssetName.isNullOrBlank() && buildReadyProcessAssetDisplayName(asset) == matchedAssetName)
            }
            .sortedByDescending { asset -> asset.version }
            .firstOrNull()
        return readyAsset?.let { asset ->
            ReadyAssetMatch(
                entry = null,
                readyAsset = asset,
                originAssetId = "ready_asset:${asset.processId}"
            )
        }
    }

    private fun readyAssetMatchesCurrentCanonicalIdentity(
        matchedReady: ReadyAssetMatch,
        appScope: String,
        processAction: String
    ): Boolean {
        val matchedAppScope = matchedReady.entry?.appScope ?: matchedReady.readyAsset?.appScope ?: return false
        if (!matchedAppScope.equals(appScope, ignoreCase = true)) {
            return false
        }
        return resolveReadyAssetProcessAction(matchedReady) == processAction
    }

    private fun resolveReadyAssetProcessAction(matchedReady: ReadyAssetMatch): String? {
        matchedReady.readyAsset?.processAction
            ?.let { action -> canonicalizeProcessAction(action, matchedReady.readyAsset.domain) }
            ?.let { action -> return action }

        val processScope = matchedReady.entry?.processScope ?: matchedReady.readyAsset?.processId.orEmpty()
        val domain = matchedReady.entry?.domain ?: matchedReady.readyAsset?.domain
        val objective = matchedReady.entry?.semanticDescription
            ?.takeIf { description -> description.isNotBlank() }
            ?: matchedReady.readyAsset?.semanticDescription
            ?: matchedReady.entry?.taskExample.orEmpty()
        val actionHint = matchedReady.entry?.businessProcessName
            ?.takeIf { name -> name.isNotBlank() }
            ?: matchedReady.readyAsset?.processAction
        return inferCanonicalProcessAction(
            processId = processScope,
            objective = objective,
            domain = domain,
            actionHint = actionHint
        )
    }

    private fun resolveLearningProcessScope(
        routeProcessScope: String?,
        currentCanonicalProcessScope: String,
        appScope: String
    ): String {
        val sanitizedRouteProcessScope = sanitizeCanonicalProcessId(routeProcessScope)
            ?: return currentCanonicalProcessScope
        return if (looksCanonicalRouteProcessScope(sanitizedRouteProcessScope, appScope)) {
            sanitizedRouteProcessScope
        } else {
            currentCanonicalProcessScope
        }
    }

    private fun looksCanonicalRouteProcessScope(processScope: String, appScope: String): Boolean {
        val normalizedProcessScope = processScope.trim().lowercase(Locale.US)
        val normalizedAppScope = canonicalizeProcessAppScope(appScope)
        return normalizedProcessScope.contains("process") ||
            normalizedProcessScope.startsWith("${normalizedAppScope}_") ||
            normalizedProcessScope.startsWith("${normalizedAppScope}-") ||
            normalizedProcessScope.contains("_${normalizedAppScope}_") ||
            normalizedProcessScope.contains("-${normalizedAppScope}-")
    }

    private fun serializeReadyAssetStages(asset: ReadyProcessAsset): String {
        if (asset.stages.isEmpty()) {
            return ""
        }
        val stages = asset.stages.mapIndexed { index, stageName ->
            linkedMapOf(
                "stage_id" to "stage_${index + 1}",
                "stage_name_nl" to stageName,
                "stage_goal_nl" to "",
                "entry_signals" to emptyList<String>(),
                "exit_signals" to emptyList<String>(),
                "transition_conditions" to emptyList<String>()
            )
        }
        return gson.toJson(stages)
    }

    private fun formatTraceStep(step: ExecutionTraceStep): String {
        val baseStep = listOf(
            step.stepType,
            step.groundingMode,
            step.expectedOutcome,
            step.note
        ).filterNot { value -> value.isNullOrBlank() }.joinToString(separator = " | ")
        val structuredBinding = step.toCanonicalTraceBinding() ?: return baseStep
        return com.atombits.pocopaw.serializeCanonicalTraceLineWithBinding(baseStep, structuredBinding)
    }

    private fun ExecutionTraceStep.toCanonicalTraceBinding(): CanonicalTraceRawMaterialBinding? {
        val hasStructuredBinding = actionType != null ||
            locatorHint != null ||
            targetX != null ||
            targetY != null ||
            inputText != null ||
            swipeFromX != null ||
            swipeFromY != null ||
            swipeToX != null ||
            swipeToY != null ||
            actionDurationMs != null ||
            pageSignature != null ||
            verificationSignals.isNotEmpty()
        if (!hasStructuredBinding) {
            return null
        }
        return CanonicalTraceRawMaterialBinding(
            stepType = stepType,
            actionType = actionType ?: com.atombits.pocopaw.VisionActionType.TAP,
            locatorHint = locatorHint,
            targetX = targetX,
            targetY = targetY,
            inputText = inputText,
            swipeFromX = swipeFromX,
            swipeFromY = swipeFromY,
            swipeToX = swipeToX,
            swipeToY = swipeToY,
            actionDurationMs = actionDurationMs,
            verificationSignals = verificationSignals,
            pageSignature = pageSignature,
            note = note
        )
    }

    private fun findReadyAssetEntryIndex(
        entries: List<ProcessAssetEntry>,
        recoveryContext: ProcessRecoveryContext,
        fallbackAssetId: String? = null,
        fallbackAssetName: String? = null
    ): Int? {
        return entries.indices.firstOrNull { index ->
            val entry = entries[index]
            entry.assetState == ProcessAssetState.READY && (
                (!recoveryContext.processAssetEntryId.isNullOrBlank() && entry.id == recoveryContext.processAssetEntryId) ||
                    (!recoveryContext.processAssetName.isNullOrBlank() && entry.assetName == recoveryContext.processAssetName) ||
                    (!fallbackAssetId.isNullOrBlank() && entry.id == fallbackAssetId) ||
                    (!fallbackAssetName.isNullOrBlank() && entry.assetName == fallbackAssetName)
                )
        }
    }

    private fun matchesRecoveryAnchor(
        recoveryContext: ProcessRecoveryContext,
        currentReadyId: String?,
        currentReadyName: String?
    ): Boolean {
        return (!recoveryContext.processAssetEntryId.isNullOrBlank() && recoveryContext.processAssetEntryId == currentReadyId) ||
            (!recoveryContext.processAssetName.isNullOrBlank() && recoveryContext.processAssetName == currentReadyName)
    }

    private fun com.atombits.pocopaw.ExecutionRuntimeState.currentProcessAssetIdHint(): String? {
        return executionResult.routeInfo
            ?.split('|')
            ?.map { segment -> segment.trim() }
            ?.firstOrNull { segment -> segment.startsWith("route_record=") }
            ?.substringAfter('=')
            ?.takeUnless { value -> value == "-" }
            ?.let { routeRecord -> currentMatchedReadyAssetIdOrNull(routeRecord) }
            ?: storeProcessRuntimeIdHint(this)
    }

    private fun com.atombits.pocopaw.ExecutionRuntimeState.currentProcessAssetNameHint(): String? {
        return executionResult.routeInfo
            ?.split('|')
            ?.map { segment -> segment.trim() }
            ?.firstOrNull { segment -> segment.startsWith("route_record=") }
            ?.substringAfter('=')
            ?.takeUnless { value -> value == "-" }
    }

    private fun currentMatchedReadyAssetIdOrNull(routeRecord: String): String? {
        return if (routeRecord.startsWith("ready_asset:")) {
            routeRecord
        } else {
            null
        }
    }

    private fun storeProcessRuntimeIdHint(runtimeState: com.atombits.pocopaw.ExecutionRuntimeState): String? {
        return runtimeState.executionResult.selectedProcessId?.takeIf { value -> value.isNotBlank() }?.let { processId ->
            "ready_asset:$processId"
        }
    }

    private data class LearningInput(
        val processScope: String,
        val objective: String,
        val semanticDescription: String,
        val appScope: String,
        val domain: String,
        val processAction: String,
        val traceLines: List<String>,
        val acceptanceCriteria: List<String>,
        val route: ExecutedLearningRoute,
        val matchedReady: ReadyAssetMatch?
    ) {
        val executedRoute: ExecutedLearningRoute
            get() = route
    }

    private data class ReadyAssetMatch(
        val entry: ProcessAssetEntry?,
        val readyAsset: ReadyProcessAsset?,
        val originAssetId: String?
    )

    private enum class ExecutedLearningRoute {
        EXPLORATORY,
        PROCESS_REFERENCE
    }
}