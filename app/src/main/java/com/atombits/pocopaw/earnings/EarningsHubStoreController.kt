package com.atombits.pocopaw.earnings

import com.atombits.pocopaw.PrototypeStore

class EarningsHubStoreController(
    private val prototypeStore: PrototypeStore,
    private val orchestrator: EarningsHubOrchestrator
) {
    suspend fun runFullScan(now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.runFullScan(store, now))
    }

    suspend fun runFullScanAndCompilePlan(now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.runFullScanAndCompilePlan(store, now))
    }

    suspend fun compilePlanFromCurrentScan(now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.compilePlanFromCurrentScan(store, now))
    }

    suspend fun setAutomationEnabled(enabled: Boolean, now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.setAutomationEnabled(store, enabled, now))
    }

    suspend fun tick(now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.tick(store, now))
    }

    suspend fun cancel(reason: String, now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.cancel(store, reason, now))
    }

    suspend fun projectUi(now: Long = System.currentTimeMillis()): EarningsHubOperationResult {
        val store = prototypeStore.load()
        return persist(orchestrator.projectUi(store, now))
    }

    private suspend fun persist(result: EarningsHubOperationResult): EarningsHubOperationResult {
        val persistedStore = prototypeStore.replaceStore(result.updatedStore)
        return result.copy(
            updatedStore = persistedStore,
            laneTickResult = result.laneTickResult?.copy(updatedStore = persistedStore)
        )
    }
}
