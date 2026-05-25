package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.ProcessExemplarActionSummary
import com.atombits.pocopaw.ProcessFailurePattern
import com.atombits.pocopaw.ProcessPageSemanticAnchor
import com.atombits.pocopaw.ProcessSlotHint
import com.atombits.pocopaw.ProcessStageReference
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ReadyProcessAsset
import com.atombits.pocopaw.inferCanonicalProcessAction
import com.atombits.pocopaw.inferCanonicalProcessDomain
import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.resolveReadyProcessAssetForEntry
import com.atombits.pocopaw.sanitizeCanonicalProcessId
import java.util.Locale

internal interface ReferenceCatalogRepository {
    fun listCatalogRows(
        store: PrototypeStoreData,
        limit: Int = maxProcessCatalogRows
    ): List<ProcessCatalogRow>
}

internal object PrototypeStoreReferenceCatalogRepository : ReferenceCatalogRepository {
    override fun listCatalogRows(
        store: PrototypeStoreData,
        limit: Int
    ): List<ProcessCatalogRow> {
        val readyAssets = store.readyProcessAssets
        return PrototypeStoreProcessAssetRepository.listReady(store, limit = limit)
            .asSequence()
            .mapNotNull { entry ->
                toCatalogRow(entry, readyAssets)
            }
            .take(limit)
            .toList()
    }
}

internal data class ProcessCatalogRow(
    val assetId: String,
    val assetName: String,
    val domain: String? = null,
    val appScope: String,
    val processScope: String? = null,
    val processEnum: String? = null,
    val semanticDescription: String = "",
    val businessProcessName: String = "",
    val acceptanceCriteria: List<String> = emptyList(),
    val stages: List<String> = emptyList(),
    val optimizedProcessTrace: List<String> = emptyList(),
    val successCount: Int = 0,
    val readyWeight: Double = 0.0,
    val extraSearchText: List<String> = emptyList(),
    val stageReferences: List<ProcessStageReference> = emptyList(),
    val pageSemanticAnchors: List<ProcessPageSemanticAnchor> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val exemplarActionSummaries: List<ProcessExemplarActionSummary> = emptyList(),
    val failurePatterns: List<ProcessFailurePattern> = emptyList(),
    val slotHints: List<ProcessSlotHint> = emptyList()
) {
    val searchBlob: String = listOf(
        assetName,
        domain.orEmpty(),
        appScope,
        processScope.orEmpty(),
        processEnum.orEmpty(),
        semanticDescription,
        businessProcessName,
        acceptanceCriteria.joinToString(" "),
        stages.joinToString(" "),
        optimizedProcessTrace.joinToString(" "),
        stageReferences.joinToString(" ") { reference ->
            listOf(
                reference.stageName,
                reference.stageGoal,
                reference.verificationSignals.joinToString(" "),
                reference.transitionNotes.joinToString(" ")
            ).joinToString(" ")
        },
        pageSemanticAnchors.joinToString(" ") { anchor ->
            listOf(
                anchor.stageName,
                anchor.semanticRole,
                anchor.pageSignature,
                anchor.locatorHints.joinToString(" "),
                anchor.verificationSignals.joinToString(" "),
                anchor.notes.joinToString(" ")
            ).joinToString(" ")
        },
        verificationSignals.joinToString(" "),
        exemplarActionSummaries.joinToString(" ") { exemplar ->
            listOf(
                exemplar.stageName,
                exemplar.stepType,
                exemplar.actionType.name,
                exemplar.outcomeSignal,
                exemplar.locatorHint,
                exemplar.pageSignature,
                exemplar.note
            ).joinToString(" ")
        },
        failurePatterns.joinToString(" ") { pattern ->
            listOf(
                pattern.stageName,
                pattern.failureMode,
                pattern.evidenceSignals.joinToString(" "),
                pattern.recoveryHints.joinToString(" "),
                pattern.note
            ).joinToString(" ")
        },
        slotHints.joinToString(" ") { hint ->
            listOf(hint.slotKey, hint.hintRole, hint.exampleValue).joinToString(" ")
        },
        extraSearchText.joinToString(" ")
    ).joinToString(" ").lowercase(Locale.US)
}

private fun toCatalogRow(
    entry: ProcessAssetEntry,
    readyAssets: List<ReadyProcessAsset>
): ProcessCatalogRow? {
    val appScope = entry.appScope.trim().ifBlank { return null }
    val sanitizedProcessScope = sanitizeCanonicalProcessId(entry.processScope)
    val matchedReadyAsset = resolveCatalogReadyAsset(entry, readyAssets)
    val processSearchText = listOf(
        entry.semanticDescription,
        entry.taskExample,
        entry.assetName,
        entry.businessProcessName,
        entry.reviewComment,
        matchedReadyAsset?.stageReferences.orEmpty().joinToString(" ") { reference ->
            listOf(
                reference.stageName,
                reference.stageGoal,
                reference.verificationSignals.joinToString(" "),
                reference.transitionNotes.joinToString(" ")
            ).joinToString(" ")
        },
        matchedReadyAsset?.pageSemanticAnchors.orEmpty().joinToString(" ") { anchor ->
            listOf(
                anchor.stageName,
                anchor.semanticRole,
                anchor.pageSignature,
                anchor.locatorHints.joinToString(" "),
                anchor.verificationSignals.joinToString(" "),
                anchor.notes.joinToString(" ")
            ).joinToString(" ")
        },
        matchedReadyAsset?.verificationSignals.orEmpty().joinToString(" "),
        matchedReadyAsset?.exemplarActionSummaries.orEmpty().joinToString(" ") { exemplar ->
            listOf(
                exemplar.stageName,
                exemplar.stepType,
                exemplar.actionType.name,
                exemplar.outcomeSignal,
                exemplar.locatorHint,
                exemplar.pageSignature,
                exemplar.note
            ).joinToString(" ")
        },
        matchedReadyAsset?.failurePatterns.orEmpty().joinToString(" ") { pattern ->
            listOf(
                pattern.stageName,
                pattern.failureMode,
                pattern.evidenceSignals.joinToString(" "),
                pattern.recoveryHints.joinToString(" "),
                pattern.note
            ).joinToString(" ")
        }
    ).map { value -> value.trim() }
        .filter { value -> value.isNotBlank() }
        .joinToString(" ")
    val processEnum = normalizeProcessHint(sanitizedProcessScope)
        ?: normalizeProcessHint(entry.businessProcessName)
        ?: normalizeProcessHint(entry.assetName)
        ?: normalizeProcessHint(
            inferCanonicalProcessAction(
                processId = sanitizedProcessScope.orEmpty(),
                objective = processSearchText,
                domain = inferCanonicalProcessDomain(
                    processId = sanitizedProcessScope.orEmpty(),
                    objective = processSearchText
                ),
                actionHint = processSearchText
            )
        )
    val processScope = resolvePreferredProcessScope(appScope, sanitizedProcessScope, processEnum)
        ?: sanitizedProcessScope?.trim()?.ifBlank { null }
    return ProcessCatalogRow(
        assetId = entry.id,
        assetName = entry.assetName.ifBlank { entry.businessProcessName.ifBlank { entry.originAssetId ?: entry.id } },
        domain = entry.domain.trim().ifBlank { null },
        appScope = appScope,
        processScope = processScope,
        processEnum = processEnum,
        semanticDescription = entry.semanticDescription,
        businessProcessName = entry.businessProcessName.ifBlank { entry.assetName },
        acceptanceCriteria = entry.businessAcceptanceCriteria,
        stages = extractBusinessStageNames(entry.businessStagesJson),
        optimizedProcessTrace = entry.optimizedProcessTrace,
        successCount = entry.successCount,
        readyWeight = maxOf(entry.readyWeight, matchedReadyAsset?.referenceWeight ?: 0.0),
        extraSearchText = listOf(entry.taskExample, entry.reviewComment, entry.diffSummary, entry.reliabilityAnalysis),
        stageReferences = matchedReadyAsset?.stageReferences.orEmpty(),
        pageSemanticAnchors = matchedReadyAsset?.pageSemanticAnchors.orEmpty(),
        verificationSignals = matchedReadyAsset?.verificationSignals.orEmpty(),
        exemplarActionSummaries = matchedReadyAsset?.exemplarActionSummaries.orEmpty(),
        failurePatterns = matchedReadyAsset?.failurePatterns.orEmpty(),
        slotHints = matchedReadyAsset?.slotHints.orEmpty()
    )
}

private fun resolveCatalogReadyAsset(
    entry: ProcessAssetEntry,
    readyAssets: List<ReadyProcessAsset>
): ReadyProcessAsset? {
    return resolveReadyProcessAssetForEntry(
        store = PrototypeStoreData(readyProcessAssets = readyAssets.toMutableList()),
        entry = entry
    )
}

private fun extractBusinessStageNames(businessStagesJson: String): List<String> {
    if (businessStagesJson.isBlank()) {
        return emptyList()
    }
    val objectStages = Regex("\"stage_name_nl\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(businessStagesJson)
        .map { result -> result.groupValues[1] }
        .filter { value -> value.isNotBlank() }
        .toList()
    if (objectStages.isNotEmpty()) {
        return objectStages
    }
    return Regex("\"([^\"]+)\"")
        .findAll(businessStagesJson)
        .map { result -> result.groupValues[1] }
        .filter { value -> value.isNotBlank() }
        .toList()
}