package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.extractCanonicalAppScope

internal object ExecutionGuidanceAssembler {
    fun assemble(
        taskIntent: StructuredTaskIntent?,
        boundaryPacket: TaskExecutionBoundaryPacket? = null,
        now: Long = System.currentTimeMillis()
    ): ProcessGuidanceLayer? {
        if (taskIntent == null && boundaryPacket == null) {
            return null
        }
        val appScope = resolvePreferredAppScope(
            taskIntent?.appScope,
            extractCanonicalAppScope(boundaryPacket?.capabilityId)
        )
        val primaryProcess = taskIntent?.primaryProcess
            ?: resolveBoundaryPacketPrimaryProcess(boundaryPacket)
        val processScope = resolvePreferredProcessScope(
            appScope = appScope,
            preferredProcessScope = boundaryPacket?.processId,
            primaryProcess = primaryProcess
        )
        val domainScope = inferDomainScope(appScope, primaryProcess)
        val taskLabel = boundaryPacket?.objectiveSummary?.trim()?.takeIf { value -> value.isNotBlank() }
        val planSummary = boundaryPacket?.planSummary?.trim()?.takeIf { value -> value.isNotBlank() }
        val semanticSummary = boundaryPacket?.reasonSummary?.trim()?.takeIf { value -> value.isNotBlank() }
            ?: boundaryPacket?.reasonSummary?.trim()?.takeIf { value -> value.isNotBlank() }
        val riskBoundary = boundaryPacket?.riskSummary?.trim()?.takeIf { value -> value.isNotBlank() }
        val guidanceLines = buildList {
            taskLabel?.let { value -> add("task=$value") }
            planSummary?.let { value -> add("plan=$value") }
            semanticSummary?.let { value -> add("semantic=$value") }
            riskBoundary?.let { value -> add("risk=$value") }
            addAll(taskIntent?.constraints.orEmpty().take(2).map { value -> "constraint=$value" })
            addAll(taskIntent?.historicalContext.orEmpty().take(2).map { value -> "history=$value" })
        }
        return ProcessGuidanceLayer(
            domainScope = domainScope,
            appScope = appScope,
            processScope = processScope,
            guidanceLines = guidanceLines,
            appSpecificCautions = buildAppSpecificCautions(appScope, primaryProcess),
            generatedAt = now
        )
    }
}

internal fun buildReferenceSummaryLines(
    guidanceLayer: ProcessGuidanceLayer,
    candidateReferences: List<CandidateProcessReference>,
    preferredReference: CandidateProcessReference?,
    selectionSummary: String?
): List<String> {
    return buildList {
        guidanceLayer.domainScope?.let { value -> add("domain_scope=$value") }
        guidanceLayer.appScope?.let { value -> add("app_scope=$value") }
        guidanceLayer.processScope?.let { value -> add("process_scope=$value") }
        selectionSummary?.takeIf { value -> value.isNotBlank() }?.let { value -> add("selection=$value") }
        preferredReference?.let { candidate ->
            add("preferred_candidate=${candidate.assetName};score=${formatReuseScore(candidate.score)}")
        }
        candidateReferences.forEachIndexed { index, candidate ->
            add("candidate_${index + 1}=${candidate.assetName};score=${formatReuseScore(candidate.score)}")
        }
    }
}

internal fun buildSelectedStageHints(
    preferredReference: CandidateProcessReference?,
    candidateReferences: List<CandidateProcessReference>
): List<String> {
    return preferredReference?.stageReferences
        ?.map { reference -> reference.stageName }
        ?.filter { value -> value.isNotBlank() }
        ?.take(3)
        ?.ifEmpty {
            candidateReferences.flatMap { candidate -> candidate.stages }
                .filter { value -> value.isNotBlank() }
                .distinct()
                .take(3)
        }
        ?: candidateReferences.flatMap { candidate -> candidate.stages }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .take(3)
}

internal fun buildWhySelected(
    guidanceLayer: ProcessGuidanceLayer,
    preferredReference: CandidateProcessReference?,
    selectionSummary: String?,
    candidateReferences: List<CandidateProcessReference>
): List<String> {
    return buildList {
        preferredReference?.verificationSignals
            ?.firstOrNull { value -> value.isNotBlank() }
            ?.let { value -> add("matched_verification=$value") }
        preferredReference?.pageSemanticAnchors
            ?.firstOrNull()
            ?.let { anchor ->
                listOf(anchor.semanticRole, anchor.pageSignature)
                    .filterNotNull()
                    .joinToString("@")
                    .takeIf { value -> value.isNotBlank() }
                    ?.let { value -> add("matched_anchor=$value") }
            }
        preferredReference?.exemplarActionSummaries
            ?.firstOrNull()
            ?.let { exemplar ->
                add("matched_exemplar=${exemplar.stepType}:${exemplar.actionType.name}")
            }
        selectionSummary?.takeIf { value -> value.isNotBlank() }?.let(::add)
        preferredReference?.processScope?.let { value -> add("matched_process_scope=$value") }
        preferredReference?.appScope?.let { value -> add("matched_app_scope=$value") }
        preferredReference?.semanticDescription
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> add("semantic_fit=$value") }
        if (isEmpty() && candidateReferences.isNotEmpty()) {
            add("ranked_ready_candidates=${candidateReferences.size}")
        }
        if (isEmpty()) {
            guidanceLayer.processScope?.let { value -> add("requested_process_scope=$value") }
        }
    }
}

internal fun buildReferenceCautions(
    guidanceLayer: ProcessGuidanceLayer,
    taskIntent: StructuredTaskIntent,
    preferredReference: CandidateProcessReference?
): List<String> {
    return buildList {
        addAll(guidanceLayer.appSpecificCautions.take(2))
        addAll(taskIntent.constraints.take(2))
        addAll(taskIntent.historicalContext.take(1))
        preferredReference?.failurePatterns.orEmpty().take(2).forEach { pattern ->
            add("failure_pattern=${pattern.failureMode}")
            addAll(pattern.recoveryHints.take(1).map { hint -> "recovery_hint=$hint" })
        }
    }.map { value -> value.trim() }
        .filter { value -> value.isNotBlank() }
        .distinct()
}