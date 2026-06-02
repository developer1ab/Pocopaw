package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.earnings.OpportunityNormalizationResult
import com.atombits.pocopaw.earnings.NormalizeOutcome
import com.atombits.pocopaw.earnings.RawScanItem
import com.atombits.pocopaw.earnings.TaskOpportunity
import java.security.MessageDigest
import java.util.Locale

interface OpportunityNormalizer {
    fun normalize(rawItem: RawScanItem): NormalizeOutcome
}

class DefaultOpportunityNormalizer(
    private val scanPolicy: EarningsScanPolicy = DefaultEarningsScanPolicy
) : OpportunityNormalizer {
    override fun normalize(rawItem: RawScanItem): NormalizeOutcome {
        val decision = scanPolicy.evaluate(rawItem)
        if (!decision.accepted) {
            return NormalizeOutcome(rejectedReason = decision.reason, notes = decision.notes)
        }
        val category = rawItem.modelCategory ?: return NormalizeOutcome(rejectedReason = "MISSING_CATEGORY")
        val displayName = rawItem.visibleTitle.trim().ifBlank {
            rawItem.evidenceText?.trim().orEmpty()
        }
        if (displayName.isBlank()) {
            return NormalizeOutcome(rejectedReason = "MISSING_DISPLAY_NAME")
        }
        val taskKey = ensureScopedTaskKey(
            appKey = rawItem.appId.stableKey,
            candidate = rawItem.modelTaskKey?.trim().orEmpty(),
            fallbackSeed = listOf(rawItem.appId.stableKey, category.name, displayName).joinToString("|")
        )
        return NormalizeOutcome(
            opportunity = TaskOpportunity(
                appId = rawItem.appId,
                taskKey = taskKey,
                sourceItemId = rawItem.rawItemId,
                sourceScreenSignature = rawItem.sourceScreenSignature,
                displayName = displayName,
                subtitle = rawItem.screenContext?.trim()?.takeIf { value -> value.isNotBlank() },
                rewardText = rawItem.rewardText?.trim()?.takeIf { value -> value.isNotBlank() },
                actionText = rawItem.actionText?.trim()?.takeIf { value -> value.isNotBlank() },
                category = category,
                repeatRule = rawItem.scheduleText?.trim()?.takeIf { value -> value.isNotBlank() },
                timeWindowHints = rawItem.windows,
                cooldownHintMinutes = rawItem.cooldownMinutes,
                estimatedRewardCoins = parseRewardCoins(rawItem.rewardText),
                estimatedRewardConfidence = rawItem.confidence,
                scanConfidence = rawItem.confidence,
                normalizationNotes = decision.notes,
                rawTextSnapshot = rawItem.combinedText().ifBlank { null },
                scanCapturedAt = rawItem.capturedAt
            ),
            notes = decision.notes
        )
    }

    fun normalizeBatch(rawItems: List<RawScanItem>): OpportunityNormalizationResult {
        var rejectedCount = 0
        val notes = mutableListOf<String>()
        val accepted = rawItems.mapNotNull { rawItem ->
            val outcome = normalize(rawItem)
            if (outcome.opportunity == null) {
                rejectedCount += 1
                outcome.rejectedReason?.let { reason -> notes += "${rawItem.rawItemId}:$reason" }
            }
            outcome.opportunity
        }
        return OpportunityNormalizationResult(
            acceptedOpportunities = mergeDuplicateOpportunities(accepted),
            uncertainOpportunities = emptyList(),
            rejectedCount = rejectedCount,
            notes = notes
        )
    }

    private fun mergeDuplicateOpportunities(opportunities: List<TaskOpportunity>): List<TaskOpportunity> {
        return opportunities
            .groupBy { opportunity -> listOf(opportunity.appId.stableKey, opportunity.taskKey).joinToString("|") }
            .values
            .map { group -> group.reduce(::mergeOpportunity) }
            .sortedWith(compareBy<TaskOpportunity> { it.appId.ordinal }.thenBy { it.taskKey })
    }

    private fun mergeOpportunity(left: TaskOpportunity, right: TaskOpportunity): TaskOpportunity {
        val winner = if (right.scanConfidence > left.scanConfidence) right else left
        val other = if (winner == left) right else left
        return winner.copy(
            sourceItemId = winner.sourceItemId ?: other.sourceItemId,
            sourceScreenSignature = winner.sourceScreenSignature ?: other.sourceScreenSignature,
            subtitle = winner.subtitle ?: other.subtitle,
            rewardText = winner.rewardText ?: other.rewardText,
            actionText = winner.actionText ?: other.actionText,
            repeatRule = winner.repeatRule ?: other.repeatRule,
            timeWindowHints = (winner.timeWindowHints + other.timeWindowHints).distinctBy { window ->
                listOf(window.label, window.startMinuteOfDay, window.endMinuteOfDay).joinToString("|")
            },
            cooldownHintMinutes = winner.cooldownHintMinutes ?: other.cooldownHintMinutes,
            estimatedRewardCoins = winner.estimatedRewardCoins ?: other.estimatedRewardCoins,
            normalizationNotes = (winner.normalizationNotes + other.normalizationNotes).distinct(),
            rawTextSnapshot = winner.rawTextSnapshot ?: other.rawTextSnapshot,
            scanCapturedAt = winner.scanCapturedAt ?: other.scanCapturedAt
        )
    }

    private fun ensureScopedTaskKey(appKey: String, candidate: String, fallbackSeed: String): String {
        val normalizedCandidate = candidate.lowercase(Locale.US).replace(Regex("\\s+"), "_").trim(':', '_')
        if (normalizedCandidate.startsWith("$appKey:")) {
            return normalizedCandidate
        }
        if (normalizedCandidate.isNotBlank()) {
            return "$appKey:$normalizedCandidate"
        }
        return "$appKey:local:${stableHash(fallbackSeed)}"
    }

    private fun stableHash(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun parseRewardCoins(rawRewardText: String?): Int? {
        val text = rawRewardText ?: return null
        return Regex("(\\d{1,6})\\s*(金币|coin|coins)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
}
