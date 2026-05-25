package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.summaryLine
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.ProcessReferenceSelectionPromptSpec
import com.atombits.pocopaw.PromptCenter
import com.atombits.pocopaw.toPromptSummary

internal object ReferenceSelector {
    fun select(
        taskIntent: StructuredTaskIntent,
        guidanceLayer: ProcessGuidanceLayer,
        rankedCandidates: List<CandidateProcessReference>,
        boundaryPacket: TaskExecutionBoundaryPacket? = null,
        previousContext: CandidateProcessReferenceContext? = null,
        selectionResolver: ProcessReferenceSelectionResolver = SemanticProcessReferenceSelectionResolver(),
        now: Long = System.currentTimeMillis()
    ): ExecutionReferenceBundle {
        val selectionOutcome = when {
            rankedCandidates.isEmpty() -> ProcessReferenceSelectionOutcome()
            selectionResolver !== LocalProcessReferenceSelectionResolver &&
                selectionResolver !is SemanticProcessReferenceSelectionResolver -> {
                val packet = buildSelectionPacket(taskIntent, guidanceLayer, rankedCandidates)
                selectionResolver.select(packet, rankedCandidates, now)
            }

            shouldInvokeModelSelection(taskIntent, rankedCandidates) -> {
                val packet = buildSelectionPacket(taskIntent, guidanceLayer, rankedCandidates)
                selectionResolver.select(packet, rankedCandidates, now)
            }

            else -> LocalProcessReferenceSelectionResolver.select(
                packet = buildSelectionPacket(taskIntent, guidanceLayer, rankedCandidates),
                localRankedCandidates = rankedCandidates,
                now = now
            )
        }
        val selectedCandidates = selectionOutcome.candidateReferences
            .ifEmpty { rankedCandidates.take(maxProcessReferenceSelectionCount) }
            .take(maxProcessReferenceSelectionCount)
        val preferredReference = resolvePreferredReference(
            references = selectedCandidates.ifEmpty { rankedCandidates },
            boundaryPacket = boundaryPacket,
            selectedReferenceId = selectionOutcome.selectedReferenceId
        )
        val emptyReason = if (selectedCandidates.isEmpty()) {
            previousContext?.emptyReason ?: "No ready process asset matched the current structured task intent."
        } else {
            null
        }
        val referenceSummaryLines = buildReferenceSummaryLines(
            guidanceLayer = guidanceLayer,
            candidateReferences = selectedCandidates,
            preferredReference = preferredReference,
            selectionSummary = selectionOutcome.selectionSummary
        )
        val candidateContext = CandidateProcessReferenceContext(
            taskIntent = taskIntent,
            candidateReferences = selectedCandidates,
            referenceSummaryLines = referenceSummaryLines,
            emptyReason = emptyReason,
            selectedByModel = selectionOutcome.selectedByModel,
            generatedAt = now,
            selectedStageHints = if (selectionOutcome.selectedByModel && selectionOutcome.selectedStageHints.isNotEmpty()) {
                selectionOutcome.selectedStageHints
            } else {
                (selectionOutcome.selectedStageHints +
                    buildSelectedStageHints(preferredReference, selectedCandidates))
                    .filter { value -> value.isNotBlank() }
                    .distinct()
                    .take(3)
            },
            whySelected = if (selectionOutcome.selectedByModel && selectionOutcome.whySelected.isNotEmpty()) {
                selectionOutcome.whySelected
            } else {
                (buildWhySelected(guidanceLayer, preferredReference, selectionOutcome.selectionSummary, selectedCandidates) +
                    selectionOutcome.whySelected)
                    .filter { value -> value.isNotBlank() }
                    .distinct()
            },
            referenceCautions = if (selectionOutcome.selectedByModel && selectionOutcome.referenceCautions.isNotEmpty()) {
                selectionOutcome.referenceCautions
            } else {
                (buildReferenceCautions(guidanceLayer, taskIntent, preferredReference) +
                    selectionOutcome.referenceCautions)
                    .filter { value -> value.isNotBlank() }
                    .distinct()
            }
        )
        return ExecutionReferenceBundle(
            taskIntent = taskIntent,
            guidanceLayer = guidanceLayer,
            candidateContext = candidateContext,
            preferredReference = preferredReference
        )
    }

    fun resolvePreferredReference(
        references: List<CandidateProcessReference>,
        boundaryPacket: TaskExecutionBoundaryPacket? = null,
        selectedReferenceId: String? = null
    ): CandidateProcessReference? {
        if (references.isEmpty()) {
            return null
        }
        selectedReferenceId?.takeIf { value -> value.isNotBlank() }?.let { referenceId ->
            references.firstOrNull { reference -> reference.assetId == referenceId }?.let { reference ->
                return reference
            }
        }
        val preferredProcess = resolveBoundaryPacketPrimaryProcess(boundaryPacket)
        if (preferredProcess != null) {
            references.firstOrNull { reference -> reference.processEnum == preferredProcess }?.let { reference ->
                return reference
            }
        }
        return references.maxByOrNull { reference -> reference.score }
    }

    private fun shouldInvokeModelSelection(
        taskIntent: StructuredTaskIntent,
        rankedCandidates: List<CandidateProcessReference>
    ): Boolean {
        if (rankedCandidates.size <= 1) {
            return false
        }
        if (taskIntent.needsModelDisambiguation) {
            return true
        }
        val topSemanticCueScore = rankedCandidates[0].scoreBreakdown["semanticCueScore"] ?: 0.0
        val runnerUpSemanticCueScore = rankedCandidates[1].scoreBreakdown["semanticCueScore"] ?: 0.0
        if (topSemanticCueScore > 0.0 && topSemanticCueScore - runnerUpSemanticCueScore >= 4.0) {
            return false
        }
        return rankedCandidates[0].score - rankedCandidates[1].score < modelSelectionGapThreshold
    }

    private fun buildSelectionPacket(
        taskIntent: StructuredTaskIntent,
        guidanceLayer: ProcessGuidanceLayer,
        rankedCandidates: List<CandidateProcessReference>
    ) = PromptCenter.buildProcessReferenceSelectionPacket(
        ProcessReferenceSelectionPromptSpec(
            taskIntentBundle = buildTaskIntentBundle(taskIntent),
            processCatalogBundle = buildProcessCatalogBundle(rankedCandidates),
            processGuidanceBundle = buildProcessGuidanceBundle(guidanceLayer),
            maxSelectionCount = maxProcessReferenceSelectionCount
        )
    )
}

private fun buildTaskIntentBundle(taskIntent: StructuredTaskIntent): String {
    return buildString {
        taskIntent.appScope?.let { value -> appendLine("app_scope=$value") }
        taskIntent.primaryProcess?.let { value -> appendLine("primary_process=$value") }
        if (taskIntent.secondaryMentions.isNotEmpty()) {
            appendLine("secondary_mentions=${taskIntent.secondaryMentions.joinToString(",")}")
        }
        if (taskIntent.forbiddenProcesses.isNotEmpty()) {
            appendLine("forbidden_processes=${taskIntent.forbiddenProcesses.joinToString(",")}")
        }
        if (taskIntent.constraints.isNotEmpty()) {
            appendLine("constraints=${taskIntent.constraints.joinToString(" | ")}")
        }
        if (taskIntent.historicalContext.isNotEmpty()) {
            appendLine("historical_context=${taskIntent.historicalContext.joinToString(" | ")}")
        }
        appendLine("task_slot_keys=${taskIntent.slotKeys.joinToString(",").ifBlank { "none" }}")
        appendLine(
            "preserved_slot_values=${taskIntent.preservedSlotValues.entries.joinToString(",") { (slotKey, slotValue) -> "${slotKey}=${slotValue}" }.ifBlank { "none" }}"
        )
        appendLine(
            "recommended_slot_values=${taskIntent.recommendedSlotValues.entries.joinToString(",") { (slotKey, slotValue) -> "${slotKey}=${slotValue}" }.ifBlank { "none" }}"
        )
        taskIntent.preferenceRecallDebugSnapshot?.let { snapshot ->
            appendLine("preference_recall_debug=${snapshot.summaryLine()}")
        }
        taskIntent.preferenceMappingTrace?.let { trace ->
            appendLine("preference_mapping_trace=${trace.summaryLine()}")
        }
        appendLine("needs_model_disambiguation=${taskIntent.needsModelDisambiguation}")
        appendLine("confidence=${formatReuseScore(taskIntent.confidence)}")
        taskIntent.segments.take(8).forEach { segment ->
            appendLine("${segment.role.name}:${segment.text}")
        }
    }.trim()
}

private fun buildProcessCatalogBundle(rankedCandidates: List<CandidateProcessReference>): String {
    return rankedCandidates.take(12).joinToString("\n") { candidate ->
        buildString {
            append("asset_id=${candidate.assetId}")
            append(" | asset_name=${candidate.assetName}")
            candidate.appScope?.let { value -> append(" | app_scope=$value") }
            candidate.processScope?.let { value -> append(" | process_scope=$value") }
            candidate.processEnum?.let { value -> append(" | process_enum=$value") }
            append(" | score=${formatReuseScore(candidate.score)}")
            if (candidate.semanticDescription.isNotBlank()) {
                append(" | semantic=${candidate.semanticDescription}")
            }
            if (candidate.stageReferences.isNotEmpty()) {
                append(" | stages=")
                append(candidate.stageReferences.joinToString(",") { reference -> reference.stageName })
            }
            if (candidate.pageSemanticAnchors.isNotEmpty()) {
                append(" | anchors=")
                append(candidate.pageSemanticAnchors.joinToString(",") { anchor ->
                    listOf(anchor.semanticRole, anchor.pageSignature).filterNotNull().joinToString("@")
                })
            }
            if (candidate.verificationSignals.isNotEmpty()) {
                append(" | verification=")
                append(candidate.verificationSignals.joinToString(","))
            }
            if (candidate.exemplarActionSummaries.isNotEmpty()) {
                append(" | exemplars=")
                append(candidate.exemplarActionSummaries.joinToString(",") { exemplar ->
                    listOf(exemplar.stepType, exemplar.actionType.name, exemplar.locatorHint, exemplar.pageSignature)
                        .filterNotNull()
                        .joinToString("@")
                })
            }
            if (candidate.failurePatterns.isNotEmpty()) {
                append(" | failure_patterns=")
                append(candidate.failurePatterns.joinToString(",") { pattern -> pattern.failureMode })
            }
            if (candidate.slotHints.isNotEmpty()) {
                append(" | slot_hints=")
                append(candidate.slotHints.toPromptSummary())
            }
        }
    }
}

private fun buildProcessGuidanceBundle(guidanceLayer: ProcessGuidanceLayer): String {
    return buildString {
        guidanceLayer.domainScope?.let { value -> appendLine("domain_scope=$value") }
        guidanceLayer.appScope?.let { value -> appendLine("app_scope=$value") }
        guidanceLayer.processScope?.let { value -> appendLine("process_scope=$value") }
        guidanceLayer.guidanceLines.forEach { value -> appendLine(value) }
        guidanceLayer.appSpecificCautions.forEach { value -> appendLine("caution=$value") }
    }.trim()
}
