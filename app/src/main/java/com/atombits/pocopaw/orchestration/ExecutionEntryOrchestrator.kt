package com.atombits.pocopaw.orchestration

import com.atombits.pocopaw.ExecutionFlowOutcome
import com.atombits.pocopaw.ExecutionFlowRunner
import com.atombits.pocopaw.PrototypeStore
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.canAutoStartExecutionFromStore

class ExecutionEntryOrchestrator(
    private val prototypeStore: PrototypeStore,
    private val executionFlowRunner: ExecutionFlowRunner
) {
    fun shouldAutoStart(store: PrototypeStoreData): Boolean {
        return canAutoStartExecutionFromStore(store)
    }

    suspend fun autoStartExecution(store: PrototypeStoreData): ExecutionFlowOutcome {
        return executionFlowRunner.run(store)
    }

    suspend fun startManualExecution(): ExecutionFlowOutcome {
        val latestStore = prototypeStore.load()
        return executionFlowRunner.run(latestStore)
    }

    suspend fun startPreparing(): PrototypeStoreData {
        return prototypeStore.markPreparingStarted()
    }
}