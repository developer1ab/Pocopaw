package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.ImportantOccurrenceStatus
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.withEarningsHubState

interface EarningsPlanReconciler {
    fun reconcileAfterExecution(store: PrototypeStoreData, executionId: String, now: Long): PrototypeStoreData
}

object DefaultEarningsPlanReconciler : EarningsPlanReconciler {
    override fun reconcileAfterExecution(store: PrototypeStoreData, executionId: String, now: Long): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(now)
        val terminalEntry = hub.executionLedgerState.entries.asReversed().firstOrNull { entry ->
            entry.executionId == executionId && entry.status != ExecutionLedgerStatus.STARTED
        } ?: return store
        val updatedQueue = hub.planningState.importantScheduleQueue.map { occurrence ->
            if (occurrence.occurrenceId != terminalEntry.occurrenceId) {
                occurrence
            } else {
                occurrence.copy(
                    status = when (terminalEntry.status) {
                        ExecutionLedgerStatus.COMPLETED -> ImportantOccurrenceStatus.SUCCEEDED
                        ExecutionLedgerStatus.FAILED -> ImportantOccurrenceStatus.FAILED
                        ExecutionLedgerStatus.CANCELLED -> ImportantOccurrenceStatus.CANCELLED
                        ExecutionLedgerStatus.SKIPPED -> ImportantOccurrenceStatus.SKIPPED
                        ExecutionLedgerStatus.STARTED -> occurrence.status
                    },
                    lastEvaluatedAt = now,
                    skipReason = terminalEntry.terminalReason.takeIf { terminalEntry.status == ExecutionLedgerStatus.SKIPPED }
                )
            }
        }
        val updatedHub: EarningsHubState = hub.copy(
            planningState = hub.planningState.copy(
                importantScheduleQueue = updatedQueue,
                nextImportantWakeAt = updatedQueue
                    .filter { occurrence -> occurrence.status in setOf(ImportantOccurrenceStatus.QUEUED, ImportantOccurrenceStatus.READY) }
                    .minOfOrNull { occurrence -> occurrence.plannedRunAt }
            )
        )
        return store.withEarningsHubState(updatedHub)
    }
}
