package com.atombits.pocopaw

data class ProactiveRuntimeRefreshOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val delivered: Boolean,
    val message: String
)

data class ProactiveFeedbackOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

data class ProactiveTurnResolution(
    val assistantReply: String,
    val semanticSummary: String? = null
)

interface ProactiveTurnResolver {
    fun resolve(
        packet: PromptPacket,
        pendingPlan: ProactiveDeliveryPlan,
        store: PrototypeStoreData,
        now: Long
    ): ProactiveTurnResolution
}

private const val ignoredProactiveCooldownMs = 30 * 60 * 1000L
private const val dismissedProactiveCooldownMs = 60 * 60 * 1000L
private const val rejectedProactiveCooldownMs = 2 * 60 * 60 * 1000L

fun applyProactiveRuntimeRefresh(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: ProactiveTurnResolver? = null
): ProactiveRuntimeRefreshOutcome {
    if (!RuntimeModuleSwitches.proactiveEngineEnabled) {
        val memoryState = store.memoryState ?: MemoryState()
        val updatedStore = store.copy(
            currentState = RuntimeModuleSwitches.clearProactiveState(store.resolveCurrentState(), now),
            memoryState = memoryState.copy(proactiveOpportunityStore = emptyList())
        )
        updatedStore.syncIntentSliceIfPresent()
        updatedStore.syncMemorySliceIfPresent()
        return ProactiveRuntimeRefreshOutcome(
            updatedStore = updatedStore,
            applied = updatedStore != store,
            delivered = false,
            message = UiStrings.resolve(
                R.string.proactive_runtime_disabled,
                "Proactive runtime is disabled."
            )
        )
    }

    val withOpportunities = applyProactiveOpportunityRefresh(store, now)
    val personalizationOutcome = applyPersonalizationPolicyRefresh(withOpportunities, now)
    return applyPendingProactiveDeliveryPlan(personalizationOutcome.updatedStore, now, resolver)
}

fun applyPendingProactiveDeliveryPlan(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    resolver: ProactiveTurnResolver? = null
): ProactiveRuntimeRefreshOutcome {
    if (!RuntimeModuleSwitches.proactiveEngineEnabled) {
        val updatedStore = store.copy(
            currentState = RuntimeModuleSwitches.clearProactiveState(store.resolveCurrentState(), now)
        )
        updatedStore.syncIntentSliceIfPresent()
        return ProactiveRuntimeRefreshOutcome(
            updatedStore = updatedStore,
            applied = updatedStore != store,
            delivered = false,
            message = UiStrings.resolve(
                R.string.proactive_delivery_disabled,
                "Proactive delivery path is disabled."
            )
        )
    }

    val currentState = store.resolveCurrentState()
    val pendingPlan = currentState.pendingProactiveDeliveryPlan ?: return ProactiveRuntimeRefreshOutcome(
        updatedStore = store,
        applied = false,
        delivered = false,
        message = UiStrings.resolve(
            R.string.proactive_no_pending_plan,
            "No visible proactive delivery plan is pending."
        )
    )
    val gatedPlan = applyProactiveSafetyGate(store, pendingPlan)
    val fingerprint = buildProactiveDeliveryFingerprint(gatedPlan)
    if (currentState.lastDeliveredProactivePlanFingerprint == fingerprint) {
        val nextCurrentState = currentState.copy(
            pendingProactiveDeliveryPlan = null,
            lastUpdatedAt = now
        )
        val clearedStore = store.copy(
            currentState = nextCurrentState
        )
        clearedStore.syncIntentSliceIfPresent()
        return ProactiveRuntimeRefreshOutcome(
            updatedStore = clearedStore,
            applied = true,
            delivered = false,
            message = UiStrings.resolve(
                R.string.proactive_duplicate_suppressed,
                "Duplicate proactive delivery plan was suppressed."
            )
        )
    }

    val gatedVisibleStage = when (gatedPlan.signal) {
        ProactiveOpportunitySignal.REQUEST_PROACTIVE_CONFIRM -> ConversationStage.PREPARING
        ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING -> ConversationStage.EXECUTING
        else -> ConversationStage.ACCUMULATING
    }
    val assistantReply = resolver?.let { proactiveResolver ->
        val packet = buildProactiveTurnPacket(store, gatedPlan)
        proactiveResolver.resolve(packet, gatedPlan, store, now).assistantReply.trim()
    }?.takeUnless { it.isBlank() } ?: formatProactiveAssistantReply(gatedPlan)
    val nextMessages = store.messages.toMutableList().apply {
        add(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = assistantReply,
                timestamp = now,
                stage = gatedVisibleStage
            )
        )
    }
    val nextSnapshots = store.snapshots.toMutableList().apply {
        add(
            TurnSnapshot(
                stage = gatedVisibleStage,
                workflowLane = WorkflowLane.PROACTIVE,
                stageOwner = StageOwner.PROACTIVE_ENGINE,
                passiveUserProgressSignal = null,
                proactiveOpportunitySignal = gatedPlan.signal,
                activeCandidateId = store.resolveCurrentActiveCandidateId(),
                assistantReply = assistantReply,
                candidates = store.currentDialogueCandidates(),
                persistedAt = now
            )
        )
    }
    val nextCurrentState = currentState.copy(
        stage = gatedVisibleStage,
        workflowLane = WorkflowLane.PROACTIVE,
        stageOwner = StageOwner.PROACTIVE_ENGINE,
        lastPassiveUserTransitionIntent = null,
        lastPassiveUserProgressSignal = null,
        currentPhase = gatedVisibleStage.toCurrentPhase(),
        userRequestSemantic = null,
        stageTransitionRecommendation = null,
        lastProactiveOpportunitySignal = gatedPlan.signal,
        pendingProactiveDeliveryPlan = null,
        lastDeliveredProactivePlanFingerprint = fingerprint,
        lastDeliveredProactivePlanAt = now,
        lastUpdatedAt = now
    )
    val updatedStore = store.copy(
        messages = nextMessages,
        snapshots = nextSnapshots,
        executionEvents = store.executionEvents.toMutableList(),
        executionTraces = store.executionTraces.toMutableList(),
        processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
        readyProcessAssets = store.readyProcessAssets.toMutableList(),
        processAssetEntries = store.processAssetEntries.toMutableList(),
        pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
        processShortcutAtlas = store.processShortcutAtlas.toMutableList(),
        processAssetEvents = store.processAssetEvents.toMutableList(),
        processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
        currentState = nextCurrentState,
        memoryState = store.memoryState ?: MemoryState()
    )
    updatedStore.syncConversationSliceIfPresent()
    updatedStore.syncIntentSliceIfPresent()
    return ProactiveRuntimeRefreshOutcome(
        updatedStore = updatedStore,
        applied = true,
        delivered = true,
        message = UiStrings.resolve(
            R.string.proactive_delivered,
            "Delivered proactive plan into visible runtime envelope."
        )
    )
}

private fun applyProactiveSafetyGate(
    store: PrototypeStoreData,
    pendingPlan: ProactiveDeliveryPlan
): ProactiveDeliveryPlan {
    val policyBundle = buildPersonalizationPolicyBundle(store)
    val safetyDecision = SafetyBoundaryEngine.assess(
        executionBoundaryPacket = if (pendingPlan.signal == ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING) {
            resolveProactiveExecutionBrief(store)
        } else {
            null
        },
        toolCapabilityBundle = null,
        context = SafetyBoundaryContext(
            workflowLane = WorkflowLane.PROACTIVE,
            proactiveDeliveryPlan = pendingPlan,
            personalizationPolicyBundle = policyBundle
        )
    )
    if (safetyDecision?.decisionType == "proactive_policy_hint_only") {
        val downgradedStyle = when (policyBundle.proactiveDeliveryPolicy.reminderAggressiveness.uppercase()) {
            "HIGH" -> "REMIND"
            "MEDIUM" -> "SUGGEST"
            else -> "HINT"
        }
        return pendingPlan.copy(
            signal = ProactiveOpportunitySignal.ISSUE_PROACTIVE_HINT,
            deliveryStyle = downgradedStyle
        )
    }
    return if (
        pendingPlan.signal == ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING &&
        safetyDecision?.needsHumanConfirm == true
    ) {
        pendingPlan.copy(signal = ProactiveOpportunitySignal.REQUEST_PROACTIVE_CONFIRM)
    } else {
        pendingPlan
    }
}

internal fun resolveProactiveExecutionBrief(store: PrototypeStoreData): TaskExecutionBoundaryPacket? {
    return resolveStoreAwareExecutionBoundaryPacket(store = store)
}

fun applyProactiveFeedback(
    store: PrototypeStoreData,
    feedbackType: ProactiveFeedbackType,
    now: Long = System.currentTimeMillis()
): ProactiveFeedbackOutcome {
    if (!RuntimeModuleSwitches.proactiveEngineEnabled) {
        val updatedStore = store.copy(
            currentState = RuntimeModuleSwitches.clearProactiveState(store.resolveCurrentState(), now)
        )
        updatedStore.syncIntentSliceIfPresent()
        return ProactiveFeedbackOutcome(
            updatedStore = updatedStore,
            applied = false,
            message = UiStrings.resolve(
                R.string.proactive_runtime_disabled,
                "Proactive runtime is disabled."
            )
        )
    }

    val currentState = store.resolveCurrentState()
    val planFingerprint = currentState.lastDeliveredProactivePlanFingerprint
        ?: return ProactiveFeedbackOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.proactive_no_feedback_plan,
                "No delivered proactive plan is available for feedback writeback."
            )
        )
    val cooldownUntil = when (feedbackType) {
        ProactiveFeedbackType.ACCEPTED -> null
        ProactiveFeedbackType.IGNORED -> now + ignoredProactiveCooldownMs
        ProactiveFeedbackType.DISMISSED -> now + dismissedProactiveCooldownMs
        ProactiveFeedbackType.REJECTED -> now + rejectedProactiveCooldownMs
    }
    val feedbackRecord = ProactiveFeedbackRecord(
        planFingerprint = planFingerprint,
        feedbackType = feedbackType,
        recordedAt = now,
        cooldownUntil = cooldownUntil
    )
    val memoryState = store.memoryState ?: MemoryState()
    val nextMemoryState = memoryState.copy(
        proactiveFeedbackStore = listOf(feedbackRecord) + memoryState.proactiveFeedbackStore
            .filterNot { record -> record.planFingerprint == planFingerprint }
            .take(11)
    )
    val nextCurrentState = currentState.copy(
        proactiveDeliveryCooldownUntil = cooldownUntil,
        workflowLane = WorkflowLane.PASSIVE,
        stageOwner = StageOwner.USER,
        lastUpdatedAt = now
    )
    val updatedStore = store.copy(
        currentState = nextCurrentState,
        memoryState = nextMemoryState
    )
    updatedStore.syncIntentSliceIfPresent()
    updatedStore.syncMemorySliceIfPresent()
    return ProactiveFeedbackOutcome(
        updatedStore = updatedStore,
        applied = true,
        message = UiStrings.resolve(
            R.string.proactive_feedback_recorded,
            "Recorded proactive feedback and refreshed cooldown semantics."
        )
    )
}

private fun buildProactiveTurnPacket(
    store: PrototypeStoreData,
    pendingPlan: ProactiveDeliveryPlan
): PromptPacket {
    val groundingText = listOf(pendingPlan.title, pendingPlan.summary)
        .filter { value -> value.isNotBlank() }
        .joinToString(" ")
        .ifBlank { pendingPlan.opportunityId }
    val memoryBundle = MemoryOrchestrator.buildPassiveEvidence(groundingText, store)?.toPromptSection()
        ?: buildFallbackProactiveMemoryBundle(store)
    return PromptCenter.buildProactiveTurnPacket(
        ProactiveTurnPromptSpec(
            summary = pendingPlan.summary,
            opportunityBundle = buildPendingPlanOpportunityBundle(pendingPlan),
            memoryBundle = memoryBundle,
            userVisibleContext = buildProactiveUserVisibleContext(store)
        )
    )
}

private fun buildPendingPlanOpportunityBundle(plan: ProactiveDeliveryPlan): String {
    return buildString {
        append("signal=")
        append(plan.signal.name)
        append("; title=")
        append(plan.title)
        append("; summary=")
        append(plan.summary)
        append("; opportunity_id=")
        append(plan.opportunityId)
    }
}

private fun buildProactiveUserVisibleContext(store: PrototypeStoreData): String {
    val recentMessages = store.messages
        .filter { message -> message.role != MessageRole.SYSTEM }
        .takeLast(4)
        .joinToString("\n") { message -> "${message.role.name}: ${message.content}" }
    return recentMessages.ifBlank { "No recent visible context." }
}

private fun buildFallbackProactiveMemoryBundle(store: PrototypeStoreData): String {
    return MemoryOrchestrator.buildOfflineDialoguePreferenceMemoryBundle(store)
}

private fun formatProactiveAssistantReply(plan: ProactiveDeliveryPlan): String {
    val prefix = when (plan.deliveryStyle?.uppercase()) {
        "REMIND" -> "提醒"
        "SUGGEST" -> "建议"
        else -> "提示"
    }
    return buildString {
        append(prefix)
        append("：")
        append(plan.title)
        if (plan.summary.isNotBlank()) {
            append("。")
            append(plan.summary)
        }
    }
}