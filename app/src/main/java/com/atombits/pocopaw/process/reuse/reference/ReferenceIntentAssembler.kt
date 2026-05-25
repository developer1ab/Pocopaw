package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.ConfirmRequirement
import com.atombits.pocopaw.DetailSlot
import com.atombits.pocopaw.DetailSlotKey
import com.atombits.pocopaw.ExecutionCheckType
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.IntentCandidate
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.toTaskSlotEvidenceSnapshot
import com.atombits.pocopaw.extractCanonicalAppScope
import com.atombits.pocopaw.directRecommendedSlotValues
import com.atombits.pocopaw.resolvePreferenceRecommendationResolution
import com.atombits.pocopaw.toPreferenceRecallDebugSnapshot
import com.atombits.pocopaw.toPreferenceSlotMappingTrace
import java.util.Locale

internal object ReferenceIntentAssembler {
    fun assemble(
        store: PrototypeStoreData,
        activeCandidate: IntentCandidate?,
        boundaryPacket: TaskExecutionBoundaryPacket?
    ): StructuredTaskIntent? {
        val objective = boundaryPacket?.objectiveSummary?.trim().orEmpty().ifBlank {
            activeCandidate?.anchoredLabel.orEmpty()
        }
        if (objective.isBlank() && activeCandidate == null && boundaryPacket == null) {
            return null
        }

        val actionIntent = boundaryPacket?.actionCode?.wireName?.trim().orEmpty().ifBlank {
            activeCandidate?.action.orEmpty()
        }
        val planSummary = boundaryPacket?.planSummary?.trim().orEmpty().ifBlank {
            listOf(actionIntent, objective)
                .filter { value -> value.isNotBlank() }
                .joinToString(" ")
        }
        val appScope = resolvePreferredAppScope(
            extractCanonicalAppScope(boundaryPacket?.capabilityId)
        )

        val constraintLines = buildList {
            boundaryPacket?.missingInformation.orEmpty().forEach { value ->
                value.trim().takeIf { text -> text.isNotBlank() }?.let(::add)
            }
            boundaryPacket?.verificationChecks.orEmpty().forEach { check ->
                if (check.type == ExecutionCheckType.SLOT_PRESERVED && check.required) {
                    DetailSlotKey.fromContractValue(check.key)?.let { slot ->
                        add("required_slot:${slot.contractName}")
                    }
                }
            }
            boundaryPacket?.riskSummary?.trim()?.takeIf { value -> value.isNotBlank() }?.let(::add)
            boundaryPacket?.requiredDetailSlots?.forEach { slot ->
                add("required_slot:${slot.contractName}")
            }
            when (boundaryPacket?.confirmRequirement ?: ConfirmRequirement.NONE) {
                ConfirmRequirement.SOFT -> add("confirm:soft")
                ConfirmRequirement.HARD -> add("confirm:hard")
                ConfirmRequirement.NONE -> Unit
            }
        }.distinct()

        val historicalLines = buildList {
            store.pendingProcessRecoveryContext?.blockedContext
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> add("pending_recovery:$value") }
            store.latestCompletedProcessReviewContext?.finalUserSummary
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() && value != objective }
                ?.let { value -> add("latest_completed:$value") }
        }
            val authoritativeSlotSnapshot = boundaryPacket?.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")
            val preservedSlotValues = authoritativeSlotSnapshot?.resolvedSlots.orEmpty()
            val slotKeys = preservedSlotValues.keys.toList()
        val preferenceRecommendationResolution = store.resolvePreferenceRecommendationResolution(
            boundaryPacket = boundaryPacket,
            userQuery = planSummary
        )
        val recommendedSlotValues = preferenceRecommendationResolution
            ?.recommendedDetailSlots
            ?.directRecommendedSlotValues()
            .orEmpty()

        val segments = buildList {
            listOf(objective, actionIntent, planSummary)
                .filter { value -> value.isNotBlank() }
                .joinToString(" | ")
                .takeIf { value -> value.isNotBlank() }
                ?.let { value -> add(StructuredTaskIntentSegment(StructuredTaskIntentSegmentRole.PRIMARY_TASK, value)) }
            buildGuidanceLines(boundaryPacket, activeCandidate).forEach { value ->
                add(StructuredTaskIntentSegment(StructuredTaskIntentSegmentRole.GUIDANCE, value))
            }
            constraintLines.forEach { value ->
                add(
                    StructuredTaskIntentSegment(
                        role = if (isNegativeReferenceGuidance(value)) {
                            StructuredTaskIntentSegmentRole.NEGATION
                        } else {
                            StructuredTaskIntentSegmentRole.CONSTRAINT
                        },
                        text = value
                    )
                )
            }
            historicalLines.forEach { value ->
                add(StructuredTaskIntentSegment(StructuredTaskIntentSegmentRole.HISTORICAL_FAILURE, value))
            }
        }

        val positiveMentions = segments
            .filterNot { segment ->
                segment.role == StructuredTaskIntentSegmentRole.HISTORICAL_FAILURE ||
                    segment.role == StructuredTaskIntentSegmentRole.NEGATION
            }
            .flatMap { segment -> extractProcessMentions(segment.text) }
        val candidatePrimaryProcess = activeCandidate?.action
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { action ->
                inferTaskIntentPrimaryProcess(
                    objective = objective,
                    actionIntent = action,
                    planSummary = planSummary,
                    selectedToolId = boundaryPacket?.capabilityId,
                    processId = null
                )
            }
        val explicitPrimaryProcess = resolveBoundaryPacketPrimaryProcess(boundaryPacket)
        val inferredPrimaryProcess = inferTaskIntentPrimaryProcess(
            objective = objective,
            actionIntent = actionIntent,
            planSummary = planSummary,
            selectedToolId = boundaryPacket?.capabilityId,
            processId = boundaryPacket?.processId
        )
        val primaryProcess = when {
            candidatePrimaryProcess != null && explicitPrimaryProcess != null && candidatePrimaryProcess != explicitPrimaryProcess -> candidatePrimaryProcess
            candidatePrimaryProcess != null && inferredPrimaryProcess != null && candidatePrimaryProcess != inferredPrimaryProcess -> candidatePrimaryProcess
            candidatePrimaryProcess != null -> candidatePrimaryProcess
            explicitPrimaryProcess != null && inferredPrimaryProcess != null && explicitPrimaryProcess != inferredPrimaryProcess -> inferredPrimaryProcess
            explicitPrimaryProcess != null -> explicitPrimaryProcess
            inferredPrimaryProcess != null -> inferredPrimaryProcess
            else -> positiveMentions.firstOrNull()
        }
        val secondaryMentions = positiveMentions.filterNot { value -> value == primaryProcess }.distinct()
        val forbiddenProcesses = segments
            .filter { segment -> segment.role == StructuredTaskIntentSegmentRole.NEGATION }
            .flatMap { segment -> extractProcessMentions(segment.text) }
            .distinct()
        val confidence = when {
            explicitPrimaryProcess != null -> 0.92
            primaryProcess != null && secondaryMentions.isEmpty() -> maxOf(activeCandidate?.confidence ?: 0.0, 0.68)
            primaryProcess != null -> maxOf(activeCandidate?.confidence ?: 0.0, 0.54)
            else -> activeCandidate?.confidence ?: 0.0
        }

        return StructuredTaskIntent(
            appScope = appScope,
            primaryProcess = primaryProcess,
            secondaryMentions = secondaryMentions,
            forbiddenProcesses = forbiddenProcesses,
            constraints = constraintLines,
            historicalContext = historicalLines,
            slotKeys = slotKeys,
            preservedSlotValues = preservedSlotValues,
            recommendedSlotValues = recommendedSlotValues,
            preferenceRecallDebugSnapshot = preferenceRecommendationResolution?.recallBundle?.toPreferenceRecallDebugSnapshot(),
            preferenceMappingTrace = preferenceRecommendationResolution?.recommendedDetailSlots?.toPreferenceSlotMappingTrace(),
            confidence = confidence,
            needsModelDisambiguation = primaryProcess == null || secondaryMentions.size > 1 || confidence < 0.55,
            segments = segments
        )
    }

    private fun buildGuidanceLines(
        boundaryPacket: TaskExecutionBoundaryPacket?,
        activeCandidate: IntentCandidate?
    ): List<String> {
        val authoritativeSlotSnapshot = boundaryPacket?.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")
        return buildList {
            boundaryPacket?.reasonSummary?.trim()?.takeIf { value -> value.isNotBlank() }?.let(::add)
            activeCandidate?.evidence?.trim()?.takeIf { value -> value.isNotBlank() }?.let(::add)
            boundaryPacket?.targetKey?.trim()?.takeIf { value -> value.isNotBlank() }?.let { targetKey ->
                add("${DetailSlotKey.TARGET_OBJECT.contractName}=$targetKey")
            }
            if (authoritativeSlotSnapshot != null) {
                authoritativeSlotSnapshot.resolvedSlots.forEach { (slotKey, slotValue) ->
                    val trimmedValue = slotValue.trim()
                    if (trimmedValue.isNotBlank()) {
                        add("$slotKey=$trimmedValue")
                    }
                }
            } else {
                boundaryPacket?.detailSlots.orEmpty().forEach { (detailSlotKey, rawValue) ->
                    val trimmedValue = rawValue.trim()
                    if (trimmedValue.isBlank()) {
                        return@forEach
                    }
                    add("${detailSlotKey.contractName}=$trimmedValue")
                }
                activeCandidate?.detailSlots.orEmpty().forEach { slot -> add(slot.toGuidanceLine()) }
            }
        }
    }
}

private fun DetailSlot.toGuidanceLine(): String {
    return "${key.contractName}=${value.trim()}"
}

private fun isNegativeReferenceGuidance(value: String): Boolean {
    val normalized = value.lowercase(Locale.US)
    return listOf("不要", "avoid", "not", "no ", "禁止").any(normalized::contains)
}
