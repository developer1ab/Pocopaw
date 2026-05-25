package com.atombits.pocopaw

internal object ConsoleTaskFormatter {

    fun formatStageDisplaySummary(state: LocalConversationState): String {
        val currentPhase = state.currentPhase ?: state.stage.toCurrentPhase()
        val userRequestSemantic = state.userRequestSemantic
            ?: state.lastPassiveUserProgressSignal?.toUserRequestSemantic()
        val stageTransitionRecommendation = state.stageTransitionRecommendation
            ?: state.lastPassiveUserProgressSignal?.toStageTransitionRecommendation()
        return buildList {
            add("current_phase: ${currentPhase.name}")
            add("user_request_semantic: ${userRequestSemantic?.name ?: "null"}")
            add("stage_transition_recommendation: ${stageTransitionRecommendation?.name ?: "null"}")
            add("next_gate: ${formatNextGate(currentPhase, userRequestSemantic, stageTransitionRecommendation)}")
            add(
                "debug: workflow_lane=${state.effectiveWorkflowLane().name}; " +
                    "stage_owner=${state.effectiveStageOwner().name}; " +
                    "proactive_opportunity_signal=${state.lastProactiveOpportunitySignal?.name ?: "null"}"
            )
        }.joinToString("\n")
    }

    fun formatStageControlSummary(state: LocalConversationState): String {
        val currentPhase = state.currentPhase ?: state.stage.toCurrentPhase()
        val userRequestSemantic = state.userRequestSemantic
            ?: state.lastPassiveUserProgressSignal?.toUserRequestSemantic()
        val stageTransitionRecommendation = state.stageTransitionRecommendation
            ?: state.lastPassiveUserProgressSignal?.toStageTransitionRecommendation()
        val proactiveSignal = state.lastProactiveOpportunitySignal?.name ?: "null"
        return buildList {
            add("current_phase: ${currentPhase.name}")
            add("user_request_semantic: ${userRequestSemantic?.name ?: "null"}")
            add("stage_transition_recommendation: ${stageTransitionRecommendation?.name ?: "null"}")
            add("workflow_lane: ${state.effectiveWorkflowLane().name}")
            add("stage_owner: ${state.effectiveStageOwner().name}")
            add("proactive_opportunity_signal: $proactiveSignal")
        }.joinToString("\n")
    }

    fun formatCandidateSummary(candidate: IntentCandidate?): String {
        if (candidate == null) {
            return "No active candidate yet. Keep chatting and accumulating."
        }
        return buildList {
            add("anchor_object: ${candidate.anchorObject.ifBlank { "-" }}")
            add("focused_object: ${candidate.focusedObject.ifBlank { "-" }}")
            add("action_intent: ${candidate.action.ifBlank { "-" }}")
            add("candidate_status: ${candidate.readiness.canonicalStatusValue()}")
            add("confidence: ${"%.2f".format(candidate.confidence)}")
            add("detail_slots: ${formatDetailSlots(candidate.detailSlots)}")
            add("required_detail_slots: ${formatRequiredDetailSlots(candidate.missingRequiredSlots)}")
            add("can_start_execution: ${candidate.canStartExecution}")
        }.joinToString("\n")
    }

    fun formatLiveCandidateSummary(store: PrototypeStoreData): String {
        val currentState = store.resolveCurrentState()
        currentState.currentTaskRecord?.let(::formatTaskRecordSummary)?.let { return it }
        currentState.currentTaskDraft?.let(::formatTaskDraftSummary)?.let { return it }
        val semanticIntentState = currentState.currentSemanticIntentState
        val activeIntent = semanticIntentState.activeIntentCandidate()
        return if (semanticIntentState != null && activeIntent != null) {
            formatSemanticIntentSummary(semanticIntentState, activeIntent)
        } else {
            formatCandidateSummary(store.resolveTaskFirstCandidate())
        }
    }

    fun formatLiveTaskDisplaySummary(store: PrototypeStoreData): String {
        val currentState = store.resolveCurrentState()
        currentState.currentTaskRecord?.let(::formatTaskRecordDisplaySummary)?.let { return it }
        currentState.currentTaskDraft?.let(::formatTaskDraftDisplaySummary)?.let { return it }
        val semanticIntentState = currentState.currentSemanticIntentState
        val activeIntent = semanticIntentState.activeIntentCandidate()
        return if (semanticIntentState != null && activeIntent != null) {
            formatSemanticIntentDisplaySummary(semanticIntentState, activeIntent)
        } else {
            formatCandidateDisplaySummary(store.resolveTaskFirstCandidate())
        }
    }

    fun formatInactiveTopicDisplaySummary(store: PrototypeStoreData): String {
        val inactiveTopics = resolveInactiveTopicDisplayItems(store)
        return buildList {
            add("count: ${inactiveTopics.size}")
            if (inactiveTopics.isEmpty()) {
                add("No inactive topics yet.")
            } else {
                inactiveTopics.forEachIndexed { index, topic ->
                    add("${index + 1}. ${topic.label}")
                }
            }
        }.joinToString("\n")
    }

    fun liveCandidatePoolCount(store: PrototypeStoreData): Int {
        val currentState = store.resolveCurrentState()
        if (currentState.currentTaskRecord != null || currentState.currentTaskDraft != null) {
            return 1
        }
        val semanticCandidates = currentState.currentSemanticIntentState?.candidateIntents
        return if (semanticCandidates.isNullOrEmpty()) {
            store.currentDialogueCandidates().size
        } else {
            semanticCandidates.size
        }
    }

    fun formatPreparingBrief(candidate: IntentCandidate): String {
        return buildList {
            add("focused_object: ${candidate.focusedObject.ifBlank { "-" }}")
            add("action_intent: ${candidate.action.ifBlank { "-" }}")
            add("candidate_status: ${candidate.readiness.canonicalStatusValue()}")
            add("required_detail_slots: ${formatRequiredDetailSlots(candidate.missingRequiredSlots)}")
        }.joinToString("\n\n")
    }

    fun formatExecutionBoundaryPacket(boundaryPacket: TaskExecutionBoundaryPacket): String {
        val resolvedDetailSlots = buildList {
            boundaryPacket.targetKey.trim().takeIf { value -> value.isNotBlank() }?.let { targetKey ->
                add(
                    DetailSlot(
                        key = DetailSlotKey.TARGET_OBJECT,
                        value = targetKey,
                        source = "TASK_RECORD"
                    )
                )
            }
            boundaryPacket.detailSlots.forEach { (key, rawValue) ->
                val value = rawValue.trim()
                if (value.isNotBlank()) {
                    add(
                        DetailSlot(
                            key = key,
                            value = value,
                            source = "TASK_RECORD"
                        )
                    )
                }
            }
        }
        return buildList {
            add("workflow_lane: ${boundaryPacket.workflowLane.name}")
            add("objective: ${boundaryPacket.objectiveSummary.ifBlank { "-" }}")
            add("action_code: ${boundaryPacket.actionCode.wireName.ifBlank { "-" }}")
            add("plan_summary: ${boundaryPacket.planSummary.ifBlank { "-" }}")
            add("risk_boundary: ${boundaryPacket.riskSummary.ifBlank { "-" }}")
            add("missing_information: ${formatStringList(boundaryPacket.missingInformation)}")
            add("required_detail_slots: ${formatRequiredDetailSlots(boundaryPacket.requiredDetailSlots)}")
            add("resolved_detail_slots: ${formatDetailSlots(resolvedDetailSlots)}")
            add("capability_id: ${boundaryPacket.capabilityId ?: "null"}")
            add("process_id: ${boundaryPacket.processId ?: "null"}")
            add("confirm_requirement: ${boundaryPacket.confirmRequirement.name}")
            add("execution_gate_flag: ${boundaryPacket.executionGateFlag.name}")
            if (!boundaryPacket.reasonSummary.isNullOrBlank()) {
                add("reason_summary: ${boundaryPacket.reasonSummary}")
            }
        }.joinToString("\n\n")
    }

    private fun formatDetailSlots(detailSlots: List<DetailSlot>): String {
        if (detailSlots.isEmpty()) {
            return "[]"
        }
        return detailSlots.joinToString(prefix = "[", postfix = "]") { slot ->
            "${slot.key.contractName}=${slot.value}"
        }
    }

    private fun formatRequiredDetailSlots(detailSlotKeys: List<DetailSlotKey>): String {
        if (detailSlotKeys.isEmpty()) {
            return "[]"
        }
        return detailSlotKeys.joinToString(prefix = "[", postfix = "]") { slotKey ->
            slotKey.contractName
        }
    }

    private fun formatStringList(values: List<String>): String {
        if (values.isEmpty()) {
            return "[]"
        }
        return values.joinToString(prefix = "[", postfix = "]")
    }

    private fun formatSemanticIntentSummary(
        state: SemanticIntentState,
        candidate: SemanticIntentCandidate
    ): String {
        val actionIntent = candidate.canonicalAction?.name ?: candidate.rawActionLabel
        return buildList {
            add("anchor_object: ${candidate.anchorObject.ifBlank { "-" }}")
            add("focused_object: ${candidate.focusedObject.ifBlank { "-" }}")
            add("action_intent: ${actionIntent.ifBlank { "-" }}")
            add("candidate_status: ${candidate.readiness?.name ?: "-"}")
            state.currentPhase?.let { currentPhase -> add("current_phase: ${currentPhase.name}") }
            state.userRequestSemantic?.let { userRequestSemantic -> add("user_request_semantic: ${userRequestSemantic.name}") }
            state.stageTransitionRecommendation?.let { recommendation -> add("stage_transition_recommendation: ${recommendation.name}") }
            add("phase_type: ${state.phaseType.name}")
            add("phase_status: ${state.phaseStatus.name}")
            add("next_move: ${(candidate.nextMove ?: state.nextMove)?.name ?: "-"}")
            add("confidence: ${"%.2f".format(candidate.confidence)}")
            add("detail_slots: ${formatDetailSlots(candidate.detailSlots)}")
            add("constraints: ${formatStringList(candidate.constraints)}")
            add("can_start_execution: ${candidate.canStartExecution}")
        }.joinToString("\n")
    }

    private fun formatCandidateDisplaySummary(candidate: IntentCandidate?): String {
        if (candidate == null) {
            return "No active topic yet. Keep chatting and accumulating."
        }
        return buildList {
            add("target: ${candidate.focusedObject.ifBlank { candidate.anchorObject.ifBlank { "-" } }}")
            add("action: ${candidate.action.ifBlank { "-" }}")
            add("readiness: ${candidate.readiness.canonicalStatusValue()}")
            add("resolved_slots: ${formatDetailSlots(candidate.detailSlots)}")
            add("missing_required_slots: ${formatRequiredDetailSlots(candidate.missingRequiredSlots)}")
            add("can_start_execution: ${candidate.canStartExecution}")
        }.joinToString("\n")
    }

    private fun formatSemanticIntentDisplaySummary(
        state: SemanticIntentState,
        candidate: SemanticIntentCandidate
    ): String {
        val actionIntent = candidate.canonicalAction?.name ?: candidate.rawActionLabel
        return buildList {
            add("target: ${candidate.focusedObject.ifBlank { candidate.anchorObject.ifBlank { "-" } }}")
            add("action: ${actionIntent.ifBlank { "-" }}")
            add("readiness: ${candidate.readiness?.name ?: "-"}")
            add("phase: ${state.phaseType.name}/${state.phaseStatus.name}")
            add("next_move: ${(candidate.nextMove ?: state.nextMove)?.name ?: "-"}")
            add("resolved_slots: ${formatDetailSlots(candidate.detailSlots)}")
            add("constraints: ${formatStringList(candidate.constraints)}")
            add("can_start_execution: ${candidate.canStartExecution}")
        }.joinToString("\n")
    }

    private fun formatTaskDraftDisplaySummary(taskDraft: TaskDraft): String? {
        val targetSummary = taskDraft.displayTarget() ?: return null
        val detailSlots = taskDraft.detailSlots.entries.map { (key, value) ->
            DetailSlot(key = key, value = value, source = "TASK_DRAFT")
        }
        return buildList {
            add("target: $targetSummary")
            add("action: ${taskDraft.actionCode?.wireName ?: "-"}")
            add("capability: ${taskDraft.capabilityId ?: "-"}")
            add("process: ${taskDraft.processId ?: "-"}")
            add("resolved_slots: ${formatDetailSlots(detailSlots)}")
            add("can_start_execution: false")
        }.joinToString("\n")
    }

    private fun formatTaskRecordDisplaySummary(taskRecord: TaskRecord): String {
        val detailSlots = taskRecord.detailSlots.entries.map { (key, value) ->
            DetailSlot(key = key, value = value, source = "TASK_RECORD")
        }
        return buildList {
            add("target: ${taskRecord.displayTarget()}")
            add("action: ${taskRecord.actionCode.wireName}")
            add("capability: ${taskRecord.capabilityId ?: "-"}")
            add("process: ${taskRecord.processId ?: "-"}")
            add("resolved_slots: ${formatDetailSlots(detailSlots)}")
            add("can_start_execution: ${taskRecord.phase == TaskPhase.EXECUTING}")
        }.joinToString("\n")
    }

    private data class InactiveTopicDisplayItem(
        val id: String,
        val label: String
    )

    private fun resolveInactiveTopicDisplayItems(store: PrototypeStoreData): List<InactiveTopicDisplayItem> {
        val currentState = store.resolveCurrentState()
        val semanticIntentState = currentState.currentSemanticIntentState
        val liveInactiveTopics = if (semanticIntentState?.candidateIntents?.isNotEmpty() == true) {
            val activeIntentId = semanticIntentState.activeIntentCandidate()?.intentId
            semanticIntentState.candidateIntents
                .filterNot { candidate -> candidate.intentId == activeIntentId }
                .map { candidate ->
                    InactiveTopicDisplayItem(
                        id = candidate.intentId,
                        label = candidate.focusedObject.ifBlank { candidate.anchorObject.ifBlank { candidate.intentId } }
                    )
                }
        } else {
            val activeCandidateId = if (currentState.currentTaskRecord != null || currentState.currentTaskDraft != null) {
                store.resolveCurrentActiveCandidateId()
            } else {
                store.resolveTaskFirstCandidate()?.id
            }
            store.currentDialogueCandidates()
                .filterNot { candidate -> candidate.id == activeCandidateId }
                .map { candidate ->
                    InactiveTopicDisplayItem(
                        id = candidate.id,
                        label = candidate.focusedObject.ifBlank { candidate.anchorObject.ifBlank { candidate.id } }
                    )
                }
        }
        val dormantTopics = currentState.dormantHistoricalCandidates.asReversed().map { candidate ->
            InactiveTopicDisplayItem(
                id = candidate.id,
                label = candidate.focusedObject.ifBlank { candidate.anchorObject.ifBlank { candidate.id } }
            )
        }
        val seenTopicIds = mutableSetOf<String>()
        return (liveInactiveTopics + dormantTopics).filter { topic -> seenTopicIds.add(topic.id) }
    }

    private fun formatTaskDraftSummary(taskDraft: TaskDraft): String? {
        val targetSummary = taskDraft.displayTarget() ?: return null
        val detailSlots = taskDraft.detailSlots.entries.map { (key, value) ->
            DetailSlot(key = key, value = value, source = "TASK_DRAFT")
        }
        return buildList {
            add("anchor_object: ${taskDraft.targetKey ?: "-"}")
            add("focused_object: $targetSummary")
            add("action_intent: ${taskDraft.actionCode?.wireName ?: "-"}")
            add("task_phase: DRAFT")
            add("target_type: ${taskDraft.targetType?.wireName ?: "-"}")
            add("capability_stack: ${taskDraft.capabilityStack?.name ?: "-"}")
            add("capability_domain: ${taskDraft.capabilityDomain?.wireName ?: "-"}")
            add("capability_id: ${taskDraft.capabilityId ?: "-"}")
            add("process_id: ${taskDraft.processId ?: "-"}")
            add("detail_slots: ${formatDetailSlots(detailSlots)}")
            add("can_start_execution: false")
        }.joinToString("\n")
    }

    private fun formatTaskRecordSummary(taskRecord: TaskRecord): String {
        val detailSlots = taskRecord.detailSlots.entries.map { (key, value) ->
            DetailSlot(key = key, value = value, source = "TASK_RECORD")
        }
        return buildList {
            add("anchor_object: ${taskRecord.targetKey}")
            add("focused_object: ${taskRecord.displayTarget()}")
            add("action_intent: ${taskRecord.actionCode.wireName}")
            add("task_phase: ${taskRecord.phase.name}")
            add("target_type: ${taskRecord.targetType.wireName}")
            add("capability_stack: ${taskRecord.capabilityStack?.name ?: "-"}")
            add("capability_domain: ${taskRecord.capabilityDomain?.wireName ?: "-"}")
            add("capability_id: ${taskRecord.capabilityId ?: "-"}")
            add("process_id: ${taskRecord.processId ?: "-"}")
            add("detail_slots: ${formatDetailSlots(detailSlots)}")
            add("can_start_execution: ${taskRecord.phase == TaskPhase.EXECUTING}")
        }.joinToString("\n")
    }

    private fun SemanticIntentState?.activeIntentCandidate(): SemanticIntentCandidate? {
        val state = this ?: return null
        return state.candidateIntents.firstOrNull { candidate ->
            candidate.intentId == state.activeIntentId
        } ?: state.candidateIntents.firstOrNull()
    }

    private fun formatNextGate(
        currentPhase: CurrentPhase,
        userRequestSemantic: UserRequestSemantic?,
        stageTransitionRecommendation: StageTransitionRecommendation?
    ): String {
        return when (userRequestSemantic) {
            UserRequestSemantic.START_EXECUTING -> "execution requested; wait for local validation before runtime start"
            UserRequestSemantic.START_PREPARING -> "plan requested; show preparation without starting execution"
            UserRequestSemantic.START_ACCUMULATING -> formatAccumulationGate(stageTransitionRecommendation)
            null -> when (currentPhase) {
                CurrentPhase.EXECUTION -> "execution state visible; do not start runtime without START_EXECUTING"
                CurrentPhase.PREPARATION -> "preparation state visible; wait for an explicit user request"
                CurrentPhase.ACCUMULATION -> formatAccumulationGate(stageTransitionRecommendation)
            }
        }
    }

    private fun formatAccumulationGate(
        stageTransitionRecommendation: StageTransitionRecommendation?
    ): String {
        return when (stageTransitionRecommendation) {
            StageTransitionRecommendation.SHOULD_ENTER_EXECUTING -> "execution readiness hint only; wait for START_EXECUTING"
            StageTransitionRecommendation.SHOULD_ENTER_PREPARING -> "preparation readiness hint only; wait for START_PREPARING"
            StageTransitionRecommendation.SHOULD_ENTER_ACCUMULATING,
            null -> "continue accumulating"
        }
    }
}

internal fun CandidateReadiness.canonicalStatusValue(): String = when (this) {
    CandidateReadiness.READY_TO_PREPARE -> "PREPARING_READY"
    CandidateReadiness.READY_TO_START -> "EXECUTION_READY"
    else -> name
}