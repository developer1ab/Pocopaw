package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.ImportantOccurrenceStatus
import com.atombits.pocopaw.earnings.earningsHubOrDefault

interface EarningsWakeScheduler {
    fun resolveNextWakeAt(store: PrototypeStoreData, now: Long): Long?
}

object DefaultEarningsWakeScheduler : EarningsWakeScheduler {
    override fun resolveNextWakeAt(store: PrototypeStoreData, now: Long): Long? {
        val hub = store.earningsHubOrDefault(now)
        val nextImportant = hub.planningState.importantScheduleQueue
            .filter { occurrence -> occurrence.status in setOf(ImportantOccurrenceStatus.QUEUED, ImportantOccurrenceStatus.READY) }
            .map { occurrence -> occurrence.plannedRunAt.coerceAtLeast(now) }
            .minOrNull()
        val nextFiller = hub.planningState.fillerCandidatePool
            .mapNotNull { candidate -> candidate.nextEligibleAt }
            .map { timestamp -> timestamp.coerceAtLeast(now) }
            .minOrNull()
        return listOfNotNull(nextImportant, nextFiller).minOrNull()
    }
}
