package com.atombits.pocopaw

data class ExpressionPolicy(
    val brevityLevel: String = "MEDIUM",
    val directnessLevel: String = "MEDIUM",
    val explanationDepth: String = "BALANCED"
)

data class SoftCompletionPolicy(
    val defaultFillConfidence: Double = 0.6
)

data class ProactiveDeliveryPolicy(
    val proactiveTolerance: String = "LOW",
    val reminderAggressiveness: String = "LOW",
    val minimumDeliveryIntervalMs: Long = 60 * 60 * 1000L
)

data class DefaultToolRankingPolicy(
    val preferredPlatformOrder: List<String> = emptyList(),
    val preferredProcessOrder: List<String> = emptyList()
)

data class PersonalizationPolicyBundle(
    val expressionPolicy: ExpressionPolicy = ExpressionPolicy(),
    val softCompletionPolicy: SoftCompletionPolicy = SoftCompletionPolicy(),
    val proactiveDeliveryPolicy: ProactiveDeliveryPolicy = ProactiveDeliveryPolicy(),
    val defaultToolRankingPolicy: DefaultToolRankingPolicy = DefaultToolRankingPolicy()
)

data class ProactiveDeliveryPlan(
    val opportunityId: String,
    val signal: ProactiveOpportunitySignal,
    val deliveryStyle: String?,
    val title: String,
    val summary: String,
    val plannedAt: Long = System.currentTimeMillis()
)

data class PersonalizationPolicyRefreshOutcome(
    val updatedStore: PrototypeStoreData,
    val policyBundle: PersonalizationPolicyBundle,
    val deliveryPlan: ProactiveDeliveryPlan?
)

private const val defaultImmediateRedeliverySuppressionMs = 10 * 60 * 1000L
private const val highAggressivenessMinimumDeliveryIntervalMs = 15 * 60 * 1000L
private const val mediumAggressivenessMinimumDeliveryIntervalMs = 30 * 60 * 1000L
private const val lowAggressivenessMinimumDeliveryIntervalMs = 60 * 60 * 1000L

fun buildProactiveDeliveryFingerprint(plan: ProactiveDeliveryPlan): String {
    return listOf(
        plan.signal.name,
        plan.deliveryStyle.orEmpty(),
        plan.title,
        plan.summary
    ).joinToString("|")
}

fun applyPersonalizationPolicyRefresh(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis()
): PersonalizationPolicyRefreshOutcome {
    val policyBundle = buildPersonalizationPolicyBundle(store)
    if (!RuntimeModuleSwitches.proactiveEngineEnabled) {
        val updatedState = RuntimeModuleSwitches.clearProactiveState(store.resolveCurrentState(), now)
        val updatedStore = store.copy(currentState = updatedState)
        updatedStore.syncIntentSliceIfPresent()
        return PersonalizationPolicyRefreshOutcome(
            updatedStore = updatedStore,
            policyBundle = policyBundle,
            deliveryPlan = null
        )
    }

    val deliveryPlan = planProactiveDelivery(store, policyBundle, now)
    val updatedState = store.resolveCurrentState().copy(
        pendingProactiveDeliveryPlan = deliveryPlan,
        lastUpdatedAt = now
    )
    val updatedStore = store.copy(currentState = updatedState)
    updatedStore.syncIntentSliceIfPresent()
    return PersonalizationPolicyRefreshOutcome(
        updatedStore = updatedStore,
        policyBundle = policyBundle,
        deliveryPlan = deliveryPlan
    )
}

fun buildPersonalizationPolicyBundle(store: PrototypeStoreData): PersonalizationPolicyBundle {
    val memoryState = store.memoryState ?: MemoryState()
    val preferenceFacts = memoryState.structuredPreferenceMemory.facts
    val biasRecords = memoryState.interactionBiasMemory.allRecords()
    val brevityLevel = latestStyleValue(memoryState, "brevity_level") ?: "MEDIUM"
    val directnessLevel = latestStyleValue(memoryState, "directness_level")
        ?: if (brevityLevel == "HIGH") "HIGH" else "MEDIUM"
    val explanationDepth = when (brevityLevel) {
        "HIGH" -> "SHORT"
        "LOW" -> "DETAILED"
        else -> "BALANCED"
    }
    val defaultFillConfidence = (
        preferenceFacts.map { fact -> fact.confidence } +
            biasRecords.map { record -> record.confidence }
        )
        .ifEmpty { listOf(0.6) }
        .average()
        .coerceIn(0.3, 0.95)

    val proactiveTolerance = when {
        memoryState.habitMemoryStore.any { habit ->
            (habit.preferredProactiveSignal?.uppercase() == ProactiveOpportunitySignal.PREPARE_OPPORTUNITY.name && habit.stabilityScore >= 0.85) ||
                habit.stabilityScore >= 0.9
        } -> "HIGH"
        preferenceFacts.any { fact -> fact.confidence >= 0.75 } ||
            biasRecords.any { record -> record.confidence >= 0.75 } -> "MEDIUM"
        else -> "LOW"
    }
    val reminderAggressiveness = when {
        memoryState.habitMemoryStore.any { habit -> habit.preferredDeliveryStyle?.uppercase() == "REMIND" } -> "HIGH"
        proactiveTolerance == "HIGH" -> "MEDIUM"
        else -> "LOW"
    }
    val minimumDeliveryIntervalMs = when (reminderAggressiveness) {
        "HIGH" -> highAggressivenessMinimumDeliveryIntervalMs
        "MEDIUM" -> mediumAggressivenessMinimumDeliveryIntervalMs
        else -> lowAggressivenessMinimumDeliveryIntervalMs
    }
    val preferredPlatformScores = buildMap<String, Int> {
        preferenceFacts
            .mapNotNull { fact -> fact.sourceApp?.takeUnless { app -> app.isBlank() } }
            .forEach { app ->
                put(app, (get(app) ?: 0) + 1)
            }
        biasRecords
            .mapNotNull { record -> record.sourceApp?.takeUnless { app -> app.isBlank() } }
            .forEach { app ->
                put(app, (get(app) ?: 0) + 1)
            }
    }
    val preferredPlatformOrder = preferredPlatformScores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }.thenBy { entry -> entry.key })
        .map { entry -> entry.key }
    val preferredProcessScores = buildMap<String, Int> {
        memoryState.interactionBiasMemory.preferredProcesses.forEach { record ->
            if (record.signalValue.isNotBlank()) {
                put(record.signalValue, (get(record.signalValue) ?: 0) + 3)
            }
        }
    }
    val preferredProcessOrder = store.readyProcessAssets
        .sortedWith(
            compareByDescending<ReadyProcessAsset> { asset -> preferredProcessScores[asset.processId] ?: 0 }
                .thenByDescending { asset -> asset.lastDerivedAt }
                .thenBy { asset -> asset.processId }
        )
        .filter { asset -> preferredPlatformOrder.isEmpty() || preferredPlatformOrder.contains(asset.appScope) }
        .map { asset -> asset.processId }

    return PersonalizationPolicyBundle(
        expressionPolicy = ExpressionPolicy(
            brevityLevel = brevityLevel,
            directnessLevel = directnessLevel,
            explanationDepth = explanationDepth
        ),
        softCompletionPolicy = SoftCompletionPolicy(defaultFillConfidence = defaultFillConfidence),
        proactiveDeliveryPolicy = ProactiveDeliveryPolicy(
            proactiveTolerance = proactiveTolerance,
            reminderAggressiveness = reminderAggressiveness,
            minimumDeliveryIntervalMs = minimumDeliveryIntervalMs
        ),
        defaultToolRankingPolicy = DefaultToolRankingPolicy(
            preferredPlatformOrder = preferredPlatformOrder,
            preferredProcessOrder = preferredProcessOrder
        )
    )
}

fun PersonalizationPolicyBundle.toPromptSection(): String {
    return buildString {
        appendLine(
            "expression_policy=brevity_level=${expressionPolicy.brevityLevel}; directness_level=${expressionPolicy.directnessLevel}; explanation_depth=${expressionPolicy.explanationDepth}"
        )
        appendLine("soft_completion_policy=default_fill_confidence=${"%.2f".format(softCompletionPolicy.defaultFillConfidence)}")
        appendLine(
            "proactive_delivery_policy=proactive_tolerance=${proactiveDeliveryPolicy.proactiveTolerance}; reminder_aggressiveness=${proactiveDeliveryPolicy.reminderAggressiveness}; minimum_delivery_interval_minutes=${proactiveDeliveryPolicy.minimumDeliveryIntervalMs / 60000}"
        )
        append(
            "default_tool_ranking_policy=preferred_platform_order=${defaultToolRankingPolicy.preferredPlatformOrder}; preferred_process_order=${defaultToolRankingPolicy.preferredProcessOrder}"
        )
    }
}

private fun latestStyleValue(memoryState: MemoryState, styleKey: String): String? {
    return memoryState.interactionStyleStore
        .filter { record -> record.styleKey.equals(styleKey, ignoreCase = true) }
        .maxByOrNull { record -> record.lastObservedAt }
        ?.styleValue
        ?.uppercase()
}

private fun planProactiveDelivery(
    store: PrototypeStoreData,
    policyBundle: PersonalizationPolicyBundle,
    now: Long
): ProactiveDeliveryPlan? {
    val currentState = store.resolveCurrentState()
    val cooldownUntil = currentState.proactiveDeliveryCooldownUntil
    if (cooldownUntil != null && now < cooldownUntil) {
        return null
    }
    val lastDeliveredAt = currentState.lastDeliveredProactivePlanAt
    if (
        lastDeliveredAt != null &&
        now - lastDeliveredAt < policyBundle.proactiveDeliveryPolicy.minimumDeliveryIntervalMs
    ) {
        return null
    }
    val memoryState = store.memoryState ?: return null
    val opportunity = memoryState.proactiveOpportunityStore
        .sortedWith(compareByDescending<ProactiveOpportunityRecord> { record -> record.confidence }.thenByDescending { record -> record.lastObservedAt })
        .firstOrNull()
        ?: return null

    val selectedSignal = when (policyBundle.proactiveDeliveryPolicy.proactiveTolerance) {
        "HIGH" -> {
            if (opportunity.confidence >= 0.85) {
                ProactiveOpportunitySignal.ISSUE_PROACTIVE_HINT
            } else {
                opportunity.signal
            }
        }
        "MEDIUM" -> {
            if (opportunity.confidence >= 0.9) {
                ProactiveOpportunitySignal.ISSUE_PROACTIVE_HINT
            } else {
                opportunity.signal
            }
        }
        else -> opportunity.signal
    }
    val deliveryStyle = if (selectedSignal == ProactiveOpportunitySignal.ISSUE_PROACTIVE_HINT) {
        when (policyBundle.proactiveDeliveryPolicy.reminderAggressiveness) {
            "HIGH" -> "REMIND"
            "MEDIUM" -> "SUGGEST"
            else -> "HINT"
        }
    } else {
        null
    }
    if (
        selectedSignal != ProactiveOpportunitySignal.ISSUE_PROACTIVE_HINT &&
        selectedSignal != ProactiveOpportunitySignal.REQUEST_PROACTIVE_CONFIRM &&
        selectedSignal != ProactiveOpportunitySignal.ENTER_PROACTIVE_EXECUTING
    ) {
        return null
    }
    val plan = ProactiveDeliveryPlan(
        opportunityId = opportunity.opportunityId,
        signal = selectedSignal,
        deliveryStyle = deliveryStyle,
        title = opportunity.title,
        summary = "${opportunity.summary}; planner_tolerance=${policyBundle.proactiveDeliveryPolicy.proactiveTolerance}",
        plannedAt = now
    )
    val justDeliveredSamePlan = currentState.lastDeliveredProactivePlanFingerprint == buildProactiveDeliveryFingerprint(plan) &&
        currentState.lastDeliveredProactivePlanAt?.let { deliveredAt ->
            now - deliveredAt < defaultImmediateRedeliverySuppressionMs
        } == true
    return if (justDeliveredSamePlan) {
        null
    } else {
        plan
    }
}