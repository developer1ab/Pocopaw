package com.atombits.pocopaw

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

internal data class AppTaskFrontCandidate(
    val basePackageName: String?,
    val topPackageName: String?,
    val moveToFront: () -> Boolean
)

internal fun moveMatchingAppTaskToFront(
    packageName: String,
    appTasks: List<AppTaskFrontCandidate>
): Boolean {
    val matchingTask = appTasks.firstOrNull { task ->
        task.topPackageName == packageName || task.basePackageName == packageName
    } ?: return false
    return matchingTask.moveToFront()
}

internal fun launchAppViaPackageManager(
    context: Context,
    packageName: String
): Boolean {
    if (packageName == context.packageName) {
        val movedToFront = moveMatchingAppTaskToFront(
            packageName = packageName,
            appTasks = resolveAppTaskFrontCandidates(context)
        )
        if (movedToFront) {
            return true
        }
    }

    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName == context.packageName) {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    } ?: return false
    context.startActivity(launchIntent)
    return true
}

private fun resolveAppTaskFrontCandidates(context: Context): List<AppTaskFrontCandidate> {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return emptyList()
    return activityManager.appTasks.map { appTask ->
        val taskInfo = runCatching { appTask.taskInfo }.getOrNull()
        AppTaskFrontCandidate(
            basePackageName = taskInfo?.baseActivity?.packageName,
            topPackageName = taskInfo?.topActivity?.packageName,
            moveToFront = {
                runCatching {
                    appTask.moveToFront()
                    true
                }.getOrDefault(false)
            }
        )
    }
}

internal fun resolveAppLaunchPackageName(
    selectedToolId: String?,
    capability: ToolCapability?
): String? {
    return resolveAppLaunchTarget(selectedToolId, capability)
}

class AppLaunchAutomationRunner private constructor(
    private val capabilityResolver: (String?) -> ToolCapability?,
    private val appLauncher: (String, TaskExecutionBoundaryPacket?) -> Boolean,
    private val phoneContactResolver: PhoneContactResolver,
    private val fallbackRunner: PrototypeAutomationRunner
) : PrototypeAutomationRunner {

    constructor(
        context: Context,
        toolspaceCatalogManager: ToolspaceCatalogManager,
        fallbackRunner: PrototypeAutomationRunner = ExploratoryAutomationRunner(
            context = context,
            toolspaceCatalogManager = toolspaceCatalogManager
        )
    ) : this(
        capabilityResolver = { capabilityId -> toolspaceCatalogManager.findCapabilityById(capabilityId) },
        appLauncher = { launchTarget, boundaryPacket ->
            launchCapabilityTarget(context, launchTarget, boundaryPacket)
        },
        phoneContactResolver = AndroidPhoneContactResolver(context.applicationContext),
        fallbackRunner = fallbackRunner
    )

    internal constructor(
        capabilityResolver: (String?) -> ToolCapability?,
        appLauncher: (String, TaskExecutionBoundaryPacket?) -> Boolean,
        phoneContactResolver: PhoneContactResolver = NoOpPhoneContactResolver,
        fallbackRunner: PrototypeAutomationRunner = LocalAutomationRunner,
        testOnly: Boolean = true
    ) : this(capabilityResolver, appLauncher, phoneContactResolver, fallbackRunner)

    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        onWriteback(
            buildMissingExecutionBoundaryWriteback(
                summary = UiStrings.resolve(
                    R.string.app_launch_missing_boundary,
                    "App launch automation requires a task-aligned execution boundary packet."
                )
            )
        )
    }

    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        if (boundaryPacket == null) {
            onWriteback(
                buildMissingExecutionBoundaryWriteback(
                    summary = UiStrings.resolve(
                        R.string.app_launch_missing_boundary,
                        "App launch automation requires a task-aligned execution boundary packet."
                    )
                )
            )
            return
        }

        var effectiveRuntimeState = runtimeState
        if (shortcutCandidate == null) {
            val selectedToolId = runtimeState.executionResult.selectedToolId
                ?: boundaryPacket.capabilityId
                ?: runtimeState.capabilityId
            resolveLaunchPreconditionFailure(
                selectedToolId = selectedToolId,
                boundaryPacket = boundaryPacket,
                phoneContactResolver = phoneContactResolver
            )?.let { failureSummary ->
                onWriteback(
                    ExecutionWritebackRecord(
                        lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                        summary = failureSummary,
                        appendedSteps = listOf(
                            ExecutionTraceStep(
                                stepType = "VERIFY",
                                groundingMode = "APP_LAUNCH",
                                expectedOutcome = "launch_prerequisite_missing",
                                fallbackPolicy = "STOP",
                                riskLevel = "LOW",
                                note = selectedToolId
                            )
                        )
                    )
                )
                return
            }
            val capability = capabilityResolver(selectedToolId)
            val launchTarget = resolveAppLaunchPackageName(selectedToolId, capability)
            if (!launchTarget.isNullOrBlank() && appLauncher(launchTarget, boundaryPacket)) {
                val launchWriteback = ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                    summary = UiStrings.resolve(
                        R.string.app_launch_executed_locally,
                        "App launch executed locally"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "OPEN_APP",
                            groundingMode = "APP_LAUNCH",
                            expectedOutcome = "app_launch_started",
                            fallbackPolicy = "STOP",
                            riskLevel = "LOW",
                            note = launchTarget
                        )
                    )
                )
                onWriteback(
                    launchWriteback
                )
                effectiveRuntimeState = runtimeState.copy(
                    executionResult = runtimeState.executionResult.copy(
                        lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                        summary = launchWriteback.summary
                    ),
                    executionTrace = runtimeState.executionTrace.copy(
                        steps = runtimeState.executionTrace.steps + launchWriteback.appendedSteps
                    )
                )
            }
        }

        fallbackRunner.execute(
            runtimeState = effectiveRuntimeState,
            boundaryPacket = boundaryPacket,
            shortcutCandidate = shortcutCandidate,
            executionMode = executionMode,
            onWriteback = onWriteback
        )
    }
}

private fun resolveLaunchPreconditionFailure(
    selectedToolId: String?,
    boundaryPacket: TaskExecutionBoundaryPacket,
    phoneContactResolver: PhoneContactResolver
): String? {
    return resolveSystemIntentPhoneRecipient(
        capabilityId = selectedToolId,
        boundaryPacket = boundaryPacket,
        contactResolver = phoneContactResolver
    )?.failureSummary
}