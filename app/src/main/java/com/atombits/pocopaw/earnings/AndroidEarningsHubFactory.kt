package com.atombits.pocopaw.earnings

import android.content.Context
import com.atombits.pocopaw.ExecutionFlowRunner
import com.atombits.pocopaw.PrototypeScreenCaptureManager
import com.atombits.pocopaw.PrototypeStore
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.earnings.rewards.AndroidRewardCapturePipeline
import com.atombits.pocopaw.earnings.runtime.AndroidFillerPostRunCooldownObserver
import com.atombits.pocopaw.earnings.runtime.CompositeFillerPostRunCooldownObserver
import com.atombits.pocopaw.earnings.runtime.DefaultEarningsExecutionLane
import com.atombits.pocopaw.earnings.runtime.ExecutionEventFillerPostRunCooldownObserver
import com.atombits.pocopaw.earnings.runtime.MainRuntimeEarningsExecutionBridge
import com.atombits.pocopaw.earnings.scan.buildAndroidFourAppScanCenter

fun buildAndroidEarningsHubOrchestrator(
    context: Context,
    prototypeStore: PrototypeStore,
    toolspaceCatalogManager: ToolspaceCatalogManager,
    screenCaptureManager: PrototypeScreenCaptureManager
): EarningsHubOrchestrator {
    val appContext = context.applicationContext
    val executionRunner = ExecutionFlowRunner(
        applicationContext = appContext,
        prototypeStore = prototypeStore,
        toolspaceCatalogManager = toolspaceCatalogManager,
        screenCaptureManager = screenCaptureManager
    )
    val executionBridge = MainRuntimeEarningsExecutionBridge(
        prototypeStore = prototypeStore,
        executionFlowRunner = executionRunner
    )
    return DefaultEarningsHubOrchestrator(
        scanCenter = buildAndroidFourAppScanCenter(appContext),
        executionLane = DefaultEarningsExecutionLane(
            executionBridge = executionBridge,
            rewardCapturePipeline = AndroidRewardCapturePipeline(),
            fillerPostRunCooldownObserver = CompositeFillerPostRunCooldownObserver(
                listOf(
                    AndroidFillerPostRunCooldownObserver(),
                    ExecutionEventFillerPostRunCooldownObserver
                )
            )
        )
    )
}

fun buildAndroidEarningsHubStoreController(
    context: Context,
    prototypeStore: PrototypeStore,
    toolspaceCatalogManager: ToolspaceCatalogManager,
    screenCaptureManager: PrototypeScreenCaptureManager
): EarningsHubStoreController {
    return EarningsHubStoreController(
        prototypeStore = prototypeStore,
        orchestrator = buildAndroidEarningsHubOrchestrator(
            context = context,
            prototypeStore = prototypeStore,
            toolspaceCatalogManager = toolspaceCatalogManager,
            screenCaptureManager = screenCaptureManager
        )
    )
}