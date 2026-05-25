package com.atombits.pocopaw

import com.atombits.pocopaw.process.runtime.ProcessVisionFallbackExecutor
import com.atombits.pocopaw.process.runtime.ProcessShortcutExecutionCoordinator
import com.atombits.pocopaw.process.reuse.CandidateProcessReference
import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import kotlinx.coroutines.delay
import java.util.Locale

data class AutomationExecutionOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

enum class AutomationExecutionMode {
    SHORTCUT,
    PROCESS_REFERENCE,
    VISION
}

interface PrototypeAutomationRunner {
    suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    )

    suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        execute(
            runtimeState = runtimeState,
            shortcutCandidate = shortcutCandidate,
            executionMode = executionMode,
            onWriteback = onWriteback
        )
    }
}

internal fun buildMissingExecutionBoundaryWriteback(
    summary: String = "Automation requires a task-aligned execution boundary packet."
): ExecutionWritebackRecord {
    return ExecutionWritebackRecord(
        lifecycleStatus = ExecutionLifecycleStatus.FAILED,
        summary = summary,
        appendedSteps = listOf(
            ExecutionTraceStep(
                stepType = "VERIFY",
                groundingMode = "BOUNDARY",
                expectedOutcome = "execution_boundary_packet_missing",
                fallbackPolicy = "STOP",
                riskLevel = "LOW",
                continuationMode = "STOP",
                note = "task-aligned execution boundary packet required"
            )
        )
    )
}

object LocalAutomationRunner : PrototypeAutomationRunner {
    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        execute(
            runtimeState = runtimeState,
            boundaryPacket = null,
            shortcutCandidate = shortcutCandidate,
            executionMode = executionMode,
            onWriteback = onWriteback
        )
    }

    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        val groundingMode = executionMode.name
        onWriteback(
            ExecutionWritebackRecord(
                lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                summary = UiStrings.resolve(
                    R.string.execution_route_missing,
                    "Execution could not continue because no grounded local route was resolved."
                ),
                appendedSteps = listOf(
                    ExecutionTraceStep(
                        stepType = "VERIFY",
                        groundingMode = groundingMode,
                        expectedOutcome = "grounded_route_missing",
                        fallbackPolicy = "STOP",
                        riskLevel = "LOW",
                        note = shortcutCandidate?.shortcutId
                            ?: boundaryPacket?.reasonSummary?.takeIf { value -> value.isNotBlank() }
                            ?: boundaryPacket?.objectiveSummary?.takeIf { value -> value.isNotBlank() }
                            ?: runtimeState.executionResult.summary.takeIf { value -> value.isNotBlank() }
                    )
                )
            )
        )
    }
}

internal fun interface AutomationGateway {
    suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    )
}

internal class RunnerAutomationGateway(
    private val automationRunner: PrototypeAutomationRunner
) : AutomationGateway {
    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        automationRunner.execute(
            runtimeState = runtimeState,
            boundaryPacket = boundaryPacket,
            shortcutCandidate = shortcutCandidate,
            executionMode = executionMode,
            onWriteback = onWriteback
        )
    }
}

internal class SessionRecoveryPlanner(
    val shortcutRecoveryPlanner: ExecutionRecoveryPlanner,
    val shortcutAutomationRecoveryPlanner: AutomationRecoveryPlanner?,
    val visionAutomationRecoveryPlanner: AutomationRecoveryPlanner
)

internal class VerificationEngine(
    private val shortcutExecutor: ProcessShortcutExecutor,
    private val accessibilityActionRunner: AccessibilityActionRunner,
    private val screenStateVerifier: ScreenStateVerifier,
    private val recoveryPlanner: SessionRecoveryPlanner,
    private val screenCaptureCoordinator: ScreenCaptureCoordinator,
    private val visionGroundingResolver: VisionGroundingResolver,
    private val groundedVisionActionRunner: GroundedVisionActionRunner,
    private val visionStepVerifier: VisionStepVerifier,
    private val pause: suspend (Long) -> Unit,
    private val nowProvider: () -> Long
) {
    fun buildVisionFallbackExecutor(
        buildProcessGuidance: () -> String?,
        resolveBoundaryPacket: (ExecutionRuntimeState) -> TaskExecutionBoundaryPacket?,
        resolvePageEvidence: (ExecutionRuntimeState, String?) -> PageEvidenceAsset?,
        resolveRuntimeState: (ExecutionRuntimeState) -> ExecutionRuntimeState,
        applyWriteback: suspend (ExecutionWritebackRecord) -> Unit,
        recordVisionPageEvidence: (ExecutionRuntimeState, VisionGroundingResult, List<String>, Long) -> Unit,
        terminalWritebackApplied: () -> Boolean
    ): ProcessVisionFallbackExecutor {
        return ProcessVisionFallbackExecutor(
            screenCaptureCoordinator = screenCaptureCoordinator,
            visionGroundingResolver = visionGroundingResolver,
            groundedVisionActionRunner = groundedVisionActionRunner,
            visionStepVerifier = visionStepVerifier,
            automationRecoveryPlanner = recoveryPlanner.visionAutomationRecoveryPlanner,
            buildProcessGuidance = buildProcessGuidance,
            resolveBoundaryPacket = resolveBoundaryPacket,
            resolvePageEvidence = resolvePageEvidence,
            resolveRuntimeState = resolveRuntimeState,
            applyWriteback = applyWriteback,
            recordVisionPageEvidence = recordVisionPageEvidence,
            terminalWritebackApplied = terminalWritebackApplied,
            nowProvider = nowProvider,
            pause = pause
        )
    }

    fun buildShortcutExecutionCoordinator(
        resolvePageEvidence: (ExecutionRuntimeState, String?) -> PageEvidenceAsset?,
        resolveRuntimeState: (ExecutionRuntimeState) -> ExecutionRuntimeState,
        applyWriteback: suspend (ExecutionWritebackRecord) -> Unit,
        executeVisionFallback: suspend (ExecutionRuntimeState, String, String?, List<String>, String?, String?) -> Unit,
        terminalWritebackApplied: () -> Boolean
    ): ProcessShortcutExecutionCoordinator {
        return ProcessShortcutExecutionCoordinator(
            shortcutExecutor = shortcutExecutor,
            accessibilityActionRunner = accessibilityActionRunner,
            screenStateVerifier = screenStateVerifier,
            recoveryPlanner = recoveryPlanner.shortcutRecoveryPlanner,
            automationRecoveryPlanner = recoveryPlanner.shortcutAutomationRecoveryPlanner,
            resolvePageEvidence = resolvePageEvidence,
            resolveRuntimeState = resolveRuntimeState,
            applyWriteback = applyWriteback,
            executeVisionFallback = executeVisionFallback,
            terminalWritebackApplied = terminalWritebackApplied
        )
    }
}

internal class SessionLoopCoordinator(
    private val automationGateway: AutomationGateway,
    private val verificationEngine: VerificationEngine,
    private val storePersister: suspend (PrototypeStoreData) -> PrototypeStoreData,
    private val nowProvider: () -> Long
) {
    suspend fun execute(store: PrototypeStoreData): AutomationExecutionOutcome {
        val runtimeState = store.resolveCurrentExecutionRuntime() ?: return AutomationExecutionOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.execution_no_running_automation,
                "No running execution is available for automation."
            )
        )
        val boundaryPacket = store.resolveExecutionBoundaryPacketFor(runtimeState)
            ?: store.resolveCurrentExecutionBoundaryPacket()
        val shortcutCandidate = boundaryPacket?.let { packet ->
            resolveProcessShortcutCandidate(store, packet)
        }
        val readyProcessAsset = boundaryPacket?.let { packet ->
            resolveReadyProcessAsset(store, packet)
        }

        var latestStore = store
        var applied = false
        var terminalWritebackApplied = false
        var latestMessage = UiStrings.resolve(
            R.string.execution_runner_no_writeback,
            "Automation runner did not emit writeback."
        )

        fun resolvePageEvidence(
            runtime: ExecutionRuntimeState,
            preferredPageSignature: String? = null
        ): PageEvidenceAsset? {
            val runtimeBoundaryPacket = latestStore.resolveExecutionBoundaryPacketFor(runtime)
                ?: latestStore.resolveCurrentExecutionBoundaryPacket()
            return resolvePreferredPageEvidenceAsset(
                store = latestStore,
                selectedToolId = runtime.executionResult.selectedToolId
                    ?: runtimeBoundaryPacket?.capabilityId
                    ?: runtime.capabilityId
                    ?: runtime.executionTrace.selectedToolId,
                processId = runtime.executionResult.selectedProcessId
                    ?: runtimeBoundaryPacket?.processId
                    ?: runtime.processId
                    ?: runtime.executionTrace.processId,
                preferredPageSignature = preferredPageSignature
            )
        }

        fun recordVisionPageEvidence(
            runtime: ExecutionRuntimeState,
            groundingResult: VisionGroundingResult,
            verificationSignals: List<String>,
            observedAt: Long
        ) {
            val runtimeBoundaryPacket = latestStore.resolveExecutionBoundaryPacketFor(runtime)
                ?: latestStore.resolveCurrentExecutionBoundaryPacket()
            latestStore = applyPageEvidenceObservation(
                store = latestStore,
                selectedToolId = runtime.executionResult.selectedToolId
                    ?: runtimeBoundaryPacket?.capabilityId
                    ?: runtime.capabilityId
                    ?: runtime.executionTrace.selectedToolId,
                processId = runtime.executionResult.selectedProcessId
                    ?: runtimeBoundaryPacket?.processId
                    ?: runtime.processId
                    ?: runtime.executionTrace.processId,
                pageSignature = groundingResult.pageSignature,
                verificationSignals = verificationSignals,
                locatorHints = listOfNotNull(groundingResult.locatorHint),
                lineageSourceTraceIds = listOf(runtime.executionTrace.traceId),
                observedAt = observedAt,
                now = observedAt
            )
        }

        suspend fun applyWriteback(writebackRecord: ExecutionWritebackRecord) {
            if (terminalWritebackApplied) {
                return
            }
            val writebackOutcome = applyExecutionWritebackRecord(
                store = latestStore,
                writebackRecord = writebackRecord,
                now = nowProvider()
            )
            latestStore = writebackOutcome.updatedStore
            applied = applied || writebackOutcome.applied
            latestMessage = writebackOutcome.message
            if (writebackOutcome.applied && (
                writebackRecord.lifecycleStatus == ExecutionLifecycleStatus.COMPLETED ||
                    writebackRecord.lifecycleStatus == ExecutionLifecycleStatus.FAILED
                )
            ) {
                terminalWritebackApplied = true
            }
            if (writebackOutcome.applied) {
                latestStore = storePersister(latestStore)
            }
        }

        val visionFallbackExecutor = verificationEngine.buildVisionFallbackExecutor(
            buildProcessGuidance = {
                latestStore.resolveCurrentProcessReuseContext()?.let(::buildVisionProcessGuidance)
            },
            resolveBoundaryPacket = { runtime ->
                latestStore.resolveExecutionBoundaryPacketFor(runtime)
                    ?: latestStore.resolveCurrentExecutionBoundaryPacket()
            },
            resolvePageEvidence = ::resolvePageEvidence,
            resolveRuntimeState = { runtime -> latestStore.resolveCurrentExecutionRuntime() ?: runtime },
            applyWriteback = ::applyWriteback,
            recordVisionPageEvidence = ::recordVisionPageEvidence,
            terminalWritebackApplied = { terminalWritebackApplied }
        )
        val shortcutExecutionCoordinator = verificationEngine.buildShortcutExecutionCoordinator(
            resolvePageEvidence = ::resolvePageEvidence,
            resolveRuntimeState = { runtime -> latestStore.resolveCurrentExecutionRuntime() ?: runtime },
            applyWriteback = ::applyWriteback,
            executeVisionFallback = { runtime, expectedOutcome, locatorHint, verificationSignals, stepNote, pageSignature ->
                val mergedReferenceHints = mergeVisionReferenceHints(
                    runtime = runtime,
                    boundaryPacket = latestStore.resolveExecutionBoundaryPacketFor(runtime)
                        ?: latestStore.resolveCurrentExecutionBoundaryPacket(),
                    reuseContext = latestStore.resolveCurrentProcessReuseContext(),
                    expectedOutcome = expectedOutcome,
                    locatorHint = locatorHint,
                    verificationSignals = verificationSignals,
                    stepNote = stepNote,
                    pageSignature = pageSignature
                )
                visionFallbackExecutor.execute(
                    runtime = runtime,
                    expectedOutcome = mergedReferenceHints.expectedOutcome,
                    locatorHint = mergedReferenceHints.locatorHint,
                    verificationSignals = mergedReferenceHints.verificationSignals,
                    stepNote = mergedReferenceHints.stepNote,
                    pageSignature = mergedReferenceHints.pageSignature
                )
            },
            terminalWritebackApplied = { terminalWritebackApplied }
        )

        suspend fun executeWithAutomationGateway(
            runtime: ExecutionRuntimeState,
            executionMode: AutomationExecutionMode
        ) {
            val runtimeBoundaryPacket = latestStore.resolveExecutionBoundaryPacketFor(runtime)
                ?: latestStore.resolveCurrentExecutionBoundaryPacket()
            automationGateway.execute(runtime, runtimeBoundaryPacket, null, executionMode) writeback@{ writebackRecord ->
                applyWriteback(writebackRecord)
                if (terminalWritebackApplied) {
                    return@writeback
                }
                val fallbackStep = writebackRecord.appendedSteps.lastOrNull()?.takeIf { step ->
                    writebackRecord.lifecycleStatus == ExecutionLifecycleStatus.RUNNING &&
                        step.stepType.equals("VERIFY", ignoreCase = true) &&
                        (step.fallbackPolicy.equals("VISION", ignoreCase = true) ||
                            step.fallbackPolicy.equals("BACKTRACK", ignoreCase = true))
                } ?: return@writeback
                val fallbackRuntime = latestStore.resolveCurrentExecutionRuntime() ?: runtime
                val mergedReferenceHints = mergeVisionReferenceHints(
                    runtime = fallbackRuntime,
                    boundaryPacket = latestStore.resolveExecutionBoundaryPacketFor(fallbackRuntime)
                        ?: latestStore.resolveCurrentExecutionBoundaryPacket(),
                    reuseContext = latestStore.resolveCurrentProcessReuseContext(),
                    expectedOutcome = fallbackStep.expectedOutcome,
                    locatorHint = fallbackStep.locatorHint,
                    verificationSignals = fallbackStep.verificationSignals,
                    stepNote = fallbackStep.note?.takeUnless { note ->
                        note.contains("route to", ignoreCase = true) ||
                            note.contains("screenshot grounding", ignoreCase = true)
                    },
                    pageSignature = fallbackStep.pageSignature
                )
                visionFallbackExecutor.execute(
                    runtime = fallbackRuntime,
                    expectedOutcome = mergedReferenceHints.expectedOutcome,
                    locatorHint = mergedReferenceHints.locatorHint,
                    verificationSignals = mergedReferenceHints.verificationSignals,
                    stepNote = mergedReferenceHints.stepNote,
                    pageSignature = mergedReferenceHints.pageSignature
                )
            }
        }

        suspend fun executeShortcutFlow(
            runtime: ExecutionRuntimeState,
            shortcut: ProcessShortcutCandidate,
            remainingRetries: Int = 1
        ) {
            shortcutExecutionCoordinator.execute(
                runtime = runtime,
                shortcut = shortcut,
                remainingRetries = remainingRetries
            )
        }

        try {
            if (readyProcessAsset != null) {
                executeWithAutomationGateway(
                    runtime = runtimeState,
                    executionMode = AutomationExecutionMode.PROCESS_REFERENCE
                )
            } else if (shortcutCandidate != null) {
                executeShortcutFlow(runtimeState, shortcutCandidate)
            } else {
                executeWithAutomationGateway(
                    runtime = runtimeState,
                    executionMode = defaultAutomationExecutionMode(runtimeState, shortcutCandidate)
                )
            }
            if (!terminalWritebackApplied) {
                applyWriteback(
                    ExecutionWritebackRecord(
                        lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                        summary = UiStrings.resolve(
                            R.string.execution_runner_no_writeback,
                            "Automation runner did not emit writeback."
                        ),
                        appendedSteps = listOf(
                            ExecutionTraceStep(
                                stepType = "VERIFY",
                                groundingMode = defaultAutomationExecutionMode(runtimeState, shortcutCandidate).name,
                                expectedOutcome = "automation_missing_writeback",
                                fallbackPolicy = "STOP",
                                riskLevel = "LOW",
                                note = "runner returned without callback"
                            )
                        )
                    )
                )
            }
        } catch (throwable: Throwable) {
            if (!terminalWritebackApplied) {
                applyWriteback(
                    ExecutionWritebackRecord(
                        lifecycleStatus = ExecutionLifecycleStatus.FAILED,
                        summary = UiStrings.resolve(
                            R.string.execution_runner_failed,
                            "Automation runner failed: %1\$s",
                            throwable.message.orEmpty().ifBlank {
                                UiStrings.resolve(R.string.execution_unknown_error, "unknown error")
                            }
                        ),
                        appendedSteps = listOf(
                            ExecutionTraceStep(
                                stepType = "VERIFY",
                                groundingMode = defaultAutomationExecutionMode(runtimeState, shortcutCandidate).name,
                                expectedOutcome = "automation_error",
                                fallbackPolicy = "STOP",
                                riskLevel = "LOW",
                                note = throwable.message
                            )
                        )
                    )
                )
            }
        }

        return AutomationExecutionOutcome(
            updatedStore = latestStore,
            applied = applied,
            message = latestMessage
        )
    }
}

suspend fun executeAutomationCallbackFlow(
    store: PrototypeStoreData,
    automationRunner: PrototypeAutomationRunner,
    shortcutExecutor: ProcessShortcutExecutor = LocalProcessShortcutExecutor,
    accessibilityActionRunner: AccessibilityActionRunner = LocalAccessibilityActionRunner,
    screenStateVerifier: ScreenStateVerifier = LocalScreenStateVerifier,
    recoveryPlanner: ExecutionRecoveryPlanner = LocalExecutionRecoveryPlanner,
    automationRecoveryPlanner: AutomationRecoveryPlanner? = null,
    screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
    visionGroundingResolver: VisionGroundingResolver = VisionGroundingResolverClient(),
    groundedVisionActionRunner: GroundedVisionActionRunner = LocalGroundedVisionActionRunner,
    visionStepVerifier: VisionStepVerifier = LocalVisionStepVerifier(),
    storePersister: suspend (PrototypeStoreData) -> PrototypeStoreData = { updatedStore -> updatedStore },
    pause: suspend (Long) -> Unit = { delay(it) },
    now: Long? = null
): AutomationExecutionOutcome {
    val coordinator = SessionLoopCoordinator(
        automationGateway = RunnerAutomationGateway(automationRunner),
        verificationEngine = VerificationEngine(
            shortcutExecutor = shortcutExecutor,
            accessibilityActionRunner = accessibilityActionRunner,
            screenStateVerifier = screenStateVerifier,
            recoveryPlanner = SessionRecoveryPlanner(
                shortcutRecoveryPlanner = recoveryPlanner,
                shortcutAutomationRecoveryPlanner = automationRecoveryPlanner,
                visionAutomationRecoveryPlanner = automationRecoveryPlanner ?: LocalAutomationRecoveryPlanner
            ),
            screenCaptureCoordinator = screenCaptureCoordinator,
            visionGroundingResolver = visionGroundingResolver,
            groundedVisionActionRunner = groundedVisionActionRunner,
            visionStepVerifier = visionStepVerifier,
            pause = pause,
            nowProvider = { now ?: System.currentTimeMillis() }
        ),
        storePersister = storePersister,
        nowProvider = { now ?: System.currentTimeMillis() }
    )
    return coordinator.execute(store)
}

private fun defaultAutomationExecutionMode(
    runtimeState: ExecutionRuntimeState,
    shortcutCandidate: ProcessShortcutCandidate?
): AutomationExecutionMode {
    return when {
        runtimeState.executionResult.selectedProcessId.isNullOrBlank().not() || runtimeState.processId.isNullOrBlank().not() -> {
            AutomationExecutionMode.PROCESS_REFERENCE
        }
        shortcutCandidate != null -> AutomationExecutionMode.SHORTCUT
        else -> AutomationExecutionMode.PROCESS_REFERENCE
    }
}

private fun buildVisionProcessGuidance(
    reuseContext: CandidateProcessReferenceContext
): String {
    return buildList {
        addAll(reuseContext.selectedStageHints.take(3).map { hint -> "stage_hint=$hint" })
        addAll(reuseContext.whySelected.take(3).map { reason -> "why=$reason" })
        addAll(reuseContext.referenceCautions.take(3).map { caution -> "caution=$caution" })
        addAll(reuseContext.referenceSummaryLines.take(4))
    }.filter { value -> value.isNotBlank() }
        .distinct()
        .joinToString(separator = " | ")
}

private data class VisionReferenceHints(
    val expectedOutcome: String,
    val locatorHint: String?,
    val verificationSignals: List<String>,
    val stepNote: String?,
    val pageSignature: String?
)

internal fun adaptReferenceTextForRuntimeTarget(
    text: String?,
    runtime: ExecutionRuntimeState,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    preferredReference: CandidateProcessReference?
): String? {
    val original = text?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    val runtimeTarget = resolveRuntimeTargetObject(runtime, boundaryPacket) ?: return original
    if (original.contains(runtimeTarget)) {
        return original
    }
    val referenceTerms = collectReferenceTargetTerms(preferredReference)
        .filterNot { term -> term.equals(runtimeTarget, ignoreCase = false) }
    if (referenceTerms.isEmpty()) {
        return original
    }

    var adapted = original
    referenceTerms.forEach { term ->
        adapted = adapted.replace(term, runtimeTarget)
        val compactTerm = term.replace(" ", "")
        if (compactTerm.isNotBlank() && compactTerm != term) {
            adapted = adapted.replace(compactTerm, runtimeTarget)
        }
    }
    return adapted
}

internal fun resolveRuntimeTargetObject(
    runtime: ExecutionRuntimeState,
    boundaryPacket: TaskExecutionBoundaryPacket?
): String? {
    val boundaryTarget = resolveBoundaryTargetExpression(boundaryPacket)

    resolvePreferredRuntimeInputText(boundaryPacket)?.let { preferredInput ->
        if (boundaryTarget != null && isRuntimeInputConstraintDowngrade(preferredInput, boundaryTarget)) {
            return boundaryTarget
        }
        return preferredInput
    }

    boundaryTarget?.let { value ->
        return value
    }

    val objective = boundaryPacket?.objectiveSummary?.trim()
        ?: runtime.executionResult.summary.trim()
    if (objective.isBlank()) {
        return null
    }

    listOf(
        Regex("搜索(?:商品)?['\"“‘]?(.+?)['\"”’]?(?:并|后|加入购物车|加入|购买|下单|$)"),
        Regex("查找(?:商品)?['\"“‘]?(.+?)['\"”’]?(?:并|后|加入购物车|加入|购买|下单|$)"),
        Regex("输入['\"“‘]?(.+?)['\"”’]?(?:到|进|$)")
    ).firstNotNullOfOrNull { pattern ->
        pattern.find(objective)?.groupValues?.getOrNull(1)?.trim()?.takeIf { value -> value.isNotBlank() }
    }?.let { value ->
        return value
    }

    return null
}

private fun resolveBoundaryTargetExpression(boundaryPacket: TaskExecutionBoundaryPacket?): String? {
    val targetKey = boundaryPacket?.targetKey?.trim()?.takeIf { value -> value.isNotBlank() }
    val targetLabel = boundaryPacket?.targetLabel?.trim()?.takeIf { value -> value.isNotBlank() }
    if (targetLabel != null && !looksLikeTaskInstruction(targetLabel)) {
        return targetLabel
    }
    return targetKey
}

private fun looksLikeTaskInstruction(value: String): Boolean {
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

private fun isRuntimeInputConstraintDowngrade(candidate: String, activeConstraint: String): Boolean {
    val normalizedCandidate = normalizeRuntimeConstraintText(candidate)
    val normalizedConstraint = normalizeRuntimeConstraintText(activeConstraint)
    if (normalizedCandidate.length < 2 || normalizedCandidate == normalizedConstraint) {
        return false
    }
    if (!normalizedConstraint.contains(normalizedCandidate)) {
        return false
    }
    return normalizedConstraint.length - normalizedCandidate.length >= 5
}

private fun normalizeRuntimeConstraintText(value: String): String {
    return value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
}

internal fun resolvePreferredRuntimeInputText(boundaryPacket: TaskExecutionBoundaryPacket?): String? {
    val packet = boundaryPacket ?: return null
    val preferredSlotKeys = CapabilityDomainProfileRegistry.preferredRuntimeInputKeys(
        domain = packet.capabilityDomain,
        capabilityId = packet.capabilityId
    )
    return preferredSlotKeys.firstNotNullOfOrNull { slotKey ->
        packet.resolvedSlots[slotKey]
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    }
}

private fun collectReferenceTargetTerms(preferredReference: CandidateProcessReference?): List<String> {
    if (preferredReference == null) {
        return emptyList()
    }

    return buildList {
        addAll(extractQuotedReferenceTerms(preferredReference.semanticDescription))
        preferredReference.acceptanceCriteria.forEach { value -> addAll(extractQuotedReferenceTerms(value)) }
        preferredReference.verificationSignals.forEach { value -> addAll(extractQuotedReferenceTerms(value)) }
        preferredReference.pageSemanticAnchors.forEach { anchor ->
            anchor.locatorHints.forEach { value -> addAll(extractQuotedReferenceTerms(value)) }
            anchor.verificationSignals.forEach { value -> addAll(extractQuotedReferenceTerms(value)) }
            anchor.notes.forEach { value -> addAll(extractQuotedReferenceTerms(value)) }
        }
        preferredReference.exemplarActionSummaries.forEach { exemplar ->
            addAll(extractQuotedReferenceTerms(exemplar.locatorHint))
            addAll(extractQuotedReferenceTerms(exemplar.outcomeSignal))
            addAll(extractQuotedReferenceTerms(exemplar.note))
        }
    }.map { value -> value.trim() }
        .filter { value -> value.isNotBlank() }
        .distinct()
}

private fun extractQuotedReferenceTerms(text: String?): List<String> {
    val source = text?.trim().orEmpty()
    if (source.isBlank()) {
        return emptyList()
    }

    val matches = Regex("['\"“‘]([^'\"”’]{1,24})['\"”’]")
        .findAll(source)
        .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim() }
        .filter { value -> value.isNotBlank() }
        .toList()
    return matches
}

private fun mergeVisionReferenceHints(
    runtime: ExecutionRuntimeState,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    reuseContext: CandidateProcessReferenceContext?,
    expectedOutcome: String,
    locatorHint: String?,
    verificationSignals: List<String>,
    stepNote: String?,
    pageSignature: String?
): VisionReferenceHints {
    val preferredReference = resolvePreferredVisionReference(reuseContext, runtime, boundaryPacket) ?: return VisionReferenceHints(
        expectedOutcome = expectedOutcome,
        locatorHint = locatorHint,
        verificationSignals = verificationSignals,
        stepNote = stepNote,
        pageSignature = pageSignature
    )
    val stageHints = reuseContext?.selectedStageHints.orEmpty()
        .map(::normalizeVisionReferenceValue)
        .filter { value -> value.isNotBlank() }
        .toSet()
    val normalizedLocatorHint = normalizeVisionReferenceValue(locatorHint)
    val normalizedPageSignature = normalizeVisionReferenceValue(pageSignature)
    val normalizedExpectedOutcome = normalizeVisionReferenceValue(expectedOutcome)
    val normalizedVerificationSignals = verificationSignals
        .map(::normalizeVisionReferenceValue)
        .filter { value -> value.isNotBlank() }
        .toSet()
    val selectedAnchor = preferredReference.pageSemanticAnchors
        .maxByOrNull { anchor ->
            var score = 0
            val normalizedStage = normalizeVisionReferenceValue(anchor.stageName)
            if (normalizedStage.isNotBlank() && normalizedStage in stageHints) {
                score += 4
            }
            if (normalizeVisionReferenceValue(anchor.pageSignature) == normalizedPageSignature && normalizedPageSignature.isNotBlank()) {
                score += 3
            }
            if (anchor.locatorHints.any { hint -> normalizeVisionReferenceValue(hint) == normalizedLocatorHint && normalizedLocatorHint.isNotBlank() }) {
                score += 3
            }
            if (anchor.verificationSignals.any { signal ->
                    val normalizedSignal = normalizeVisionReferenceValue(signal)
                    normalizedSignal == normalizedExpectedOutcome || normalizedSignal in normalizedVerificationSignals
                }) {
                score += 2
            }
            if (anchor.pageSignature.isNullOrBlank().not()) {
                score += 1
            }
            if (anchor.locatorHints.isNotEmpty()) {
                score += 1
            }
            score
        }
    val selectedExemplar = preferredReference.exemplarActionSummaries
        .maxByOrNull { exemplar ->
            var score = 0
            val normalizedStage = normalizeVisionReferenceValue(exemplar.stageName)
            if (normalizedStage.isNotBlank() && normalizedStage in stageHints) {
                score += 4
            }
            if (normalizeVisionReferenceValue(exemplar.pageSignature) == normalizedPageSignature && normalizedPageSignature.isNotBlank()) {
                score += 3
            }
            if (normalizeVisionReferenceValue(exemplar.locatorHint) == normalizedLocatorHint && normalizedLocatorHint.isNotBlank()) {
                score += 3
            }
            if (normalizeVisionReferenceValue(exemplar.outcomeSignal) == normalizedExpectedOutcome && normalizedExpectedOutcome.isNotBlank()) {
                score += 2
            }
            if (exemplar.note.isNullOrBlank().not()) {
                score += 1
            }
            score
        }

    return VisionReferenceHints(
        expectedOutcome = adaptReferenceTextForRuntimeTarget(expectedOutcome, runtime, boundaryPacket, preferredReference)
            ?: expectedOutcome,
        locatorHint = adaptReferenceTextForRuntimeTarget(
            locatorHint
                ?: selectedAnchor?.locatorHints?.firstOrNull { value -> value.isNotBlank() }
                ?: selectedExemplar?.locatorHint?.takeIf { value -> value.isNotBlank() },
            runtime,
            boundaryPacket,
            preferredReference
        ),
        verificationSignals = buildList {
            addAll(selectedAnchor?.verificationSignals.orEmpty())
            selectedExemplar?.outcomeSignal?.takeIf { value -> value.isNotBlank() }?.let(::add)
            addAll(preferredReference.verificationSignals)
            add(expectedOutcome)
            addAll(verificationSignals)
        }.mapNotNull { value -> adaptReferenceTextForRuntimeTarget(value, runtime, boundaryPacket, preferredReference) }
            .filter { value -> value.isNotBlank() }
            .distinct(),
        stepNote = adaptReferenceTextForRuntimeTarget(
            stepNote
                ?: selectedExemplar?.note?.takeIf { value -> value.isNotBlank() }
                ?: selectedAnchor?.notes?.firstOrNull { value -> value.isNotBlank() },
            runtime,
            boundaryPacket,
            preferredReference
        ),
        pageSignature = pageSignature
            ?: selectedAnchor?.pageSignature?.takeIf { value -> value.isNotBlank() }
            ?: selectedExemplar?.pageSignature?.takeIf { value -> value.isNotBlank() }
    )
}

private fun resolvePreferredVisionReference(
    reuseContext: CandidateProcessReferenceContext?,
    runtime: ExecutionRuntimeState,
    boundaryPacket: TaskExecutionBoundaryPacket?
): CandidateProcessReference? {
    val references = reuseContext?.candidateReferences.orEmpty()
    if (references.isEmpty()) {
        return null
    }
    val selectedProcessId = runtime.executionResult.selectedProcessId
        ?: boundaryPacket?.processId
        ?: runtime.processId
        ?: runtime.executionTrace.processId
    val normalizedSelectedProcessId = normalizeVisionReferenceValue(selectedProcessId)
    return references.firstOrNull { reference ->
        normalizeVisionReferenceValue(reference.processScope) == normalizedSelectedProcessId ||
            normalizeVisionReferenceValue(reference.processEnum) == normalizedSelectedProcessId ||
            normalizeVisionReferenceValue(reference.assetId) == normalizedSelectedProcessId
    } ?: references.firstOrNull()
}

private fun normalizeVisionReferenceValue(value: String?): String {
    return value.orEmpty().trim().lowercase(Locale.US)
}