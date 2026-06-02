package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.ExecutionLedgerEntry
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.ImportantOccurrenceStatus
import com.atombits.pocopaw.earnings.SweepResult
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.withEarningsHubState
import java.util.UUID

interface ImportantOccurrenceSweeper {
    fun sweep(store: PrototypeStoreData, now: Long): SweepResult
}

class DefaultImportantOccurrenceSweeper(
    private val ledgerCenter: ExecutionLedgerCenter = DefaultExecutionLedgerCenter,
    private val wakeScheduler: EarningsWakeScheduler = DefaultEarningsWakeScheduler
) : ImportantOccurrenceSweeper {
    override fun sweep(store: PrototypeStoreData, now: Long): SweepResult {
        val hub = store.earningsHubOrDefault(now)
        val skipped = hub.planningState.importantScheduleQueue.filter { occurrence ->
            occurrence.status in setOf(ImportantOccurrenceStatus.QUEUED, ImportantOccurrenceStatus.READY) && now > occurrence.plannedLatestAt
        }
        if (skipped.isEmpty()) {
            return SweepResult(updatedStore = store, nextWakeAt = wakeScheduler.resolveNextWakeAt(store, now))
        }
        val skippedIds = skipped.map { occurrence -> occurrence.occurrenceId }
        val updatedQueue = hub.planningState.importantScheduleQueue.map { occurrence ->
            if (occurrence.occurrenceId in skippedIds) {
                occurrence.copy(status = ImportantOccurrenceStatus.SKIPPED, skipReason = "overdue", lastEvaluatedAt = now)
            } else {
                occurrence
            }
        }
        val updatedHub: EarningsHubState = hub.copy(
            planningState = hub.planningState.copy(importantScheduleQueue = updatedQueue)
        )
        var updatedStore = store.withEarningsHubState(updatedHub)
        skipped.forEach { occurrence ->
            updatedStore = ledgerCenter.append(
                updatedStore,
                ExecutionLedgerEntry(
                    executionId = "skip_${UUID.randomUUID()}",
                    occurrenceId = occurrence.occurrenceId,
                    appId = occurrence.appId,
                    taskKey = occurrence.taskKey,
                    displayName = occurrence.displayName,
                    category = occurrence.category,
                    windowLabel = occurrence.windowLabel,
                    plannedWindowId = occurrence.plannedWindowId,
                    startedAt = occurrence.plannedRunAt,
                    finishedAt = now,
                    status = ExecutionLedgerStatus.SKIPPED,
                    terminalReason = "overdue"
                )
            )
        }
        return SweepResult(
            updatedStore = updatedStore,
            skippedOccurrenceIds = skippedIds,
            nextWakeAt = wakeScheduler.resolveNextWakeAt(updatedStore, now)
        )
    }
}
