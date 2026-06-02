package com.atombits.pocopaw.earnings.rewards

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.AppDailyRewardSummary
import com.atombits.pocopaw.earnings.EarningsDateKeys
import com.atombits.pocopaw.earnings.EarningsUiProjectionState
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.RewardLedgerEntry
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.RewardLedgerStatus
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.earningsHubOrDefault

interface DailyRewardProjector {
    fun project(store: PrototypeStoreData, now: Long): EarningsUiProjectionState
}

object DefaultDailyRewardProjector : DailyRewardProjector {
    override fun project(store: PrototypeStoreData, now: Long): EarningsUiProjectionState {
        val hub = store.earningsHubOrDefault(now)
        val recalculated = DailyRewardProjectorSupport.recalculate(hub.rewardLedgerState, now)
        val latest = recalculated.entries.lastOrNull()
        return EarningsUiProjectionState(
            latestExecutionResultCard = latest?.toLatestResultCard(),
            dailyRewardSummaryCards = buildList {
                add("today_total=${recalculated.todayTotalCoins}")
                recalculated.todayByApp.forEach { summary ->
                    add("${summary.appId.stableKey}=${summary.totalCoins}; success=${summary.successCount}")
                }
            }
        )
    }
}

internal object DailyRewardProjectorSupport {
    fun recalculate(state: RewardLedgerState, now: Long): RewardLedgerState {
        val todayKey = EarningsDateKeys.forTimestamp(now)
        val confirmedToday = state.entries.filter { entry ->
            entry.status == RewardLedgerStatus.CONFIRMED && EarningsDateKeys.forTimestamp(entry.finishedAt) == todayKey
        }
        val byApp = EntertainmentAppId.defaultOrder().map { appId ->
            val entries = confirmedToday.filter { entry -> entry.appId == appId }
            AppDailyRewardSummary(
                appId = appId,
                totalCoins = entries.sumOf { entry -> entry.actualRewardCoins ?: 0 },
                successCount = entries.size,
                fillerCoinsPerMinute = computeFillerVelocity(entries)
            )
        }
        return state.copy(
            todayByApp = byApp,
            todayTotalCoins = byApp.sumOf { summary -> summary.totalCoins },
            recentFillerRewardVelocityByApp = byApp.mapNotNull { summary ->
                summary.fillerCoinsPerMinute?.let { velocity -> summary.appId.stableKey to velocity }
            }.toMap()
        )
    }

    private fun computeFillerVelocity(entries: List<RewardLedgerEntry>): Double? {
        val fillerEntries = entries.filter { entry -> entry.category == TaskCategory.FILLER_REPEATABLE_DECAY }
        if (fillerEntries.isEmpty()) {
            return null
        }
        val coins = fillerEntries.sumOf { entry -> entry.actualRewardCoins ?: 0 }
        val minutes = fillerEntries.sumOf { entry -> ((entry.finishedAt - entry.startedAt).coerceAtLeast(1000L)).toDouble() / 60000.0 }
        if (minutes <= 0.0) {
            return null
        }
        return coins / minutes
    }
}

private fun RewardLedgerEntry.toLatestResultCard(): String {
    val reward = actualRewardCoins?.toString() ?: "unconfirmed"
    return "${appId.stableKey}:${displayName}; reward=$reward; status=$status; method=${rewardObservationMethod ?: "none"}"
}
