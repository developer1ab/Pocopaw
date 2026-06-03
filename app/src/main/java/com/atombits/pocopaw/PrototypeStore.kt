package com.atombits.pocopaw

import android.content.Context
import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime
import com.atombits.pocopaw.process.runtime.PreparedExecutionStart
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal data class ExecutionStartObservability(
    val routeDecisionRecord: RouteDecisionRecord,
    val routeDecisionHistory: List<RouteDecisionRecord>,
    val preferenceRecallDebugSnapshot: PreferenceRecallDebugSnapshot? = null,
    val preferenceMappingTrace: PreferenceSlotMappingTrace? = null
)

class PrototypeStore(context: Context) {

    companion object {
        private var hasPreparedFreshLaunchStateForProcess = false
        private val storeMutex = Mutex()
    }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TaskPhase::class.java, TaskPhaseStoreAdapter)
        .setPrettyPrinting()
        .create()
    private val storeFile = File(context.filesDir, "prototype_store.json")

    suspend fun load(): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val (store, needsNormalizationWrite) = readStoreWithNormalizationState()
            if (needsNormalizationWrite) {
                writeStore(store)
            }
            if (hasPreparedFreshLaunchStateForProcess) {
                return@withLock store
            }

            val preparedStore = prepareStoreForFreshLaunch(store)
            if (preparedStore != store) {
                writeStore(preparedStore)
            }
            hasPreparedFreshLaunchStateForProcess = true
            preparedStore
        }
    }

    suspend fun appendSemanticTurn(
        userMessageText: String,
        response: SemanticTurnResponse,
        searchEnhancementContext: SearchEnhancementTurnContext? = null,
        turnOptions: ChatTurnOptions? = null
    ): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val now = System.currentTimeMillis()
            val updatedStore = applySemanticTurn(
                store = readStore(),
                userMessageText = userMessageText,
                response = response,
                searchEnhancementContext = searchEnhancementContext,
                turnOptions = turnOptions,
                now = now
            )
            writeStore(updatedStore)
            updatedStore
        }
    }

    suspend fun markPreparingStarted(): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val now = System.currentTimeMillis()
            val store = readStore()
            val resolvedCurrentState = store.resolveCurrentState()
            val activeCandidateId = store.resolveCurrentActiveCandidateId()
            val persistLegacyCandidateMirrors = resolvedCurrentState.currentSemanticIntentState
                ?.candidateIntents
                ?.isNotEmpty() != true
            store.currentState = resolvedCurrentState.copy(
                stage = ConversationStage.PREPARING,
                workflowLane = WorkflowLane.PASSIVE,
                stageOwner = StageOwner.USER,
                lastPassiveUserTransitionIntent = PassiveUserTransitionIntent.SAME_TOPIC_PREPARE,
                lastPassiveUserProgressSignal = UserProgressSignal.ENTER_PREPARING,
                currentPhase = CurrentPhase.PREPARATION,
                userRequestSemantic = UserRequestSemantic.START_PREPARING,
                stageTransitionRecommendation = StageTransitionRecommendation.SHOULD_ENTER_PREPARING,
                lastProactiveOpportunitySignal = null,
                activeCandidateId = if (persistLegacyCandidateMirrors) activeCandidateId else null,
                currentDialogueCandidates = if (persistLegacyCandidateMirrors) store.currentDialogueCandidates() else emptyList(),
                awaitingApproval = false,
                executionStartedAt = null,
                pendingExecutionRecovery = null,
                legacyExecutionReviewForMigration = null,
                pendingProcessFeedbackDraft = null,
                lastUpdatedAt = now
            )
            store.syncIntentSliceIfPresent()
            writeStore(store)
            store
        }
    }

    suspend fun appendSystemMessage(
        content: String,
        stage: ConversationStage
    ): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val now = System.currentTimeMillis()
            val store = readStore()
            val effectiveStage = stage.normalized()
            store.messages.add(
                ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = content,
                    timestamp = now,
                    stage = effectiveStage
                )
            )
            val resolvedCurrentState = store.resolveCurrentState()
            store.currentState = resolvedCurrentState.copy(
                stage = effectiveStage,
                workflowLane = resolvedCurrentState.effectiveWorkflowLane(),
                stageOwner = resolvedCurrentState.effectiveStageOwner(),
                currentDialogueCandidates = store.currentDialogueCandidates(),
                lastUpdatedAt = now
            )
            store.syncConversationSliceIfPresent()
            store.syncIntentSliceIfPresent()
            writeStore(store)
            store
        }
    }

    suspend fun appendAssistantMessage(
        content: String,
        stage: ConversationStage,
        tokenUsage: TokenUsage? = null,
        reasoningContent: String? = null,
        turnOptions: ChatTurnOptions? = null
    ): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val now = System.currentTimeMillis()
            val store = readStore()
            val effectiveStage = stage.normalized()
            store.messages.add(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = content,
                    timestamp = now,
                    stage = effectiveStage,
                    tokenUsage = tokenUsage,
                    reasoningContent = reasoningContent,
                    turnOptions = turnOptions
                )
            )
            val resolvedCurrentState = store.resolveCurrentState()
            store.currentState = resolvedCurrentState.copy(
                stage = effectiveStage,
                workflowLane = resolvedCurrentState.effectiveWorkflowLane(),
                stageOwner = resolvedCurrentState.effectiveStageOwner(),
                currentDialogueCandidates = store.currentDialogueCandidates(),
                lastUpdatedAt = now
            )
            store.syncConversationSliceIfPresent()
            store.syncIntentSliceIfPresent()
            writeStore(store)
            store
        }
    }

    suspend fun updateSemanticRuntimePreferences(
        preferences: SemanticRuntimePreferences
    ): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val store = readStore()
            store.semanticRuntimePreferences = preferences
            store.syncIntentSliceIfPresent()
            writeStore(store)
            store
        }
    }

    @Deprecated(
        message = "Use startExecutionRuntime so started events and runtime state are written only by the execution bridge.",
        replaceWith = ReplaceWith("startExecutionRuntime(boundaryPacket, candidateId, summary)")
    )
    suspend fun markExecutionStarted(): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            readStore()
        }
    }

    suspend fun startExecutionRuntime(
        boundaryPacket: TaskExecutionBoundaryPacket,
        candidateId: String? = null,
        summary: String = "Execution engine started",
        routeInfo: String? = null,
        routeEntryType: ExecutionRouteEntryType? = null,
        processReuseContext: CandidateProcessReferenceContext? = null,
        preparedExecutionStart: PreparedExecutionStart? = null
    ): ExecutionStartOutcome = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val now = System.currentTimeMillis()
            val store = readStore()
            if (store.resolveCurrentExecutionRuntime() != null) {
                return@withLock ExecutionStartOutcome(
                    updatedStore = store,
                    started = false,
                    message = UiStrings.resolve(
                        R.string.execution_already_running,
                        "Execution is already running."
                    )
                )
            }
            val resolvedCandidateId = candidateId ?: store.resolveCurrentActiveCandidateId()
            val startResult = ExecutionRuntimeOrchestrator.start(
                candidateId = resolvedCandidateId,
                boundaryPacket = boundaryPacket,
                now = now,
                summary = summary,
                routeInfo = routeInfo,
                routeEntryType = routeEntryType
            )

            if (!startResult.accepted || startResult.boundaryPacket == null || startResult.executionResult == null || startResult.executionTrace == null) {
                return@withLock ExecutionStartOutcome(
                    updatedStore = store,
                    started = false,
                    message = startResult.rejectionReason ?: "Execution could not start."
                )
            }
            val startBoundaryPacket = startResult.boundaryPacket
            val resolvedReuseContext = processReuseContext ?: ProcessReuseRuntime.resolve(
                store = store,
                activeCandidate = store.resolveTaskFirstCandidate(),
                boundaryPacket = startBoundaryPacket,
                previousContext = store.resolveCurrentProcessReuseContext(),
                now = now
            )?.candidateContext
            val executionObservability = buildExecutionStartObservability(
                store = store,
                boundaryPacket = startBoundaryPacket,
                routeInfo = startResult.executionResult.routeInfo,
                routeEntryType = startResult.executionResult.routeEntryType,
                processReuseContext = resolvedReuseContext,
                traceId = startResult.executionTrace.traceId,
                now = now
            )

            store.executionEvents.add(
                ExecutionEvent(
                    candidateId = resolvedCandidateId,
                    phase = ExecutionEventPhase.STARTING,
                    lifecycleStatus = startResult.executionResult.lifecycleStatus,
                    summary = startResult.executionResult.summary,
                    keyInfo = startBoundaryPacket.toExecutionKeyInfo(
                        routeInfo = startResult.executionResult.routeInfo,
                        routeDecisionRecord = executionObservability.routeDecisionRecord,
                        routeDecisionHistory = executionObservability.routeDecisionHistory,
                        preferenceRecallDebugSnapshot = executionObservability.preferenceRecallDebugSnapshot,
                        preferenceMappingTrace = executionObservability.preferenceMappingTrace
                    ),
                    startedAt = now
                )
            )
            val executionRuntime = ExecutionRuntimeState(
                candidateId = resolvedCandidateId,
                taskId = startBoundaryPacket.taskId,
                taskUpdatedAt = startBoundaryPacket.taskUpdatedAt,
                capabilityId = startBoundaryPacket.capabilityId,
                processId = startBoundaryPacket.processId,
                verificationChecks = startBoundaryPacket.verificationChecks,
                executionResult = startResult.executionResult,
                executionTrace = startResult.executionTrace,
                routeDecisionRecord = executionObservability.routeDecisionRecord,
                preferenceRecallDebugSnapshot = executionObservability.preferenceRecallDebugSnapshot,
                preferenceMappingTrace = executionObservability.preferenceMappingTrace,
                startedAt = now,
                routeDecisionHistory = executionObservability.routeDecisionHistory
            )
            val resolvedProcessRuntime = projectProcessRuntimeState(
                executionRuntime = executionRuntime,
                boundaryPacket = startBoundaryPacket,
                reuseContext = resolvedReuseContext,
                previousRuntime = store.resolveCurrentProcessRuntime(),
                now = now
            )
            store.updateCurrentExecutionSession(
                preparedExecutionStart = preparedExecutionStart,
                executionRuntime = executionRuntime,
                boundaryPacket = startBoundaryPacket,
                processReuseContext = resolvedReuseContext,
                processRuntime = resolvedProcessRuntime,
                updatedAt = now
            )
            val resolvedCurrentState = store.resolveCurrentState()
            store.currentState = resolvedCurrentState.copy(
                stage = ConversationStage.EXECUTING,
                workflowLane = WorkflowLane.PASSIVE,
                stageOwner = StageOwner.USER,
                lastPassiveUserTransitionIntent = PassiveUserTransitionIntent.SAME_TOPIC_EXECUTE,
                lastPassiveUserProgressSignal = UserProgressSignal.ENTER_EXECUTING,
                currentPhase = CurrentPhase.EXECUTION,
                userRequestSemantic = UserRequestSemantic.START_EXECUTING,
                stageTransitionRecommendation = StageTransitionRecommendation.SHOULD_ENTER_EXECUTING,
                lastProactiveOpportunitySignal = null,
                currentSemanticIntentState = resolvedCurrentState.currentSemanticIntentState,
                currentDialogueCandidates = store.currentDialogueCandidates(),
                awaitingApproval = false,
                executionStartedAt = now,
                pendingExecutionRecovery = null,
                legacyExecutionReviewForMigration = null,
                pendingProcessFeedbackDraft = null,
                lastUpdatedAt = now
            )
            store.syncIntentSliceIfPresent()
            writeStore(store)
            ExecutionStartOutcome(
                updatedStore = store,
                started = true,
                message = startResult.executionResult.summary
            )
        }
    }

    suspend fun replaceStore(store: PrototypeStoreData): PrototypeStoreData = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            val normalizedStore = normalizeStore(migrateLegacySnapshotState(store))
            writeStore(normalizedStore)
            hasPreparedFreshLaunchStateForProcess = true
            normalizedStore
        }
    }

    private fun derivePostExecutionStage(): ConversationStage {
        return ConversationStage.ACCUMULATING
    }

    private fun buildExecutionSummary(activeCandidate: IntentCandidate?): String = buildString {
        append("Execution started locally")
        if (activeCandidate != null) {
            append(" for ")
            append(activeCandidate.anchoredLabel.ifBlank { "current candidate" })
        }
    }

    private fun resolveActiveCandidate(
        store: PrototypeStoreData,
        candidateId: String?
    ): IntentCandidate? {
        if (candidateId.isNullOrBlank()) {
            return store.resolveTaskFirstCandidate()
        }
        return store.snapshots.asReversed()
            .asSequence()
            .flatMap { it.candidates.asSequence() }
            .firstOrNull { it.id == candidateId }
            ?: store.resolveTaskFirstCandidate()
    }

    private fun readStore(): PrototypeStoreData {
        return readStoreWithNormalizationState().first
    }

    private fun readStoreWithNormalizationState(): Pair<PrototypeStoreData, Boolean> {
        if (!storeFile.exists()) {
            return PrototypeStoreData() to false
        }
        val raw = storeFile.readText()
        if (raw.isBlank()) {
            return PrototypeStoreData() to false
        }
        val parsedStore = parseStore(raw)
        val normalizedStore = normalizeStore(migrateLegacySnapshotState(parsedStore), raw)
        return normalizedStore to (normalizedStore != parsedStore)
    }

    private fun parseStore(raw: String): PrototypeStoreData {
        return runCatching {
            gson.fromJson(raw, PrototypeStoreData::class.java) ?: PrototypeStoreData()
        }.getOrDefault(PrototypeStoreData())
    }

    internal fun normalizeStore(store: PrototypeStoreData, raw: String? = null): PrototypeStoreData {
        return normalizePrototypeStoreData(store, raw)
    }

    private fun writeStore(store: PrototypeStoreData) {
        storeFile.writeText(gson.toJson(store))
    }
}

internal fun normalizePrototypeStoreData(store: PrototypeStoreData, raw: String? = null): PrototypeStoreData {
    store.hydrateLegacyRootsFromSlices()
    normalizeModelArtifacts(store)
    for (index in store.messages.indices) {
        val message = store.messages[index]
        store.messages[index] = message.copy(
            stage = message.stage?.normalized(),
            reasoningContent = normalizePersistedReasoningContent(message.reasoningContent)
        )
    }
    for (index in store.snapshots.indices) {
        val snapshot = store.snapshots[index]
        val workflowLane = snapshot.workflowLane ?: WorkflowLane.PASSIVE
        store.snapshots[index] = snapshot.copy(
            stage = snapshot.stage.normalized(),
            workflowLane = workflowLane,
            stageOwner = snapshot.stageOwner ?: defaultStageOwnerFor(workflowLane)
        )
    }
    for (index in store.executionEvents.indices) {
        val event = store.executionEvents[index]
        store.executionEvents[index] = event.copy(
            phase = resolveExecutionEventPhase(
                phase = runCatching { event.phase }.getOrNull(),
                lifecycleStatus = event.lifecycleStatus,
                summary = event.summary
            )
        )
    }
    val currentState = store.currentState
    val normalizedStage = currentState.stage.normalized()
    val workflowLane = currentState.workflowLane ?: WorkflowLane.PASSIVE
    store.latestCompletedProcessReviewContext = resolveNormalizeOnlyProcessReviewContext(
        currentProcessReviewContext = store.latestCompletedProcessReviewContext,
        legacyExecutionReviewContext = currentState.legacyExecutionReviewForMigration
    )
    store.currentState = currentState.copy(
        stage = normalizedStage,
        workflowLane = workflowLane,
        stageOwner = currentState.stageOwner ?: defaultStageOwnerFor(workflowLane),
        currentDialogueCandidates = currentState.currentDialogueCandidates,
        dormantHistoricalCandidates = currentState.dormantHistoricalCandidates,
        awaitingApproval = normalizedStage == ConversationStage.PREPARING && currentState.awaitingApproval,
        legacyExecutionReviewForMigration = null,
        executionStartedAt = if (normalizedStage == ConversationStage.EXECUTING) {
            currentState.executionStartedAt
        } else {
            null
        }
    )
    store.semanticRuntimePreferences = store.semanticRuntimePreferences ?: SemanticRuntimePreferences()
    store.memoryState = migrateLegacyPreferenceMemoryState(raw, store.memoryState ?: MemoryState())
    store.assistantOverlayState = store.assistantOverlayState ?: AssistantOverlayState()
    store.syncAllSlicesFromLegacy()
    if (normalizedStage != ConversationStage.EXECUTING) {
        store.updateCurrentExecutionSession(
            preparedExecutionStart = store.resolveCurrentPreparedExecutionStart(),
            executionRuntime = null,
            processReuseContext = store.resolveCurrentProcessReuseContext(),
            processRuntime = store.resolveCurrentProcessRuntime(),
            updatedAt = store.resolveCurrentProcessRuntime()?.updatedAt ?: System.currentTimeMillis()
        )
    }
    return repairHistoricalPreferenceBacklogState(repairHistoricalProcessExtractionState(store))
}

private fun migrateLegacyPreferenceMemoryState(
    raw: String?,
    currentMemoryState: MemoryState
): MemoryState {
    if (raw.isNullOrBlank()) {
        return currentMemoryState
    }
    val hasStructuredPreferenceData = currentMemoryState.structuredPreferenceMemory.facts.isNotEmpty() ||
        currentMemoryState.structuredPreferenceMemory.recentFacts.isNotEmpty() ||
        currentMemoryState.structuredPreferenceMemory.semanticChunks.isNotEmpty() ||
        currentMemoryState.interactionBiasMemory.preferredProcesses.isNotEmpty() ||
        currentMemoryState.interactionBiasMemory.preferredPages.isNotEmpty() ||
        currentMemoryState.interactionBiasMemory.preferredShortcuts.isNotEmpty()
    if (hasStructuredPreferenceData) {
        return currentMemoryState
    }
    val rootObject = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return currentMemoryState
    val legacyStateNodes = listOfNotNull(
        rootObject.getJsonObjectOrNull("memoryState"),
        rootObject.getJsonObjectOrNull("currentMemorySlice")?.getJsonObjectOrNull("memoryState")
    )
    if (legacyStateNodes.isEmpty()) {
        return currentMemoryState
    }
    val legacySummaryCards = buildList {
        legacyStateNodes.forEach { stateNode ->
            stateNode.getJsonArrayOrEmpty("preferenceSummaryCards").forEach { item ->
                item.asJsonObjectOrNull()?.toLegacyPreferenceSummaryCard()?.let(::add)
            }
        }
    }
    val legacyEvidenceRecords = buildList {
        legacyStateNodes.forEach { stateNode ->
            stateNode.getJsonArrayOrEmpty("preferenceEvidenceStore").forEach { item ->
                item.asJsonObjectOrNull()?.toLegacyPreferenceEvidenceRecord()?.let(::add)
            }
        }
    }
    return if (legacyEvidenceRecords.isEmpty() && legacySummaryCards.isEmpty()) {
        currentMemoryState
    } else {
        currentMemoryState.withProjectedPreferenceRecords(
            records = legacyEvidenceRecords,
            projectedSummaryCards = legacySummaryCards
        )
    }
}

private fun JsonObject.toLegacyPreferenceEvidenceRecord(): MemoryPreferenceEvidenceRecord? {
    val slotKey = getStringOrNull("slotKey")?.trim()
        ?: getStringOrNull("slot_key")?.trim()
        ?: return null
    val slotValue = getStringOrNull("slotValue")?.trim()
        ?: getStringOrNull("slot_value")?.trim()
        ?: return null
    if (slotKey.isBlank() || slotValue.isBlank()) {
        return null
    }
    return MemoryPreferenceEvidenceRecord(
        evidenceId = getStringOrNull("evidenceId")?.trim().takeUnless { it.isNullOrBlank() } ?: java.util.UUID.randomUUID().toString(),
        domain = getStringOrNull("domain")?.trim()?.takeIf { value -> value.isNotBlank() },
        anchorObject = getStringOrNull("anchorObject")?.trim()?.takeIf { value -> value.isNotBlank() },
        slotKey = slotKey,
        slotValue = slotValue,
        polarity = getStringOrNull("polarity")?.trim()?.ifBlank { "PREFER" } ?: "PREFER",
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        sourceType = getStringOrNull("sourceType")?.trim()?.ifBlank { "LEGACY_PREFERENCE_STORE" } ?: "LEGACY_PREFERENCE_STORE",
        sourceApp = getStringOrNull("sourceApp")?.trim()?.takeIf { value -> value.isNotBlank() },
        freshnessHint = getStringOrNull("freshnessHint")?.trim()?.ifBlank { "RECENT" } ?: "RECENT",
        lastObservedAt = getLongOrDefault("lastObservedAt", System.currentTimeMillis())
    )
}

private fun JsonObject.toLegacyPreferenceSummaryCard(): PreferenceSummaryCard? {
    val domain = getStringOrNull("domain")?.trim().orEmpty()
    val anchorObject = getStringOrNull("anchorObject")?.trim().orEmpty()
    val summary = getStringOrNull("summary")?.trim().orEmpty()
    if (domain.isBlank() || anchorObject.isBlank() || summary.isBlank()) {
        return null
    }
    return PreferenceSummaryCard(
        cardId = getStringOrNull("cardId")?.trim().takeUnless { it.isNullOrBlank() } ?: java.util.UUID.randomUUID().toString(),
        domain = domain,
        anchorObject = anchorObject,
        summary = summary,
        supportingSourceApps = getJsonArrayOrEmpty("supportingSourceApps").mapNotNull { item ->
            item.asStringOrNull()?.trim()?.takeIf { value -> value.isNotBlank() }
        },
        confidence = getDoubleOrDefault("confidence", 0.8).coerceIn(0.0, 1.0),
        lastObservedAt = getLongOrDefault("lastObservedAt", System.currentTimeMillis())
    )
}

private fun JsonObject.getJsonObjectOrNull(memberName: String): JsonObject? {
    val value = get(memberName) ?: return null
    return if (value.isJsonObject) value.asJsonObject else null
}

private fun JsonObject.getJsonArrayOrEmpty(memberName: String): JsonArray {
    val value = get(memberName) ?: return JsonArray()
    return if (value.isJsonArray) value.asJsonArray else JsonArray()
}

private fun JsonObject.getStringOrNull(memberName: String): String? {
    val value = get(memberName) ?: return null
    return if (value.isJsonNull) null else runCatching { value.asString }.getOrNull()
}

private fun JsonObject.getLongOrDefault(memberName: String, defaultValue: Long): Long {
    val value = get(memberName) ?: return defaultValue
    return if (value.isJsonNull) defaultValue else runCatching { value.asLong }.getOrDefault(defaultValue)
}

private fun JsonObject.getDoubleOrDefault(memberName: String, defaultValue: Double): Double {
    val value = get(memberName) ?: return defaultValue
    return if (value.isJsonNull) defaultValue else runCatching { value.asDouble }.getOrDefault(defaultValue)
}

private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private fun com.google.gson.JsonElement.asStringOrNull(): String? {
    return if (isJsonNull) null else runCatching { asString }.getOrNull()
}

internal fun normalizePersistedReasoningContent(reasoningContent: String?): String? {
    val normalized = reasoningContent
        ?.trim()
        ?.replace("\\n", "\n")
        ?.replace("\\t", "\t")
        ?.replace(Regex("(?:null){3,}\\s*$", RegexOption.IGNORE_CASE), "")
        ?.trimEnd()
        ?.takeIf { content -> content.isNotBlank() }
    return normalized
}

internal fun resolveNormalizeOnlyProcessReviewContext(
    currentProcessReviewContext: ProcessReviewContext?,
    legacyExecutionReviewContext: ProcessReviewContext?
): ProcessReviewContext? {
    return currentProcessReviewContext ?: legacyExecutionReviewContext
}

internal fun repairHistoricalPreferenceBacklogState(store: PrototypeStoreData): PrototypeStoreData {
    val memoryState = store.memoryState ?: MemoryState()
    if (memoryState.dialoguePreferenceBacklog.isNotEmpty() || memoryState.dialoguePreferenceExtractionRuntime.lastConsumedAt != null) {
        store.memoryState = memoryState
        return store
    }

    val snapshotBacklog = store.snapshots.asReversed().mapNotNull { snapshot ->
        val candidate = snapshot.candidates.firstOrNull { it.id == snapshot.activeCandidateId } ?: snapshot.candidates.firstOrNull()
        val assistantReply = snapshot.assistantReply.trim().ifBlank { return@mapNotNull null }
        DialoguePreferenceBacklogRecord(
            recordId = buildString {
                append("legacy_dialogue_")
                append(snapshot.persistedAt)
                append('_')
                append(candidate?.id ?: snapshot.activeCandidateId ?: "none")
            },
            sourceType = "DIALOGUE",
            candidateId = candidate?.id,
            anchorObject = candidate?.anchorObject,
            focusedObject = candidate?.focusedObject,
            action = candidate?.action,
            assistantReply = assistantReply,
            detailSlots = candidate?.detailSlots.orEmpty(),
            semanticSummary = assistantReply,
            lastObservedAt = snapshot.persistedAt
        )
    }
    val executionBacklog = store.executionEvents.asReversed().mapNotNull { event ->
        val factText = event.summary.trim().ifBlank { return@mapNotNull null }
        val candidate = store.snapshots.asReversed()
            .asSequence()
            .flatMap { snapshot -> snapshot.candidates.asSequence() }
            .firstOrNull { candidate -> event.candidateId != null && candidate.id == event.candidateId }
        DialoguePreferenceBacklogRecord(
            recordId = buildString {
                append("legacy_execution_")
                append(event.startedAt)
                append('_')
                append(event.candidateId ?: "none")
                append('_')
                append(event.phase.name)
            },
            sourceType = "EXECUTION",
            candidateId = candidate?.id ?: event.candidateId,
            anchorObject = candidate?.anchorObject,
            focusedObject = candidate?.focusedObject,
            action = candidate?.action,
            assistantReply = factText,
            detailSlots = candidate?.detailSlots.orEmpty(),
            semanticSummary = factText,
            lastObservedAt = event.startedAt
        )
    }
    val repairedBacklog = (snapshotBacklog + executionBacklog)
        .distinctBy { record -> record.recordId }
        .sortedByDescending { record -> record.lastObservedAt }
        .take(20)
    if (repairedBacklog.isEmpty()) {
        store.memoryState = memoryState
        return store
    }

    store.memoryState = memoryState.copy(dialoguePreferenceBacklog = repairedBacklog)
    return store
}

internal fun repairHistoricalProcessExtractionState(store: PrototypeStoreData): PrototypeStoreData {
    for (index in store.processExtractionRawMaterials.indices) {
        val rawMaterial = store.processExtractionRawMaterials[index]
        val repairedProcessAction = rawMaterial.processAction
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: rawMaterial.resolvedProcessAction()
        store.processExtractionRawMaterials[index] = rawMaterial.copy(
            processAction = repairedProcessAction
        )
    }

    for (index in store.readyProcessAssets.indices) {
        val asset = store.readyProcessAssets[index]
        val repairedProcessAction = asset.processAction
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: asset.resolvedProcessAction()
        store.readyProcessAssets[index] = asset.copy(
            processAction = repairedProcessAction,
            pathIndex = asset.pathIndex.coerceAtLeast(1)
        )
    }

    for (index in store.processShortcutAtlas.indices) {
        val shortcut = store.processShortcutAtlas[index]
        val repairedProcessAction = shortcut.processAction
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: shortcut.resolvedProcessAction()
        val repairedPathIndex = shortcut.pathIndex.coerceAtLeast(1)
        val repairedShortcutId = shortcut.shortcutId.takeIf { value -> value.isNotBlank() }
            ?: buildProcessShortcutId(
                appScope = shortcut.appScope,
                processAction = repairedProcessAction,
                pathIndex = repairedPathIndex,
                version = shortcut.version
            )
        store.processShortcutAtlas[index] = shortcut.copy(
            shortcutId = repairedShortcutId,
            processAction = repairedProcessAction,
            pathIndex = repairedPathIndex
        )
    }

    return store
}

private data class NormalizedSemanticTurn(
    val stage: ConversationStage,
    val userProgressSignal: UserProgressSignal,
    val transitionIntent: PassiveUserTransitionIntent,
    val currentPhase: CurrentPhase,
    val userRequestSemantic: UserRequestSemantic,
    val stageTransitionRecommendation: StageTransitionRecommendation,
    val semanticIntentState: SemanticIntentState?
)

private fun UserRequestSemantic.toRequiredCurrentPhase(): CurrentPhase = when (this) {
    UserRequestSemantic.START_ACCUMULATING -> CurrentPhase.ACCUMULATION
    UserRequestSemantic.START_PREPARING -> CurrentPhase.PREPARATION
    UserRequestSemantic.START_EXECUTING -> CurrentPhase.EXECUTION
}

private fun normalizeSemanticTurnForExecution(
    responseStage: ConversationStage,
    currentPhase: CurrentPhase?,
    userRequestSemantic: UserRequestSemantic?,
    stageTransitionRecommendation: StageTransitionRecommendation?,
    userProgressSignal: UserProgressSignal,
    transitionIntent: PassiveUserTransitionIntent?,
    semanticIntentState: SemanticIntentState?
): NormalizedSemanticTurn {
    val resolvedTransitionIntent = transitionIntent ?: userProgressSignal.toTransitionIntent()
    val resolvedUserRequestSemantic = userRequestSemantic ?: userProgressSignal.toUserRequestSemantic()
    val resolvedCurrentPhase = if (userRequestSemantic != null) {
        resolvedUserRequestSemantic.toRequiredCurrentPhase()
    } else {
        currentPhase ?: responseStage.toCurrentPhase()
    }
    val resolvedStageTransitionRecommendation = stageTransitionRecommendation ?: userProgressSignal.toStageTransitionRecommendation()
    val resolvedStage = if (userRequestSemantic != null) {
        resolvedCurrentPhase.toConversationStage()
    } else {
        responseStage
    }
    val resolvedSemanticIntentState = semanticIntentState?.copy(
        currentPhase = resolvedCurrentPhase,
        userRequestSemantic = resolvedUserRequestSemantic,
        stageTransitionRecommendation = resolvedStageTransitionRecommendation
    )
    return NormalizedSemanticTurn(
        stage = resolvedStage,
        userProgressSignal = userProgressSignal,
        transitionIntent = resolvedTransitionIntent,
        currentPhase = resolvedCurrentPhase,
        userRequestSemantic = resolvedUserRequestSemantic,
        stageTransitionRecommendation = resolvedStageTransitionRecommendation,
        semanticIntentState = resolvedSemanticIntentState
    )
}

fun applySemanticTurn(
    store: PrototypeStoreData,
    userMessageText: String,
    response: SemanticTurnResponse,
    searchEnhancementContext: SearchEnhancementTurnContext? = null,
    turnOptions: ChatTurnOptions? = null,
    now: Long = System.currentTimeMillis()
): PrototypeStoreData {
    val normalizedSemanticTurn = normalizeSemanticTurnForExecution(
        responseStage = response.stage.normalized(),
        currentPhase = response.currentPhase,
        userRequestSemantic = response.userRequestSemantic,
        stageTransitionRecommendation = response.stageTransitionRecommendation,
        userProgressSignal = response.userProgressSignal,
        transitionIntent = response.passiveUserTransitionIntent,
        semanticIntentState = response.semanticIntentState
    )
    val resolvedCurrentState = store.resolveCurrentState()
    val updatedStore = PrototypeStoreData(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        currentConversationSlice = store.currentConversationSlice,
        currentIntentSlice = store.currentIntentSlice,
        executionEvents = store.executionEvents.toMutableList(),
        currentExecutionRuntime = if (normalizedSemanticTurn.stage == ConversationStage.EXECUTING) {
            store.resolveCurrentExecutionRuntime()
        } else {
            null
        },
        currentExecutionSlice = store.currentExecutionSlice,
        executionTraces = store.executionTraces.toMutableList(),
        currentAssetSlice = store.currentAssetSlice,
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        lastProcessCurationSummary = store.lastProcessCurationSummary,
        latestCompletedProcessReviewContext = store.latestCompletedProcessReviewContext,
        currentProcessRuntime = store.resolveCurrentProcessRuntime(),
        semanticRuntimePreferences = store.semanticRuntimePreferences ?: SemanticRuntimePreferences(),
        currentState = resolvedCurrentState,
        currentMemorySlice = store.currentMemorySlice,
        memoryState = store.memoryState ?: MemoryState(),
        earningsHubState = store.earningsHubState,
        processLearningMaterials = store.processLearningMaterials.toMutableList(),
        assistantOverlayState = store.assistantOverlayState
    )
    val effectiveStage = normalizedSemanticTurn.stage
    val effectiveUserProgressSignal = normalizedSemanticTurn.userProgressSignal
    val effectiveTransitionIntent = normalizedSemanticTurn.transitionIntent
    val effectiveCurrentPhase = normalizedSemanticTurn.currentPhase
    val effectiveUserRequestSemantic = normalizedSemanticTurn.userRequestSemantic
    val effectiveStageTransitionRecommendation = normalizedSemanticTurn.stageTransitionRecommendation
    val resolvedSemanticIntentState = normalizedSemanticTurn.semanticIntentState
    val responseTaskDraft = response.taskDraft
    val effectiveResponseCandidates = response.candidates
    val effectiveResponseActiveCandidateId = response.activeCandidateId
    val responseHasTaskAuthority = responseTaskDraft != null ||
        effectiveResponseCandidates.isNotEmpty() ||
        effectiveResponseActiveCandidateId != null ||
        resolvedSemanticIntentState?.candidateIntents?.isNotEmpty() == true
    val shouldPreserveExistingTaskAuthority = !responseHasTaskAuthority && effectiveUserProgressSignal.keepsExecutionPreparation()
    val resolvedTaskDraft = when {
        effectiveUserProgressSignal == UserProgressSignal.SWITCH_CONTEXT -> responseTaskDraft
        responseTaskDraft != null -> responseTaskDraft
        shouldPreserveExistingTaskAuthority && resolvedCurrentState.currentTaskDraft != null -> resolvedCurrentState.currentTaskDraft
        else -> null
    }
    val resolvedTaskRecord = when {
        resolvedTaskDraft != null -> resolvedTaskDraft.resolveTaskRecord(
            previousTaskRecord = resolvedCurrentState.currentTaskRecord,
            sourceTurnId = "turn_$now",
            stage = effectiveStage,
            semanticSummary = response.semanticSummary,
            now = now,
            allowReuseTaskId = effectiveUserProgressSignal != UserProgressSignal.SWITCH_CONTEXT
        )

        effectiveUserProgressSignal == UserProgressSignal.SWITCH_CONTEXT -> null

        !responseHasTaskAuthority && !shouldPreserveExistingTaskAuthority -> null

        else -> resolvedCurrentState.currentTaskRecord?.copy(
            sourceTurnId = "turn_$now",
            phase = effectiveStage.toTaskPhase(),
            reasonSummary = response.semanticSummary.takeIf { value -> value.isNotBlank() }
                ?: resolvedCurrentState.currentTaskRecord.reasonSummary,
            updatedAt = now
        )
    }
    val enrichedTaskRecord = resolvedTaskRecord?.let { taskRecord ->
        taskRecord.copy(
            capabilityStack = taskRecord.capabilityStack
                ?: inferCapabilityStackFromCapabilityId(taskRecord.capabilityId, taskRecord.capabilityDomain)
        )
    }
    val currentActiveCandidateId = store.resolveCurrentActiveCandidateId()
    val preservedLiveCandidates = if (effectiveUserProgressSignal == UserProgressSignal.SWITCH_CONTEXT) {
        emptyList()
    } else {
        store.currentDialogueCandidates()
    }
    val refreshedActiveCandidates = selectRefreshedActiveCandidates(
        candidates = effectiveResponseCandidates,
        requestedActiveCandidateId = effectiveResponseActiveCandidateId,
        currentActiveCandidateId = currentActiveCandidateId,
        liveCandidates = preservedLiveCandidates,
        dormantHistoricalCandidates = resolvedCurrentState.dormantHistoricalCandidates,
        explicitlyMentionedCandidateIds = buildExplicitlyMentionedCandidateIds(userMessageText, store)
    )
    val resolvedActiveCandidateId = resolveCurrentTurnActiveCandidateId(
        requestedCandidateId = effectiveResponseActiveCandidateId,
        candidates = refreshedActiveCandidates,
        existingActiveCandidateId = currentActiveCandidateId,
        existingLiveCandidates = preservedLiveCandidates,
        signal = effectiveUserProgressSignal
    )
    val mergedLiveCandidates = mergeLiveCandidates(
        existingLiveCandidates = preservedLiveCandidates,
        refreshedCandidates = refreshedActiveCandidates,
        activeCandidateId = resolvedActiveCandidateId,
        signal = effectiveUserProgressSignal
    )
    val hasFreshTaskPayload = response.taskDraft != null
    val resolvedTaskExecutionBoundaryPacket = if (effectiveUserProgressSignal.keepsExecutionPreparation()) {
        when {
            enrichedTaskRecord != null -> enrichedTaskRecord.toTaskExecutionBoundaryPacket()
            hasFreshTaskPayload -> null
            else -> resolvedCurrentState.currentTaskRecord?.toTaskExecutionBoundaryPacket()
        }
    } else {
        null
    }
    val resolvedProcessReuseContext = retainPreparedProcessReuseContext(
        previousContext = store.resolveCurrentProcessReuseContext(),
        previousBoundaryPacket = store.resolveCurrentExecutionBoundaryPacket(),
        nextBoundaryPacket = resolvedTaskExecutionBoundaryPacket,
        keepsExecutionPreparation = effectiveUserProgressSignal.keepsExecutionPreparation()
    )
    val resolvedPreparedExecutionStart = resolveSemanticPreparedExecutionStart(
        previousPreparedExecutionStart = store.resolveCurrentPreparedExecutionStart(),
        resolvedTaskExecutionBoundaryPacket = resolvedTaskExecutionBoundaryPacket,
        resolvedProcessReuseContext = resolvedProcessReuseContext,
        keepsExecutionPreparation = effectiveUserProgressSignal.keepsExecutionPreparation()
    )
    val dormantHistoricalCandidates = if (effectiveUserProgressSignal == UserProgressSignal.SWITCH_CONTEXT) {
        mergeDormantHistoricalCandidates(
            resolvedCurrentState.dormantHistoricalCandidates,
            store.currentDialogueCandidates()
        )
    } else {
        resolvedCurrentState.dormantHistoricalCandidates.filterNot { candidate ->
            mergedLiveCandidates.any { it.id == candidate.id }
        }
    }.filterNot { candidate ->
        mergedLiveCandidates.any { it.id == candidate.id }
    }
    updatedStore.messages.add(
        ChatMessage(
            role = MessageRole.USER,
            content = userMessageText,
            timestamp = now,
            stage = updatedStore.currentState.stage.normalized()
        )
    )
    updatedStore.messages.add(
        ChatMessage(
            role = MessageRole.ASSISTANT,
            content = response.assistantReply,
            timestamp = now + 1,
            stage = effectiveStage,
            goalAndPlanContent = searchEnhancementContext?.goalAndPlanContent,
            searchDetailContent = searchEnhancementContext?.searchDetailContent,
            searchSummaryContent = response.searchSummaryContent,
            tokenUsage = response.tokenUsage,
            reasoningContent = response.reasoningContent.takeIf {
                turnOptions?.thinkingEnabled != false || searchEnhancementContext != null
            },
            searchAttribution = searchEnhancementContext?.searchAttribution,
            turnOptions = turnOptions
        )
    )
    updatedStore.snapshots.add(
        TurnSnapshot(
            stage = effectiveStage,
            workflowLane = response.workflowLane,
            stageOwner = response.stageOwner,
            passiveUserTransitionIntent = effectiveTransitionIntent,
            passiveUserProgressSignal = effectiveUserProgressSignal,
            currentPhase = effectiveCurrentPhase,
            userRequestSemantic = effectiveUserRequestSemantic,
            stageTransitionRecommendation = effectiveStageTransitionRecommendation,
            proactiveOpportunitySignal = response.proactiveOpportunitySignal,
            activeCandidateId = resolvedActiveCandidateId,
            assistantReply = response.assistantReply,
            candidates = effectiveResponseCandidates,
            semanticIntentState = resolvedSemanticIntentState,
            taskDraft = resolvedTaskDraft,
            taskRecord = enrichedTaskRecord,
            goalAndPlanContent = searchEnhancementContext?.goalAndPlanContent,
            searchQueries = searchEnhancementContext?.searchQueries.orEmpty(),
            searchScope = searchEnhancementContext?.searchScope.orEmpty(),
            searchPlanRequestPayload = searchEnhancementContext?.searchPlanRequestPayload,
            searchPlanResponsePayload = searchEnhancementContext?.searchPlanResponsePayload,
            semanticRequestPayload = response.requestPayload,
            semanticResponsePayload = response.responsePayload,
            persistedAt = now
        )
    )
    val preservedPendingExecutionRecovery = when {
        effectiveUserProgressSignal == UserProgressSignal.SWITCH_CONTEXT -> null
        effectiveStage == ConversationStage.EXECUTING -> null
        else -> resolvedCurrentState.pendingExecutionRecovery?.let { recovery ->
            if (recovery.awaitingUserGuidance) {
                recovery.copy(
                    awaitingUserGuidance = false,
                    guidanceReceivedAt = now
                )
            } else {
                recovery
            }
        }
    }
    val preservedPendingProcessFeedbackDraft = when {
        response.userProgressSignal == UserProgressSignal.SWITCH_CONTEXT -> null
        effectiveStage == ConversationStage.EXECUTING -> null
        else -> resolvedCurrentState.pendingProcessFeedbackDraft
    }
    val persistLegacyCandidateMirrors = effectiveResponseCandidates.isNotEmpty()
    if (effectiveStage == ConversationStage.EXECUTING) {
        updatedStore.currentState = LocalConversationState(
            stage = effectiveStage,
            workflowLane = response.workflowLane,
            stageOwner = response.stageOwner,
            lastPassiveUserTransitionIntent = effectiveTransitionIntent,
            lastPassiveUserProgressSignal = effectiveUserProgressSignal,
            currentPhase = effectiveCurrentPhase,
            userRequestSemantic = effectiveUserRequestSemantic,
            stageTransitionRecommendation = effectiveStageTransitionRecommendation,
            lastProactiveOpportunitySignal = response.proactiveOpportunitySignal,
            currentSemanticIntentState = null,
            currentTaskDraft = resolvedTaskDraft,
            currentTaskRecord = enrichedTaskRecord,
            activeCandidateId = if (persistLegacyCandidateMirrors) resolvedActiveCandidateId else null,
            currentDialogueCandidates = if (persistLegacyCandidateMirrors) mergedLiveCandidates else emptyList(),
            dormantHistoricalCandidates = dormantHistoricalCandidates,
            awaitingApproval = false,
            executionStartedAt = resolvedCurrentState.executionStartedAt,
            pendingExecutionRecovery = preservedPendingExecutionRecovery,
            legacyExecutionReviewForMigration = null,
            pendingProcessFeedbackDraft = preservedPendingProcessFeedbackDraft,
            lastUpdatedAt = now
        )
    } else {
        updatedStore.currentState = LocalConversationState(
            stage = effectiveStage,
            workflowLane = response.workflowLane,
            stageOwner = response.stageOwner,
            lastPassiveUserTransitionIntent = effectiveTransitionIntent,
            lastPassiveUserProgressSignal = effectiveUserProgressSignal,
            currentPhase = effectiveCurrentPhase,
            userRequestSemantic = effectiveUserRequestSemantic,
            stageTransitionRecommendation = effectiveStageTransitionRecommendation,
            lastProactiveOpportunitySignal = response.proactiveOpportunitySignal,
            currentSemanticIntentState = null,
            currentTaskDraft = resolvedTaskDraft,
            currentTaskRecord = enrichedTaskRecord,
            activeCandidateId = if (persistLegacyCandidateMirrors) resolvedActiveCandidateId else null,
            currentDialogueCandidates = if (persistLegacyCandidateMirrors) mergedLiveCandidates else emptyList(),
            dormantHistoricalCandidates = dormantHistoricalCandidates,
            awaitingApproval = false,
            executionStartedAt = null,
            pendingExecutionRecovery = preservedPendingExecutionRecovery,
            legacyExecutionReviewForMigration = null,
            pendingProcessFeedbackDraft = preservedPendingProcessFeedbackDraft,
            lastUpdatedAt = now
        )
    }
    val resolvedActiveCandidate = resolveActiveCandidateForTurn(updatedStore, resolvedActiveCandidateId)
    updatedStore.memoryState = MemoryOrchestrator.recordPassiveTurn(
        store = updatedStore,
        activeCandidate = resolvedActiveCandidate,
        semanticSummary = response.semanticSummary,
        userMessage = userMessageText,
        assistantReply = response.assistantReply,
        now = now
    )
    updatedStore.updateCurrentExecutionSession(
        preparedExecutionStart = resolvedPreparedExecutionStart,
        executionRuntime = if (effectiveStage == ConversationStage.EXECUTING) {
            updatedStore.resolveCurrentExecutionRuntime()
        } else {
            null
        },
        processReuseContext = resolvedProcessReuseContext,
        processRuntime = updatedStore.resolveCurrentProcessRuntime()
    )
    updatedStore.syncAllSlicesFromLegacy()
    return updatedStore
}

private fun resolveSemanticPreparedExecutionStart(
    previousPreparedExecutionStart: PreparedExecutionStart?,
    resolvedTaskExecutionBoundaryPacket: TaskExecutionBoundaryPacket?,
    resolvedProcessReuseContext: CandidateProcessReferenceContext?,
    keepsExecutionPreparation: Boolean
): PreparedExecutionStart? {
    if (!keepsExecutionPreparation) {
        return null
    }
    val resolvedBoundaryPacket = resolvedTaskExecutionBoundaryPacket ?: return null
    val samePreparedTask = previousPreparedExecutionStart?.taskId == resolvedBoundaryPacket.taskId &&
        previousPreparedExecutionStart.taskUpdatedAt == resolvedBoundaryPacket.taskUpdatedAt
    val guidanceLayer = resolvedProcessReuseContext?.let { context ->
        ProcessReuseRuntime.buildGuidanceLayer(
            taskIntent = context.taskIntent,
            boundaryPacket = resolvedBoundaryPacket
        )
    } ?: previousPreparedExecutionStart?.guidanceLayer?.takeIf { samePreparedTask }
    val routeInfo = buildList {
        add("route=task_record")
        extractCanonicalAppScope(resolvedBoundaryPacket.capabilityId)?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("app_scope=$value")
        }
        resolvedBoundaryPacket.processId
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> add("route_process=$value") }
    }.joinToString(" | ")
    return previousPreparedExecutionStart?.takeIf { samePreparedTask }?.copy(
        capabilityId = null,
        processId = null,
        routeDecisionSource = null,
        routeReasonSummary = null,
        verificationChecks = resolvedBoundaryPacket.verificationChecks,
        processReuseContext = resolvedProcessReuseContext,
        guidanceLayer = guidanceLayer,
        routeInfo = routeInfo.ifBlank { previousPreparedExecutionStart.routeInfo.orEmpty() }
    ) ?: PreparedExecutionStart(
        taskId = resolvedBoundaryPacket.taskId,
        taskUpdatedAt = resolvedBoundaryPacket.taskUpdatedAt,
        verificationChecks = resolvedBoundaryPacket.verificationChecks,
        processReuseContext = resolvedProcessReuseContext,
        guidanceLayer = guidanceLayer,
        launchDirective = ExecutionLaunchDirective.START_AUTOMATION,
        startSummary = UiStrings.resolve(
            R.string.execution_prepared_from_current_task,
            "Execution prepared from current task."
        ),
        routeInfo = routeInfo.ifBlank { "route=task_record" },
        routeEntryType = null
    )
}

fun prepareStoreForFreshLaunch(store: PrototypeStoreData): PrototypeStoreData {
    val currentState = store.resolveCurrentState()
    val hasLiveDialogueState = store.resolveCurrentActiveCandidateId() != null ||
        store.currentDialogueCandidates().isNotEmpty() ||
        resolveTaskFirstExecutionBoundaryPacket(currentState) != null ||
        currentState.stage.normalized() != ConversationStage.ACCUMULATING

    if (!hasLiveDialogueState) {
        return store
    }

    return PrototypeStoreData(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        executionEvents = store.executionEvents.toMutableList(),
        currentExecutionRuntime = null,
        executionTraces = store.executionTraces.toMutableList(),
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        semanticRuntimePreferences = store.semanticRuntimePreferences ?: SemanticRuntimePreferences(),
        memoryState = store.memoryState ?: MemoryState(),
        earningsHubState = store.earningsHubState,
        processLearningMaterials = store.processLearningMaterials.toMutableList(),
        assistantOverlayState = store.assistantOverlayState,
        currentState = currentState.copy(
            stage = ConversationStage.ACCUMULATING,
            workflowLane = WorkflowLane.PASSIVE,
            stageOwner = StageOwner.USER,
            lastPassiveUserTransitionIntent = null,
            lastPassiveUserProgressSignal = null,
            currentPhase = CurrentPhase.ACCUMULATION,
            userRequestSemantic = null,
            stageTransitionRecommendation = null,
            lastProactiveOpportunitySignal = null,
            activeCandidateId = null,
            currentDialogueCandidates = emptyList(),
            dormantHistoricalCandidates = mergeDormantHistoricalCandidates(
                currentState.dormantHistoricalCandidates,
                store.currentDialogueCandidates()
            ),
            awaitingApproval = false,
            currentSemanticIntentState = null,
            currentTaskDraft = null,
            currentTaskRecord = null,
            executionStartedAt = null,
            pendingExecutionRecovery = null,
            pendingProactiveDeliveryPlan = null,
            lastDeliveredProactivePlanFingerprint = null,
            lastDeliveredProactivePlanAt = null,
            proactiveDeliveryCooldownUntil = null
        )
    ).syncAllSlicesFromLegacy()
}

private fun mergeLiveCandidates(
    existingLiveCandidates: List<IntentCandidate>,
    refreshedCandidates: List<IntentCandidate>,
    activeCandidateId: String?,
    signal: UserProgressSignal
): List<IntentCandidate> {
    val baseCandidates = if (signal == UserProgressSignal.SWITCH_CONTEXT) {
        refreshedCandidates
    } else if (refreshedCandidates.isEmpty()) {
        existingLiveCandidates
    } else {
        val refreshedIds = refreshedCandidates.map { it.id }.toSet()
        refreshedCandidates + existingLiveCandidates.filterNot { it.id in refreshedIds }
    }
    return orderCandidatesWithActiveFirst(baseCandidates, activeCandidateId)
}

private fun SemanticIntentState.activeIntentShadowCandidate(): IntentCandidate? {
    val activeIntent = candidateIntents.firstOrNull { candidate ->
        candidate.intentId == activeIntentId
    } ?: candidateIntents.firstOrNull()
    return activeIntent?.toShadowIntentCandidate()
}

private fun SemanticIntentCandidate.toShadowIntentCandidate(): IntentCandidate {
    return IntentCandidate(
        id = intentId,
        anchorObject = anchorObject,
        focusedObject = focusedObject,
        action = canonicalAction?.name?.lowercase(Locale.US) ?: rawActionLabel,
        readiness = toShadowCandidateReadiness(),
        confidence = confidence,
        evidence = reasonSummary.orEmpty(),
        rationale = reasonSummary.orEmpty(),
        detailSlots = detailSlots,
        missingRequiredSlots = emptyList(),
        canStartExecution = canStartExecution
    )
}

private fun SemanticIntentCandidate.toShadowCandidateReadiness(): CandidateReadiness {
    return when {
        canStartExecution || readiness == SemanticIntentReadiness.READY_FOR_EXECUTION -> CandidateReadiness.READY_TO_START
        readiness == SemanticIntentReadiness.READY_FOR_CONFIRMATION ||
            readiness == SemanticIntentReadiness.READY_FOR_OFFER -> CandidateReadiness.READY_TO_PREPARE
        readiness == SemanticIntentReadiness.CONVERGING -> CandidateReadiness.ACCUMULATING
        else -> CandidateReadiness.EMERGING
    }
}

private fun mergeDormantHistoricalCandidates(
    existingDormantCandidates: List<IntentCandidate>,
    demotedCandidates: List<IntentCandidate>
): List<IntentCandidate> {
    if (demotedCandidates.isEmpty()) {
        return existingDormantCandidates
    }

    val merged = linkedMapOf<String, IntentCandidate>()
    existingDormantCandidates.forEach { merged[it.id] = it }
    demotedCandidates.forEach { merged[it.id] = it }
    return merged.values.toList()
}

private fun orderCandidatesWithActiveFirst(
    candidates: List<IntentCandidate>,
    activeCandidateId: String?
): List<IntentCandidate> {
    if (activeCandidateId.isNullOrBlank()) {
        return candidates
    }
    val activeCandidate = candidates.firstOrNull { it.id == activeCandidateId } ?: return candidates
    return buildList {
        add(activeCandidate)
        addAll(candidates.filterNot { it.id == activeCandidateId })
    }
}

private fun resolveCurrentTurnActiveCandidateId(
    requestedCandidateId: String?,
    candidates: List<IntentCandidate>,
    existingActiveCandidateId: String?,
    existingLiveCandidates: List<IntentCandidate>,
    signal: UserProgressSignal
): String? {
    if (signal == UserProgressSignal.SWITCH_CONTEXT && candidates.isEmpty()) {
        return null
    }
    if (candidates.isEmpty()) {
        return existingActiveCandidateId?.takeIf { activeId ->
            existingLiveCandidates.any { it.id == activeId }
        } ?: existingLiveCandidates.firstOrNull()?.id
    }
    return requestedCandidateId?.takeIf { candidateId ->
        candidateId.isNotBlank() && candidates.any { it.id == candidateId }
    } ?: candidates.first().id
}

private fun selectRefreshedActiveCandidates(
    candidates: List<IntentCandidate>,
    requestedActiveCandidateId: String?,
    currentActiveCandidateId: String?,
    liveCandidates: List<IntentCandidate>,
    dormantHistoricalCandidates: List<IntentCandidate>,
    explicitlyMentionedCandidateIds: Set<String>
): List<IntentCandidate> {
    if (candidates.isEmpty()) {
        return emptyList()
    }
    val activeCandidate = requestedActiveCandidateId?.let { candidateId ->
        candidates.firstOrNull { it.id == candidateId }
    } ?: candidates.firstOrNull()
    val selectedCandidate = activeCandidate ?: return emptyList()
    val knownLocalCandidateIds = (liveCandidates + dormantHistoricalCandidates).map { it.id }.toSet()
    val isKnownLocalCandidate = selectedCandidate.id in knownLocalCandidateIds
    val isCurrentActiveCandidate = selectedCandidate.id == currentActiveCandidateId
    val isExplicitlyMentionedReactivation = selectedCandidate.id in explicitlyMentionedCandidateIds
    return if (!isKnownLocalCandidate || isCurrentActiveCandidate || isExplicitlyMentionedReactivation) {
        listOf(selectedCandidate)
    } else {
        emptyList()
    }
}

private fun buildExplicitlyMentionedCandidateIds(
    userMessageText: String,
    store: PrototypeStoreData
): Set<String> {
    val activeCandidateId = store.resolveCurrentActiveCandidateId()
    val resolvedCurrentState = store.resolveCurrentState()
    return buildSet {
        addAll(
            collectExplicitReMentionedCandidateIds(
                userMessageText,
                store.currentDialogueCandidates().filterNot { it.id == activeCandidateId }
            )
        )
        addAll(
            collectExplicitReMentionedCandidateIds(
                userMessageText,
                resolvedCurrentState.dormantHistoricalCandidates
            )
        )
    }
}

private fun resolveActiveCandidateForTurn(
    store: PrototypeStoreData,
    candidateId: String?
): IntentCandidate? {
    if (candidateId.isNullOrBlank()) {
        return store.resolveTaskFirstCandidate()
    }
    return store.currentDialogueCandidates().firstOrNull { it.id == candidateId }
        ?: store.resolveTaskFirstCandidate()
}

private fun buildExecutionSummaryForTurn(activeCandidate: IntentCandidate?): String = buildString {
    append("Execution started locally")
    if (activeCandidate != null) {
        append(" for ")
        append(activeCandidate.anchoredLabel.ifBlank { "current candidate" })
    }
}

private object TaskPhaseStoreAdapter : JsonSerializer<TaskPhase>, JsonDeserializer<TaskPhase> {
    override fun serialize(
        src: TaskPhase?,
        typeOfSrc: java.lang.reflect.Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        return JsonPrimitive(src?.name ?: TaskPhase.ACCUMULATING.name)
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: java.lang.reflect.Type?,
        context: JsonDeserializationContext?
    ): TaskPhase {
        return TaskPhase.fromPersistedValue(json?.asString) ?: TaskPhase.ACCUMULATING
    }
}

internal fun buildExecutionStartObservability(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket,
    routeInfo: String?,
    routeEntryType: ExecutionRouteEntryType?,
    processReuseContext: CandidateProcessReferenceContext?,
    traceId: String,
    now: Long = System.currentTimeMillis()
): ExecutionStartObservability {
    val routeFacts = routeInfo.orEmpty()
        .split('|')
        .mapNotNull { rawSegment ->
            val segment = rawSegment.trim()
            val separatorIndex = segment.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex == segment.lastIndex) {
                null
            } else {
                segment.substring(0, separatorIndex).trim() to segment.substring(separatorIndex + 1).trim()
            }
        }
        .toMap()
    val selectedRoute = routeFacts["route"].orEmpty().ifBlank {
        routeEntryType?.name?.lowercase(Locale.US) ?: "task_record"
    }
    val routeLabel = when (routeEntryType) {
        ExecutionRouteEntryType.PROCESS_REFERENCE -> "Reusable process"
        ExecutionRouteEntryType.SHORTCUT -> "Reusable shortcut"
        ExecutionRouteEntryType.SYSTEM_INTENT -> "System intent"
        ExecutionRouteEntryType.EXPLORATORY -> "Exploratory runtime"
        null -> when (selectedRoute) {
            "task_record" -> "Task record"
            else -> selectedRoute.replace('_', ' ')
                .split(' ')
                .joinToString(" ") { token -> token.replaceFirstChar { character -> character.uppercase(Locale.US) } }
        }
    }
    val candidateSummary = listOfNotNull(
        routeFacts["preferred_candidate"],
        routeFacts["selection"],
        processReuseContext?.referenceSummaryLines?.firstOrNull { line ->
            line.startsWith("preferred_candidate=") || line.startsWith("selection=")
        }?.substringAfter('=', missingDelimiterValue = "")?.trim()?.takeIf { value -> value.isNotBlank() }
    ).firstOrNull { value -> value.isNotBlank() }
    val reasonSummary = listOfNotNull(
        routeFacts["route_why"],
        routeFacts["route_guidance"],
        processReuseContext?.whySelected?.firstOrNull { value -> value.isNotBlank() },
        boundaryPacket.reasonSummary
    ).firstOrNull { value -> value.isNotBlank() }
    val existingRouteHistory = resolveRouteDecisionHistorySeed(
        store = store,
        taskId = boundaryPacket.taskId
    )
    val startingEvents = store.resolveExecutionEvents().filter { event ->
        resolveExecutionEventPhase(event.phase, event.lifecycleStatus, event.summary) == ExecutionEventPhase.STARTING
    }
    val sameTaskAttemptCount = startingEvents.count { event ->
        event.keyInfo?.contains("task=${boundaryPacket.taskId}") == true
    }
    val fallbackAttemptCount = if (sameTaskAttemptCount > 0) {
        sameTaskAttemptCount
    } else {
        startingEvents.size
    }
    val attemptIndex = if (existingRouteHistory.isNotEmpty()) {
        existingRouteHistory.maxOf { record -> record.attemptIndex } + 1
    } else {
        fallbackAttemptCount + 1
    }
    val routeDecisionRecord = RouteDecisionRecord(
        taskId = boundaryPacket.taskId,
        traceId = traceId,
        attemptIndex = attemptIndex,
        selectedRoute = selectedRoute,
        routeLabel = routeLabel,
        routeEntryType = routeEntryType,
        selectedToolId = boundaryPacket.capabilityId,
        selectedProcessId = boundaryPacket.processId ?: routeFacts["route_process"],
        candidateSummary = candidateSummary,
        reasonSummary = reasonSummary,
        routeInfo = routeInfo,
        createdAt = now
    )
    return ExecutionStartObservability(
        routeDecisionRecord = routeDecisionRecord,
        routeDecisionHistory = (existingRouteHistory + routeDecisionRecord)
            .distinctBy { record -> record.decisionId }
            .sortedBy { record -> record.attemptIndex },
        preferenceRecallDebugSnapshot = processReuseContext?.taskIntent?.preferenceRecallDebugSnapshot,
        preferenceMappingTrace = processReuseContext?.taskIntent?.preferenceMappingTrace
    )
}

private fun resolveRouteDecisionHistorySeed(
    store: PrototypeStoreData,
    taskId: String
): List<RouteDecisionRecord> {
    return buildList {
        store.resolvePendingProcessRecoveryContext()
            ?.takeIf { recovery -> recovery.taskId == taskId }
            ?.routeDecisionHistory
            ?.let(::addAll)
        store.resolveCurrentState().pendingExecutionRecovery
            ?.takeIf { recovery -> recovery.taskId == taskId }
            ?.routeDecisionHistory
            ?.let(::addAll)
        store.resolveLatestCompletedProcessReviewContext()
            ?.takeIf { review -> review.taskId == taskId }
            ?.routeDecisionHistory
            ?.let(::addAll)
    }
        .distinctBy { record -> record.decisionId }
        .sortedBy { record -> record.attemptIndex }
}