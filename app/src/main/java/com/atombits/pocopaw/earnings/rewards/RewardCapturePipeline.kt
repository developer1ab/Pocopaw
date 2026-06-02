package com.atombits.pocopaw.earnings.rewards

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.RewardBaseline
import com.atombits.pocopaw.earnings.RewardCaptureContext
import com.atombits.pocopaw.earnings.RewardCaptureResult
import com.atombits.pocopaw.earnings.RewardLedgerEntry
import com.atombits.pocopaw.earnings.RewardLedgerStatus
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.delay

interface RewardCapturePipeline {
    suspend fun captureBeforeExecution(store: PrototypeStoreData, appId: EntertainmentAppId, taskKey: String, now: Long): RewardBaseline
    suspend fun captureAfterExecution(store: PrototypeStoreData, baseline: RewardBaseline, context: RewardCaptureContext, now: Long): RewardCaptureResult
}

object UnobservedRewardCapturePipeline : RewardCapturePipeline {
    override suspend fun captureBeforeExecution(store: PrototypeStoreData, appId: EntertainmentAppId, taskKey: String, now: Long): RewardBaseline {
        return RewardBaseline(appId = appId, taskKey = taskKey, capturedAt = now)
    }

    override suspend fun captureAfterExecution(store: PrototypeStoreData, baseline: RewardBaseline, context: RewardCaptureContext, now: Long): RewardCaptureResult {
        return RewardCaptureResult(
            ledgerEntry = RewardLedgerEntry(
                executionId = context.executionId,
                occurrenceId = context.occurrenceId,
                appId = context.appId,
                taskKey = context.taskKey,
                displayName = context.displayName,
                category = context.category,
                windowLabel = context.windowLabel,
                plannedWindowId = context.plannedWindowId,
                startedAt = context.startedAt,
                finishedAt = now,
                balanceBefore = baseline.balanceBefore,
                status = RewardLedgerStatus.REWARD_UNCONFIRMED,
                failureReason = "reward observation unavailable"
            ),
            summary = "reward observation unavailable"
        )
    }
}

class AndroidRewardCapturePipeline(
    private val observationDelayMs: Long = 1_500L,
    private val screenTextProvider: () -> String? = {
        PrototypeAccessibilityService.instance?.captureScreenTextSnapshot()?.rewardParsingText()
    }
) : RewardCapturePipeline {
    override suspend fun captureBeforeExecution(
        store: PrototypeStoreData,
        appId: EntertainmentAppId,
        taskKey: String,
        now: Long
    ): RewardBaseline {
        val beforeText = screenTextProvider().orEmpty()
        return RewardBaseline(
            appId = appId,
            taskKey = taskKey,
            capturedAt = now,
            balanceBefore = extractBalanceCoins(beforeText)
        )
    }

    override suspend fun captureAfterExecution(
        store: PrototypeStoreData,
        baseline: RewardBaseline,
        context: RewardCaptureContext,
        now: Long
    ): RewardCaptureResult {
        if (observationDelayMs > 0) {
            delay(observationDelayMs)
        }
        val afterText = screenTextProvider().orEmpty()
        val balanceAfter = extractBalanceCoins(afterText)
        val balanceDelta = if (baseline.balanceBefore != null && balanceAfter != null) {
            balanceAfter - baseline.balanceBefore
        } else {
            null
        }
        val explicitReward = extractExplicitRewardCoins(afterText)
        val actualReward = when {
            balanceDelta != null && balanceDelta > 0 -> balanceDelta
            explicitReward != null && explicitReward > 0 -> explicitReward
            else -> null
        }
        val method = when {
            balanceDelta != null && balanceDelta > 0 -> "accessibility_balance_delta"
            explicitReward != null && explicitReward > 0 -> "accessibility_reward_text"
            else -> null
        }
        val status = if (actualReward != null) {
            RewardLedgerStatus.CONFIRMED
        } else {
            RewardLedgerStatus.REWARD_UNCONFIRMED
        }
        val failureReason = if (status == RewardLedgerStatus.CONFIRMED) {
            null
        } else {
            "confirmed reward text or balance delta not observed"
        }
        return RewardCaptureResult(
            ledgerEntry = RewardLedgerEntry(
                executionId = context.executionId,
                occurrenceId = context.occurrenceId,
                appId = context.appId,
                taskKey = context.taskKey,
                displayName = context.displayName,
                category = context.category,
                windowLabel = context.windowLabel,
                plannedWindowId = context.plannedWindowId,
                startedAt = context.startedAt,
                finishedAt = System.currentTimeMillis().takeIf { value -> value > now } ?: now,
                balanceBefore = baseline.balanceBefore,
                balanceAfter = balanceAfter,
                actualRewardCoins = actualReward,
                rewardObservationMethod = method,
                status = status,
                failureReason = failureReason
            ),
            summary = if (actualReward != null) {
                "reward confirmed: $actualReward coins"
            } else {
                failureReason
            }
        )
    }

    private fun extractBalanceCoins(text: String): Int? {
        val explicitBalancePatterns = listOf(
            Regex("(?:余额|当前金币|金币余额|总金币|账户金币|balance|coin balance|total coins)[^0-9]{0,16}([0-9][0-9,]*)", RegexOption.IGNORE_CASE),
            Regex("([0-9][0-9,]*)[^0-9]{0,8}(?:总金币|金币余额|coin balance|total coins)", RegexOption.IGNORE_CASE)
        )
        return explicitBalancePatterns.asSequence()
            .mapNotNull { pattern -> pattern.find(text)?.groupValues?.getOrNull(1)?.toCoinIntOrNull() }
            .firstOrNull()
    }

    private fun extractExplicitRewardCoins(text: String): Int? {
        val rewardPatterns = listOf(
            Regex("(?:获得|奖励|领取|到账|收益|已得|reward|earned|received)[^0-9+]{0,18}\\+?([0-9][0-9,]*)\\s*(?:金币|coin|coins)?", RegexOption.IGNORE_CASE),
            Regex("\\+\\s*([0-9][0-9,]*)\\s*(?:金币|coin|coins)", RegexOption.IGNORE_CASE)
        )
        return rewardPatterns.asSequence()
            .mapNotNull { pattern -> pattern.find(text)?.groupValues?.getOrNull(1)?.toCoinIntOrNull() }
            .firstOrNull { value -> value > 0 }
    }

    private fun String.toCoinIntOrNull(): Int? {
        return replace(",", "").trim().toIntOrNull()
    }
}

private fun com.atombits.pocopaw.service.AccessibilityScreenTextSnapshot.rewardParsingText(): String {
    return textLines.joinToString("\n") { line ->
        line.substringBefore(" @[").trim()
    }
}
