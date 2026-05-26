package com.atombits.pocopaw

import android.content.Context
import android.widget.Toast
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PreferenceDiscoveryManualScanRequest(
    val target: PreferenceDiscoveryAppTarget,
    val pageCount: Int,
    val countdownSeconds: Int = 30
)

data class PreferenceDiscoveryManualScanOutcome(
    val updatedStore: PrototypeStoreData,
    val capturedPageCount: Int,
    val applied: Boolean,
    val message: String
)

data class PreferenceDiscoveryVisionRequest(
    val target: PreferenceDiscoveryAppTarget,
    val screenshots: List<String>
)

data class PreferenceDiscoveryVisionResult(
    val observations: List<PreferenceDiscoveryStructuredObservation>,
    val summary: String
)

data class PreferenceDiscoveryVisionResolution(
    val result: PreferenceDiscoveryVisionResult? = null,
    val failureReason: String? = null
) {
    companion object {
        fun success(result: PreferenceDiscoveryVisionResult): PreferenceDiscoveryVisionResolution {
            return PreferenceDiscoveryVisionResolution(result = result)
        }

        fun failure(reason: String): PreferenceDiscoveryVisionResolution {
            return PreferenceDiscoveryVisionResolution(failureReason = reason)
        }
    }
}

interface PreferenceDiscoveryVisionResolver {
    suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution
}

internal class VisionPreferenceDiscoveryResolver(
    private val apiKey: String = ProviderRuntimeConfigs.vision.apiKey,
    private val model: String? = null,
    private val endpoint: String = ProviderRuntimeConfigs.vision.endpoint,
    private val httpClient: OkHttpClient = createPreferenceDiscoveryHttpClient(),
    private val provider: VisionProviderKind = ProviderRuntimeConfigs.visionProviderKind()
) : PreferenceDiscoveryVisionResolver {
    companion object {
        internal const val PREFERENCE_DISCOVERY_CALL_TIMEOUT_SECONDS = 180L
        internal const val PREFERENCE_DISCOVERY_READ_TIMEOUT_SECONDS = 120L

        internal fun createPreferenceDiscoveryHttpClient(): OkHttpClient {
            return VisionAutomationAgentClient.createAutomationHttpClient()
                .newBuilder()
                .callTimeout(PREFERENCE_DISCOVERY_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PREFERENCE_DISCOVERY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
        runCatching {
            DemoReleaseControl.ensureBackendAccessAllowed()
        }.onFailure {
            return PreferenceDiscoveryVisionResolution.failure("DEMO_QUOTA_EXCEEDED")
        }
        if (apiKey.isBlank()) {
            return PreferenceDiscoveryVisionResolution.failure("api key not configured")
        }
        if (request.screenshots.isEmpty()) {
            return PreferenceDiscoveryVisionResolution.failure("no screenshots supplied")
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val selectedModel = model ?: ProviderRuntimeConfigs.vision.model
                val requestBody = JSONObject().apply {
                    put("model", selectedModel)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", buildSystemPrompt(request.target))
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "content",
                                        JSONArray().apply {
                                            put(
                                                JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", buildUserPrompt(request))
                                                }
                                            )
                                            request.screenshots.forEach { imageDataUrl ->
                                                put(
                                                    JSONObject().apply {
                                                        put("type", "image_url")
                                                        put(
                                                            "image_url",
                                                            JSONObject().apply {
                                                                put("url", imageDataUrl)
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }.applyVisionRequestTuning(provider)

                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext PreferenceDiscoveryVisionResolution.failure(
                            buildHttpFailureReason(response.code, body)
                        )
                    }
                    if (body.isBlank()) {
                        return@withContext PreferenceDiscoveryVisionResolution.failure("response body empty")
                    }
                    val responseRoot = runCatching { JSONObject(body) }.getOrElse {
                        return@withContext PreferenceDiscoveryVisionResolution.failure("response body is not valid json")
                    }
                    val content = responseRoot
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    if (content.isBlank()) {
                        return@withContext PreferenceDiscoveryVisionResolution.failure("message.content empty")
                    }
                    val root = runCatching { JSONObject(content) }.getOrElse {
                        return@withContext PreferenceDiscoveryVisionResolution.failure("message.content is not valid json")
                    }
                    val observations = runCatching {
                        parsePreferenceDiscoveryObservations(content)
                    }.getOrElse { throwable ->
                        return@withContext PreferenceDiscoveryVisionResolution.failure(
                            buildThrowableFailureReason("observations parse failed", throwable)
                        )
                    }
                    PreferenceDiscoveryVisionResolution.success(
                        PreferenceDiscoveryVisionResult(
                            observations = observations,
                            summary = root.optString("summary").trim()
                        )
                    )
                }
            }.getOrElse { throwable ->
                PreferenceDiscoveryVisionResolution.failure(
                    buildThrowableFailureReason("request failed", throwable)
                )
            }
        }
    }

    private fun buildHttpFailureReason(statusCode: Int, body: String): String {
        val bodyMessage = runCatching {
            JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
        }.getOrDefault("")

        val diagnostic = sanitizeDiagnostic(bodyMessage.ifBlank { body })
        return if (diagnostic.isBlank()) {
            "http $statusCode"
        } else {
            "http $statusCode: $diagnostic"
        }
    }

    private fun buildThrowableFailureReason(prefix: String, throwable: Throwable): String {
        val throwableType = throwable::class.java.simpleName.ifBlank { "Exception" }
        val throwableMessage = sanitizeDiagnostic(throwable.message.orEmpty())
        return if (throwableMessage.isBlank()) {
            "$prefix: $throwableType"
        } else {
            "$prefix: $throwableType: $throwableMessage"
        }
    }

    private fun sanitizeDiagnostic(rawValue: String, maxLength: Int = 160): String {
        val normalized = rawValue.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength - 3).trimEnd() + "..."
        }
    }

    private fun buildSystemPrompt(target: PreferenceDiscoveryAppTarget): String {
        val supportedSlotKeys = PreferenceDiscoveryCatalog.supportedSlotKeys(target.domain)
        return buildString {
            appendLine("You are a mobile preference discovery extraction model.")
            appendLine("You receive screenshots from ${target.displayName} order/history pages.")
            appendLine("Extract only stable user preference evidence that is directly supported by visible history items.")
            appendLine("Do not infer identity, exact addresses, phone numbers, account ids, or payment credentials.")
            appendLine("Use only these slot keys: ${supportedSlotKeys.joinToString(", ")}.")
            appendLine("If evidence is weak, omit it.")
            appendLine("Return JSON only in this contract:")
            appendLine("{\"summary\":\"string\",\"observations\":[{\"anchor_object\":\"string|null\",\"slot_key\":\"string\",\"slot_value\":\"string\",\"polarity\":\"PREFER|AVOID\",\"confidence\":0.0,\"freshness_hint\":\"RECENT|LONG_TERM\"}]}")
        }.trim()
    }

    private fun buildUserPrompt(request: PreferenceDiscoveryVisionRequest): String {
        return buildString {
            appendLine("Domain: ${request.target.domain.wireName}")
            appendLine("App: ${request.target.displayName}")
            appendLine("Page type: ${request.target.pageType}")
            append("Attached screenshots: ${request.screenshots.size}")
        }.trim()
    }
}

class PreferenceDiscoveryManualScanRunner(
    private val appContext: Context,
    private val screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
    private val visionResolver: PreferenceDiscoveryVisionResolver = VisionPreferenceDiscoveryResolver(),
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appLauncher: suspend (Context, String) -> Boolean = { context, packageName ->
        withContext(Dispatchers.Main) {
            launchAppViaPackageManager(context, packageName)
        }
    },
    private val countdownNotifier: suspend (Context, String) -> Unit = { context, message ->
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    },
    private val accessibilityReady: () -> Boolean = {
        PrototypeAccessibilityService.instance != null
    },
    private val pageSwiper: suspend () -> Boolean = {
        withContext(Dispatchers.Main) {
            PrototypeAccessibilityService.instance?.swipe(
                fromX = 0.5f,
                fromY = 0.82f,
                toX = 0.5f,
                toY = 0.22f,
                durationMs = 420L
            ) == true
        }
    },
    private val pause: suspend (Long) -> Unit = { durationMs ->
        delay(durationMs)
    },
    private val nowProvider: () -> Long = {
        System.currentTimeMillis()
    },
    private val stringResolver: (Int, Array<out Any?>) -> String = { resId, formatArgs ->
        if (formatArgs.isEmpty()) {
            appContext.getString(resId)
        } else {
            appContext.getString(resId, *formatArgs)
        }
    }
) {
    suspend fun run(
        store: PrototypeStoreData,
        request: PreferenceDiscoveryManualScanRequest
    ): PreferenceDiscoveryManualScanOutcome {
        if (!screenCaptureCoordinator.hasPermission()) {
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = store,
                capturedPageCount = 0,
                applied = false,
                message = resolveString(R.string.screen_capture_permission_required)
            )
        }
        if (!accessibilityReady()) {
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = store,
                capturedPageCount = 0,
                applied = false,
                message = resolveString(R.string.preference_discovery_accessibility_required)
            )
        }

        val launched = appLauncher(appContext, request.target.packageName)
        if (!launched) {
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = store,
                capturedPageCount = 0,
                applied = false,
                message = resolveString(
                    R.string.preference_discovery_launch_failed,
                    request.target.displayName
                )
            )
        }

        countdownNotifier(
            appContext,
            resolveString(
                R.string.preference_discovery_countdown_toast,
                request.target.displayName,
                request.countdownSeconds
            )
        )
        pause(1_500L)
        pause(request.countdownSeconds.coerceAtLeast(0) * 1_000L)

        val screenshots = withContext(captureDispatcher) {
            captureScreenshots(request)
        }
        relaunchAgentApp()
        if (screenshots.isEmpty()) {
            val outcomeMessage = resolveString(R.string.preference_discovery_capture_failed)
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = markPreferenceDiscoveryAttempt(
                    store = store,
                    sourceApp = request.target.packageName,
                    outcomeMessage = outcomeMessage
                ),
                capturedPageCount = 0,
                applied = false,
                message = outcomeMessage
            )
        }

        val extractionResolution = visionResolver.resolve(
            PreferenceDiscoveryVisionRequest(
                target = request.target,
                screenshots = screenshots
            )
        )
        val extractionResult = extractionResolution.result ?: run {
            val outcomeMessage = buildVisionFailureMessage(extractionResolution.failureReason)
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = markPreferenceDiscoveryAttempt(
                    store = store,
                    sourceApp = request.target.packageName,
                    outcomeMessage = outcomeMessage
                ),
                capturedPageCount = screenshots.size,
                applied = false,
                message = outcomeMessage
            )
        }

        val payload = buildPreferenceDiscoveryPayload(
            target = request.target,
            observations = extractionResult.observations,
            capturedAt = nowProvider()
        ) ?: run {
            val outcomeMessage = resolveString(R.string.preference_discovery_no_evidence)
            return PreferenceDiscoveryManualScanOutcome(
                updatedStore = markPreferenceDiscoveryAttempt(
                    store = store,
                    sourceApp = request.target.packageName,
                    outcomeMessage = outcomeMessage
                ),
                capturedPageCount = screenshots.size,
                applied = false,
                message = outcomeMessage
            )
        }

        val writebackOutcome = applyPreferenceDiscoveryScan(store, payload, nowProvider())
        val outcomeMessage = if (writebackOutcome.applied) {
            resolveString(R.string.preference_discovery_success, screenshots.size)
        } else {
            resolveString(R.string.preference_discovery_no_change)
        }
        val updatedStore = markPreferenceDiscoveryAttempt(
            store = writebackOutcome.updatedStore,
            sourceApp = request.target.packageName,
            outcomeMessage = outcomeMessage
        )
        return PreferenceDiscoveryManualScanOutcome(
            updatedStore = updatedStore,
            capturedPageCount = screenshots.size,
            applied = writebackOutcome.applied,
            message = outcomeMessage
        )
    }

    private suspend fun relaunchAgentApp() {
        val agentPackageName = appContext.packageName.takeIf { packageName ->
            packageName.isNotBlank()
        } ?: return
        appLauncher(appContext, agentPackageName)
    }

    private suspend fun captureScreenshots(request: PreferenceDiscoveryManualScanRequest): List<String> {
        val screenshots = mutableListOf<String>()
        val captureCount = request.pageCount.coerceAtLeast(1)
        repeat(captureCount) { index ->
            screenCaptureCoordinator.captureSnapshot(timeoutMs = 5_000L)?.imageDataUrl?.takeIf { imageDataUrl ->
                imageDataUrl.isNotBlank()
            }?.let(screenshots::add)

            if (index >= captureCount - 1) {
                return@repeat
            }

            val scrolled = pageSwiper()
            if (!scrolled) {
                return screenshots
            }
            pause(1_400L)
        }
        return screenshots
    }

    private fun markPreferenceDiscoveryAttempt(
        store: PrototypeStoreData,
        sourceApp: String,
        outcomeMessage: String
    ): PrototypeStoreData {
        val memoryState = (store.memoryState ?: MemoryState())
        val attemptedSourceApps = (listOf(sourceApp) + memoryState.preferenceDiscoveryRuntime.attemptedSourceApps)
            .map { packageName -> packageName.trim() }
            .filter { packageName -> packageName.isNotBlank() }
            .distinct()
        val updatedMemoryState = memoryState.copy(
            preferenceDiscoveryRuntime = memoryState.preferenceDiscoveryRuntime.copy(
                lastConsumedAt = nowProvider(),
                attemptedSourceApps = attemptedSourceApps,
                lastOutcomeMessage = outcomeMessage
            )
        )
        return store.withUpdatedMemoryState(updatedMemoryState)
    }

    private fun buildVisionFailureMessage(failureReason: String?): String {
        val diagnostic = failureReason?.trim().orEmpty()
        return if (diagnostic.isBlank()) {
            resolveString(R.string.preference_discovery_vision_unavailable)
        } else {
            resolveString(R.string.preference_discovery_vision_unavailable_detail, diagnostic)
        }
    }

    private fun resolveString(resId: Int, vararg formatArgs: Any?): String {
        return stringResolver(resId, formatArgs)
    }
}

fun buildPreferenceDiscoveryStatusSummary(
    context: Context,
    memoryState: MemoryState,
    installedTargetCount: Int
): String {
    val filteredAppCount = countFilteredPreferenceDiscoveryApps(memoryState)
    val pendingAppCount = (installedTargetCount - filteredAppCount).coerceAtLeast(0)
    val discoveredPreferenceCount = countDiscoveredPreferenceSummaries(memoryState)
    val lastOutcomeMessage = memoryState.preferenceDiscoveryRuntime.lastOutcomeMessage
        ?.takeIf { message -> message.isNotBlank() }
    val updatedAtText = memoryState.preferenceDiscoveryRuntime.lastConsumedAt?.let { timestamp ->
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    } ?: context.getString(R.string.settings_not_updated_yet)

    return buildString {
        append(context.getString(R.string.preference_discovery_pending_apps_summary, pendingAppCount))
        appendLine()
        append(context.getString(R.string.preference_discovery_filtered_apps_summary, filteredAppCount))
        appendLine()
        append(context.getString(R.string.preference_discovery_total_preferences_summary, discoveredPreferenceCount))
        appendLine()
        append(context.getString(R.string.settings_last_updated_summary, updatedAtText))
        lastOutcomeMessage?.let { message ->
            appendLine()
            append(context.getString(R.string.preference_discovery_last_result_summary, message))
        }
        buildPreferenceDebugSummaryLines(memoryState, projectionSourceType = "APP_SCAN").forEach { line ->
            appendLine()
            append(line)
        }
        appendLine()
        append(context.getString(R.string.preference_discovery_list_title))
        appendLine()
        append(buildPreferenceDiscoveryListSummary(context, memoryState))
    }
}

internal fun buildPreferenceDebugSummaryLines(
    memoryState: MemoryState,
    projectionSourceType: String
): List<String> {
    return buildList {
        memoryState.preferenceDebugStore.latestProjectionSnapshot(projectionSourceType)?.let { snapshot ->
            add("last_projection=${snapshot.summaryLine()}")
        }
        memoryState.preferenceDebugStore.lastRecallDebugSnapshot?.let { snapshot ->
            add("last_recall=${snapshot.summaryLine()}")
        }
        memoryState.preferenceDebugStore.lastMappingTrace?.let { trace ->
            add("last_mapping=${trace.summaryLine()}")
        }
    }
}

fun countPreferenceDiscoveryEvidence(memoryState: MemoryState): Int {
    return memoryState.structuredPreferenceMemory.facts.count { fact ->
        fact.sourceType == "APP_SCAN"
    } + memoryState.interactionBiasMemory.allRecords().count { record ->
        record.sourceType == "APP_SCAN"
    }
}

fun countFilteredPreferenceDiscoveryApps(memoryState: MemoryState): Int {
    return collectFilteredPreferenceDiscoveryApps(memoryState).size
}

fun collectFilteredPreferenceDiscoveryApps(memoryState: MemoryState): Set<String> {
    val successfulSourceApps = (
        memoryState.structuredPreferenceMemory.facts.asSequence()
            .filter { fact -> fact.sourceType == "APP_SCAN" }
            .mapNotNull { fact ->
                fact.sourceApp?.trim()?.takeIf { sourceApp ->
                    sourceApp.isNotBlank() && sourceApp.contains('.')
                }
            } + memoryState.interactionBiasMemory.allRecords().asSequence()
            .filter { record -> record.sourceType == "APP_SCAN" }
            .mapNotNull { record ->
                record.sourceApp?.trim()?.takeIf { sourceApp ->
                    sourceApp.isNotBlank() && sourceApp.contains('.')
                }
            }
        )
        .toSet()
    val attemptedSourceApps = memoryState.preferenceDiscoveryRuntime.attemptedSourceApps
        .asSequence()
        .mapNotNull { sourceApp ->
            sourceApp.trim().takeIf { packageName ->
                packageName.isNotBlank() && packageName.contains('.')
            }
        }
        .toSet()
    return successfulSourceApps + attemptedSourceApps
}

fun countPreferenceExtractionEvidence(memoryState: MemoryState): Int {
    return memoryState.structuredPreferenceMemory.facts.count { fact ->
        fact.sourceType == "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION"
    } + memoryState.interactionBiasMemory.allRecords().count { record ->
        record.sourceType == "OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION"
    }
}

fun countDiscoveredPreferenceSummaries(memoryState: MemoryState): Int {
    return countPreferenceDiscoveryEvidence(memoryState)
}

fun buildPreferenceDiscoveryListSummary(context: Context, memoryState: MemoryState): String {
    val evidenceLines = (
        memoryState.structuredPreferenceMemory.facts
            .filter { fact -> fact.sourceType == "APP_SCAN" }
            .map { fact ->
                TimestampedPreferenceDiscoveryLine(
                    timestamp = fact.lastObservedAt,
                    content = "- ${fact.anchorObject.ifBlank { fact.domain }}: ${fact.facetKey}=${fact.facetValue}"
                )
            } + memoryState.interactionBiasMemory.allRecords()
            .filter { record -> record.sourceType == "APP_SCAN" }
            .map { record ->
                TimestampedPreferenceDiscoveryLine(
                    timestamp = record.lastObservedAt,
                    content = "- ${record.anchorObject.ifBlank { record.domain }}: ${record.signalKey}=${record.signalValue}"
                )
            }
        )
        .sortedByDescending { line -> line.timestamp }
        .take(6)
        .map { line -> line.content }
        .toList()

    return if (evidenceLines.isNotEmpty()) {
        evidenceLines.joinToString(separator = "\n")
    } else {
        context.getString(R.string.settings_empty_list)
    }
}

private data class TimestampedPreferenceDiscoveryLine(
    val timestamp: Long,
    val content: String
)