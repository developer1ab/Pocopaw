package com.atombits.pocopaw.process.reuse

import java.util.Locale

internal object ReferenceScorer {
    fun rank(
        catalogRows: List<ProcessCatalogRow>,
        taskIntent: StructuredTaskIntent,
        guidanceLayer: ProcessGuidanceLayer
    ): List<CandidateProcessReference> {
        return catalogRows
            .mapNotNull { row ->
                scoreCatalogRow(
                    row = row,
                    taskIntent = taskIntent,
                    guidanceLayer = guidanceLayer
                )
            }
            .sortedByDescending { candidate -> candidate.score }
            .take(maxProcessCatalogRows)
    }

    private fun scoreCatalogRow(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent,
        guidanceLayer: ProcessGuidanceLayer
    ): CandidateProcessReference? {
        if (taskIntent.primaryProcess != null && row.processEnum != taskIntent.primaryProcess) {
            return null
        }
        val domainScore = if (!guidanceLayer.domainScope.isNullOrBlank() && row.domain.equals(guidanceLayer.domainScope, ignoreCase = true)) {
            60.0
        } else {
            0.0
        }
        val appScore = if (!guidanceLayer.appScope.isNullOrBlank() && row.appScope.equals(guidanceLayer.appScope, ignoreCase = true)) {
            50.0
        } else {
            0.0
        }
        val processScopeScore = if (!guidanceLayer.processScope.isNullOrBlank() && row.processScope.equals(guidanceLayer.processScope, ignoreCase = true)) {
            24.0
        } else {
            0.0
        }
        val primaryProcessScore = if (taskIntent.primaryProcess != null && row.processEnum == taskIntent.primaryProcess) {
            26.0
        } else {
            0.0
        }
        val secondaryProcessScore = if (taskIntent.secondaryMentions.any { mention -> mention == row.processEnum || row.searchBlob.contains(mention) }) {
            10.0
        } else {
            0.0
        }
        val processHits = buildProcessHitTokens(taskIntent)
            .count { token -> token.isNotBlank() && row.searchBlob.contains(token) }
        val processScore = minOf(processHits * 12.0, 36.0)
        val semanticCueScore = scoreSemanticCueMatches(row, taskIntent)
        val slotOverlapScore = scoreSlotHintOverlap(row, taskIntent)
        val valuePreserveScore = scoreValuePreserveHints(row, taskIntent)
        val recommendedSlotScore = scoreRecommendedPrimaryFilterHints(row, taskIntent)
        val missingPrimaryFilterPenalty = scoreMissingPrimaryFilters(row, taskIntent)
        val hasGroundedReuseSignal = primaryProcessScore > 0.0 ||
            secondaryProcessScore > 0.0 ||
            processScore > 0.0 ||
            semanticCueScore > 0.0 ||
            slotOverlapScore > 0.0 ||
            valuePreserveScore > 0.0
        if (taskIntent.primaryProcess == null && !hasGroundedReuseSignal) {
            return null
        }
        val confidenceScore = row.successCount.coerceIn(0, 10).toDouble()
        val readyWeightScore = row.readyWeight.coerceIn(0.0, 1.0) * 12.0
        val total = domainScore + appScore + processScopeScore + primaryProcessScore +
            secondaryProcessScore + processScore + semanticCueScore + slotOverlapScore +
            valuePreserveScore + recommendedSlotScore + missingPrimaryFilterPenalty + confidenceScore + readyWeightScore
        if (total <= 0.0) {
            return null
        }
        return CandidateProcessReference(
            assetId = row.assetId,
            assetName = row.assetName,
            appScope = row.appScope,
            processScope = row.processScope,
            processEnum = row.processEnum,
            semanticDescription = row.semanticDescription,
            businessProcessName = row.businessProcessName,
            acceptanceCriteria = row.acceptanceCriteria,
            stages = row.stages,
            optimizedProcessTrace = row.optimizedProcessTrace,
            readyWeight = row.readyWeight,
            score = total,
            scoreBreakdown = linkedMapOf(
                "domainScore" to domainScore,
                "appScore" to appScore,
                "processScopeScore" to processScopeScore,
                "primaryProcessScore" to primaryProcessScore,
                "secondaryProcessScore" to secondaryProcessScore,
                "processScore" to processScore,
                "semanticCueScore" to semanticCueScore,
                "slotOverlapScore" to slotOverlapScore,
                "valuePreserveScore" to valuePreserveScore,
                "recommendedSlotScore" to recommendedSlotScore,
                "missingPrimaryFilterPenalty" to missingPrimaryFilterPenalty,
                "confidenceScore" to confidenceScore,
                "readyWeightScore" to readyWeightScore
            ),
            stageReferences = row.stageReferences.ifEmpty {
                row.stages.map { stage ->
                    com.atombits.pocopaw.ProcessStageReference(
                        stageName = stage,
                        stageGoal = stage
                    )
                }
            },
            pageSemanticAnchors = row.pageSemanticAnchors,
            verificationSignals = row.verificationSignals,
            exemplarActionSummaries = row.exemplarActionSummaries,
            failurePatterns = row.failurePatterns,
            slotHints = row.slotHints
        )
    }

    private fun scoreSlotHintOverlap(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent
    ): Double {
        if (row.slotHints.isEmpty() || taskIntent.slotKeys.isEmpty()) {
            return 0.0
        }
        val overlapCount = row.slotHints
            .distinctBy { hint -> listOf(hint.slotKey, hint.hintRole).joinToString("|") }
            .count { hint -> taskIntent.slotKeys.contains(hint.slotKey) }
        return minOf(overlapCount * 6.0, 18.0)
    }

    private fun scoreValuePreserveHints(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent
    ): Double {
        if (row.slotHints.isEmpty() || taskIntent.preservedSlotValues.isEmpty()) {
            return 0.0
        }
        return row.slotHints.filter { hint -> hint.hintRole == "VALUE_PRESERVE" }
            .sumOf { hint ->
                val taskValue = taskIntent.preservedSlotValues[hint.slotKey]?.trim().orEmpty()
                val exampleValue = hint.exampleValue?.trim().orEmpty()
                when {
                    taskValue.isBlank() -> 0.0
                    exampleValue.isNotBlank() && taskValue.equals(exampleValue, ignoreCase = true) -> 6.0
                    else -> 4.0
                }
            }
            .coerceAtMost(12.0)
    }

    private fun scoreRecommendedPrimaryFilterHints(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent
    ): Double {
        if (row.slotHints.isEmpty() || taskIntent.recommendedSlotValues.isEmpty()) {
            return 0.0
        }
        return row.slotHints.filter { hint ->
            hint.hintRole == "PRIMARY_FILTER" && !taskIntent.slotKeys.contains(hint.slotKey)
        }
            .sumOf { hint ->
                val recommendedValue = taskIntent.recommendedSlotValues[hint.slotKey]?.trim().orEmpty()
                val exampleValue = hint.exampleValue?.trim().orEmpty()
                when {
                    recommendedValue.isBlank() -> 0.0
                    exampleValue.isNotBlank() && recommendedValue.equals(exampleValue, ignoreCase = true) -> 4.0
                    else -> 2.0
                }
            }
            .coerceAtMost(8.0)
    }

    private fun scoreMissingPrimaryFilters(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent
    ): Double {
        if (row.slotHints.isEmpty()) {
            return 0.0
        }
        val missingCount = row.slotHints.count { hint ->
            hint.hintRole == "PRIMARY_FILTER" && !taskIntent.slotKeys.contains(hint.slotKey)
        }
        return -(missingCount * 8.0).coerceAtMost(24.0)
    }

    private fun scoreSemanticCueMatches(
        row: ProcessCatalogRow,
        taskIntent: StructuredTaskIntent
    ): Double {
        val taskText = taskIntent.segments.joinToString(" ") { segment -> segment.text }
            .lowercase(Locale.US)
            .trim()
        if (taskText.isBlank()) {
            return 0.0
        }
        val stageHits = row.stageReferences.asSequence()
            .flatMap { reference -> sequenceOf(reference.stageName, reference.stageGoal) + reference.verificationSignals.asSequence() }
            .countDistinctCueMatches(taskText)
        val anchorHits = row.pageSemanticAnchors.asSequence()
            .flatMap { anchor ->
                sequenceOf(anchor.stageName, anchor.semanticRole, anchor.pageSignature) +
                    anchor.locatorHints.asSequence() +
                    anchor.verificationSignals.asSequence() +
                    anchor.notes.asSequence()
            }
            .countDistinctCueMatches(taskText)
        val verificationHits = row.verificationSignals.asSequence().countDistinctCueMatches(taskText)
        return minOf(stageHits * 6.0, 12.0) +
            minOf(anchorHits * 5.0, 10.0) +
            minOf(verificationHits * 4.0, 8.0)
    }
}