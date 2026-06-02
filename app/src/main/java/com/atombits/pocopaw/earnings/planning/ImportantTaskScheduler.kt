package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.earnings.EarningsDateKeys
import com.atombits.pocopaw.earnings.EarningsTimeWindow
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.ImportantOccurrence
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.RewardLedgerStatus
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity
import com.atombits.pocopaw.earnings.permanentCompletionKey
import java.util.Calendar
import java.util.Locale

interface ImportantTaskScheduler {
    fun buildSchedule(
        opportunities: List<TaskOpportunity>,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long,
        planAdvice: EarningsPlanAdvice? = null
    ): List<ImportantOccurrence>
}

object DefaultImportantTaskScheduler : ImportantTaskScheduler {
    override fun buildSchedule(
        opportunities: List<TaskOpportunity>,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long,
        planAdvice: EarningsPlanAdvice?
    ): List<ImportantOccurrence> {
        val activeDateKey = EarningsDateKeys.forTimestamp(now)
        return opportunities
            .filterNot { opportunity -> opportunity.category == TaskCategory.FILLER_REPEATABLE_DECAY }
            .flatMap { opportunity -> buildOccurrencesForOpportunity(opportunity, executionLedgerState, rewardLedgerState, activeDateKey, now, planAdvice) }
            .sortedWith(compareBy<ImportantOccurrence> { it.plannedRunAt }.thenByDescending { it.finalScore })
    }

    private fun buildOccurrencesForOpportunity(
        opportunity: TaskOpportunity,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        activeDateKey: String,
        now: Long,
        planAdvice: EarningsPlanAdvice?
    ): List<ImportantOccurrence> {
        if (opportunity.category == TaskCategory.ONE_TIME && executionLedgerState.completedOneTimeKeys.contains(
                permanentCompletionKey(opportunity.appId, opportunity.taskKey)
            )
        ) {
            return emptyList()
        }
        if (opportunity.category != TaskCategory.DAILY_WINDOWED_REPEAT && hasTodayNonRepeatCompletion(
                opportunity,
                executionLedgerState,
                rewardLedgerState,
                activeDateKey
            )
        ) {
            return emptyList()
        }

        val windows = if (opportunity.category == TaskCategory.DAILY_WINDOWED_REPEAT) {
            opportunity.timeWindowHints.filter { window -> window.startMinuteOfDay != null || window.endMinuteOfDay != null }
        } else {
            listOf(EarningsTimeWindow(label = defaultWindowLabel(opportunity.category)))
        }
        return windows.mapNotNull { window ->
            val plannedWindowId = plannedWindowId(opportunity.category, window) ?: return@mapNotNull null
            if (opportunity.category == TaskCategory.DAILY_WINDOWED_REPEAT && hasCompletedWindow(
                    opportunity,
                    plannedWindowId,
                    executionLedgerState,
                    rewardLedgerState,
                    activeDateKey
                )
            ) {
                return@mapNotNull null
            }
            val earliestAt = window.startMinuteOfDay?.let { minute -> millisForMinuteOfDay(now, minute) } ?: now
            val latestAt = window.endMinuteOfDay?.let { minute -> millisForMinuteOfDay(now, minute) } ?: endOfDay(now)
            if (latestAt < now) {
                return@mapNotNull null
            }
            val matchingAdvice = planAdvice?.matchImportantAdvice(opportunity, plannedWindowId, window.label)
            val plannedRunAt = resolvePlannedRunAt(matchingAdvice, earliestAt.coerceAtLeast(now), latestAt, now)
            val baseScore = basePriorityScore(opportunity.category)
            val urgency = urgencyScore(latestAt, now)
            val streakRisk = streakRiskScore(opportunity)
            val rewardPerMinute = expectedRewardPerMinute(opportunity)
            val rewardScore = rewardScore(rewardPerMinute)
            val switchCost = switchCostScore(opportunity, executionLedgerState)
            val failurePenalty = failurePenaltyScore(opportunity, executionLedgerState, activeDateKey)
            val finalScore = baseScore + urgency + streakRisk + rewardScore - switchCost - failurePenalty
            val occurrenceId = listOf(activeDateKey, opportunity.appId.stableKey, opportunity.taskKey, plannedWindowId).joinToString("|")
            ImportantOccurrence(
                occurrenceId = occurrenceId,
                appId = opportunity.appId,
                taskKey = opportunity.taskKey,
                displayName = opportunity.displayName,
                category = opportunity.category,
                windowLabel = window.label,
                plannedWindowId = plannedWindowId,
                plannedEarliestAt = earliestAt,
                plannedLatestAt = latestAt,
                plannedRunAt = plannedRunAt,
                basePriorityScore = baseScore,
                urgencyScore = urgency,
                finalScore = finalScore,
                expectedRewardCoins = opportunity.estimatedRewardCoins,
                expectedRewardPerMinute = rewardPerMinute,
                lastEvaluatedAt = now
            )
        }
    }

    private fun hasTodayNonRepeatCompletion(
        opportunity: TaskOpportunity,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        activeDateKey: String
    ): Boolean {
        val executionCompleted = executionLedgerState.entries.any { entry ->
            entry.appId == opportunity.appId &&
                entry.taskKey == opportunity.taskKey &&
                entry.status == ExecutionLedgerStatus.COMPLETED &&
                EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt) == activeDateKey
        }
        val rewardObserved = rewardLedgerState.entries.any { entry ->
            entry.appId == opportunity.appId &&
                entry.taskKey == opportunity.taskKey &&
                entry.status in setOf(RewardLedgerStatus.CONFIRMED, RewardLedgerStatus.REWARD_UNCONFIRMED) &&
                EarningsDateKeys.forTimestamp(entry.finishedAt) == activeDateKey
        }
        return executionCompleted || rewardObserved
    }

    private fun hasCompletedWindow(
        opportunity: TaskOpportunity,
        plannedWindowId: String,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        activeDateKey: String
    ): Boolean {
        val executionCompleted = executionLedgerState.entries.any { entry ->
            entry.appId == opportunity.appId &&
                entry.taskKey == opportunity.taskKey &&
                entry.plannedWindowId == plannedWindowId &&
                entry.status == ExecutionLedgerStatus.COMPLETED &&
                EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt) == activeDateKey
        }
        val rewardObserved = rewardLedgerState.entries.any { entry ->
            entry.appId == opportunity.appId &&
                entry.taskKey == opportunity.taskKey &&
                entry.plannedWindowId == plannedWindowId &&
                entry.status in setOf(RewardLedgerStatus.CONFIRMED, RewardLedgerStatus.REWARD_UNCONFIRMED) &&
                EarningsDateKeys.forTimestamp(entry.finishedAt) == activeDateKey
        }
        return executionCompleted || rewardObserved
    }

    private fun defaultWindowLabel(category: TaskCategory): String = when (category) {
        TaskCategory.ONE_TIME -> "one_time"
        TaskCategory.STREAK_MULTI_DAY -> "streak_daily"
        TaskCategory.DAILY_ONCE -> "daily_once"
        TaskCategory.DAILY_WINDOWED_REPEAT -> "windowed"
        TaskCategory.FILLER_REPEATABLE_DECAY -> "filler"
    }

    private fun plannedWindowId(category: TaskCategory, window: EarningsTimeWindow): String? {
        return when (category) {
            TaskCategory.ONE_TIME -> "one_time"
            TaskCategory.STREAK_MULTI_DAY -> "streak_daily"
            TaskCategory.DAILY_ONCE -> "daily_once"
            TaskCategory.DAILY_WINDOWED_REPEAT -> "window:${normalizeWindowLabel(window.label)}".takeIf { window.label.isNotBlank() }
            TaskCategory.FILLER_REPEATABLE_DECAY -> null
        }
    }

    private fun normalizeWindowLabel(label: String): String {
        return label.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9:_-]+"), "_").trim('_')
    }

    private fun basePriorityScore(category: TaskCategory): Int = when (category) {
        TaskCategory.ONE_TIME -> 1000
        TaskCategory.STREAK_MULTI_DAY -> 800
        TaskCategory.DAILY_ONCE -> 600
        TaskCategory.DAILY_WINDOWED_REPEAT -> 400
        TaskCategory.FILLER_REPEATABLE_DECAY -> 0
    }

    private fun urgencyScore(latestAt: Long, now: Long): Int {
        val minutesLeft = ((latestAt - now) / 60000L).coerceAtLeast(0L)
        return when {
            minutesLeft < 10 -> 500
            minutesLeft < 30 -> 300
            else -> 0
        }
    }

    private fun expectedRewardPerMinute(opportunity: TaskOpportunity): Double? {
        val reward = opportunity.estimatedRewardCoins ?: return null
        val seconds = opportunity.estimatedDurationSeconds ?: return reward.toDouble()
        if (seconds <= 0) {
            return reward.toDouble()
        }
        return reward / (seconds / 60.0)
    }

    private fun rewardScore(expectedRewardPerMinute: Double?): Int {
        val value = expectedRewardPerMinute ?: return 0
        return when {
            value >= 200.0 -> 200
            value >= 100.0 -> 150
            value >= 50.0 -> 100
            value > 0.0 -> 50
            else -> 0
        }
    }

    private fun streakRiskScore(opportunity: TaskOpportunity): Int {
        if (opportunity.category != TaskCategory.STREAK_MULTI_DAY) {
            return 0
        }
        val text = listOfNotNull(opportunity.displayName, opportunity.subtitle, opportunity.streakRule, opportunity.rawTextSnapshot)
            .joinToString(" ")
            .lowercase(Locale.US)
        return if (text.contains("断") || text.contains("miss") || text.contains("break")) 250 else 120
    }

    private fun switchCostScore(opportunity: TaskOpportunity, executionLedgerState: ExecutionLedgerState): Int {
        val lastAppId = executionLedgerState.entries.maxByOrNull { entry -> entry.finishedAt ?: entry.startedAt }?.appId
        return if (lastAppId == null || lastAppId == opportunity.appId) 0 else 40
    }

    private fun failurePenaltyScore(
        opportunity: TaskOpportunity,
        executionLedgerState: ExecutionLedgerState,
        activeDateKey: String
    ): Int {
        val todayTaskEntries = executionLedgerState.entries
            .filter { entry ->
                entry.appId == opportunity.appId &&
                    entry.taskKey == opportunity.taskKey &&
                    EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt) == activeDateKey
            }
            .sortedBy { entry -> entry.finishedAt ?: entry.startedAt }
        val consecutiveFailures = todayTaskEntries.asReversed()
            .takeWhile { entry -> entry.status == ExecutionLedgerStatus.FAILED }
            .count()
        return when {
            consecutiveFailures >= 3 -> 200
            consecutiveFailures >= 2 -> 100
            else -> 0
        }
    }

    private fun EarningsPlanAdvice.matchImportantAdvice(
        opportunity: TaskOpportunity,
        plannedWindowId: String,
        windowLabel: String?
    ): EarningsImportantTaskAdvice? {
        val candidates = importantAdvice.filter { advice ->
            advice.appId == opportunity.appId && advice.taskKey == opportunity.taskKey
        }
        return candidates.firstOrNull { advice -> advice.plannedWindowId == plannedWindowId }
            ?: candidates.firstOrNull { advice ->
                val advisedLabel = advice.windowLabel?.trim()?.lowercase(Locale.US)
                val localLabel = windowLabel?.trim()?.lowercase(Locale.US)
                advisedLabel != null && advisedLabel == localLabel
            }
            ?: candidates.minByOrNull { advice -> advice.priorityRank ?: Int.MAX_VALUE }
    }

    private fun resolvePlannedRunAt(
        advice: EarningsImportantTaskAdvice?,
        localEarliestAt: Long,
        localLatestAt: Long,
        now: Long
    ): Long {
        val advisedRunAt = advice?.recommendedRunAt
        return if (advisedRunAt != null && advisedRunAt in now..localLatestAt) {
            advisedRunAt.coerceAtLeast(localEarliestAt)
        } else {
            localEarliestAt
        }
    }

    private fun millisForMinuteOfDay(anchor: Long, minuteOfDay: Int): Long {
        val minutes = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return Calendar.getInstance().apply {
            timeInMillis = anchor
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfDay(anchor: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = anchor
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
