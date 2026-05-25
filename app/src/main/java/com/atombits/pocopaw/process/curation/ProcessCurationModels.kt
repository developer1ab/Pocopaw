package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.ProcessSlotHint
import com.atombits.pocopaw.ProcessExemplarActionSummary
import com.atombits.pocopaw.ProcessFailurePattern
import com.atombits.pocopaw.ProcessPageSemanticAnchor
import com.atombits.pocopaw.ProcessStageReference
import com.atombits.pocopaw.TaskSlotEvidenceSnapshot

import java.util.UUID

enum class ProcessAssetState {
    RECORDED,
    PENDING,
    READY,
    FAILED,
    SUPERSEDED
}

enum class ProcessAssetSourceType {
    TMP,
    CANDIDATE,
    RECORDED
}

enum class ProcessAssetEventType {
    CREATED,
    UPDATED,
    PROMOTED_READY,
    MARKED_FAILED,
    SUPERSEDED
}

data class BusinessProcessStage(
    val stageId: String = UUID.randomUUID().toString(),
    val stageNameNl: String = "",
    val stageGoalNl: String = "",
    val entrySignals: List<String> = emptyList(),
    val exitSignals: List<String> = emptyList(),
    val transitionConditions: List<String> = emptyList()
)

data class CanonicalProcessTraceBundle(
    val task: String = "",
    val appScope: String = "",
    val processScope: String = "",
    val canonicalTrace: List<String> = emptyList(),
    val summarizedActions: List<String> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val removedNoiseCount: Int = 0,
    val duplicateCollapseCount: Int = 0,
    val generatedAt: Long = System.currentTimeMillis()
)

data class StructuredProcessDraftResult(
    val processEnum: String? = null,
    val semanticDescription: String = "",
    val processName: String = "",
    val acceptanceCriteria: List<String> = emptyList(),
    val stages: List<BusinessProcessStage> = emptyList(),
    val optimizedProcessTrace: List<String> = emptyList(),
    val diffSummary: String = "",
    val reliabilityAnalysis: String = "",
    val decision: String = "",
    val confidence: Double = 0.0,
    val traceBundle: CanonicalProcessTraceBundle? = null,
    val structuredReferenceAsset: StructuredProcessReferenceDraftResult? = null
)

data class StructuredProcessReferenceDraftResult(
    val slotHints: List<ProcessSlotHint> = emptyList(),
    val stageReferences: List<ProcessStageReference> = emptyList(),
    val pageSemanticAnchors: List<ProcessPageSemanticAnchor> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val exemplarActionSummaries: List<ProcessExemplarActionSummary> = emptyList(),
    val failurePatterns: List<ProcessFailurePattern> = emptyList(),
    val generalizationNotes: List<String> = emptyList(),
    val referenceWeight: Double = 0.0
)

data class ProcessAssetEntry(
    val id: String = UUID.randomUUID().toString(),
    val domain: String = "",
    val appScope: String = "",
    val processScope: String = "",
    val sourceType: ProcessAssetSourceType = ProcessAssetSourceType.RECORDED,
    val assetName: String = "",
    val revision: Int = 1,
    val semanticDescription: String = "",
    val assetState: ProcessAssetState = ProcessAssetState.RECORDED,
    val assetUpdatedAt: Long = System.currentTimeMillis(),
    val taskExample: String = "",
    val planningTrace: String = "",
    val stepCount: Int = 0,
    val successCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val reviewComment: String = "",
    val businessProcessName: String = "",
    val businessAcceptanceCriteria: List<String> = emptyList(),
    val businessStagesJson: String = "",
    val optimizedProcessTrace: List<String> = emptyList(),
    val diffSummary: String = "",
    val reliabilityAnalysis: String = "",
    val reviewDecision: String = "",
    val reviewConfidence: Double = 0.0,
    val readyWeight: Double = 0.0,
    val originAssetId: String? = null,
    val slotEvidenceSnapshot: TaskSlotEvidenceSnapshot? = null,
    val slotHints: List<ProcessSlotHint> = emptyList(),
)

data class ProcessFinalizeResult(
    val resolvedAssetName: String = "",
    val resolvedEntryId: String? = null,
    val lineagePath: List<String> = emptyList(),
    val version: Int = 1,
    val decision: String = "",
    val versionBumped: Boolean = false,
    val supersededEntryIds: List<String> = emptyList()
)

data class ProcessAssetEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val assetEntryId: String? = null,
    val assetName: String? = null,
    val eventType: ProcessAssetEventType,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ProcessCurationSummary(
    val summaryId: String = UUID.randomUUID().toString(),
    val assetEntryId: String? = null,
    val assetName: String? = null,
    val assetState: ProcessAssetState? = null,
    val summary: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)