package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessAssetEvent
import com.atombits.pocopaw.process.curation.ProcessCurationSummary
import com.atombits.pocopaw.process.reuse.CandidateProcessReference
import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.reuse.SemanticProcessReferenceSelectionResolver
import com.atombits.pocopaw.process.reuse.ProcessGuidanceLayer
import com.atombits.pocopaw.process.reuse.ProcessReferenceSelectionResolver
import com.atombits.pocopaw.process.reuse.ProcessReuseResolution
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime
import com.atombits.pocopaw.process.reuse.StructuredTaskIntent
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState
import com.atombits.pocopaw.process.runtime.ProcessReviewContext

@Suppress("FunctionName")
fun taskBoundaryPacket(
    boundaryTaskId: String? = null,
    task: TaskBoundaryTask,
    parameters: TaskBoundaryParameters = TaskBoundaryParameters(),
    route: TaskBoundaryBinding = TaskBoundaryBinding(),
    verification: TaskBoundaryVerification = TaskBoundaryVerification(),
    taskId: String = "test-task",
    taskUpdatedAt: Long = 1L
): TaskExecutionBoundaryPacket {
    val objective = task.focusedObject?.takeIf { value -> value.isNotBlank() } ?: task.anchorObject
    val action = ActionCode.fromCanonicalAction(task.canonicalAction)?.wireName ?: task.actionLabel
    val requiredSlots = verification.requiredCoreSlots.map(ExecutionCoreSlotKey::toDetailSlotKey)
    val canStartExecution = route.boundToolId != null || route.boundProcessId != null
    val resolvedSlots = parameters.coreSlots.mapNotNull { (slotKey, binding) ->
        slotKey.toDetailSlotKey().contractName.let { detailSlotKey -> detailSlotKey to binding.value }
    }.toMap()
    return boundaryPacket(
        taskId = boundaryTaskId ?: taskId,
        taskUpdatedAt = taskUpdatedAt,
        workflowLane = WorkflowLane.PASSIVE,
        objective = objective,
        action = action,
        plan = listOf(action, objective).filter { value -> value.isNotBlank() }.joinToString(" "),
        riskBoundary = verification.riskBoundary.orEmpty(),
        missingInformation = verification.missingFacts,
        canStartExecution = canStartExecution,
        requiredSlots = requiredSlots.map(DetailSlotKey::contractName),
        resolvedSlots = resolvedSlots,
        selectedToolId = route.boundToolId ?: route.boundAppScope ?: route.preferredAppScope,
        selectedAppScope = route.boundAppScope ?: route.preferredAppScope,
        selectedProcessId = route.boundProcessId,
        semanticProcessId = route.boundProcessId,
        reasonSummary = route.reasonSummary ?: verification.notes.firstOrNull().orEmpty(),
        confirmRequirement = verification.confirmRequirement,
        verificationChecks = verification.checks,
        executionGateFlag = when {
            requiredSlots.isNotEmpty() -> ExecutionGateFlag.BLOCKED
            verification.confirmRequirement != ConfirmRequirement.NONE -> ExecutionGateFlag.NEEDS_CONFIRM
            canStartExecution -> ExecutionGateFlag.READY_TO_START
            else -> ExecutionGateFlag.NO_EXECUTION
        }
    )
}

val TaskExecutionBoundaryPacket.route: TaskBoundaryBinding
    get() = TaskBoundaryBinding(
        preferredAppScope = extractCanonicalAppScope(capabilityId) ?: capabilityId,
        boundAppScope = extractCanonicalAppScope(capabilityId) ?: capabilityId,
        boundProcessId = processId,
        boundToolId = capabilityId,
        reasonSummary = reasonSummary,
        decisionSource = null
    )

val TaskExecutionBoundaryPacket.verification: TaskBoundaryVerification
    get() = TaskBoundaryVerification(
        requiredCoreSlots = requiredDetailSlots.mapNotNull(DetailSlotKey::toExecutionCoreSlotKey).toSet(),
        missingFacts = missingInformation,
        confirmRequirement = confirmRequirement,
        riskBoundary = riskSummary.takeIf { value -> value.isNotBlank() },
        notes = listOfNotNull(reasonSummary?.takeIf { value -> value.isNotBlank() }),
        checks = verificationChecks
    )

val TaskExecutionBoundaryPacket.parameters: TaskBoundaryParameters
    get() {
        val coreSlots = linkedMapOf<ExecutionCoreSlotKey, ExecutionSlotBinding>()
        targetLabel?.takeIf { value -> value.isNotBlank() }?.let { label ->
            coreSlots[ExecutionCoreSlotKey.TARGET_OBJECT] = ExecutionSlotBinding(
                value = label,
                source = ExecutionValueSource.COMPATIBILITY_BRIDGE,
                binding = SlotBindingStrength.HARD,
                updatedAt = taskUpdatedAt
            )
        }
        detailSlots.forEach { (slotKey, value) ->
            slotKey.toExecutionCoreSlotKey()?.let { coreSlotKey ->
                coreSlots[coreSlotKey] = ExecutionSlotBinding(
                    value = value,
                    source = ExecutionValueSource.COMPATIBILITY_BRIDGE,
                    binding = SlotBindingStrength.HARD,
                    updatedAt = taskUpdatedAt
                )
            }
        }
        return TaskBoundaryParameters(coreSlots = coreSlots)
    }

@Suppress("FunctionName")
fun LocalConversationState(
    currentExecutionBoundaryPacket: TaskExecutionBoundaryPacket,
    stage: ConversationStage = ConversationStage.ACCUMULATING,
    workflowLane: WorkflowLane? = WorkflowLane.PASSIVE,
    stageOwner: StageOwner? = StageOwner.USER,
    lastPassiveUserTransitionIntent: PassiveUserTransitionIntent? = null,
    lastPassiveUserProgressSignal: PassiveUserProgressSignal? = null,
    lastProactiveOpportunitySignal: ProactiveOpportunitySignal? = null,
    pendingProactiveDeliveryPlan: ProactiveDeliveryPlan? = null,
    lastDeliveredProactivePlanFingerprint: String? = null,
    lastDeliveredProactivePlanAt: Long? = null,
    proactiveDeliveryCooldownUntil: Long? = null,
    activeCandidateId: String? = null,
    currentDialogueCandidates: List<IntentCandidate> = emptyList(),
    dormantHistoricalCandidates: List<IntentCandidate> = emptyList(),
    awaitingApproval: Boolean = false,
    currentSemanticIntentState: SemanticIntentState? = null,
    currentTaskDraft: TaskDraft? = null,
    currentTaskRecord: TaskRecord? = null,
    executionStartedAt: Long? = null,
    pendingExecutionRecovery: ProcessRecoveryContext? = null,
    legacyExecutionReviewForMigration: ProcessReviewContext? = null,
    pendingProcessFeedbackDraft: PendingProcessFeedbackDraft? = null,
    lastUpdatedAt: Long = System.currentTimeMillis()
): com.atombits.pocopaw.LocalConversationState {
    return com.atombits.pocopaw.LocalConversationState(
        stage = stage,
        workflowLane = workflowLane,
        stageOwner = stageOwner,
        lastPassiveUserTransitionIntent = lastPassiveUserTransitionIntent,
        lastPassiveUserProgressSignal = lastPassiveUserProgressSignal,
        lastProactiveOpportunitySignal = lastProactiveOpportunitySignal,
        pendingProactiveDeliveryPlan = pendingProactiveDeliveryPlan,
        lastDeliveredProactivePlanFingerprint = lastDeliveredProactivePlanFingerprint,
        lastDeliveredProactivePlanAt = lastDeliveredProactivePlanAt,
        proactiveDeliveryCooldownUntil = proactiveDeliveryCooldownUntil,
        activeCandidateId = activeCandidateId,
        currentDialogueCandidates = currentDialogueCandidates,
        dormantHistoricalCandidates = dormantHistoricalCandidates,
        awaitingApproval = awaitingApproval,
        currentSemanticIntentState = currentSemanticIntentState,
        currentTaskDraft = currentTaskDraft,
        currentTaskRecord = currentTaskRecord ?: taskRecordFromBoundaryPacket(currentExecutionBoundaryPacket),
        executionStartedAt = executionStartedAt,
        pendingExecutionRecovery = pendingExecutionRecovery,
        legacyExecutionReviewForMigration = legacyExecutionReviewForMigration,
        pendingProcessFeedbackDraft = pendingProcessFeedbackDraft,
        lastUpdatedAt = lastUpdatedAt
    )
}

@Suppress("FunctionName")
fun PrototypeStoreData(
    currentExecutionBoundaryPacket: TaskExecutionBoundaryPacket,
    messages: MutableList<ChatMessage> = mutableListOf(),
    snapshots: MutableList<TurnSnapshot> = mutableListOf(),
    currentConversationSlice: ConversationSlice? = null,
    currentIntentSlice: IntentSlice? = null,
    executionEvents: MutableList<ExecutionEvent> = mutableListOf(),
    currentExecutionRuntime: ExecutionRuntimeState? = null,
    currentExecutionSlice: ExecutionSlice? = null,
    currentProcessReuseContext: CandidateProcessReferenceContext? = null,
    currentProcessRuntime: ProcessRuntimeState? = null,
    executionTraces: MutableList<ExecutionTrace> = mutableListOf(),
    currentAssetSlice: AssetSlice? = null,
    processExtractionRawMaterials: MutableList<CanonicalTraceRawMaterial> = mutableListOf(),
    readyProcessAssets: MutableList<ReadyProcessAsset> = mutableListOf(),
    processAssetEntries: MutableList<ProcessAssetEntry> = mutableListOf(),
    pageEvidenceAssets: MutableList<PageEvidenceAsset> = mutableListOf(),
    processShortcutAtlas: MutableList<ProcessShortcutCandidate> = mutableListOf(),
    processAssetEvents: MutableList<ProcessAssetEvent> = mutableListOf(),
    processExtractionConsumedIds: MutableList<String> = mutableListOf(),
    lastProcessCurationSummary: ProcessCurationSummary? = null,
    latestCompletedProcessReviewContext: ProcessReviewContext? = null,
    pendingProcessRecoveryContext: ProcessRecoveryContext? = null,
    semanticRuntimePreferences: SemanticRuntimePreferences? = SemanticRuntimePreferences(),
    currentState: LocalConversationState = com.atombits.pocopaw.LocalConversationState(),
    currentMemorySlice: MemorySlice? = null,
    memoryState: MemoryState? = MemoryState(),
    processLearningMaterials: MutableList<ProcessLearningMaterial> = mutableListOf()
): com.atombits.pocopaw.PrototypeStoreData {
    val resolvedExecutionSlice = currentExecutionSlice ?: if (
        currentExecutionRuntime != null || currentProcessReuseContext != null || currentProcessRuntime != null
    ) {
        ExecutionSlice(
            executionSession = ExecutionSession(
                executionRuntime = currentExecutionRuntime,
                processReuseContext = currentProcessReuseContext,
                processRuntime = currentProcessRuntime
            )
        )
    } else {
        null
    }
    return com.atombits.pocopaw.PrototypeStoreData(
        messages = messages,
        snapshots = snapshots,
        currentConversationSlice = currentConversationSlice,
        currentIntentSlice = currentIntentSlice,
        executionEvents = executionEvents,
        currentExecutionRuntime = currentExecutionRuntime,
        currentExecutionSlice = resolvedExecutionSlice,
        currentProcessRuntime = currentProcessRuntime,
        executionTraces = executionTraces,
        currentAssetSlice = currentAssetSlice,
        processExtractionRawMaterials = processExtractionRawMaterials,
        readyProcessAssets = readyProcessAssets,
        processAssetEntries = processAssetEntries,
        pageEvidenceAssets = pageEvidenceAssets,
        processShortcutAtlas = processShortcutAtlas,
        processAssetEvents = processAssetEvents,
        processExtractionConsumedIds = processExtractionConsumedIds,
        lastProcessCurationSummary = lastProcessCurationSummary,
        latestCompletedProcessReviewContext = latestCompletedProcessReviewContext,
        pendingProcessRecoveryContext = pendingProcessRecoveryContext,
        semanticRuntimePreferences = semanticRuntimePreferences,
        currentState = currentState.copy(
            currentTaskRecord = taskRecordFromBoundaryPacket(currentExecutionBoundaryPacket)
        ),
        currentMemorySlice = currentMemorySlice,
        memoryState = memoryState,
        processLearningMaterials = processLearningMaterials
    )
}

fun executionRuntimeStateWithBoundaryPacket(
    candidateId: String? = null,
    runtimeBoundaryPacket: TaskExecutionBoundaryPacket,
    executionResult: ExecutionResult,
    executionTrace: ExecutionTrace,
    executionBoundaryPacket: TaskExecutionBoundaryPacket? = runtimeBoundaryPacket,
    startedAt: Long = System.currentTimeMillis()
): com.atombits.pocopaw.ExecutionRuntimeState {
    return ExecutionRuntimeState(
        candidateId = candidateId,
        boundaryPacket = executionBoundaryPacket ?: runtimeBoundaryPacket,
        executionResult = executionResult,
        executionTrace = executionTrace,
        startedAt = startedAt
    )
}

fun normalizeTestExecutionBoundaryPacketForRuntime(
    boundaryPacket: TaskExecutionBoundaryPacket,
    store: PrototypeStoreData,
    availableCapabilities: List<ToolCapability>
): TaskExecutionBoundaryPacket {
    return normalizeExecutionBoundaryPacketForRuntime(
        boundaryPacket = boundaryPacket,
        store = store,
        availableCapabilities = availableCapabilities
    )
}

fun resolveTestExecutionBoundaryPacketWithReadyProcesses(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): TaskExecutionBoundaryPacket {
    return resolveExecutionBoundaryPacketWithReadyProcesses(
        store = store,
        boundaryPacket = boundaryPacket
    )
}

fun ProcessExtractionWritebackBridge.buildCanonicalTraceMaterial(
    boundaryPacket: TaskExecutionBoundaryPacket,
    executionResult: ExecutionResult,
    executionTrace: ExecutionTrace,
    executionBoundaryPacket: TaskExecutionBoundaryPacket? = boundaryPacket,
    now: Long = System.currentTimeMillis()
): CanonicalTraceRawMaterial? {
    return ProcessExtractionWritebackBridge.buildCanonicalTraceMaterial(
        boundaryPacket = executionBoundaryPacket ?: boundaryPacket,
        executionResult = executionResult,
        executionTrace = executionTrace,
        now = now
    )
}

fun adaptReferenceTextForRuntimeTarget(
    text: String?,
    runtime: ExecutionRuntimeState,
    preferredReference: CandidateProcessReference?
): String? {
    return adaptReferenceTextForRuntimeTarget(
        text = text,
        runtime = runtime,
        boundaryPacket = runtime.executionBoundaryPacket,
        preferredReference = preferredReference
    )
}

internal fun ProcessReuseRuntime.resolve(
    store: PrototypeStoreData,
    activeCandidate: IntentCandidate?,
    boundaryPacket: TaskExecutionBoundaryPacket? = null,
    executionBoundaryPacket: TaskExecutionBoundaryPacket? = boundaryPacket,
    previousContext: CandidateProcessReferenceContext? = null,
    selectionResolver: ProcessReferenceSelectionResolver = SemanticProcessReferenceSelectionResolver(),
    now: Long = System.currentTimeMillis()
): ProcessReuseResolution? {
    return ProcessReuseRuntime.resolve(
        store = store,
        activeCandidate = activeCandidate,
        boundaryPacket = executionBoundaryPacket ?: boundaryPacket,
        previousContext = previousContext,
        selectionResolver = selectionResolver,
        now = now
    )
}

internal fun ProcessReuseRuntime.buildGuidanceLayer(
    taskIntent: StructuredTaskIntent?,
    boundaryPacket: TaskExecutionBoundaryPacket? = null,
    executionBoundaryPacket: TaskExecutionBoundaryPacket? = null,
    now: Long = System.currentTimeMillis()
): ProcessGuidanceLayer? {
    return ProcessReuseRuntime.buildGuidanceLayer(
        taskIntent = taskIntent,
        boundaryPacket = executionBoundaryPacket ?: boundaryPacket,
        now = now
    )
}


