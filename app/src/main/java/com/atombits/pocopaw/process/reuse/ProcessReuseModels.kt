package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.PreferenceRecallDebugSnapshot
import com.atombits.pocopaw.PreferenceSlotMappingTrace
import com.atombits.pocopaw.ProcessExemplarActionSummary
import com.atombits.pocopaw.ProcessFailurePattern
import com.atombits.pocopaw.ProcessPageSemanticAnchor
import com.atombits.pocopaw.ProcessSlotHint
import com.atombits.pocopaw.ProcessStageReference

enum class StructuredTaskIntentSegmentRole {
    PRIMARY_TASK,
    CONSTRAINT,
    HISTORICAL_FAILURE,
    GUIDANCE,
    NEGATION,
    NOISE
}

data class StructuredTaskIntentSegment(
    val role: StructuredTaskIntentSegmentRole,
    val text: String
)

data class StructuredTaskIntent(
    val appScope: String? = null,
    val primaryProcess: String? = null,
    val secondaryMentions: List<String> = emptyList(),
    val forbiddenProcesses: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val historicalContext: List<String> = emptyList(),
    val slotKeys: List<String> = emptyList(),
    val preservedSlotValues: Map<String, String> = emptyMap(),
    val recommendedSlotValues: Map<String, String> = emptyMap(),
    val preferenceRecallDebugSnapshot: PreferenceRecallDebugSnapshot? = null,
    val preferenceMappingTrace: PreferenceSlotMappingTrace? = null,
    val confidence: Double = 0.0,
    val needsModelDisambiguation: Boolean = false,
    val segments: List<StructuredTaskIntentSegment> = emptyList()
)

data class ProcessGuidanceLayer(
    val domainScope: String? = null,
    val appScope: String? = null,
    val processScope: String? = null,
    val guidanceLines: List<String> = emptyList(),
    val appSpecificCautions: List<String> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

data class CandidateProcessReference(
    val assetId: String,
    val assetName: String,
    val appScope: String? = null,
    val processScope: String? = null,
    val processEnum: String? = null,
    val semanticDescription: String = "",
    val businessProcessName: String = "",
    val acceptanceCriteria: List<String> = emptyList(),
    val stages: List<String> = emptyList(),
    val optimizedProcessTrace: List<String> = emptyList(),
    val readyWeight: Double = 0.0,
    val score: Double = 0.0,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val stageReferences: List<ProcessStageReference> = emptyList(),
    val pageSemanticAnchors: List<ProcessPageSemanticAnchor> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val exemplarActionSummaries: List<ProcessExemplarActionSummary> = emptyList(),
    val failurePatterns: List<ProcessFailurePattern> = emptyList(),
    val slotHints: List<ProcessSlotHint> = emptyList()
)

data class ExecutionReferenceBundle(
    val taskIntent: StructuredTaskIntent,
    val guidanceLayer: ProcessGuidanceLayer,
    val candidateContext: CandidateProcessReferenceContext,
    val preferredReference: CandidateProcessReference? = null
)

data class CandidateProcessReferenceContext(
    val taskIntent: StructuredTaskIntent = StructuredTaskIntent(),
    val candidateReferences: List<CandidateProcessReference> = emptyList(),
    val referenceSummaryLines: List<String> = emptyList(),
    val emptyReason: String? = null,
    val selectedByModel: Boolean = false,
    val generatedAt: Long = System.currentTimeMillis(),
    val selectedStageHints: List<String> = emptyList(),
    val whySelected: List<String> = emptyList(),
    val referenceCautions: List<String> = emptyList()
)