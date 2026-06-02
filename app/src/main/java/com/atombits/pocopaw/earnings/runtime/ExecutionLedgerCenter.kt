package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.earnings.EarningsDateKeys
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.ExecutionLedgerEntry
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.ExecutionLedgerStatus
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.earnings.permanentCompletionKey
import com.atombits.pocopaw.earnings.withEarningsHubState

interface ExecutionLedgerCenter {
    fun append(store: PrototypeStoreData, entry: ExecutionLedgerEntry): PrototypeStoreData
    fun hasPermanentCompletion(state: ExecutionLedgerState, appId: EntertainmentAppId, taskKey: String): Boolean
    fun hasCompletedWindow(state: ExecutionLedgerState, appId: EntertainmentAppId, taskKey: String, plannedWindowId: String): Boolean
}

object DefaultExecutionLedgerCenter : ExecutionLedgerCenter {
    override fun append(store: PrototypeStoreData, entry: ExecutionLedgerEntry): PrototypeStoreData {
        val hub = store.earningsHubOrDefault(entry.finishedAt ?: entry.startedAt)
        val state = hub.executionLedgerState
        val activeDateKey = hub.activeDateKey ?: EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt)
        val completedOneTimeKeys = buildList {
            addAll(state.completedOneTimeKeys)
            if (entry.permanentCompletion || entry.category == TaskCategory.ONE_TIME && entry.status == ExecutionLedgerStatus.COMPLETED) {
                add(permanentCompletionKey(entry.appId, entry.taskKey))
            }
        }.distinct()
        val todayCompletedOccurrenceIds = buildList {
            addAll(state.todayCompletedOccurrenceIds)
            if (entry.status == ExecutionLedgerStatus.COMPLETED && entry.occurrenceId != null && EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt) == activeDateKey) {
                add(entry.occurrenceId)
            }
        }.distinct()
        val completedWindows = state.todayCompletedWindowIdsByScopedTaskKey.toMutableMap()
        if (entry.status == ExecutionLedgerStatus.COMPLETED && entry.plannedWindowId != null && EarningsDateKeys.forTimestamp(entry.finishedAt ?: entry.startedAt) == activeDateKey) {
            val existing = completedWindows[entry.taskKey].orEmpty()
            completedWindows[entry.taskKey] = (existing + entry.plannedWindowId).distinct()
        }
        val updatedHub: EarningsHubState = hub.copy(
            executionLedgerState = state.copy(
                entries = (state.entries + entry).takeLast(1000),
                lastEntryId = entry.entryId,
                completedOneTimeKeys = completedOneTimeKeys,
                todayCompletedOccurrenceIds = todayCompletedOccurrenceIds,
                todayCompletedWindowIdsByScopedTaskKey = completedWindows
            )
        )
        return store.withEarningsHubState(updatedHub)
    }

    override fun hasPermanentCompletion(state: ExecutionLedgerState, appId: EntertainmentAppId, taskKey: String): Boolean {
        return permanentCompletionKey(appId, taskKey) in state.completedOneTimeKeys || state.entries.any { entry ->
            entry.appId == appId && entry.taskKey == taskKey && entry.permanentCompletion
        }
    }

    override fun hasCompletedWindow(state: ExecutionLedgerState, appId: EntertainmentAppId, taskKey: String, plannedWindowId: String): Boolean {
        return plannedWindowId in state.todayCompletedWindowIdsByScopedTaskKey[taskKey].orEmpty() || state.entries.any { entry ->
            entry.appId == appId &&
                entry.taskKey == taskKey &&
                entry.plannedWindowId == plannedWindowId &&
                entry.status == ExecutionLedgerStatus.COMPLETED
        }
    }
}
