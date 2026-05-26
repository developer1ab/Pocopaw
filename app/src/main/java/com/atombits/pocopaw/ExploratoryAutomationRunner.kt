package com.atombits.pocopaw

import android.content.Context
import com.atombits.pocopaw.process.runtime.ProcessExplorationLoopConfig
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class AutomationQueryRequest(
    val objective: String,
    val plan: String,
    val step: Int,
    val selectedToolId: String?,
    val executionBoundaryPacket: TaskExecutionBoundaryPacket? = null,
    val executionBrief: String?,
    val processGuidance: String? = null,
    val toolBundle: String?,
    val historyLines: List<String>,
    val imageDataUrl: String,
    val captureWidth: Int,
    val captureHeight: Int
)

enum class AutomationFlowState {
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    companion object {
        fun fromWire(rawValue: String?): AutomationFlowState {
            return when (rawValue?.trim()?.lowercase(Locale.US)) {
                "completed" -> COMPLETED
                "failed" -> FAILED
                else -> IN_PROGRESS
            }
        }
    }
}
enum class AutomationBusinessState {
    SUCCESS,
    FAILED,
    UNKNOWN;

    companion object {
        fun fromWire(rawValue: String?): AutomationBusinessState {
            return when (rawValue?.trim()?.lowercase(Locale.US)) {
                "success" -> SUCCESS
                "failed" -> FAILED
                else -> UNKNOWN
            }
        }
    }
}

data class AutomationFallbackPolicy(
    val maxAttempts: Int = 0,
    val onFailure: String = "STOP"
)

data class AutomationApprovalContext(
    val required: Boolean = false,
    val approvalScope: String = "none",
    val contextFingerprint: String = ""
)

data class AutomationSemanticContext(
    val goal: String = "",
    val expectedOutcome: String = "",
    val verificationSignals: List<String> = emptyList(),
    val fallbackPolicy: AutomationFallbackPolicy = AutomationFallbackPolicy(),
    val approval: AutomationApprovalContext = AutomationApprovalContext()
)

data class AutomationBlockedContext(
    val reason: String = "",
    val evidence: String = "",
    val stage: String = ""
)

data class AutomationAgentError(
    val code: String,
    val requestedApp: String = "",
    val message: String = ""
)

data class AutomationAgentResponse(
    val thought: String,
    val action: BridgeAction?,
    val message: String,
    val flowState: AutomationFlowState,
    val businessState: AutomationBusinessState,
    val executionStatus: String,
    val blockedContext: AutomationBlockedContext? = null,
    val recoveryAction: String = "",
    val retryBudget: Int = 0,
    val error: AutomationAgentError? = null,
    val finalUserSummary: String = "",
    val semanticContext: AutomationSemanticContext? = null,
    val rawPayload: String = ""
) {
    val isTerminal: Boolean
        get() = flowState != AutomationFlowState.IN_PROGRESS
}

interface AutomationAgentClient {
    suspend fun query(request: AutomationQueryRequest): AutomationAgentResponse
}

internal class VisionAutomationAgentClient(
    private val runtimeConfigProvider: () -> ProviderRuntimeConfig = { ProviderRuntimeConfigs.vision },
    private val model: String? = null,
    private val visionProviderKindProvider: () -> VisionProviderKind = { ProviderRuntimeConfigs.visionProviderKind() }
) : AutomationAgentClient {
    companion object {
        private const val QUERY_TEMPERATURE = 0.2
        private const val QUERY_TOP_P = 0.8
        private const val QUERY_MAX_TOKENS = 1024

        internal const val AUTOMATION_CALL_TIMEOUT_SECONDS = 90L
        internal const val AUTOMATION_CONNECT_TIMEOUT_SECONDS = 15L
        internal const val AUTOMATION_READ_TIMEOUT_SECONDS = 60L
        internal const val AUTOMATION_WRITE_TIMEOUT_SECONDS = 60L

        internal fun createAutomationHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(AUTOMATION_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(AUTOMATION_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(AUTOMATION_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(AUTOMATION_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    private val httpClient = createAutomationHttpClient()

    override suspend fun query(request: AutomationQueryRequest): AutomationAgentResponse {
        runCatching {
            DemoReleaseControl.ensureBackendAccessAllowed()
        }.onFailure {
            return AutomationAgentResponse(
                thought = "automation quota exhausted",
                action = null,
                message = "DEMO_QUOTA_EXCEEDED",
                flowState = AutomationFlowState.FAILED,
                businessState = AutomationBusinessState.FAILED,
                executionStatus = "failed"
            )
        }
        val runtimeConfig = runtimeConfigProvider()
        if (!runtimeConfig.isConfigured()) {
            return AutomationAgentResponse(
                thought = "automation api unavailable",
                action = null,
                message = UiStrings.resolve(
                    R.string.vision_model_not_configured,
                    "Vision model configuration is missing."
                ),
                flowState = AutomationFlowState.FAILED,
                businessState = AutomationBusinessState.FAILED,
                executionStatus = "failed"
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val selectedModel = model ?: runtimeConfig.model
                val packet = PromptCenter.buildAutomationAgentPacket(
                    AutomationQueryPromptSpec(
                        objective = request.objective,
                        plan = request.plan,
                        step = request.step,
                        selectedToolId = request.selectedToolId,
                        executionBoundaryPacket = request.executionBoundaryPacket,
                        executionBrief = request.executionBrief,
                        processGuidance = request.processGuidance,
                        toolBundle = request.toolBundle,
                        historyLines = request.historyLines,
                        captureWidth = request.captureWidth,
                        captureHeight = request.captureHeight
                    )
                )
                val requestBody = if (runtimeConfig.apiStyle == ProviderApiStyle.GEMINI_GENERATE_CONTENT) {
                    buildGeminiAutomationRequestBody(packet, request)
                } else {
                    buildOpenAiAutomationRequestBody(packet, request, selectedModel, visionProviderKindProvider())
                }

                val httpRequestBuilder = Request.Builder()
                    .url(resolveAutomationEndpoint(runtimeConfig, selectedModel))
                    .addHeader("Content-Type", "application/json")

                if (runtimeConfig.apiStyle == ProviderApiStyle.OPENAI_CHAT) {
                    httpRequestBuilder.addHeader("Authorization", "Bearer ${runtimeConfig.apiKey}")
                }

                val httpRequest = httpRequestBuilder
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val bodyPreview = body.takeIf { raw -> raw.isNotBlank() }
                        ?.let(::buildAutomationDebugPreview)
                    if (!response.isSuccessful) {
                        return@withContext AutomationAgentResponse(
                            thought = "automation request failed",
                            action = null,
                            message = buildAutomationFailureMessage(
                                prefix = "Automation request failed: HTTP ${response.code}",
                                detail = response.message.takeIf { it.isNotBlank() },
                                preview = bodyPreview
                            ),
                            flowState = AutomationFlowState.FAILED,
                            businessState = AutomationBusinessState.FAILED,
                            executionStatus = "failed",
                            rawPayload = body
                        )
                    }
                    val content = extractAutomationResponseContent(body, runtimeConfig.apiStyle)
                    if (content.isBlank()) {
                        return@withContext AutomationAgentResponse(
                            thought = "automation response empty",
                            action = null,
                            message = buildAutomationFailureMessage(
                                prefix = "Automation response was empty",
                                preview = bodyPreview
                            ),
                            flowState = AutomationFlowState.FAILED,
                            businessState = AutomationBusinessState.FAILED,
                            executionStatus = "failed",
                            rawPayload = body
                        )
                    }
                    runCatching {
                        parseAutomationAgentResponse(content)
                    }.getOrElse { throwable ->
                        AutomationAgentResponse(
                            thought = "automation response parse failed",
                            action = null,
                            message = buildAutomationFailureMessage(
                                prefix = "Automation response parse failed",
                                detail = throwable.toAutomationDebugDetail(),
                                preview = content
                            ),
                            flowState = AutomationFlowState.FAILED,
                            businessState = AutomationBusinessState.FAILED,
                            executionStatus = "failed",
                            rawPayload = content
                        )
                    }
                }
            }.getOrElse { throwable ->
                AutomationAgentResponse(
                    thought = "automation query crashed",
                    action = null,
                    message = buildAutomationFailureMessage(
                        prefix = "Automation query crashed",
                        detail = throwable.toAutomationDebugDetail()
                    ),
                    flowState = AutomationFlowState.FAILED,
                    businessState = AutomationBusinessState.FAILED,
                    executionStatus = "failed"
                )
            }
        }
    }

    private fun buildOpenAiAutomationRequestBody(
        packet: PromptPacket,
        request: AutomationQueryRequest,
        selectedModel: String,
        provider: VisionProviderKind
    ): JSONObject {
        return JSONObject().apply {
            put("model", selectedModel)
            put("messages", JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", packet.promptMessages.first().content)
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", packet.promptMessages.last().content)
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", request.imageDataUrl)
                                    })
                                }
                            )
                        })
                    }
                )
            })
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("temperature", QUERY_TEMPERATURE)
            put("top_p", QUERY_TOP_P)
            put("max_tokens", QUERY_MAX_TOKENS)
        }.applyVisionRequestTuning(provider)
    }

    private fun buildGeminiAutomationRequestBody(
        packet: PromptPacket,
        request: AutomationQueryRequest
    ): JSONObject {
        val imagePart = buildGeminiInlineImagePart(request.imageDataUrl)
        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().apply {
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().apply {
                                put("text", packet.promptMessages.first().content)
                            }
                        )
                    )
                }
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("text", packet.promptMessages.last().content)
                                    }
                                )
                                put(imagePart)
                            }
                        )
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", QUERY_TEMPERATURE)
                    put("topP", QUERY_TOP_P)
                    put("maxOutputTokens", QUERY_MAX_TOKENS)
                }
            )
        }
    }

    internal fun buildGeminiInlineImagePart(imageDataUrl: String): JSONObject {
        val prefixEnd = imageDataUrl.indexOf(',')
        require(prefixEnd > 0) { "Automation image payload is not a valid data URL" }

        val metadata = imageDataUrl.substring(0, prefixEnd)
        val data = imageDataUrl.substring(prefixEnd + 1)
        val mimeType = metadata.removePrefix("data:").substringBefore(';').ifBlank { "image/png" }
        require(data.isNotBlank()) { "Automation image payload is empty" }

        return JSONObject().apply {
            put(
                "inlineData",
                JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", data)
                }
            )
        }
    }

    internal fun resolveAutomationEndpoint(
        runtimeConfig: ProviderRuntimeConfig,
        selectedModel: String
    ): String {
        if (runtimeConfig.apiStyle == ProviderApiStyle.OPENAI_CHAT) {
            return runtimeConfig.endpoint
        }

        val encodedKey = URLEncoder.encode(runtimeConfig.apiKey, Charsets.UTF_8.name())
        return "${runtimeConfig.endpoint.trimEnd('/')}/$selectedModel:generateContent?key=$encodedKey"
    }

    internal fun extractAutomationResponseContent(
        rawBody: String,
        apiStyle: ProviderApiStyle
    ): String {
        val root = JSONObject(rawBody)
        return if (apiStyle == ProviderApiStyle.GEMINI_GENERATE_CONTENT) {
            buildString {
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return@buildString
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optBoolean("thought")) {
                        continue
                    }
                    val text = part.optString("text")
                    if (text.isNotBlank()) {
                        append(text)
                    }
                }
            }
        } else {
            root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }
    }
}

class ExploratoryAutomationRunner private constructor(
    private val toolBundleProvider: (String, String?) -> String?,
    private val automationClient: AutomationAgentClient,
    private val screenCaptureCoordinator: ScreenCaptureCoordinator,
    private val actionExecutor: BridgeActionExecutor,
    private val foregroundPackageProvider: () -> String?,
    private val maxSteps: Int,
    private val settleMs: Long,
    private val maxSameActionRepeat: Int,
    private val pause: suspend (Long) -> Unit
) : PrototypeAutomationRunner {

    companion object {
        const val MAX_STEPS = 15
        const val SETTLE_MS = 1500L
        const val APP_LAUNCH_CAPTURE_SETTLE_MS = 3000L
        const val MAX_SAME_ACTION_REPEAT = 2
        private const val QUERY_HISTORY_LIMIT = 6
        private val validFirstActions = setOf("appLaunch")
    }

    constructor(
        context: Context,
        toolspaceCatalogManager: ToolspaceCatalogManager,
        automationClient: AutomationAgentClient = VisionAutomationAgentClient(),
        screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
        actionExecutor: BridgeActionExecutor = LocalBridgeActionExecutor(
            launchContextProvider = { context },
            deviceControllerProvider = { PrototypeAccessibilityService.instance }
        ),
        loopConfig: ProcessExplorationLoopConfig = ProcessExplorationLoopConfig.prototypeCurrent()
    ) : this(
        toolBundleProvider = { objective, selectedToolId ->
            buildAutomationToolBundle(
                toolspaceCatalogManager = toolspaceCatalogManager,
                objective = objective,
                selectedToolId = selectedToolId
            )
        },
        automationClient = automationClient,
        screenCaptureCoordinator = screenCaptureCoordinator,
        actionExecutor = actionExecutor,
        foregroundPackageProvider = {
            PrototypeAccessibilityService.instance
                ?.rootInActiveWindow
                ?.packageName
                ?.toString()
                ?.trim()
                ?.ifBlank { null }
        },
        maxSteps = loopConfig.maxSteps,
        settleMs = loopConfig.settleMs,
        maxSameActionRepeat = loopConfig.maxSameActionRepeat,
        pause = { delay(it) }
    )

    internal constructor(
        toolBundleProvider: (String, String?) -> String?,
        automationClient: AutomationAgentClient,
        screenCaptureCoordinator: ScreenCaptureCoordinator,
        actionExecutor: BridgeActionExecutor,
        foregroundPackageProvider: () -> String? = { null },
        maxSteps: Int = MAX_STEPS,
        settleMs: Long = SETTLE_MS,
        maxSameActionRepeat: Int = MAX_SAME_ACTION_REPEAT,
        pause: suspend (Long) -> Unit = { delay(it) },
        testOnly: Boolean = true
    ) : this(
        toolBundleProvider = toolBundleProvider,
        automationClient = automationClient,
        screenCaptureCoordinator = screenCaptureCoordinator,
        actionExecutor = actionExecutor,
        foregroundPackageProvider = foregroundPackageProvider,
        maxSteps = maxSteps,
        settleMs = settleMs,
        maxSameActionRepeat = maxSameActionRepeat,
        pause = pause
    )

    override suspend fun execute(
        runtimeState: ExecutionRuntimeState,
        shortcutCandidate: ProcessShortcutCandidate?,
        executionMode: AutomationExecutionMode,
        onWriteback: suspend (ExecutionWritebackRecord) -> Unit
    ) {
        onWriteback(
            buildMissingExecutionBoundaryWriteback(
                summary = UiStrings.resolve(
                    R.string.exploratory_missing_boundary,
                    "Exploratory automation requires a task-aligned execution boundary packet."
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
                        R.string.exploratory_missing_boundary,
                        "Exploratory automation requires a task-aligned execution boundary packet."
                    )
                )
            )
            return
        }

        val history = mutableListOf<String>()
        var effectiveRuntimeState = runtimeState
        var lastActionSignature: String? = null
        var sameActionCount = 0
        var pendingAppLaunchCaptureSettle =
            effectiveRuntimeState.executionTrace.steps.lastOrNull()?.stepType == "OPEN_APP"

        for (step in 1..maxSteps) {
            val isPostLaunchRound = pendingAppLaunchCaptureSettle
            if (pendingAppLaunchCaptureSettle) {
                val settleWriteback = buildProgressCheckpointWriteback(
                    step = step,
                    checkpoint = UiStrings.resolve(
                        R.string.exploratory_checkpoint_waiting_for_launch,
                        "waiting for launched app to settle"
                    )
                )
                onWriteback(settleWriteback)
                effectiveRuntimeState = effectiveRuntimeState.afterWriteback(settleWriteback)
                pause(APP_LAUNCH_CAPTURE_SETTLE_MS)
                pendingAppLaunchCaptureSettle = false
            }

            if (!screenCaptureCoordinator.hasPermission()) {
                onWriteback(
                    buildFailureWriteback(
                        summary = UiStrings.resolve(
                            R.string.exploratory_capture_permission_missing,
                            "Exploratory automation unavailable: screen capture permission missing"
                        ),
                        expectedOutcome = "screen_capture_permission_unavailable"
                    )
                )
                return
            }

            if (isPostLaunchRound) {
                val captureWriteback = buildProgressCheckpointWriteback(
                    step = step,
                    checkpoint = UiStrings.resolve(
                        R.string.exploratory_checkpoint_capturing_screen,
                        "capturing current screen"
                    )
                )
                onWriteback(captureWriteback)
                effectiveRuntimeState = effectiveRuntimeState.afterWriteback(captureWriteback)
            }
            val snapshot = screenCaptureCoordinator.captureSnapshot()
            if (snapshot == null) {
                onWriteback(
                    buildFailureWriteback(
                        summary = UiStrings.resolve(
                            R.string.exploratory_capture_failed,
                            "Exploratory automation unavailable: screen capture failed"
                        ),
                        expectedOutcome = "screen_capture_failed"
                    )
                )
                return
            }

            val objective = boundaryPacket.executionObjectiveSummary.takeIf { value -> value.isNotBlank() }
                ?: effectiveRuntimeState.executionResult.summary.takeIf { value -> value.isNotBlank() }
                ?: UiStrings.resolve(R.string.execution_current_task_fallback, "the current task")
            val plan = boundaryPacket.executionPlanSummary.takeIf { value -> value.isNotBlank() }
                ?: objective
            val selectedToolId = effectiveRuntimeState.executionResult.selectedToolId
                ?: boundaryPacket.capabilityId
                ?: effectiveRuntimeState.capabilityId
            if (isPostLaunchRound) {
                val plannerWriteback = buildProgressCheckpointWriteback(
                    step = step,
                    checkpoint = "waiting for planner response"
                )
                onWriteback(plannerWriteback)
                effectiveRuntimeState = effectiveRuntimeState.afterWriteback(plannerWriteback)
            }
            val plannerResponse = automationClient.query(
                AutomationQueryRequest(
                    objective = objective,
                    plan = plan,
                    step = step,
                    selectedToolId = selectedToolId,
                    executionBoundaryPacket = boundaryPacket,
                    executionBrief = effectiveRuntimeState.toExecutionKeyInfo(),
                    processGuidance = buildAutomationProcessGuidance(effectiveRuntimeState, boundaryPacket),
                    toolBundle = toolBundleProvider(
                        objective,
                        selectedToolId
                    ),
                    historyLines = history.takeLast(QUERY_HISTORY_LIMIT),
                    imageDataUrl = snapshot.imageDataUrl,
                    captureWidth = snapshot.captureWidth,
                    captureHeight = snapshot.captureHeight
                )
            )
            val response = plannerResponse
            recordPlannerHistory(history, step, response)

            val constraintDowngrade = detectConstraintDowngrade(response, boundaryPacket)
            if (constraintDowngrade != null) {
                history += JSONObject().apply {
                    put("step", step)
                    put("action", response.action?.type ?: "done")
                    put("error", "CONSTRAINT_DOWNGRADE_BLOCKED")
                    put("msg", "planner action silently projected a more specific active constraint into a broader input; replan without losing task constraints")
                    put("action_text", constraintDowngrade.actionText)
                    put("more_specific_constraint", constraintDowngrade.moreSpecificConstraint)
                }.toString()
                continue
            }

            if (response.error?.code == "APP_NOT_FOUND") {
                onWriteback(
                    buildFailureWriteback(
                        summary = response.error.message.ifBlank {
                            "Unable to locate the target app on this device."
                        },
                        expectedOutcome = "app_not_found",
                        verificationSignals = response.semanticContext?.verificationSignals.orEmpty(),
                        note = response.error.requestedApp.ifBlank { response.message },
                            automationResponsePayload = response.rawPayload.ifBlank { null },
                            routeInfo = effectiveRuntimeState.executionResult.routeInfo
                    )
                )
                return
            }

            if (response.action == null) {
                if (response.flowState != AutomationFlowState.IN_PROGRESS) {
                    onWriteback(buildTerminalWriteback(effectiveRuntimeState, response))
                    return
                }

                onWriteback(
                    ExecutionWritebackRecord(
                        lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                        summary = UiStrings.resolve(
                            R.string.exploratory_missing_terminal_state,
                            "Automation response missing explicit terminal flow_state; replanning on current screen"
                        ),
                        automationResponsePayload = response.rawPayload.ifBlank { null }
                    )
                )
                history += JSONObject().apply {
                    put("step", step)
                    put("error", "NULL_ACTION_NON_TERMINAL")
                    put("msg", "planner omitted the required next action; replan on current screen")
                    if (response.rawPayload.isNotBlank()) {
                        put("payload_preview", buildAutomationDebugPreview(response.rawPayload))
                    }
                }.toString()
                continue
            }

            val hasLaunchAlready = effectiveRuntimeState.executionTrace.steps.any { traceStep ->
                traceStep.stepType == "OPEN_APP"
            }
            if (step == 1 && !hasLaunchAlready && response.action.type !in validFirstActions) {
                history += JSONObject().apply {
                    put("step", step)
                    put("action", response.action.type)
                    put("error", "FIRST_ACTION_MUST_OPEN_RESOURCE")
                    put("msg", "first action must select a resource entry")
                }.toString()
                continue
            }

            val signature = actionSignature(response.action)
            sameActionCount = if (signature == lastActionSignature) {
                sameActionCount + 1
            } else {
                lastActionSignature = signature
                1
            }
            if (sameActionCount > maxSameActionRepeat && isGuardableRepeat(response.action)) {
                history += JSONObject().apply {
                    put("step", step)
                    put("action", response.action.type)
                    put("error", "REPEATED_ACTION_BLOCKED")
                    put("msg", "repeated action blocked to force replanning")
                }.toString()
                continue
            }

            if (response.action.type == "appLaunch") {
                val systemIntentCapabilityId = resolveBridgeLaunchSystemCapabilityId(response.action.uri)
                if (!systemIntentCapabilityId.isNullOrBlank()) {
                    history += JSONObject().apply {
                        put("step", step)
                        put("action", response.action.type)
                        put("error", "SYSTEM_INTENT_APP_LAUNCH_FORBIDDEN")
                        put("msg", "system intent launches are resolved locally before visual automation starts")
                        put("capability", systemIntentCapabilityId)
                    }.toString()
                    continue
                }
            }

            if (response.action.type == "appLaunch") {
                val targetPackage = resolveBridgeLaunchPackage(response.action.uri)
                val foregroundPackage = foregroundPackageProvider()
                if (!targetPackage.isNullOrBlank() && targetPackage == foregroundPackage) {
                    val redundantLaunchWriteback = buildRedundantAppLaunchWriteback(response, targetPackage)
                    onWriteback(redundantLaunchWriteback)
                    effectiveRuntimeState = effectiveRuntimeState.afterWriteback(redundantLaunchWriteback)
                    history += JSONObject().apply {
                        put("step", step)
                        put("action", response.action.type)
                        put("execution", "skipped")
                        put("reason", "REDUNDANT_APP_LAUNCH_ALREADY_FOREGROUND")
                        put("package", targetPackage)
                    }.toString()
                    continue
                }
            }

            val executionResult = actionExecutor.executeWithResult(response.action)
            if (!executionResult.executed) {
                if (response.action.type == "appLaunch") {
                    val payload = describeActionPayload(response.action)
                    onWriteback(
                        buildFailureWriteback(
                            summary = UiStrings.resolve(
                                R.string.exploratory_launch_failed,
                                "Exploratory automation failed to launch the target app (payload: %1\$s)",
                                payload
                            ),
                            expectedOutcome = response.semanticContext?.expectedOutcome.orEmpty().ifBlank {
                                "app_launch_failed"
                            },
                            verificationSignals = response.semanticContext?.verificationSignals.orEmpty(),
                            note = buildString {
                                response.message.ifBlank { null }?.let(::append)
                                if (isNotEmpty()) {
                                    append(" | ")
                                }
                                append("payload: ")
                                append(payload)
                            },
                            automationResponsePayload = response.rawPayload.ifBlank { null },
                            routeInfo = effectiveRuntimeState.executionResult.routeInfo
                        )
                    )
                    return
                }
                val executionFailureWriteback = buildActionExecutionFailureWriteback(
                    runtimeState = effectiveRuntimeState,
                    response = response,
                    executionNote = executionResult.note
                )
                onWriteback(executionFailureWriteback)
                effectiveRuntimeState = effectiveRuntimeState.afterWriteback(executionFailureWriteback)
                history += JSONObject().apply {
                    put("step", step)
                    put("action", response.action.type)
                    put("error", "ACTION_EXECUTION_FAILED")
                    put("msg", "local action execution failed")
                    executionResult.note?.takeIf { value -> value.isNotBlank() }?.let { note ->
                        put("note", note)
                    }
                }.toString()
            } else {
                val runningWriteback = ExecutionWritebackRecord(
                    lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                    summary = response.message.ifBlank {
                        UiStrings.resolve(
                            R.string.exploratory_action_executed_locally,
                            "Exploratory action executed locally"
                        )
                    },
                    automationResponsePayload = response.rawPayload.ifBlank { null },
                    appendedSteps = listOf(buildActionStep(effectiveRuntimeState, response.action, response))
                )
                onWriteback(runningWriteback)
                effectiveRuntimeState = effectiveRuntimeState.afterWriteback(runningWriteback)
                history += JSONObject().apply {
                    put("step", step)
                    put("action", response.action.type)
                    put("execution", "ok")
                    put("msg", response.message)
                    executionResult.note?.takeIf { value -> value.isNotBlank() }?.let { note ->
                        put("note", note)
                    }
                }.toString()
            }

            if (response.action.type == "appLaunch") {
                pendingAppLaunchCaptureSettle = true
            } else {
                pause(settleMs)
            }
        }

        onWriteback(
            buildFailureWriteback(
                summary = UiStrings.resolve(
                    R.string.exploratory_max_steps,
                    "Exploratory automation reached the max step budget without a terminal outcome"
                ),
                expectedOutcome = "automation_max_steps_exhausted"
            )
        )
    }

    private fun buildActionStep(
        runtimeState: ExecutionRuntimeState,
        action: BridgeAction,
        response: AutomationAgentResponse
    ): ExecutionTraceStep {
        val semanticContext = response.semanticContext
        val routeFallback = parseRouteSemanticFallback(runtimeState.executionResult.routeInfo)
        val expectedOutcome = semanticContext?.expectedOutcome?.ifBlank { null }
            ?: routeFallback.expectedOutcome
            ?: response.message.ifBlank { "action_executed" }
        val verificationSignals = (semanticContext?.verificationSignals.orEmpty() + routeFallback.verificationSignals)
            .filter { value -> value.isNotBlank() }
            .distinct()
        return ExecutionTraceStep(
            stepType = action.toTraceStepType(),
            groundingMode = if (action.type == "appLaunch") "APP_LAUNCH" else "VISION",
            expectedOutcome = expectedOutcome,
            fallbackPolicy = semanticContext?.fallbackPolicy?.onFailure?.ifBlank { "STOP" } ?: "STOP",
            riskLevel = if (semanticContext?.approval?.required == true) "HIGH" else "LOW",
            verificationSignals = verificationSignals,
            continuationMode = "VERIFY_THEN_CONTINUE",
            note = response.message.ifBlank {
                semanticContext?.goal?.ifBlank { null }
                    ?: routeFallback.note
            },
            actionType = action.toVisionActionType(),
            locatorHint = semanticContext?.goal?.ifBlank { null } ?: routeFallback.locatorHint,
            targetX = action.x ?: action.toX,
            targetY = action.y ?: action.toY,
            inputText = action.text,
            swipeFromX = action.fromX,
            swipeFromY = action.fromY,
            swipeToX = action.toX,
            swipeToY = action.toY,
            actionDurationMs = action.duration,
            pageSignature = routeFallback.pageSignature
        )
    }

    private fun buildActionExecutionFailureWriteback(
        runtimeState: ExecutionRuntimeState,
        response: AutomationAgentResponse,
        executionNote: String?
    ): ExecutionWritebackRecord {
        val actionLabel = response.action?.type.orEmpty().ifBlank { "unknown_action" }
        val detail = listOfNotNull(
            response.message.takeIf { value -> value.isNotBlank() },
            executionNote?.takeIf { value -> value.isNotBlank() }
        ).joinToString(" | ").ifBlank { actionLabel }
        val routeFallback = parseRouteSemanticFallback(runtimeState.executionResult.routeInfo)
        return ExecutionWritebackRecord(
            lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
            summary = UiStrings.resolve(
                R.string.exploratory_action_execution_failed,
                "Local action execution failed: %1\$s",
                detail
            ),
            automationResponsePayload = response.rawPayload.ifBlank { null },
            appendedSteps = listOf(
                ExecutionTraceStep(
                    stepType = "VERIFY",
                    groundingMode = "VISION",
                    expectedOutcome = response.semanticContext?.expectedOutcome?.ifBlank { null }
                        ?: routeFallback.expectedOutcome
                        ?: "action_execution_failed",
                    fallbackPolicy = "STOP",
                    riskLevel = "LOW",
                    verificationSignals = (response.semanticContext?.verificationSignals.orEmpty() + routeFallback.verificationSignals)
                        .filter { value -> value.isNotBlank() }
                        .distinct(),
                    continuationMode = "STOP",
                    note = listOfNotNull(
                        response.message.takeIf { value -> value.isNotBlank() },
                        executionNote?.takeIf { value -> value.isNotBlank() },
                        routeFallback.note?.takeIf { value -> value.isNotBlank() }
                    ).joinToString(" | ").ifBlank { null },
                    locatorHint = routeFallback.locatorHint,
                    pageSignature = routeFallback.pageSignature
                )
            )
        )
    }

    private fun buildProgressCheckpointWriteback(
        step: Int,
        checkpoint: String
    ): ExecutionWritebackRecord {
        return ExecutionWritebackRecord(
            lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
            summary = UiStrings.resolve(
                R.string.exploratory_post_launch_step,
                "Exploratory post-launch step %1\$d/%2\$d: %3\$s",
                step,
                maxSteps,
                checkpoint
            )
        )
    }

    private fun buildRedundantAppLaunchWriteback(
        response: AutomationAgentResponse,
        targetPackage: String
    ): ExecutionWritebackRecord {
        return ExecutionWritebackRecord(
            lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
            summary = UiStrings.resolve(
                R.string.exploratory_redundant_launch,
                "Ignored redundant appLaunch for already focused package: %1\$s",
                targetPackage
            ),
            automationResponsePayload = response.rawPayload.ifBlank { null }
        )
    }

    private fun buildTerminalWriteback(
        runtimeState: ExecutionRuntimeState,
        response: AutomationAgentResponse
    ): ExecutionWritebackRecord {
        val routeFallback = parseRouteSemanticFallback(runtimeState.executionResult.routeInfo)
        val lifecycleStatus = if (response.flowState == AutomationFlowState.FAILED) {
            ExecutionLifecycleStatus.FAILED
        } else {
            ExecutionLifecycleStatus.COMPLETED
        }
        val terminalExpectedOutcome = response.semanticContext?.expectedOutcome
            ?.ifBlank { null }
            ?: routeFallback.expectedOutcome
            ?: if (lifecycleStatus == ExecutionLifecycleStatus.COMPLETED) {
                "automation_completed"
            } else {
                "automation_failed"
            }
        return ExecutionWritebackRecord(
            lifecycleStatus = lifecycleStatus,
            summary = response.message.ifBlank {
                if (lifecycleStatus == ExecutionLifecycleStatus.COMPLETED) {
                    "Exploratory automation completed locally"
                } else {
                    "Exploratory automation failed locally"
                }
            },
            automationResponsePayload = response.rawPayload.ifBlank { null },
            appendedSteps = listOf(
                ExecutionTraceStep(
                    stepType = "VERIFY",
                    groundingMode = "VISION",
                    expectedOutcome = terminalExpectedOutcome,
                    fallbackPolicy = "STOP",
                    riskLevel = if (lifecycleStatus == ExecutionLifecycleStatus.COMPLETED) "LOW" else "MEDIUM",
                    verificationSignals = (response.semanticContext?.verificationSignals.orEmpty() + routeFallback.verificationSignals)
                        .filter { value -> value.isNotBlank() }
                        .distinct(),
                    continuationMode = "STOP",
                    note = response.finalUserSummary.ifBlank { response.message.ifBlank { routeFallback.note } },
                    locatorHint = routeFallback.locatorHint,
                    pageSignature = routeFallback.pageSignature
                )
            )
        )
    }

    private fun buildFailureWriteback(
        summary: String,
        expectedOutcome: String,
        verificationSignals: List<String> = emptyList(),
        note: String? = null,
        automationResponsePayload: String? = null,
        routeInfo: String? = null
    ): ExecutionWritebackRecord {
        val routeFallback = parseRouteSemanticFallback(routeInfo)
        return ExecutionWritebackRecord(
            lifecycleStatus = ExecutionLifecycleStatus.FAILED,
            summary = summary,
            automationResponsePayload = automationResponsePayload,
            appendedSteps = listOf(
                ExecutionTraceStep(
                    stepType = "VERIFY",
                    groundingMode = "VISION",
                    expectedOutcome = expectedOutcome,
                    fallbackPolicy = "STOP",
                    riskLevel = "LOW",
                    verificationSignals = (verificationSignals + routeFallback.verificationSignals)
                        .filter { value -> value.isNotBlank() }
                        .distinct(),
                    continuationMode = "STOP",
                    note = note ?: routeFallback.note,
                    locatorHint = routeFallback.locatorHint,
                    pageSignature = routeFallback.pageSignature
                )
            )
        )
    }

    private fun recordPlannerHistory(
        history: MutableList<String>,
        step: Int,
        response: AutomationAgentResponse
    ) {
        if (response.message.isBlank() && response.thought.isBlank()) {
            return
        }
        history += JSONObject().apply {
            put("step", step)
            put("action", response.action?.type ?: "done")
            put("msg", response.message)
            put("thought", response.thought)
            response.semanticContext?.let { semanticContext ->
                put("goal", semanticContext.goal)
                put("expected_outcome", semanticContext.expectedOutcome)
                if (semanticContext.verificationSignals.isNotEmpty()) {
                    put("verification_signals", JSONArray(semanticContext.verificationSignals))
                }
            }
        }.toString()
    }

    private fun actionSignature(action: BridgeAction): String {
        fun quantize(value: Float?): String {
            return if (value == null) {
                "-"
            } else {
                String.format(Locale.US, "%.3f", value)
            }
        }

        return when (action.type) {
            "tap", "longPress" -> "${action.type}:${quantize(action.x)}:${quantize(action.y)}"
            "swipe" -> "${action.type}:${quantize(action.fromX)}:${quantize(action.fromY)}:${quantize(action.toX)}:${quantize(action.toY)}"
            "inputText" -> "${action.type}:${action.text.orEmpty().take(32)}"
            "appLaunch" -> "${action.type}:${action.uri.orEmpty()}"
            "keyevent" -> "${action.type}:${action.keyCode ?: -1}"
            else -> action.type
        }
    }

    private fun describeActionPayload(action: BridgeAction?): String {
        if (action == null) {
            return "type=<missing>"
        }
        return when (action.type) {
            "appLaunch" -> "type=appLaunch, uri=${action.uri?.ifBlank { null } ?: "<missing>"}"
            else -> "type=${action.type}"
        }
    }

    private fun isGuardableRepeat(action: BridgeAction): Boolean {
        return when (action.type) {
            "tap", "longPress", "inputText" -> true
            else -> false
        }
    }
}

private fun ExecutionRuntimeState.afterWriteback(writebackRecord: ExecutionWritebackRecord): ExecutionRuntimeState {
    return copy(
        executionResult = executionResult.copy(
            lifecycleStatus = writebackRecord.lifecycleStatus,
            summary = writebackRecord.summary
        ),
        executionTrace = executionTrace.copy(
            steps = executionTrace.steps + writebackRecord.appendedSteps
        )
    )
}

private fun BridgeAction.toTraceStepType(): String {
    return when (type) {
        "appLaunch" -> "OPEN_APP"
        "tap" -> "CLICK"
        "swipe" -> "SWIPE"
        "inputText" -> "INPUT"
        "longPress" -> "LONG_PRESS"
        "keyevent" -> if (keyCode == 4) "BACK" else "KEYEVENT"
        "wait" -> "WAIT"
        else -> type.uppercase(Locale.US)
    }
}

private fun BridgeAction.toVisionActionType(): VisionActionType? {
    return when (type) {
        "tap", "longPress" -> VisionActionType.TAP
        "swipe" -> VisionActionType.SWIPE
        "inputText" -> VisionActionType.INPUT
        "keyevent" -> if (keyCode == 4) VisionActionType.BACK else VisionActionType.NONE
        "wait" -> VisionActionType.WAIT
        else -> null
    }
}

private fun buildAutomationToolBundle(
    toolspaceCatalogManager: ToolspaceCatalogManager,
    objective: String,
    selectedToolId: String?
): String? {
    return buildTaskCapabilityBundle(
        toolspaceCatalogManager = toolspaceCatalogManager,
        task = objective,
        selectedToolId = selectedToolId
    )?.toPromptSection()
}

internal fun parseAutomationAgentResponse(content: String): AutomationAgentResponse {
    val parsed = JSONObject(content)
    val actionObject = parsed.optJSONObject("action")
    val actionType = actionObject?.optString("type", "")?.trim().orEmpty()
    val action = if (actionObject != null && actionType.isNotBlank() && actionType != "done") {
        BridgeAction.fromJson(parsed)
    } else {
        null
    }
    val errorObject = parsed.optJSONObject("error")
    val error = errorObject?.optString("code")?.takeIf { code -> code.isNotBlank() }?.let { code ->
        AutomationAgentError(
            code = code,
            requestedApp = errorObject.optString("requestedApp", ""),
            message = errorObject.optString("message", "")
        )
    }
    val blockedContextObject = parsed.optJSONObject("blocked_context")
    val blockedContext = blockedContextObject?.let { blocked ->
        val reason = blocked.optString("reason", "").trim()
        val evidence = blocked.optString("evidence", "").trim()
        val stage = blocked.optString("stage", "").trim()
        if (reason.isBlank() && evidence.isBlank() && stage.isBlank()) {
            null
        } else {
            AutomationBlockedContext(reason = reason, evidence = evidence, stage = stage)
        }
    }
    return normalizeAutomationAgentResponse(
        AutomationAgentResponse(
        thought = parsed.optString("thought", ""),
        action = action,
        message = parsed.optString("message", ""),
        flowState = parsed.optString("flow_state", "")
            .takeIf { value -> value.isNotBlank() }
            ?.let(AutomationFlowState::fromWire)
            ?: if (actionType == "done") {
                AutomationFlowState.COMPLETED
            } else {
                AutomationFlowState.IN_PROGRESS
            },
        businessState = AutomationBusinessState.fromWire(parsed.optString("business_state", "")),
        executionStatus = parsed.optString("execution_status", "").trim().lowercase(Locale.US)
            .ifBlank { if (actionType == "done") "ok" else "blocked" },
        blockedContext = blockedContext,
        recoveryAction = parsed.optString("recovery_action", "").trim(),
        retryBudget = parsed.optInt("retry_budget", 0).coerceAtLeast(0),
        error = error,
        finalUserSummary = parsed.optString("final_user_summary", "").trim(),
        semanticContext = parseAutomationSemanticContext(parsed.optJSONObject("semantic_context")),
        rawPayload = content.trim()
        )
    )
}

private fun normalizeAutomationAgentResponse(response: AutomationAgentResponse): AutomationAgentResponse {
    if (response.action == null || response.flowState == AutomationFlowState.IN_PROGRESS) {
        return response
    }
    return response.copy(
        flowState = AutomationFlowState.IN_PROGRESS,
        finalUserSummary = ""
    )
}

private fun parseAutomationSemanticContext(raw: JSONObject?): AutomationSemanticContext? {
    if (raw == null) {
        return null
    }
    val verificationSignals = buildList {
        val values = raw.optJSONArray("verification_signals") ?: JSONArray()
        for (index in 0 until values.length()) {
            val signal = values.optString(index).trim()
            if (signal.isNotBlank()) {
                add(signal)
            }
        }
    }
    val fallbackObject = raw.optJSONObject("fallback_policy")
    val approvalObject = raw.optJSONObject("approval")
    val semanticContext = AutomationSemanticContext(
        goal = raw.optString("goal", "").trim(),
        expectedOutcome = raw.optString("expected_outcome", "").trim(),
        verificationSignals = verificationSignals,
        fallbackPolicy = AutomationFallbackPolicy(
            maxAttempts = fallbackObject?.optInt("max_attempts", 0)?.coerceAtLeast(0) ?: 0,
            onFailure = fallbackObject?.optString("on_failure", "STOP")?.trim()?.ifBlank { "STOP" } ?: "STOP"
        ),
        approval = AutomationApprovalContext(
            required = approvalObject?.optBoolean("required", false) ?: false,
            approvalScope = approvalObject?.optString("approval_scope", "none")?.trim()?.ifBlank { "none" } ?: "none",
            contextFingerprint = approvalObject?.optString("context_fingerprint", "")?.trim().orEmpty()
        )
    )
    return if (
        semanticContext.goal.isBlank() &&
        semanticContext.expectedOutcome.isBlank() &&
        semanticContext.verificationSignals.isEmpty() &&
        semanticContext.fallbackPolicy.maxAttempts == 0 &&
        semanticContext.fallbackPolicy.onFailure == "STOP" &&
        !semanticContext.approval.required &&
        semanticContext.approval.contextFingerprint.isBlank()
    ) {
        null
    } else {
        semanticContext
    }
}

internal fun Throwable.toAutomationDebugDetail(): String {
    val simpleName = javaClass.simpleName.ifBlank { "Exception" }
    val rawMessage = message?.trim().orEmpty()
    return if (rawMessage.isBlank() || rawMessage.equals(simpleName, ignoreCase = true)) {
        simpleName
    } else {
        "$simpleName: $rawMessage"
    }
}

internal fun buildAutomationDebugPreview(raw: String, maxChars: Int = 160): String {
    val normalized = raw.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) {
        return normalized
    }
    return normalized.take(maxChars - 3).trimEnd() + "..."
}

internal fun buildAutomationFailureMessage(
    prefix: String,
    detail: String? = null,
    preview: String? = null
): String {
    val normalizedDetail = detail
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?.let { value -> buildAutomationDebugPreview(value, maxChars = 120) }
    val normalizedPreview = preview
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?.let(::buildAutomationDebugPreview)

    return buildString {
        append(prefix)
        if (!normalizedDetail.isNullOrBlank()) {
            append(": ")
            append(normalizedDetail)
        }
        if (!normalizedPreview.isNullOrBlank()) {
            append(" | preview=")
            append(normalizedPreview)
        }
    }
}

private data class RouteSemanticFallback(
    val expectedOutcome: String? = null,
    val verificationSignals: List<String> = emptyList(),
    val locatorHint: String? = null,
    val pageSignature: String? = null,
    val note: String? = null
)

private fun parseRouteSemanticFallback(routeInfo: String?): RouteSemanticFallback {
    val segments = routeInfo.orEmpty()
        .split(" | ")
        .map { segment -> segment.trim() }
        .filter { segment -> segment.isNotBlank() }
    val routeWhyValues = segments.mapNotNull { segment ->
        segment.removePrefix("route_why=").takeIf { value -> value != segment && value.isNotBlank() }
    }
    val routeGuidance = segments.firstNotNullOfOrNull { segment ->
        segment.removePrefix("route_guidance=").takeIf { value -> value != segment && value.isNotBlank() }
    }
    val routeCaution = segments.firstNotNullOfOrNull { segment ->
        segment.removePrefix("route_caution=").takeIf { value -> value != segment && value.isNotBlank() }
    }
    val matchedVerification = routeWhyValues.firstNotNullOfOrNull { value ->
        value.removePrefix("matched_verification=").takeIf { parsed -> parsed != value && parsed.isNotBlank() }
    }
    val matchedAnchor = routeWhyValues.firstNotNullOfOrNull { value ->
        value.removePrefix("matched_anchor=").takeIf { parsed -> parsed != value && parsed.isNotBlank() }
    }
    return RouteSemanticFallback(
        expectedOutcome = matchedVerification,
        verificationSignals = listOfNotNull(matchedVerification),
        locatorHint = matchedAnchor?.substringBefore('@')?.takeIf { value -> value.isNotBlank() },
        pageSignature = matchedAnchor
            ?.substringAfter('@', "")
            ?.takeIf { value -> value.isNotBlank() },
        note = routeGuidance ?: routeCaution
    )
}

private fun buildAutomationProcessGuidance(
    runtimeState: ExecutionRuntimeState,
    boundaryPacket: TaskExecutionBoundaryPacket?
): String? {
    val routeInfo = runtimeState.executionResult.routeInfo
    val processScope = extractRouteInfoValue(routeInfo, "route_scope")
        ?: runtimeState.executionResult.selectedProcessId?.takeIf { value -> value.isNotBlank() }
        ?: boundaryPacket?.processId?.takeIf { value -> value.isNotBlank() }
        ?: runtimeState.processId?.takeIf { value -> value.isNotBlank() }
    val routeGuidance = extractRouteInfoValue(routeInfo, "route_guidance")
    val routeCaution = extractRouteInfoValue(routeInfo, "route_caution")
    return buildList {
        processScope?.let { scope -> add("process_scope=$scope") }
        routeGuidance?.let { guidance -> add("guidance=$guidance") }
        routeCaution?.let { caution -> add("caution=$caution") }
    }.distinct().takeIf { values -> values.isNotEmpty() }?.joinToString("\n")
}

private fun extractRouteInfoValue(routeInfo: String?, key: String): String? {
    return routeInfo.orEmpty()
        .split(" | ")
        .map { segment -> segment.trim() }
        .firstNotNullOfOrNull { segment ->
            segment.removePrefix("$key=").takeIf { value -> value != segment && value.isNotBlank() }
        }
}

private data class ConstraintDowngrade(
    val actionText: String,
    val moreSpecificConstraint: String
)

private fun detectConstraintDowngrade(
    response: AutomationAgentResponse,
    boundaryPacket: TaskExecutionBoundaryPacket?
): ConstraintDowngrade? {
    if (response.flowState != AutomationFlowState.IN_PROGRESS) {
        return null
    }
    val action = response.action ?: return null
    if (action.type != "inputText") {
        return null
    }
    val actionText = action.text?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    val activeConstraints = collectActiveConstraintExpressions(boundaryPacket)
    return activeConstraints.firstOrNull { constraint ->
        isBroaderProjectionOfConstraint(
            actionText = actionText,
            constraint = constraint
        ) && !responseExplicitlyCarriesPendingConstraint(response, constraint)
    }?.let { constraint ->
        ConstraintDowngrade(
            actionText = actionText,
            moreSpecificConstraint = constraint
        )
    }
}

private fun collectActiveConstraintExpressions(boundaryPacket: TaskExecutionBoundaryPacket?): List<String> {
    if (boundaryPacket == null) {
        return emptyList()
    }
    return buildList {
        add(boundaryPacket.targetLabel.orEmpty())
        add(boundaryPacket.targetKey)
        boundaryPacket.resolvedSlots.values.forEach(::add)
        boundaryPacket.verificationChecks.forEach { check ->
            check.expectedValue?.let(::add)
        }
    }.mapNotNull { value ->
        value.trim().takeIf { trimmed -> trimmed.isNotBlank() }
    }.distinct()
}

private fun isBroaderProjectionOfConstraint(
    actionText: String,
    constraint: String
): Boolean {
    val normalizedAction = normalizeConstraintText(actionText)
    val normalizedConstraint = normalizeConstraintText(constraint)
    if (normalizedAction.length < 2 || normalizedAction == normalizedConstraint) {
        return false
    }
    if (normalizedConstraint.contains(normalizedAction)) {
        return normalizedConstraint.length - normalizedAction.length >= 5
    }
    if (normalizedAction.length >= normalizedConstraint.length) {
        return false
    }
    val constraintTokens = extractConstraintCoverageTokens(normalizedConstraint)
    if (constraintTokens.size < 2) {
        return false
    }
    val carriedTokens = constraintTokens.filter { token -> normalizedAction.contains(token) }
    if (carriedTokens.isEmpty()) {
        return false
    }
    val omittedTokens = constraintTokens.filterNot { token -> normalizedAction.contains(token) }
    if (omittedTokens.isEmpty()) {
        return false
    }
    return carriedTokens.any(::isDistinctiveConstraintToken) && omittedTokens.any(::isDistinctiveConstraintToken)
}

private fun normalizeConstraintText(value: String): String {
    return value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
}

private val latinNumberConstraintTokenPattern = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
private val cjkConstraintRunPattern = Regex("[\\u4E00-\\u9FFF]+")

private fun extractConstraintCoverageTokens(value: String): Set<String> {
    return linkedSetOf<String>().apply {
        latinNumberConstraintTokenPattern.findAll(value).forEach { match ->
            val token = match.value.trim('.', '_', '-')
            if (isDistinctiveConstraintToken(token)) {
                add(token)
            }
            token.split('.', '_', '-').forEach { part ->
                if (isDistinctiveConstraintToken(part)) {
                    add(part)
                }
            }
        }
        cjkConstraintRunPattern.findAll(value).forEach { match ->
            val run = match.value
            if (run.length <= 2) {
                if (isDistinctiveConstraintToken(run)) {
                    add(run)
                }
            } else {
                add(run)
                for (index in 0 until run.length - 1) {
                    add(run.substring(index, index + 2))
                }
            }
        }
    }
}

private fun isDistinctiveConstraintToken(token: String): Boolean {
    return token.length >= 2 || token.any { char -> char.isDigit() }
}

private fun responseExplicitlyCarriesPendingConstraint(
    response: AutomationAgentResponse,
    constraint: String
): Boolean {
    val signals = buildList {
        response.thought.trim().takeIf { value -> value.isNotBlank() }?.let(::add)
        response.message.trim().takeIf { value -> value.isNotBlank() }?.let(::add)
        response.semanticContext?.goal?.trim()?.takeIf { value -> value.isNotBlank() }?.let(::add)
        response.semanticContext?.expectedOutcome
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?.let(::add)
        addAll(
            response.semanticContext?.verificationSignals.orEmpty().mapNotNull { signal ->
                signal.trim().takeIf { value -> value.isNotBlank() }
            }
        )
    }
    if (signals.isEmpty()) {
        return false
    }
    val joined = signals.joinToString("\n")
    val normalizedJoined = joined.lowercase(Locale.US)
    val hasPendingCue = listOf(
        "pending",
        "later",
        "filter",
        "verify",
        "verification",
        "preserve",
        "constraint"
    ).any { cue -> normalizedJoined.contains(cue) } || listOf(
        "待办",
        "后续",
        "稍后",
        "筛选",
        "过滤",
        "验证",
        "保留",
        "约束"
    ).any { cue -> joined.contains(cue) }
    return hasPendingCue && joined.contains(constraint)
}