package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.AutomationExecutionMode
import com.atombits.pocopaw.AutomationRecoveryPlanner
import com.atombits.pocopaw.DetailSlotKey
import com.atombits.pocopaw.ExploratoryAutomationRunner
import com.atombits.pocopaw.AutomationRecoveryRequest
import com.atombits.pocopaw.ExecutionLifecycleStatus
import com.atombits.pocopaw.ExecutionRuntimeState
import com.atombits.pocopaw.ExecutionTraceStep
import com.atombits.pocopaw.ExecutionWritebackRecord
import com.atombits.pocopaw.GroundedVisionActionRunner
import com.atombits.pocopaw.PageEvidenceAsset
import com.atombits.pocopaw.RecoveryNextAction
import com.atombits.pocopaw.ScreenCaptureCoordinator
import com.atombits.pocopaw.VisionActionExecutionResult
import com.atombits.pocopaw.VisionContinuationMode
import com.atombits.pocopaw.VisionGroundingRequest
import com.atombits.pocopaw.VisionGroundingResolver
import com.atombits.pocopaw.VisionGroundingResult
import com.atombits.pocopaw.VisionStepVerifier
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import kotlinx.coroutines.delay
import java.util.Locale

private const val DEFAULT_VISION_FALLBACK_PASSES = 2

internal class ProcessVisionFallbackExecutor(
    private val screenCaptureCoordinator: ScreenCaptureCoordinator,
    private val visionGroundingResolver: VisionGroundingResolver,
    private val groundedVisionActionRunner: GroundedVisionActionRunner,
    private val visionStepVerifier: VisionStepVerifier,
    private val automationRecoveryPlanner: AutomationRecoveryPlanner,
    private val buildProcessGuidance: () -> String?,
    private val resolveBoundaryPacket: (ExecutionRuntimeState) -> TaskExecutionBoundaryPacket?,
    private val resolvePageEvidence: (ExecutionRuntimeState, String?) -> PageEvidenceAsset?,
    private val resolveRuntimeState: (ExecutionRuntimeState) -> ExecutionRuntimeState,
    private val applyWriteback: suspend (ExecutionWritebackRecord) -> Unit,
    private val recordVisionPageEvidence: (ExecutionRuntimeState, VisionGroundingResult, List<String>, Long) -> Unit,
    private val terminalWritebackApplied: () -> Boolean,
    private val nowProvider: () -> Long,
    private val preCaptureSettleMs: Long = ExploratoryAutomationRunner.APP_LAUNCH_CAPTURE_SETTLE_MS,
    private val postActionSettleMs: Long = ExploratoryAutomationRunner.SETTLE_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {

    suspend fun execute(
        runtime: ExecutionRuntimeState,
        expectedOutcome: String,
        locatorHint: String? = null,
        verificationSignals: List<String> = emptyList(),
        stepNote: String? = null,
        pageSignature: String? = null,
        remainingPasses: Int = 2
    ) {
        if (!screenCaptureCoordinator.hasPermission()) {
            applyWriteback(
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.vision_fallback_unavailable,
                        "Vision fallback unavailable"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "VERIFY",
                            groundingMode = "VISION",
                            expectedOutcome = expectedOutcome,
                            fallbackPolicy = "STOP",
                            riskLevel = "LOW",
                            note = com.atombits.pocopaw.UiStrings.resolve(
                                com.atombits.pocopaw.R.string.vision_screen_capture_permission_unavailable,
                                "screen capture permission unavailable"
                            )
                        )
                    )
                )
            )
            return
        }

        if (remainingPasses == DEFAULT_VISION_FALLBACK_PASSES && preCaptureSettleMs > 0L) {
            pause(preCaptureSettleMs)
        }

        val snapshot = screenCaptureCoordinator.captureSnapshot()
        if (snapshot == null) {
            applyWriteback(
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.vision_fallback_unavailable,
                        "Vision fallback unavailable"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "VERIFY",
                            groundingMode = "VISION",
                            expectedOutcome = expectedOutcome,
                            fallbackPolicy = "STOP",
                            riskLevel = "LOW",
                            note = com.atombits.pocopaw.UiStrings.resolve(
                                com.atombits.pocopaw.R.string.vision_screen_capture_failed,
                                "screen capture failed"
                            )
                        )
                    )
                )
            )
            return
        }

        val boundaryPacket = resolveBoundaryPacket(runtime)
        val groundingResult = visionGroundingResolver.resolve(
            VisionGroundingRequest(
                objective = boundaryPacket?.objectiveSummary?.takeIf { value -> value.isNotBlank() }
                    ?: runtime.executionResult.summary.takeIf { value -> value.isNotBlank() }
                    ?: com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.execution_current_task_fallback,
                        "the current task"
                    ),
                expectedOutcome = expectedOutcome,
                imageDataUrl = snapshot.imageDataUrl,
                captureWidth = snapshot.captureWidth,
                captureHeight = snapshot.captureHeight,
                locatorHint = locatorHint,
                verificationSignals = verificationSignals,
                stepNote = stepNote,
                pageSignature = pageSignature,
                processGuidance = buildProcessGuidance(),
                inputCandidates = buildAuthoritativeInputCandidates(boundaryPacket)
            )
        )
        val visionActionOutcome = if (groundingResult.resolved) {
            groundedVisionActionRunner.runAction(runtime, groundingResult)
        } else {
            VisionActionExecutionResult(executed = false, note = groundingResult.note)
        }
        val actionStep = buildVisionActionStep(groundingResult, visionActionOutcome)
        if (!groundingResult.resolved || !visionActionOutcome.executed) {
            applyWriteback(
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.vision_grounding_failed,
                        "Vision grounding failed"
                    ),
                    appendedSteps = listOf(actionStep)
                )
            )
            return
        }

        if (postActionSettleMs > 0L) {
            pause(postActionSettleMs)
        }

        when (groundingResult.continuationMode) {
            VisionContinuationMode.VERIFY_ONLY,
            VisionContinuationMode.VERIFY_THEN_CONTINUE -> executeVerificationContinuation(
                runtime = runtime,
                groundingResult = groundingResult,
                actionStep = actionStep,
                remainingPasses = remainingPasses
            )

            VisionContinuationMode.REGROUND -> executeRegroundContinuation(
                runtime = runtime,
                groundingResult = groundingResult,
                actionStep = actionStep,
                remainingPasses = remainingPasses
            )

            VisionContinuationMode.STOP -> {
                applyWriteback(
                    ExecutionWritebackRecord(
                        lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
                        summary = com.atombits.pocopaw.UiStrings.resolve(
                            com.atombits.pocopaw.R.string.vision_grounding_executed,
                            "Vision grounding executed locally"
                        ),
                        appendedSteps = listOf(actionStep)
                    )
                )
                recordVisionPageEvidence(
                    runtime,
                    groundingResult,
                    groundingResult.verificationSignals.ifEmpty {
                        listOf(groundingResult.expectedOutcome)
                    },
                    nowProvider()
                )
            }
        }
    }

    private suspend fun executeVerificationContinuation(
        runtime: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult,
        actionStep: ExecutionTraceStep,
        remainingPasses: Int
    ) {
        applyWriteback(
            ExecutionWritebackRecord(
                lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                summary = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.vision_action_executed,
                    "Vision action executed locally"
                ),
                appendedSteps = listOf(actionStep)
            )
        )
        val verificationRuntime = resolveRuntimeState(runtime)
        val verificationResult = visionStepVerifier.verify(verificationRuntime, groundingResult)
        val recoveryPlan = if (verificationResult.matched) {
            null
        } else {
            automationRecoveryPlanner.plan(
                runtimeState = verificationRuntime,
                recoveryRequest = AutomationRecoveryRequest(
                    route = AutomationExecutionMode.VISION,
                    expectedOutcome = verificationResult.expectedOutcome,
                    fallbackPolicy = groundingResult.fallbackPolicy,
                    riskLevel = groundingResult.riskLevel,
                    verificationSignals = groundingResult.verificationSignals,
                    pageEvidenceAsset = resolvePageEvidence(verificationRuntime, groundingResult.pageSignature),
                    note = verificationResult.note,
                    attemptCount = 2 - remainingPasses
                )
            )
        }
        applyWriteback(
            if (verificationResult.matched) {
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.COMPLETED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.vision_continuation_verified,
                        "Vision continuation verified locally"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "VERIFY",
                            groundingMode = "VISION",
                            expectedOutcome = verificationResult.expectedOutcome,
                            fallbackPolicy = groundingResult.fallbackPolicy,
                            riskLevel = groundingResult.riskLevel,
                            verificationSignals = groundingResult.verificationSignals,
                            continuationMode = "STOP",
                            note = verificationResult.note
                        )
                    )
                )
            } else {
                recoveryPlan!!.writebackRecord
            }
        )
        if (verificationResult.matched) {
            recordVisionPageEvidence(
                verificationRuntime,
                groundingResult,
                (groundingResult.verificationSignals + verificationResult.expectedOutcome).distinct(),
                nowProvider()
            )
        }
        if (!verificationResult.matched && !terminalWritebackApplied()) {
            if (recoveryPlan!!.nextAction == RecoveryNextAction.VISION_FALLBACK) {
                execute(
                    runtime = resolveRuntimeState(verificationRuntime),
                    expectedOutcome = verificationResult.expectedOutcome,
                    locatorHint = groundingResult.locatorHint,
                    verificationSignals = groundingResult.verificationSignals,
                    stepNote = groundingResult.note,
                    pageSignature = groundingResult.pageSignature,
                    remainingPasses = remainingPasses - 1
                )
            }
        }
    }

    private suspend fun executeRegroundContinuation(
        runtime: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult,
        actionStep: ExecutionTraceStep,
        remainingPasses: Int
    ) {
        applyWriteback(
            ExecutionWritebackRecord(
                lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                summary = com.atombits.pocopaw.UiStrings.resolve(
                    com.atombits.pocopaw.R.string.vision_continuation_reground,
                    "Vision continuation requested another grounding pass"
                ),
                appendedSteps = listOf(actionStep)
            )
        )
        if (remainingPasses <= 1) {
            applyWriteback(
                ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                    summary = com.atombits.pocopaw.UiStrings.resolve(
                        com.atombits.pocopaw.R.string.vision_grounding_failed,
                        "Vision grounding failed"
                    ),
                    appendedSteps = listOf(
                        ExecutionTraceStep(
                            stepType = "VERIFY",
                            groundingMode = "VISION",
                            expectedOutcome = groundingResult.verificationSignals.firstOrNull()
                                ?: groundingResult.expectedOutcome,
                            fallbackPolicy = "STOP",
                            riskLevel = groundingResult.riskLevel,
                            continuationMode = "STOP",
                            note = com.atombits.pocopaw.UiStrings.resolve(
                                com.atombits.pocopaw.R.string.vision_reground_limit_reached,
                                "vision reground limit reached"
                            )
                        )
                    )
                )
            )
            return
        }
        execute(
            runtime = resolveRuntimeState(runtime),
            expectedOutcome = groundingResult.verificationSignals.firstOrNull()
                ?: groundingResult.expectedOutcome,
            locatorHint = groundingResult.locatorHint,
            verificationSignals = groundingResult.verificationSignals,
            stepNote = groundingResult.note,
            pageSignature = groundingResult.pageSignature,
            remainingPasses = remainingPasses - 1
        )
    }

    private fun buildVisionActionStep(
        groundingResult: VisionGroundingResult,
        visionActionOutcome: VisionActionExecutionResult
    ): ExecutionTraceStep {
        return ExecutionTraceStep(
            stepType = groundingResult.stepType,
            groundingMode = "VISION",
            expectedOutcome = groundingResult.expectedOutcome,
            fallbackPolicy = groundingResult.fallbackPolicy,
            riskLevel = groundingResult.riskLevel,
            verificationSignals = groundingResult.verificationSignals,
            continuationMode = groundingResult.continuationMode.name,
            note = visionActionOutcome.note ?: groundingResult.note,
            actionType = groundingResult.actionType,
            locatorHint = groundingResult.locatorHint,
            targetX = groundingResult.targetX,
            targetY = groundingResult.targetY,
            inputText = groundingResult.inputText,
            swipeFromX = groundingResult.swipeFromX,
            swipeFromY = groundingResult.swipeFromY,
            swipeToX = groundingResult.swipeToX,
            swipeToY = groundingResult.swipeToY,
            actionDurationMs = groundingResult.actionDurationMs,
            pageSignature = groundingResult.pageSignature
        )
    }
}

private val visionInputCandidateLeafKeys = setOf(
    DetailSlotKey.TARGET_OBJECT.wireName,
    DetailSlotKey.PRODUCT_TYPE.wireName,
    DetailSlotKey.BRAND.wireName,
    DetailSlotKey.DESTINATION.wireName,
    DetailSlotKey.LOCATION.wireName,
    "query",
    "keyword",
    "recipient",
    "contact",
    "message_body",
    "body",
    "content",
    "destination",
    "origin",
    "pickup_location",
    "dropoff_location",
    "merchant_name",
    "service_type",
    "document_type",
    "title",
    "setting_value",
    "target_state",
    "network_name",
    "device_name",
    "address",
    "name"
)

private fun buildAuthoritativeInputCandidates(boundaryPacket: TaskExecutionBoundaryPacket?): Map<String, String> {
    val packet = boundaryPacket ?: return emptyMap()
    val candidates = linkedMapOf<String, String>()

    fun addCandidate(slotKey: String, rawValue: String?) {
        val normalizedKey = slotKey.trim().lowercase(Locale.US)
        val normalizedValue = rawValue?.trim().orEmpty()
        if (normalizedKey.isBlank() || normalizedValue.isBlank() || normalizedKey in candidates) {
            return
        }
        candidates[normalizedKey] = normalizedValue
    }

    addCandidate(
        DetailSlotKey.TARGET_OBJECT.wireName,
        resolveVisionTargetCandidate(packet)
    )

    packet.resolvedSlots.forEach { (rawKey, rawValue) ->
        val normalizedKey = rawKey.trim().lowercase(Locale.US)
        if (
            normalizedKey.isBlank() ||
            normalizedKey == DetailSlotKey.TARGET_OBJECT.contractName.lowercase(Locale.US) ||
            normalizedKey == DetailSlotKey.TARGET_OBJECT.wireName
        ) {
            return@forEach
        }
        val leafKey = normalizedKey.substringAfterLast('.')
        if (leafKey !in visionInputCandidateLeafKeys) {
            return@forEach
        }
        if (isBroadVisionCandidate(rawValue, candidates.values)) {
            return@forEach
        }
        addCandidate(normalizedKey, rawValue)
    }

    return candidates
}

private fun resolveVisionTargetCandidate(packet: TaskExecutionBoundaryPacket): String? {
    val targetLabel = packet.targetLabel?.trim()?.takeIf { value -> value.isNotBlank() }
    if (targetLabel != null && !looksLikeVisionTaskInstruction(targetLabel)) {
        return targetLabel
    }
    return listOf(
        packet.resolvedSlots[DetailSlotKey.TARGET_OBJECT.contractName],
        packet.targetKey
    ).firstNotNullOfOrNull { value ->
        value?.trim()?.takeIf { trimmed -> trimmed.isNotBlank() }
    }
}

private fun looksLikeVisionTaskInstruction(value: String): Boolean {
    val normalized = value.lowercase(Locale.US)
    return listOf(
        "search",
        "buy",
        "purchase",
        "send",
        "open",
        "add to cart",
        "checkout"
    ).any { cue -> normalized.contains(cue) } || listOf(
        "搜索",
        "查找",
        "购买",
        "下单",
        "加入购物车",
        "加入",
        "发送",
        "打开"
    ).any { cue -> value.contains(cue) }
}

private fun isBroadVisionCandidate(candidateValue: String, activeCandidates: Collection<String>): Boolean {
    return activeCandidates.any { activeCandidate ->
        isBroadVisionCandidate(candidateValue, activeCandidate)
    }
}

private fun isBroadVisionCandidate(candidateValue: String, activeConstraint: String): Boolean {
    val normalizedCandidate = normalizeVisionCandidateText(candidateValue)
    val normalizedConstraint = normalizeVisionCandidateText(activeConstraint)
    if (normalizedCandidate.length < 2 || normalizedCandidate == normalizedConstraint) {
        return false
    }
    if (!normalizedConstraint.contains(normalizedCandidate)) {
        return false
    }
    return normalizedConstraint.length - normalizedCandidate.length >= 5
}

private fun normalizeVisionCandidateText(value: String): String {
    return value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
}