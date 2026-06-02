package com.atombits.pocopaw.earnings.rewards

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.RewardLedgerEntry
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.withEarningsHubState

interface RewardLedgerCenter {
    fun append(store: PrototypeStoreData, entry: RewardLedgerEntry): PrototypeStoreData
}

object DefaultRewardLedgerCenter : RewardLedgerCenter {
    override fun append(store: PrototypeStoreData, entry: RewardLedgerEntry): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(entry.finishedAt)
        val previous = hub.rewardLedgerState
        val updatedState: RewardLedgerState = DailyRewardProjectorSupport.recalculate(
            previous.copy(
                entries = (previous.entries + entry).takeLast(1000),
                lastEntryId = entry.entryId
            ),
            entry.finishedAt
        )
        val updatedHub: EarningsHubState = hub.copy(rewardLedgerState = updatedState)
        return store.withEarningsHubState(updatedHub)
    }
}
