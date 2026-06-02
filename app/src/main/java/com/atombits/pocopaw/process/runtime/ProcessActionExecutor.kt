package com.atombits.pocopaw.process.runtime

import android.content.Context
import com.atombits.pocopaw.AppLaunchAutomationRunner
import com.atombits.pocopaw.AutomationExecutionOutcome
import com.atombits.pocopaw.ExploratoryAutomationRunner
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.executeAutomationCallbackFlow

data class ProcessExplorationLoopConfig(
    val maxSteps: Int,
    val settleMs: Long,
    val maxSameActionRepeat: Int
) {
    companion object {
        fun design9(): ProcessExplorationLoopConfig {
            return ProcessExplorationLoopConfig(
                maxSteps = 15,
                settleMs = 1500L,
                maxSameActionRepeat = 2
            )
        }

        fun prototypeCurrent(): ProcessExplorationLoopConfig {
            return ProcessExplorationLoopConfig(
                maxSteps = 15,
                settleMs = 1500L,
                maxSameActionRepeat = 2
            )
        }
    }
}

interface ProcessActionExecutor {
    suspend fun execute(
        store: PrototypeStoreData,
        storePersister: suspend (PrototypeStoreData) -> PrototypeStoreData = { updatedStore -> updatedStore }
    ): AutomationExecutionOutcome
}

class PrototypeProcessActionExecutor(
    applicationContext: Context,
    toolspaceCatalogManager: ToolspaceCatalogManager,
    loopConfig: ProcessExplorationLoopConfig = ProcessExplorationLoopConfig.design9()
) : ProcessActionExecutor {
    private val automationRunner = AppLaunchAutomationRunner(
        context = applicationContext,
        toolspaceCatalogManager = toolspaceCatalogManager,
        fallbackRunner = ExploratoryAutomationRunner(
            context = applicationContext,
            toolspaceCatalogManager = toolspaceCatalogManager,
            loopConfig = loopConfig
        )
    )

    override suspend fun execute(
        store: PrototypeStoreData,
        storePersister: suspend (PrototypeStoreData) -> PrototypeStoreData
    ): AutomationExecutionOutcome {
        return executeAutomationCallbackFlow(
            store = store,
            automationRunner = automationRunner,
            storePersister = storePersister
        )
    }
}