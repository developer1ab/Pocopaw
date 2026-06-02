package com.atombits.pocopaw.earnings.ui

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.EarningsUiProjectionState
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.ImportantOccurrenceStatus
import com.atombits.pocopaw.earnings.TaskOpportunity
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.rewards.DailyRewardProjector
import com.atombits.pocopaw.earnings.rewards.DefaultDailyRewardProjector
import com.atombits.pocopaw.earnings.withEarningsHubState

interface EarningsUiProjectionRenderer {
    fun buildProjection(store: PrototypeStoreData, now: Long): EarningsUiProjectionState
}

class DefaultEarningsUiProjectionRenderer(
    private val rewardProjector: DailyRewardProjector = DefaultDailyRewardProjector
) : EarningsUiProjectionRenderer {
    override fun buildProjection(store: PrototypeStoreData, now: Long): EarningsUiProjectionState {
        val hub = store.earningsHubOrDefault(now)
        val rewardProjection = rewardProjector.project(store, now)
        return rewardProjection.copy(
            scanSummaryCard = buildScanSummary(hub.scanState),
            importantQueueCards = buildPlanQueueCards(hub, now),
            fillerStatusCard = buildFillerStatus(hub, now)
        )
    }

    private fun buildPlanQueueCards(hub: EarningsHubState, now: Long): List<String> {
        val importantQueue = hub.planningState.importantScheduleQueue
        val fillerPool = hub.planningState.fillerCandidatePool
        val activeImportant = importantQueue.count { occurrence ->
            occurrence.status in setOf(ImportantOccurrenceStatus.QUEUED, ImportantOccurrenceStatus.READY, ImportantOccurrenceStatus.RUNNING)
        }
        val readyFillers = fillerPool.count { candidate -> candidate.nextEligibleAt == null || candidate.nextEligibleAt <= now }
        val plannerErrors = hub.planningState.plannerDiagnostics.filter { diagnostic -> diagnostic.severity == "ERROR" }
        return buildList {
            add("important=$activeImportant/${importantQueue.size}; filler=$readyFillers/${fillerPool.size}; ${buildFillerStatus(hub, now)}")
            if (importantQueue.isEmpty() && fillerPool.isEmpty() && plannerErrors.isNotEmpty()) {
                add("plan not compiled")
                plannerErrors.take(3).forEach { diagnostic -> add("ERROR: ${clip(diagnostic.message, maxLength = 96)}") }
            }
            if (importantQueue.isNotEmpty()) {
                add("important tasks")
                importantQueue.forEachIndexed { index, occurrence ->
                    add("${index + 1}. ${occurrence.appId.displayName} | ${occurrence.displayName} | ${occurrence.status} | score=${occurrence.finalScore}")
                }
            }
            if (fillerPool.isNotEmpty()) {
                add("filler candidates")
                fillerPool.forEachIndexed { index, candidate ->
                    val state = if (candidate.nextEligibleAt == null || candidate.nextEligibleAt <= now) "READY" else "WAIT"
                    add("${index + 1}. ${candidate.appId.displayName} | ${candidate.displayName} | $state")
                }
            }
        }
    }

    private fun buildScanSummary(scanState: FourAppScanState): String {
        if (scanState.lastCompletedBatchId == null && scanState.appSnapshots.isEmpty()) {
            return "No four-app scan yet."
        }
        val totalRaw = scanState.appSnapshots.sumOf { snapshot -> snapshot.rawItemCount }
        val succeededApps = scanState.appSnapshots.count { snapshot -> snapshot.failureReason == null }
        val totalApps = scanState.appSnapshots.size
        val appLines = scanState.appSnapshots.map { snapshot ->
            val status = snapshot.failureReason?.let { reason -> "failed=${reason}" } ?: snapshot.status.name.lowercase()
            "${snapshot.appId.stableKey}: raw=${snapshot.rawItemCount}; accepted=${snapshot.acceptedCount}; rejected=${snapshot.rejectedCount}; $status"
        }
        val acceptedLines = scanState.acceptedOpportunities.mapIndexed { index, opportunity ->
            formatOpportunity(index + 1, "accepted", opportunity)
        }
        val uncertainLines = scanState.uncertainOpportunities.mapIndexed { index, opportunity ->
            formatOpportunity(index + 1, "uncertain", opportunity)
        }
        return buildString {
            append("accepted=${scanState.acceptedOpportunities.size}; uncertain=${scanState.uncertainOpportunities.size}; rejected=${scanState.rejectedItemCount}; raw=$totalRaw")
            if (totalApps > 0) {
                append("\napps=$succeededApps/$totalApps")
            }
            appLines.take(4).forEach { line ->
                append('\n')
                append(line)
            }
            if (acceptedLines.isNotEmpty() || uncertainLines.isNotEmpty()) {
                append("\nitems")
                (acceptedLines + uncertainLines).forEach { line ->
                    append('\n')
                    append(line)
                }
            }
        }
    }

    private fun formatOpportunity(index: Int, status: String, opportunity: TaskOpportunity): String {
        val details = buildList {
            opportunity.rewardText?.takeIf { value -> value.isNotBlank() }?.let { reward -> add("reward=${clip(reward)}") }
            opportunity.actionText?.takeIf { value -> value.isNotBlank() }?.let { action -> add("action=${clip(action)}") }
            opportunity.cooldownHintMinutes?.let { cooldown -> add("cooldown=${cooldown}m") }
            opportunity.timeWindowHints.takeIf { windows -> windows.isNotEmpty() }?.let { windows ->
                add("windows=${windows.joinToString("|") { window -> clip(window.label, maxLength = 24) }}")
            }
            opportunity.scanConfidence.takeIf { confidence -> confidence > 0.0 }?.let { confidence -> add("confidence=${"%.2f".format(confidence)}") }
        }.joinToString("; ")
        return buildString {
            append(index)
            append(". ")
            append(status)
            append(" | ")
            append(opportunity.appId.stableKey)
            append(" | ")
            append(opportunity.category.name)
            append(" | ")
            append(clip(opportunity.displayName))
            if (details.isNotBlank()) {
                append(" | ")
                append(details)
            }
            append(" | key=")
            append(opportunity.taskKey)
        }
    }

    private fun clip(value: String, maxLength: Int = 48): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxLength) {
            return normalized
        }
        return normalized.take(maxLength - 3).trimEnd() + "..."
    }

    fun projectIntoStore(store: PrototypeStoreData, now: Long): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(now)
        val projection = buildProjection(store, now)
        val updatedHub: EarningsHubState = hub.copy(uiProjectionState = projection)
        return store.withEarningsHubState(updatedHub)
    }

    private fun buildFillerStatus(hub: EarningsHubState, now: Long): String {
        val readyCount = hub.planningState.fillerCandidatePool.count { candidate -> candidate.nextEligibleAt == null || candidate.nextEligibleAt <= now }
        val activeImportant = hub.planningState.importantScheduleQueue.count { occurrence ->
            occurrence.status in setOf(ImportantOccurrenceStatus.QUEUED, ImportantOccurrenceStatus.READY, ImportantOccurrenceStatus.RUNNING)
        }
        return "mode=${hub.planningState.fillerRotationPolicy.policyMode}; ready_fillers=$readyCount; active_important=$activeImportant"
    }
}
