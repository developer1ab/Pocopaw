package com.atombits.pocopaw

enum class RecoveryNextAction {
    NONE,
    SHORTCUT_RETRY,
    VISION_FALLBACK
}

data class AutomationRecoveryRequest(
    val route: AutomationExecutionMode,
    val expectedOutcome: String,
    val fallbackPolicy: String,
    val riskLevel: String,
    val verificationSignals: List<String> = emptyList(),
    val pageEvidenceAsset: PageEvidenceAsset? = null,
    val note: String? = null,
    val currentStepIndex: Int? = null,
    val attemptCount: Int? = null,
    val maxRetryBudget: Int? = null,
    val store: PrototypeStoreData? = null
)

interface AutomationRecoveryPlanner {
    fun plan(
        runtimeState: ExecutionRuntimeState,
        recoveryRequest: AutomationRecoveryRequest
    ): RecoveryPlan
}

private data class RecoveryPolicyEvidence(
    val appScope: String?,
    val processId: String?,
    val pageSignature: String?
)

private data class RecoveryPolicyProfile(
    val allowShortcutRetry: Boolean,
    val shortcutRetryBudget: Int,
    val allowProcessBacktrack: Boolean,
    val allowVisionReground: Boolean,
    val visionRetryBudget: Int,
    val preferVisionFallback: Boolean,
    val forceStop: Boolean
)

object LocalAutomationRecoveryPlanner : AutomationRecoveryPlanner {
    override fun plan(
        runtimeState: ExecutionRuntimeState,
        recoveryRequest: AutomationRecoveryRequest
    ): RecoveryPlan {
        val evidence = buildRecoveryPolicyEvidence(runtimeState, recoveryRequest)
        val policyProfile = buildRecoveryPolicyProfile(evidence)
        val retryBudget = recoveryRequest.maxRetryBudget ?: when (recoveryRequest.route) {
            AutomationExecutionMode.SHORTCUT -> policyProfile.shortcutRetryBudget
            AutomationExecutionMode.PROCESS_REFERENCE -> policyProfile.visionRetryBudget
            AutomationExecutionMode.VISION -> policyProfile.visionRetryBudget
        }
        val attemptCount = recoveryRequest.attemptCount
            ?: countRecoveryAttempts(runtimeState.executionTrace, recoveryRequest.route)
        val nextAction = when (recoveryRequest.route) {
            AutomationExecutionMode.SHORTCUT -> planShortcutAction(
                fallbackPolicy = recoveryRequest.fallbackPolicy,
                riskLevel = recoveryRequest.riskLevel,
                attemptCount = attemptCount,
                retryBudget = retryBudget,
                policyProfile = policyProfile
            )

            AutomationExecutionMode.PROCESS_REFERENCE -> planVisionAction(
                fallbackPolicy = recoveryRequest.fallbackPolicy,
                riskLevel = recoveryRequest.riskLevel,
                attemptCount = attemptCount,
                retryBudget = retryBudget,
                policyProfile = policyProfile
            )

            AutomationExecutionMode.VISION -> planVisionAction(
                fallbackPolicy = recoveryRequest.fallbackPolicy,
                riskLevel = recoveryRequest.riskLevel,
                attemptCount = attemptCount,
                retryBudget = retryBudget,
                policyProfile = policyProfile
            )
        }
        val summary = when (nextAction) {
            RecoveryNextAction.SHORTCUT_RETRY -> "Retrying shortcut path"
            RecoveryNextAction.VISION_FALLBACK -> if (recoveryRequest.route == AutomationExecutionMode.VISION) {
                "Re-grounding after failed vision verification"
            } else {
                "Escalating to vision fallback"
            }

            RecoveryNextAction.NONE -> "${recoveryRequest.route.name} recovery stopped"
        }
        val lifecycleStatus = if (nextAction == RecoveryNextAction.NONE) {
            ExecutionLifecycleStatus.FAILED
        } else {
            ExecutionLifecycleStatus.RUNNING
        }
        return RecoveryPlan(
            writebackRecord = ExecutionWritebackRecord(
                lifecycleStatus = lifecycleStatus,
                summary = summary,
                appendedSteps = listOf(
                    ExecutionTraceStep(
                        stepType = "VERIFY",
                        groundingMode = recoveryRequest.route.name,
                        expectedOutcome = recoveryRequest.expectedOutcome,
                        fallbackPolicy = recoveryRequest.fallbackPolicy,
                        riskLevel = recoveryRequest.riskLevel,
                        verificationSignals = recoveryRequest.verificationSignals,
                        continuationMode = when (nextAction) {
                            RecoveryNextAction.SHORTCUT_RETRY -> "SHORTCUT_RETRY"
                            RecoveryNextAction.VISION_FALLBACK -> if (recoveryRequest.route == AutomationExecutionMode.VISION) {
                                "REGROUND"
                            } else {
                                "VISION_FALLBACK"
                            }

                            RecoveryNextAction.NONE -> "STOP"
                        },
                        note = recoveryRequest.note
                    )
                )
            ),
            continueWithVisionFallback = nextAction == RecoveryNextAction.VISION_FALLBACK,
            nextAction = nextAction
        )
    }

    private fun planShortcutAction(
        fallbackPolicy: String,
        riskLevel: String,
        attemptCount: Int,
        retryBudget: Int,
        policyProfile: RecoveryPolicyProfile
    ): RecoveryNextAction {
        val normalizedPolicy = fallbackPolicy.trim().uppercase()
        if (normalizedPolicy == "STOP" || policyProfile.forceStop) {
            return RecoveryNextAction.NONE
        }
        if (policyProfile.preferVisionFallback) {
            return RecoveryNextAction.VISION_FALLBACK
        }
        if (
            normalizedPolicy == "RETRY" &&
            policyProfile.allowShortcutRetry &&
            attemptCount < retryBudget &&
            !riskLevel.equals("HIGH", ignoreCase = true)
        ) {
            return RecoveryNextAction.SHORTCUT_RETRY
        }
        return when (normalizedPolicy) {
            "RETRY", "VISION", "BACKTRACK" -> RecoveryNextAction.VISION_FALLBACK
            else -> RecoveryNextAction.NONE
        }
    }

    private fun planVisionAction(
        fallbackPolicy: String,
        riskLevel: String,
        attemptCount: Int,
        retryBudget: Int,
        policyProfile: RecoveryPolicyProfile
    ): RecoveryNextAction {
        val normalizedPolicy = fallbackPolicy.trim().uppercase()
        if (
            normalizedPolicy == "STOP" ||
            riskLevel.equals("HIGH", ignoreCase = true) ||
            policyProfile.forceStop ||
            !policyProfile.allowVisionReground
        ) {
            return RecoveryNextAction.NONE
        }
        return if (
            (normalizedPolicy == "VISION" || normalizedPolicy == "RETRY" || normalizedPolicy == "BACKTRACK") &&
            attemptCount < retryBudget
        ) {
            RecoveryNextAction.VISION_FALLBACK
        } else {
            RecoveryNextAction.NONE
        }
    }

    private fun countRecoveryAttempts(
        executionTrace: ExecutionTrace,
        route: AutomationExecutionMode
    ): Int {
        return executionTrace.steps.count { step ->
            step.stepType == "VERIFY" && step.groundingMode.equals(route.name, ignoreCase = true)
        }
    }

    private fun buildRecoveryPolicyEvidence(
        runtimeState: ExecutionRuntimeState,
        recoveryRequest: AutomationRecoveryRequest
    ): RecoveryPolicyEvidence {
        val appScope = extractCanonicalAppScope(
            runtimeState.executionResult.selectedToolId
                ?: runtimeState.capabilityId
                ?: recoveryRequest.store?.resolveCurrentProcessRuntime()?.matchedReadyAssetName
                ?: runtimeState.executionTrace.selectedToolId
        )
        val processId = runtimeState.executionResult.selectedProcessId
            ?: runtimeState.processId
            ?: runtimeState.executionTrace.processId
        val pageSignature = (
            runtimeState.executionTrace.steps
                .asReversed()
                .mapNotNull { step -> step.pageSignature ?: step.note }
                .firstOrNull { value -> value.isNotBlank() }
                ?: recoveryRequest.pageEvidenceAsset?.pageSignature
            )?.lowercase()
        return RecoveryPolicyEvidence(
            appScope = appScope?.lowercase(),
            processId = processId?.lowercase(),
            pageSignature = pageSignature
        )
    }

    private fun buildRecoveryPolicyProfile(evidence: RecoveryPolicyEvidence): RecoveryPolicyProfile {
        val appScope = evidence.appScope.orEmpty()
        val processId = evidence.processId.orEmpty()
        val pageSignature = evidence.pageSignature.orEmpty()
        val isSensitiveProcess = appScope.contains("bank") ||
            processId.contains("transfer") ||
            processId.contains("payment") ||
            processId.contains("withdraw")
        val isStableCommercePage =
            (appScope == "jd" || appScope == "taobao" || appScope == "meituan") &&
                (processId.contains("buy") || processId.contains("order") || processId.contains("cart")) &&
                (pageSignature.contains("product") ||
                    pageSignature.contains("detail") ||
                    pageSignature.contains("sku") ||
                    pageSignature.contains("cart") ||
                    pageSignature.contains("confirm"))
        val isPageDrifted = pageSignature.contains("home") ||
            pageSignature.contains("feed") ||
            pageSignature.contains("login") ||
            pageSignature.contains("splash") ||
            pageSignature.contains("permission")
        return RecoveryPolicyProfile(
            allowShortcutRetry = !isSensitiveProcess && !isPageDrifted,
            shortcutRetryBudget = if (isStableCommercePage) 1 else 1,
            allowProcessBacktrack = !isSensitiveProcess && !isPageDrifted,
            allowVisionReground = !isSensitiveProcess,
            visionRetryBudget = 1,
            preferVisionFallback = isPageDrifted,
            forceStop = isSensitiveProcess
        )
    }
}