package com.atombits.pocopaw.earnings

import com.atombits.pocopaw.PrototypeStoreData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class EntertainmentAppId(val stableKey: String, val displayName: String, val packageName: String) {
    DOUYIN_LITE("douyin_lite", "Douyin Lite", "com.ss.android.ugc.aweme.lite"),
    FANQIE("fanqie", "Fanqie Novel", "com.dragon.read"),
    HONGGUO("hongguo", "Hongguo Short Drama", "com.phoenix.read"),
    TOUTIAO_LITE("toutiao_lite", "Toutiao Lite", "com.ss.android.article.lite");

    companion object {
        fun defaultOrder(): List<EntertainmentAppId> = listOf(DOUYIN_LITE, FANQIE, HONGGUO, TOUTIAO_LITE)

        fun fromStableKey(value: String?): EntertainmentAppId? {
            val normalized = value?.trim()?.lowercase(Locale.US) ?: return null
            return values().firstOrNull { app -> app.stableKey == normalized || app.name.lowercase(Locale.US) == normalized }
        }
    }
}

enum class TaskCategory {
    ONE_TIME,
    STREAK_MULTI_DAY,
    DAILY_ONCE,
    DAILY_WINDOWED_REPEAT,
    FILLER_REPEATABLE_DECAY;

    companion object {
        fun fromRaw(value: String?): TaskCategory? {
            return runCatching { value?.trim()?.uppercase(Locale.US)?.let(TaskCategory::valueOf) }.getOrNull()
        }
    }
}

enum class ExecutionLedgerStatus {
    STARTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}

enum class RewardLedgerStatus {
    CONFIRMED,
    REWARD_UNCONFIRMED,
    FAILED,
    CANCELLED
}

enum class EarningsPolicyMode {
    STATIC_ROUND_ROBIN,
    DYNAMIC_REWARD_AWARE
}

enum class EarningsLaneStatus {
    IDLE,
    WAITING_IMPORTANT_WINDOW,
    RUNNING_IMPORTANT,
    RUNNING_FILLER,
    CAPTURING_REWARD,
    BLOCKED,
    CANCELLED
}

enum class ImportantOccurrenceStatus {
    QUEUED,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED
}

enum class AppScanStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}

data class EarningsHubState(
    val activeDateKey: String? = null,
    val lastFullScanAt: Long? = null,
    val lastPlanCompiledAt: Long? = null,
    val enabled: Boolean = false,
    val selectedPolicyMode: EarningsPolicyMode = EarningsPolicyMode.STATIC_ROUND_ROBIN,
    val scanState: FourAppScanState = FourAppScanState(),
    val planningState: EarningsPlanningState = EarningsPlanningState(),
    val executionLaneState: EarningsExecutionLaneState = EarningsExecutionLaneState(),
    val executionLedgerState: ExecutionLedgerState = ExecutionLedgerState(),
    val rewardLedgerState: RewardLedgerState = RewardLedgerState(),
    val uiProjectionState: EarningsUiProjectionState = EarningsUiProjectionState(),
    val diagnosticsState: EarningsDiagnosticsState = EarningsDiagnosticsState()
)

data class FourAppScanState(
    val currentBatchId: String? = null,
    val lastCompletedBatchId: String? = null,
    val appSnapshots: List<AppScanSnapshot> = emptyList(),
    val acceptedOpportunities: List<TaskOpportunity> = emptyList(),
    val uncertainOpportunities: List<TaskOpportunity> = emptyList(),
    val rejectedItemCount: Int = 0,
    val lastScanSummary: String? = null
)

data class EarningsPlanningState(
    val planId: String? = null,
    val compiledAt: Long? = null,
    val importantScheduleQueue: List<ImportantOccurrence> = emptyList(),
    val fillerCandidatePool: List<FillerCandidateRecord> = emptyList(),
    val fillerRotationPolicy: FillerRotationPolicy = FillerRotationPolicy(),
    val nextImportantWakeAt: Long? = null,
    val nextFillerEligibleAt: Long? = null,
    val plannerDiagnostics: List<PlannerDiagnosticItem> = emptyList()
)

data class EarningsExecutionLaneState(
    val laneStatus: EarningsLaneStatus = EarningsLaneStatus.IDLE,
    val activeExecutionId: String? = null,
    val activeOccurrenceId: String? = null,
    val activeTaskKey: String? = null,
    val activeAppId: EntertainmentAppId? = null,
    val activeExecutionKind: TaskCategory? = null,
    val resumeCheckpoint: String? = null,
    val cancelRequestedAt: Long? = null,
    val blockReason: String? = null,
    val nextWakeAt: Long? = null,
    val lastDispatchSummary: String? = null
)

data class EarningsTimeWindow(
    val label: String,
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null
)

data class TaskOpportunity(
    val appId: EntertainmentAppId,
    val taskKey: String,
    val sourceItemId: String? = null,
    val sourceScreenSignature: String? = null,
    val displayName: String,
    val subtitle: String? = null,
    val rewardText: String? = null,
    val actionText: String? = null,
    val category: TaskCategory,
    val repeatRule: String? = null,
    val timeWindowHints: List<EarningsTimeWindow> = emptyList(),
    val cooldownHintMinutes: Int? = null,
    val streakRule: String? = null,
    val estimatedDurationSeconds: Int? = null,
    val estimatedRewardCoins: Int? = null,
    val estimatedRewardConfidence: Double? = null,
    val decayProfileHint: String? = null,
    val scanConfidence: Double = 0.0,
    val normalizationNotes: List<String> = emptyList(),
    val rawTextSnapshot: String? = null,
    val scanCapturedAt: Long? = null
)

data class ImportantOccurrence(
    val occurrenceId: String,
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val category: TaskCategory,
    val windowLabel: String? = null,
    val plannedWindowId: String? = null,
    val plannedEarliestAt: Long,
    val plannedLatestAt: Long,
    val plannedRunAt: Long,
    val basePriorityScore: Int,
    val urgencyScore: Int,
    val finalScore: Int,
    val expectedRewardCoins: Int? = null,
    val expectedRewardPerMinute: Double? = null,
    val interruptsFiller: Boolean = true,
    val status: ImportantOccurrenceStatus = ImportantOccurrenceStatus.QUEUED,
    val lastEvaluatedAt: Long? = null,
    val skipReason: String? = null
)

data class FillerCandidateRecord(
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val nextEligibleAt: Long? = null,
    val recentRewardPerMinute: Double? = null,
    val recentFailureRate: Double? = null,
    val decayLevel: Int = 0,
    val consecutiveRunsOnSameApp: Int = 0,
    val lastRunFinishedAt: Long? = null,
    val estimatedDurationSeconds: Int? = null
)

data class ExecutionLedgerEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val executionId: String,
    val occurrenceId: String? = null,
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val category: TaskCategory,
    val windowLabel: String? = null,
    val plannedWindowId: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: ExecutionLedgerStatus,
    val terminalReason: String? = null,
    val permanentCompletion: Boolean = false
)

data class RewardLedgerEntry(
    val entryId: String = UUID.randomUUID().toString(),
    val executionId: String,
    val executionLedgerEntryId: String? = null,
    val occurrenceId: String? = null,
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val category: TaskCategory,
    val windowLabel: String? = null,
    val plannedWindowId: String? = null,
    val startedAt: Long,
    val finishedAt: Long,
    val balanceBefore: Int? = null,
    val balanceAfter: Int? = null,
    val actualRewardCoins: Int? = null,
    val rewardObservationMethod: String? = null,
    val status: RewardLedgerStatus,
    val failureReason: String? = null
)

data class ExecutionLedgerState(
    val entries: List<ExecutionLedgerEntry> = emptyList(),
    val lastEntryId: String? = null,
    val completedOneTimeKeys: List<String> = emptyList(),
    val todayCompletedOccurrenceIds: List<String> = emptyList(),
    val todayCompletedWindowIdsByScopedTaskKey: Map<String, List<String>> = emptyMap()
)

data class AppDailyRewardSummary(
    val appId: EntertainmentAppId,
    val totalCoins: Int = 0,
    val successCount: Int = 0,
    val fillerCoinsPerMinute: Double? = null
)

data class RewardLedgerState(
    val entries: List<RewardLedgerEntry> = emptyList(),
    val lastEntryId: String? = null,
    val todayByApp: List<AppDailyRewardSummary> = emptyList(),
    val todayTotalCoins: Int = 0,
    val recentFillerRewardVelocityByApp: Map<String, Double> = emptyMap()
)

data class RawScanItem(
    val rawItemId: String = UUID.randomUUID().toString(),
    val appId: EntertainmentAppId,
    val visibleTitle: String,
    val rewardText: String? = null,
    val scheduleText: String? = null,
    val actionText: String? = null,
    val screenContext: String? = null,
    val evidenceText: String? = null,
    val modelTaskKey: String? = null,
    val modelCategory: TaskCategory? = null,
    val scheduleLabel: String? = null,
    val cooldownMinutes: Int? = null,
    val windows: List<EarningsTimeWindow> = emptyList(),
    val semanticMatchReason: String? = null,
    val confidence: Double = 0.0,
    val sourceScreenSignature: String? = null,
    val capturedAt: Long? = null
)

data class ScanPolicyDecision(
    val accepted: Boolean,
    val reason: String? = null,
    val notes: List<String> = emptyList()
)

data class NormalizeOutcome(
    val opportunity: TaskOpportunity? = null,
    val rejectedReason: String? = null,
    val notes: List<String> = emptyList()
)

data class AppScanSnapshot(
    val appId: EntertainmentAppId,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: AppScanStatus = AppScanStatus.QUEUED,
    val rawItemCount: Int = 0,
    val acceptedCount: Int = 0,
    val uncertainCount: Int = 0,
    val rejectedCount: Int = 0,
    val failureReason: String? = null
)

data class AppEarningsScanResult(
    val snapshot: AppScanSnapshot,
    val rawItems: List<RawScanItem> = emptyList()
)

data class EarningsScreenRecognitionRequest(
    val appId: EntertainmentAppId,
    val capturedAt: Long,
    val screenText: String,
    val screenshotDataUrl: String? = null,
    val screenshotPath: String? = null,
    val accessibilityPackageName: String? = null,
    val sourceScreenSignature: String? = null
)

data class EarningsScreenRecognitionResult(
    val appId: EntertainmentAppId,
    val rawItems: List<RawScanItem> = emptyList(),
    val summary: String? = null,
    val failureReason: String? = null
)

data class FillerRotationPolicy(
    val policyMode: EarningsPolicyMode = EarningsPolicyMode.STATIC_ROUND_ROBIN,
    val candidateApps: List<EntertainmentAppId> = emptyList(),
    val maxConsecutiveRunsPerApp: Int = 2,
    val maxConsecutiveMinutesPerApp: Int = 8,
    val cooldownBetweenSameAppRunsMs: Long? = null,
    val soonImportantTaskThresholdMinutes: Int = 15,
    val dynamicWeightByApp: Map<String, Double> = emptyMap(),
    val lastChosenApp: EntertainmentAppId? = null,
    val lastPolicyUpdateAt: Long? = null
)

data class PlannerDiagnosticItem(
    val code: String,
    val message: String,
    val severity: String = "INFO"
)

data class EarningsUiProjectionState(
    val scanSummaryCard: String? = null,
    val importantQueueCards: List<String> = emptyList(),
    val fillerStatusCard: String? = null,
    val latestExecutionResultCard: String? = null,
    val dailyRewardSummaryCards: List<String> = emptyList()
)

data class EarningsDiagnosticsState(
    val plannerDiagnostics: List<PlannerDiagnosticItem> = emptyList(),
    val runtimeDiagnostics: List<String> = emptyList()
)

data class EarningsDispatchRequest(
    val executionId: String,
    val occurrenceId: String? = null,
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val category: TaskCategory,
    val windowLabel: String? = null,
    val plannedWindowId: String? = null,
    val maxSteps: Int = EarningsConstants.MAX_EXECUTION_STEPS
)

data class DispatchResult(
    val updatedStore: PrototypeStoreData,
    val executionId: String? = null,
    val started: Boolean = false,
    val completed: Boolean = false,
    val terminalStatus: ExecutionLedgerStatus? = null,
    val terminalSummary: String? = null,
    val finishedAt: Long? = null,
    val failureReason: String? = null
)

data class RewardBaseline(
    val appId: EntertainmentAppId,
    val taskKey: String,
    val capturedAt: Long,
    val balanceBefore: Int? = null
)

data class RewardCaptureContext(
    val executionId: String,
    val occurrenceId: String? = null,
    val appId: EntertainmentAppId,
    val taskKey: String,
    val displayName: String,
    val category: TaskCategory,
    val windowLabel: String? = null,
    val plannedWindowId: String? = null,
    val startedAt: Long
)

data class RewardCaptureResult(
    val ledgerEntry: RewardLedgerEntry,
    val summary: String? = null
)

data class ScanBatchResult(
    val updatedStore: PrototypeStoreData,
    val batchId: String,
    val acceptedCount: Int,
    val uncertainCount: Int,
    val rejectedCount: Int
)

data class OpportunityNormalizationResult(
    val acceptedOpportunities: List<TaskOpportunity>,
    val uncertainOpportunities: List<TaskOpportunity>,
    val rejectedCount: Int,
    val notes: List<String> = emptyList()
)

data class EarningsLaneTickResult(
    val updatedStore: PrototypeStoreData,
    val dispatchedExecutionId: String? = null,
    val nextWakeAt: Long? = null,
    val summary: String? = null
)

data class SweepResult(
    val updatedStore: PrototypeStoreData,
    val skippedOccurrenceIds: List<String> = emptyList(),
    val nextWakeAt: Long? = null
)

object EarningsConstants {
    const val MAX_EXECUTION_STEPS = 15
}

object EarningsDateKeys {
    private val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun forTimestamp(timestamp: Long): String = synchronized(formatter) {
        formatter.format(Date(timestamp))
    }
}

fun PrototypeStoreData.earningsHubOrDefault(now: Long = System.currentTimeMillis()): EarningsHubState {
    val dateKey = EarningsDateKeys.forTimestamp(now)
    val current = earningsHubState ?: EarningsHubState(activeDateKey = dateKey)
    return if (current.activeDateKey == null) {
        current.copy(activeDateKey = dateKey)
    } else {
        current
    }
}

fun PrototypeStoreData.withEarningsHubState(state: EarningsHubState): PrototypeStoreData {
    return copy(earningsHubState = state)
}

fun permanentCompletionKey(appId: EntertainmentAppId, taskKey: String): String {
    return listOf(appId.stableKey, taskKey).joinToString("|")
}
