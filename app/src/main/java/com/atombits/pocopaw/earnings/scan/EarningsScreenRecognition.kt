package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.DemoReleaseControl
import com.atombits.pocopaw.ProviderRuntimeConfigs
import com.atombits.pocopaw.VisionAutomationAgentClient
import com.atombits.pocopaw.VisionProviderKind
import com.atombits.pocopaw.applyVisionRequestTuning
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionRequest
import com.atombits.pocopaw.earnings.EarningsScreenRecognitionResult
import com.atombits.pocopaw.earnings.EarningsTimeWindow
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.RawScanItem
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.extractStructuredPromptPayloadText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface VisionEarningsScreenRecognizer {
    suspend fun recognize(request: EarningsScreenRecognitionRequest): EarningsScreenRecognitionResult
}

internal class DefaultVisionEarningsScreenRecognizer(
    private val apiKey: String = ProviderRuntimeConfigs.vision.apiKey,
    private val model: String? = null,
    private val endpoint: String = ProviderRuntimeConfigs.vision.endpoint,
    private val provider: VisionProviderKind = ProviderRuntimeConfigs.visionProviderKind(),
    private val httpClient: OkHttpClient = VisionAutomationAgentClient.createAutomationHttpClient()
) : VisionEarningsScreenRecognizer {
    override suspend fun recognize(request: EarningsScreenRecognitionRequest): EarningsScreenRecognitionResult {
        runCatching {
            DemoReleaseControl.ensureBackendAccessAllowed()
        }.onFailure {
            return EarningsScreenRecognitionResult(appId = request.appId, failureReason = "DEMO_QUOTA_EXCEEDED")
        }
        if (apiKey.isBlank()) {
            return EarningsScreenRecognitionResult(appId = request.appId, failureReason = "vision api key not configured")
        }
        if (request.screenText.isBlank() && request.screenshotDataUrl.isNullOrBlank()) {
            return EarningsScreenRecognitionResult(appId = request.appId, failureReason = "screen evidence empty")
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
                                    put("content", buildSystemPrompt(request.appId))
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
                                            request.screenshotDataUrl?.takeIf { value -> value.isNotBlank() }?.let { imageDataUrl ->
                                                put(
                                                    JSONObject().apply {
                                                        put("type", "image_url")
                                                        put(
                                                            "image_url",
                                                            JSONObject().apply { put("url", imageDataUrl) }
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
                        return@withContext EarningsScreenRecognitionResult(
                            appId = request.appId,
                            failureReason = buildHttpFailureReason(response.code, body)
                        )
                    }
                    if (body.isBlank()) {
                        return@withContext EarningsScreenRecognitionResult(appId = request.appId, failureReason = "vision response body empty")
                    }
                    val content = runCatching { extractStructuredPromptPayloadText(body) }.getOrElse { throwable ->
                        return@withContext EarningsScreenRecognitionResult(
                            appId = request.appId,
                            failureReason = "vision response parse failed: ${throwable.message ?: throwable::class.java.simpleName}"
                        )
                    }
                    EarningsScreenRecognitionJsonParser.parse(request.appId, content, request.capturedAt)
                }
            }.getOrElse { throwable ->
                EarningsScreenRecognitionResult(
                    appId = request.appId,
                    failureReason = "vision request failed: ${throwable.message ?: throwable::class.java.simpleName}"
                )
            }
        }
    }

    private fun buildSystemPrompt(appId: EntertainmentAppId): String {
        return buildString {
            appendLine("You identify concrete reward-earning tasks in exactly one target mobile app screen.")
            appendLine("Target app: ${appId.displayName} (${appId.stableKey}).")
            appendLine("Use only visible screenshot/accessibility evidence. Do not invent tasks, rewards, windows, or completion state.")
            appendLine("Reject generic entry cards, balances, paid tasks, finance, games, creator/live tasks, and cross-app redirect tasks.")
            appendLine("Category rule: ad watching, video swiping/watching, treasure chests, countdowns, interval claims, cooldown-repeat tasks, and wording such as every 20 minutes must be FILLER_REPEATABLE_DECAY.")
            appendLine("Category rule: DAILY_WINDOWED_REPEAT is only for stable named daily windows such as breakfast, lunch, dinner, morning, afternoon, evening, or explicit clock ranges; do not use it for cooldown/interval repeat tasks.")
            appendLine("If a repeatable filler task card shows a countdown such as 18:36, 11m 01s, or 11分01秒后领, keep the exact countdown text in scheduleText or evidenceText for that same task card; do not report the countdown itself as a clickable action.")
            appendLine("screenContext may describe the page or section, but visibleTitle, evidenceText, rewardText, scheduleText, and actionText must describe the concrete task item only.")
            appendLine("Return JSON only: {\"summary\":\"string\",\"screenSignature\":\"string\",\"items\":[{\"visibleTitle\":\"string\",\"rewardText\":\"string|null\",\"scheduleText\":\"string|null\",\"actionText\":\"string|null\",\"screenContext\":\"string|null\",\"confidence\":0.0,\"evidenceText\":\"string\",\"taskKey\":\"${appId.stableKey}:category:stable_name\",\"category\":\"ONE_TIME|STREAK_MULTI_DAY|DAILY_ONCE|DAILY_WINDOWED_REPEAT|FILLER_REPEATABLE_DECAY\",\"scheduleLabel\":\"string|null\",\"cooldownMinutes\":0,\"windows\":[{\"label\":\"string\",\"startMinuteOfDay\":0,\"endMinuteOfDay\":0}],\"semanticMatchReason\":\"string\"}]}.")
        }.trim()
    }

    private fun buildUserPrompt(request: EarningsScreenRecognitionRequest): String {
        return buildString {
            appendLine("App: ${request.appId.displayName}")
            appendLine("App scope: ${request.appId.stableKey}")
            appendLine("Expected package: ${request.appId.packageName}")
            appendLine("Accessibility package: ${request.accessibilityPackageName.orEmpty()}")
            appendLine("Screen signature: ${request.sourceScreenSignature.orEmpty()}")
            appendLine("Captured at: ${request.capturedAt}")
            appendLine("Accessibility text:")
            appendLine(request.screenText.take(8000))
        }.trim()
    }

    private fun buildHttpFailureReason(statusCode: Int, body: String): String {
        val bodyMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val normalized = bodyMessage.ifBlank { body }.replace(Regex("\\s+"), " ").trim()
        return if (normalized.isBlank()) {
            "vision http $statusCode"
        } else {
            "vision http $statusCode: ${normalized.take(160)}"
        }
    }
}

object EarningsScreenRecognitionJsonParser {
    fun parse(appId: EntertainmentAppId, content: String, capturedAt: Long): EarningsScreenRecognitionResult {
        val root = runCatching { JSONObject(content) }.getOrElse { throwable ->
            return EarningsScreenRecognitionResult(appId = appId, failureReason = throwable.message ?: "invalid json")
        }
        val items = root.optJSONArray("items") ?: root.optJSONArray("tasks")
        val rawItems = buildList {
            if (items == null) {
                return@buildList
            }
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("visibleTitle", item.optString("visible_title")).trim()
                if (title.isBlank()) {
                    continue
                }
                add(
                    RawScanItem(
                        appId = appId,
                        visibleTitle = title,
                        rewardText = item.optNullableString("rewardText") ?: item.optNullableString("reward_text"),
                        scheduleText = item.optNullableString("scheduleText") ?: item.optNullableString("schedule_text"),
                        actionText = item.optNullableString("actionText") ?: item.optNullableString("action_text"),
                        screenContext = item.optNullableString("screenContext") ?: item.optNullableString("screen_context"),
                        evidenceText = item.optNullableString("evidenceText") ?: item.optNullableString("evidence_text"),
                        modelTaskKey = item.optNullableString("taskKey") ?: item.optNullableString("task_key"),
                        modelCategory = TaskCategory.fromRaw(item.optNullableString("category")),
                        scheduleLabel = item.optNullableString("scheduleLabel") ?: item.optNullableString("schedule_label"),
                        cooldownMinutes = item.optNullableInt("cooldownMinutes") ?: item.optNullableInt("cooldown_minutes"),
                        windows = parseWindows(item),
                        semanticMatchReason = item.optNullableString("semanticMatchReason") ?: item.optNullableString("semantic_match_reason"),
                        confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                        sourceScreenSignature = root.optNullableString("screenSignature") ?: root.optNullableString("screen_signature"),
                        capturedAt = capturedAt
                    )
                )
            }
        }
        return EarningsScreenRecognitionResult(
            appId = appId,
            rawItems = rawItems,
            summary = root.optNullableString("summary")
        )
    }

    private fun parseWindows(item: JSONObject): List<EarningsTimeWindow> {
        val windows = item.optJSONArray("windows") ?: return emptyList()
        return buildList {
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                val label = window.optString("label").trim()
                if (label.isNotBlank()) {
                    add(
                        EarningsTimeWindow(
                            label = label,
                            startMinuteOfDay = window.optNullableInt("startMinuteOfDay") ?: window.optNullableInt("start_minute_of_day"),
                            endMinuteOfDay = window.optNullableInt("endMinuteOfDay") ?: window.optNullableInt("end_minute_of_day")
                        )
                    )
                }
            }
        }
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name).trim().takeIf { value -> value.isNotBlank() }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return runCatching { getInt(name) }.getOrNull()
}
