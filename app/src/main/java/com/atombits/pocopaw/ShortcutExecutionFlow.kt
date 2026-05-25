package com.atombits.pocopaw

data class ShortcutExecutionStep(
    val stepType: String,
    val expectedOutcome: String,
    val fallbackPolicy: String,
    val riskLevel: String,
    val note: String? = null
)

data class ScreenVerificationResult(
    val matched: Boolean,
    val expectedOutcome: String,
    val note: String? = null
)

data class AccessibilityActionResult(
    val executed: Boolean,
    val note: String? = null
)

data class RecoveryPlan(
    val writebackRecord: ExecutionWritebackRecord,
    val continueWithVisionFallback: Boolean = false,
    val nextAction: RecoveryNextAction = if (continueWithVisionFallback) {
        RecoveryNextAction.VISION_FALLBACK
    } else {
        RecoveryNextAction.NONE
    }
)

interface ProcessShortcutExecutor {
    suspend fun executeShortcut(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate
    ): ShortcutExecutionStep
}

interface AccessibilityActionRunner {
    suspend fun runAction(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        executedStep: ShortcutExecutionStep
    ): AccessibilityActionResult
}

interface ScreenStateVerifier {
    suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        executedStep: ShortcutExecutionStep
    ): ScreenVerificationResult
}

interface ExecutionRecoveryPlanner {
    fun plan(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        verificationResult: ScreenVerificationResult
    ): RecoveryPlan
}

object LocalProcessShortcutExecutor : ProcessShortcutExecutor {
    override suspend fun executeShortcut(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate
    ): ShortcutExecutionStep {
        return ShortcutExecutionStep(
            stepType = "CLICK",
            expectedOutcome = shortcutCandidate.verificationHint,
            fallbackPolicy = "VISION",
            riskLevel = if (shortcutCandidate.stabilityScore >= 0.75) {
                "LOW"
            } else {
                "MEDIUM"
            },
            note = shortcutCandidate.elementRole
        )
    }
}

object LocalAccessibilityActionRunner : AccessibilityActionRunner {
    private val bridgeActionExecutor = LocalBridgeActionExecutor(
        launchContextProvider = { com.atombits.pocopaw.service.PrototypeAccessibilityService.instance }
    )

    override suspend fun runAction(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        executedStep: ShortcutExecutionStep
    ): AccessibilityActionResult {
        val executed = bridgeActionExecutor.execute(
            BridgeAction(
                type = "tap",
                x = shortcutCandidate.tapX,
                y = shortcutCandidate.tapY
            )
        )
        return AccessibilityActionResult(
            executed = executed,
            note = if (executed) {
                executedStep.note ?: shortcutCandidate.elementRole
            } else {
                "accessibility action failed"
            }
        )
    }
}

object LocalScreenStateVerifier : ScreenStateVerifier {
    override suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        executedStep: ShortcutExecutionStep
    ): ScreenVerificationResult {
        val expectedOutcome = executedStep.expectedOutcome.trim()
        return ScreenVerificationResult(
            matched = false,
            expectedOutcome = expectedOutcome,
            note = if (expectedOutcome.isBlank()) {
                "shortcut verification requires observable page evidence"
            } else {
                "shortcut verification requires observable page evidence for $expectedOutcome"
            }
        )
    }
}

object LocalExecutionRecoveryPlanner : ExecutionRecoveryPlanner {
    override fun plan(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate,
        verificationResult: ScreenVerificationResult
    ): RecoveryPlan {
        return LocalAutomationRecoveryPlanner.plan(
            runtimeState = runtimeState,
            recoveryRequest = AutomationRecoveryRequest(
                route = AutomationExecutionMode.SHORTCUT,
                expectedOutcome = verificationResult.expectedOutcome,
                fallbackPolicy = "VISION",
                riskLevel = "LOW",
                note = verificationResult.note ?: shortcutCandidate.screenSignature
            )
        )
    }
}