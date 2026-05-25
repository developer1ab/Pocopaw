package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.AccessibilityActionRunner
import com.atombits.pocopaw.AutomationExecutionMode
import com.atombits.pocopaw.AutomationRecoveryPlanner
import com.atombits.pocopaw.AutomationRecoveryRequest
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ExecutionRecoveryPlanner
import com.atombits.pocopaw.ExecutionRuntimeState
import com.atombits.pocopaw.ExecutionTraceStep
import com.atombits.pocopaw.ExecutionWritebackRecord
import com.atombits.pocopaw.PageEvidenceAsset
import com.atombits.pocopaw.ProcessShortcutCandidate
import com.atombits.pocopaw.ProcessShortcutExecutor
import com.atombits.pocopaw.RecoveryNextAction
import com.atombits.pocopaw.ScreenStateVerifier
import com.atombits.pocopaw.ScreenVerificationResult
import com.atombits.pocopaw.VisionActionType

internal class ProcessShortcutExecutionCoordinator(
    private val shortcutExecutor: ProcessShortcutExecutor,
    private val accessibilityActionRunner: AccessibilityActionRunner,
    private val screenStateVerifier: ScreenStateVerifier,
    private val recoveryPlanner: ExecutionRecoveryPlanner,
    private val automationRecoveryPlanner: AutomationRecoveryPlanner?,
    private val resolvePageEvidence: (ExecutionRuntimeState, String?) -> PageEvidenceAsset?,
    private val resolveRuntimeState: (ExecutionRuntimeState) -> ExecutionRuntimeState,
    private val applyWriteback: suspend (ExecutionWritebackRecord) -> Unit,
    private val executeVisionFallback: suspend (ExecutionRuntimeState, String, String?, List<String>, String?, String?) -> Unit,
    private val terminalWritebackApplied: () -> Boolean
) {

    suspend fun execute(
        runtime: ExecutionRuntimeState,
        shortcut: ProcessShortcutCandidate,
        remainingRetries: Int = 1
    ) {
        val executedShortcutStep = shortcutExecutor.executeShortcut(runtime, shortcut)
        applyWriteback(
            ExecutionWritebackRecord(
                lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                summary = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.shortcut_action_executed,
                    "Shortcut action executed locally"
                ),
                appendedSteps = listOf(
                    ExecutionTraceStep(
                        stepType = executedShortcutStep.stepType,
                        groundingMode = "SHORTCUT",
                        expectedOutcome = executedShortcutStep.expectedOutcome,
                        fallbackPolicy = executedShortcutStep.fallbackPolicy,
                        riskLevel = executedShortcutStep.riskLevel,
                        note = executedShortcutStep.note,
                        actionType = VisionActionType.TAP,
                        locatorHint = shortcut.elementRole,
                        targetX = shortcut.tapX,
                        targetY = shortcut.tapY,
                        pageSignature = shortcut.screenSignature
                    )
                )
            )
        )
        val verificationRuntime = resolveRuntimeState(runtime)
        val accessibilityActionResult = accessibilityActionRunner.runAction(
            runtimeState = verificationRuntime,
            shortcutCandidate = shortcut,
            executedStep = executedShortcutStep
        )
        val verificationResult = if (accessibilityActionResult.executed) {
            screenStateVerifier.verify(
                runtimeState = verificationRuntime,
                shortcutCandidate = shortcut,
                executedStep = executedShortcutStep
            )
        } else {
            ScreenVerificationResult(
                matched = false,
                expectedOutcome = executedShortcutStep.expectedOutcome,
                note = accessibilityActionResult.note ?: com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.shortcut_accessibility_action_failed,
                    "Accessibility action failed"
                )
            )
        }
        if (verificationResult.matched) {
            applyWriteback(
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.shortcut_execution_verified,
                        "Shortcut execution verified locally"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "VERIFY",
                            groundingMode = "SHORTCUT",
                            expectedOutcome = verificationResult.expectedOutcome,
                            fallbackPolicy = "STOP",
                            riskLevel = "LOW",
                            note = verificationResult.note
                        )
                    )
                )
            )
            return
        }
        val recoveryPlan = if (automationRecoveryPlanner == null) {
            recoveryPlanner.plan(
                runtimeState = verificationRuntime,
                shortcutCandidate = shortcut,
                verificationResult = verificationResult
            )
        } else {
            automationRecoveryPlanner.plan(
                runtimeState = verificationRuntime,
                recoveryRequest = AutomationRecoveryRequest(
                    route = AutomationExecutionMode.SHORTCUT,
                    expectedOutcome = verificationResult.expectedOutcome,
                    fallbackPolicy = executedShortcutStep.fallbackPolicy,
                    riskLevel = executedShortcutStep.riskLevel,
                    pageEvidenceAsset = resolvePageEvidence(verificationRuntime, shortcut.screenSignature),
                    note = verificationResult.note,
                    attemptCount = 1 - remainingRetries
                )
            )
        }
        applyWriteback(recoveryPlan.writebackRecord)
        if (terminalWritebackApplied()) {
            return
        }
        when (recoveryPlan.nextAction) {
            RecoveryNextAction.SHORTCUT_RETRY -> {
                if (remainingRetries <= 0) {
                    return
                }
                execute(
                    runtime = resolveRuntimeState(runtime),
                    shortcut = shortcut,
                    remainingRetries = remainingRetries - 1
                )
            }

            RecoveryNextAction.VISION_FALLBACK -> {
                executeVisionFallback(
                    resolveRuntimeState(runtime),
                    verificationResult.expectedOutcome,
                    shortcut.elementRole,
                    listOf(verificationResult.expectedOutcome),
                    executedShortcutStep.note,
                    shortcut.screenSignature
                )
            }

            RecoveryNextAction.NONE -> return
        }
    }
}