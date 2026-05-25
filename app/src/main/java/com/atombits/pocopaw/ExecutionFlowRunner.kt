package com.atombits.pocopaw

import android.content.Context
import com.atombits.pocopaw.process.runtime.ProcessExplorationRuntime

data class ExecutionFlowOutcome(
    val updatedStore: PrototypeStoreData,
    val message: String
)

class ExecutionFlowRunner(
    private val applicationContext: Context,
    private val prototypeStore: PrototypeStore,
    private val toolspaceCatalogManager: ToolspaceCatalogManager,
    private val screenCaptureManager: PrototypeScreenCaptureManager
) {
    init {
        UiStrings.initialize(applicationContext)
    }

    private val processExplorationRuntime by lazy(LazyThreadSafetyMode.NONE) {
        ProcessExplorationRuntime(
            applicationContext = applicationContext,
            prototypeStore = prototypeStore,
            toolspaceCatalogManager = toolspaceCatalogManager,
            screenCaptureManager = screenCaptureManager
        )
    }

    suspend fun run(store: PrototypeStoreData): ExecutionFlowOutcome {
        return processExplorationRuntime.run(store)
    }
}
