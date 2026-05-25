package com.atombits.pocopaw.process.runtime

import android.content.Context
import com.atombits.pocopaw.ExecutionFlowOutcome
import com.atombits.pocopaw.ExecutionLaunchDirective
import com.atombits.pocopaw.PrototypeScreenCaptureManager
import com.atombits.pocopaw.PrototypeStore
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.activeCandidate
import com.atombits.pocopaw.launchExecutionReturnToPrototype
import com.atombits.pocopaw.resolveCurrentActiveCandidateId
import com.atombits.pocopaw.resolveExecutionBoundaryPacketFor
import com.atombits.pocopaw.requestExecutionReturnToPrototypeIfNeeded

class ExecutionSessionRuntime(
    private val applicationContext: Context,
    private val prototypeStore: PrototypeStore,
    private val toolspaceCatalogManager: ToolspaceCatalogManager,
    private val loopConfig: ProcessExplorationLoopConfig = ProcessExplorationLoopConfig.design9()
) {
    private val processActionExecutor by lazy(LazyThreadSafetyMode.NONE) {
        PrototypeProcessActionExecutor(
            applicationContext = applicationContext,
            toolspaceCatalogManager = toolspaceCatalogManager,
            loopConfig = loopConfig
        )
    }

    suspend fun runPrepared(
        store: PrototypeStoreData,
        preparedStart: PreparedExecutionStart
    ): ExecutionFlowOutcome {
        val boundaryPacket = store.resolveExecutionBoundaryPacketFor(preparedStart)
            ?: return ExecutionFlowOutcome(
                updatedStore = store,
                message = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.execution_start_stale,
                    "Execution start is stale relative to the current task."
                )
            )
        val startOutcome = prototypeStore.startExecutionRuntime(
            boundaryPacket = boundaryPacket,
            candidateId = store.resolveCurrentActiveCandidateId(),
            summary = preparedStart.startSummary,
            routeInfo = preparedStart.routeInfo,
            routeEntryType = preparedStart.routeEntryType,
            processReuseContext = preparedStart.processReuseContext,
            preparedExecutionStart = preparedStart
        )
        if (!startOutcome.started) {
            return ExecutionFlowOutcome(
                updatedStore = startOutcome.updatedStore,
                message = startOutcome.message
            )
        }

        val automationOutcome = processActionExecutor.execute(
            store = startOutcome.updatedStore,
            storePersister = { updatedStore ->
                prototypeStore.replaceStore(updatedStore)
            }
        )
        val updatedStore = prototypeStore.replaceStore(automationOutcome.updatedStore)
        requestExecutionReturnToPrototypeIfNeeded(updatedStore) { reason ->
            launchExecutionReturnToPrototype(applicationContext, reason)
        }
        return ExecutionFlowOutcome(
            updatedStore = updatedStore,
            message = automationOutcome.message
        )
    }
}

class ProcessExplorationRuntime(
    applicationContext: Context,
    private val prototypeStore: PrototypeStore,
    toolspaceCatalogManager: ToolspaceCatalogManager,
    screenCaptureManager: PrototypeScreenCaptureManager,
    loopConfig: ProcessExplorationLoopConfig = ProcessExplorationLoopConfig.design9()
) {
    private val executionSessionRuntime by lazy(LazyThreadSafetyMode.NONE) {
        ExecutionSessionRuntime(
            applicationContext = applicationContext,
            prototypeStore = prototypeStore,
            toolspaceCatalogManager = toolspaceCatalogManager,
            loopConfig = loopConfig
        )
    }
    private val executionPlanningPipeline by lazy(LazyThreadSafetyMode.NONE) {
        ExecutionPlanningPipeline(
            capabilityCatalog = ToolspaceExecutionCapabilityCatalog(toolspaceCatalogManager),
            hasScreenCapturePermission = { screenCaptureManager.hasPermission() }
        )
    }

    suspend fun run(store: PrototypeStoreData): ExecutionFlowOutcome {
        val preparedStart = executionPlanningPipeline.prepare(store)
            ?: return ExecutionFlowOutcome(
                updatedStore = store,
                message = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.execution_no_matching_route,
                    "No executable tool or process matched the current objective."
                )
            )
        val preparedStore = prototypeStore.replaceStore(store)
        if (preparedStart.launchDirective == ExecutionLaunchDirective.REQUEST_SCREEN_CAPTURE_PERMISSION) {
            return ExecutionFlowOutcome(
                updatedStore = preparedStore,
                message = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.execution_exploratory_requires_capture,
                    "Screen capture permission is required before starting exploratory automation."
                )
            )
        }
        return executionSessionRuntime.runPrepared(preparedStore, preparedStart)
    }
}