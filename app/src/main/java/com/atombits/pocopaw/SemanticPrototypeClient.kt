package com.atombits.pocopaw

import com.atombits.pocopaw.intent.IntentGateway
import com.atombits.pocopaw.reply.ChatReplyGateway
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class SemanticPrototypeClient {

    internal data class PromptRequestConfig(
        val temperature: Double,
        val topP: Double,
        val maxTokens: Int,
        val requestTag: String
    )

    internal data class RawPromptExchange(
        val requestPayload: String,
        val responsePayload: String
    )

    internal data class StreamingTurnDelta(
        val assistantReply: String,
        val searchSummaryContent: String? = null,
        val reasoningContent: String? = null,
        val completed: Boolean = false
    )

    internal class StreamingResponseAccumulator {
        val contentBuffer: StringBuilder = StringBuilder()
        val reasoningBuffer: StringBuilder = StringBuilder()
        var tokenUsage: TokenUsage? = null
        var lastPublishedAssistantReply: String = ""
        var lastPublishedSearchSummaryContent: String = ""
        var lastPublishedReasoningContent: String = ""
    }

    private val defaultRuntimeConfig: ProviderRuntimeConfig
        get() = ProviderRuntimeConfigs.semanticRuntimeConfig(SemanticModelTier.FAST)

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val intentGateway by lazy(LazyThreadSafetyMode.NONE) { IntentGateway(this) }
    private val chatReplyGateway by lazy(LazyThreadSafetyMode.NONE) { ChatReplyGateway(this) }

    fun isConfigured(): Boolean = defaultRuntimeConfig.isConfigured()

    suspend fun planTurn(
        userMessage: String,
        store: PrototypeStoreData,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        searchPlanBundle: String? = null,
        searchBundle: String? = null,
        toolCapabilityBundle: ToolCapabilityBundle? = null
    ): SemanticTurnResponse = intentGateway.planTurn(
        userMessage = userMessage,
        store = store,
        turnOptions = turnOptions,
        searchPlanBundle = searchPlanBundle,
        searchBundle = searchBundle,
        toolCapabilityBundle = toolCapabilityBundle
    )

    suspend fun planSearchTurn(
        userMessage: String,
        store: PrototypeStoreData
    ): SearchPlanResponse = intentGateway.planSearchTurn(
        userMessage = userMessage,
        store = store
    )

    internal suspend fun streamPlanTurn(
        userMessage: String,
        store: PrototypeStoreData,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        searchPlanBundle: String? = null,
        searchBundle: String? = null,
        toolCapabilityBundle: ToolCapabilityBundle? = null,
        onDelta: (StreamingTurnDelta) -> Unit
    ): SemanticTurnResponse = intentGateway.streamPlanTurn(
        userMessage = userMessage,
        store = store,
        turnOptions = turnOptions,
        searchPlanBundle = searchPlanBundle,
        searchBundle = searchBundle,
        toolCapabilityBundle = toolCapabilityBundle,
        onDelta = onDelta
    )

    suspend fun buildExecutionAssistantReply(
        factsBundle: String,
        store: PrototypeStoreData,
        stage: ConversationStage = ConversationStage.ACCUMULATING
    ): AssistantReplyResult = chatReplyGateway.buildExecutionAssistantReply(
        factsBundle = factsBundle,
        store = store,
        stage = stage
    )

    internal fun resolveRequestConfig(promptPacket: PromptPacket): PromptRequestConfig {
        return when (promptPacket.packetType) {
            PromptPacketType.SEARCH_PLAN_QUERY -> PromptRequestConfig(
                temperature = 0.2,
                topP = 0.85,
                maxTokens = promptPacket.tokenBudget.requestMaxTokens,
                requestTag = "search_plan_query"
            )

            PromptPacketType.EXECUTION_CHAT_REPLY -> PromptRequestConfig(
                temperature = 0.2,
                topP = 0.85,
                maxTokens = promptPacket.tokenBudget.requestMaxTokens,
                requestTag = "execution_chat_reply"
            )

            PromptPacketType.PROCESS_CURATION_QUERY -> PromptRequestConfig(
                temperature = 0.2,
                topP = 0.8,
                maxTokens = promptPacket.tokenBudget.requestMaxTokens,
                requestTag = "structured_process_draft"
            )

            PromptPacketType.PROCESS_REFERENCE_SELECTION_QUERY -> PromptRequestConfig(
                temperature = 0.2,
                topP = 0.8,
                maxTokens = promptPacket.tokenBudget.requestMaxTokens,
                requestTag = "process_reference_selection"
            )

            else -> PromptRequestConfig(
                temperature = 0.2,
                topP = 0.85,
                maxTokens = promptPacket.tokenBudget.requestMaxTokens,
                requestTag = promptPacket.packetType.name.lowercase(Locale.US)
            )
        }
    }

    internal fun requestPromptPacket(
        promptPacket: PromptPacket,
        requestConfig: PromptRequestConfig = resolveRequestConfig(promptPacket),
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions()
    ): String {
        return requestPromptPacketExchange(promptPacket, requestConfig, runtimeConfig, turnOptions).responsePayload
    }

    internal fun requestPromptPacketExchange(
        promptPacket: PromptPacket,
        requestConfig: PromptRequestConfig = resolveRequestConfig(promptPacket),
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions()
    ): RawPromptExchange {
        return requestPromptMessagesExchange(promptPacket.promptMessages, requestConfig, runtimeConfig, turnOptions)
    }

    internal fun requestPromptPacketStreaming(
        promptPacket: PromptPacket,
        requestConfig: PromptRequestConfig = resolveRequestConfig(promptPacket),
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        onDelta: (StreamingTurnDelta) -> Unit
    ): String {
        return requestPromptPacketStreamingExchange(promptPacket, requestConfig, runtimeConfig, turnOptions, onDelta).responsePayload
    }

    internal fun requestPromptPacketStreamingExchange(
        promptPacket: PromptPacket,
        requestConfig: PromptRequestConfig = resolveRequestConfig(promptPacket),
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        onDelta: (StreamingTurnDelta) -> Unit
    ): RawPromptExchange {
        return requestPromptMessagesStreamingExchange(
            promptMessages = promptPacket.promptMessages,
            requestConfig = requestConfig,
            runtimeConfig = runtimeConfig,
            turnOptions = turnOptions,
            onDelta = onDelta
        )
    }

    internal fun requestPromptMessages(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions()
    ): String {
        return requestPromptMessagesExchange(promptMessages, requestConfig, runtimeConfig, turnOptions).responsePayload
    }

    internal fun requestPromptMessagesExchange(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions()
    ): RawPromptExchange {
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }

        val requestBody = buildRequestBodyJson(promptMessages, requestConfig, runtimeConfig, turnOptions)

        if (runtimeConfig.apiStyle == ProviderApiStyle.GEMINI_GENERATE_CONTENT) {
            return requestGeminiPromptMessagesExchange(requestBody, runtimeConfig)
        }

        val request = Request.Builder()
            .url(runtimeConfig.endpoint)
            .header("Authorization", "Bearer ${runtimeConfig.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Semantic provider request failed: ${response.code} ${body.take(240)}")
            }
            RawPromptExchange(
                requestPayload = requestBody.toString(),
                responsePayload = body
            )
        }
    }

    internal fun requestPromptMessagesStreaming(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        onDelta: (StreamingTurnDelta) -> Unit
    ): String {
        return requestPromptMessagesStreamingExchange(promptMessages, requestConfig, runtimeConfig, turnOptions, onDelta).responsePayload
    }

    internal fun requestPromptMessagesStreamingExchange(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        onDelta: (StreamingTurnDelta) -> Unit
    ): RawPromptExchange {
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }

        val requestBody = buildRequestBodyJson(
            promptMessages = promptMessages,
            requestConfig = requestConfig,
            runtimeConfig = runtimeConfig,
            turnOptions = turnOptions,
            stream = true,
            includeStreamUsage = true
        )

        if (runtimeConfig.apiStyle == ProviderApiStyle.GEMINI_GENERATE_CONTENT) {
            return requestGeminiPromptMessagesStreamingExchange(requestBody, runtimeConfig, onDelta)
        }

        val request = Request.Builder()
            .url(runtimeConfig.endpoint)
            .header("Authorization", "Bearer ${runtimeConfig.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Semantic provider streaming response body was empty")
            if (!response.isSuccessful) {
                val bodyText = body.string()
                throw IOException("Semantic provider request failed: ${response.code} ${bodyText.take(240)}")
            }

            val accumulator = StreamingResponseAccumulator()
            body.source().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    consumeStreamingDataLine(line, accumulator)?.let(onDelta)
                }
            }
            RawPromptExchange(
                requestPayload = requestBody.toString(),
                responsePayload = buildStreamingRawResponse(accumulator)
            )
        }
    }

    internal fun buildRequestBodyJson(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        runtimeConfig: ProviderRuntimeConfig = defaultRuntimeConfig,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        stream: Boolean = false,
        includeStreamUsage: Boolean = false
    ): JSONObject {
        if (runtimeConfig.apiStyle == ProviderApiStyle.GEMINI_GENERATE_CONTENT) {
            return buildGeminiRequestBodyJson(
                promptMessages = promptMessages,
                requestConfig = requestConfig,
                turnOptions = turnOptions,
                modelName = runtimeConfig.model
            )
        }

        val effectiveTurnOptions = coerceSemanticTurnOptionsForModel(
            modelName = runtimeConfig.model,
            requested = turnOptions
        )
        val semanticControlSupport = resolveSemanticModelControlSupport(runtimeConfig.model)

        return JSONObject().apply {
            put("model", runtimeConfig.model)
            put("messages", JSONArray().apply {
                promptMessages.forEach { message ->
                    put(
                        JSONObject().apply {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    )
                }
            })
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("temperature", requestConfig.temperature)
            put("top_p", requestConfig.topP)
            put("max_tokens", requestConfig.maxTokens)
            when (semanticControlSupport.thinkingBackend) {
                SemanticThinkingControlBackend.DEEPSEEK_DEFAULT_ON -> {
                    if (!effectiveTurnOptions.thinkingEnabled) {
                        put("thinking", JSONObject().apply {
                            put("type", "disabled")
                        })
                    }
                }
                SemanticThinkingControlBackend.QWEN_ENABLE_THINKING -> {
                    put("enable_thinking", effectiveTurnOptions.thinkingEnabled)
                }
                SemanticThinkingControlBackend.OPENAI_REASONING_EFFORT -> {
                    put("reasoning_effort", if (effectiveTurnOptions.thinkingEnabled) "high" else "none")
                }
                SemanticThinkingControlBackend.GEMINI_THINKING_BUDGET,
                SemanticThinkingControlBackend.NONE -> Unit
            }
            if (stream) {
                put("stream", true)
                if (includeStreamUsage) {
                    put("stream_options", JSONObject().apply { put("include_usage", true) })
                }
            }
        }
    }

    private fun requestGeminiPromptMessagesExchange(
        requestBody: JSONObject,
        runtimeConfig: ProviderRuntimeConfig
    ): RawPromptExchange {
        val request = Request.Builder()
            .url(buildGeminiRequestUrl(runtimeConfig, stream = false))
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Semantic provider request failed: ${response.code} ${body.take(240)}")
            }
            RawPromptExchange(
                requestPayload = requestBody.toString(),
                responsePayload = normalizeGeminiResponseBody(body)
            )
        }
    }

    private fun requestGeminiPromptMessagesStreamingExchange(
        requestBody: JSONObject,
        runtimeConfig: ProviderRuntimeConfig,
        onDelta: (StreamingTurnDelta) -> Unit
    ): RawPromptExchange {
        val request = Request.Builder()
            .url(buildGeminiRequestUrl(runtimeConfig, stream = true))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Semantic provider streaming response body was empty")
            if (!response.isSuccessful) {
                val bodyText = body.string()
                throw IOException("Semantic provider request failed: ${response.code} ${bodyText.take(240)}")
            }

            val accumulator = StreamingResponseAccumulator()
            body.source().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    consumeStreamingDataLine(line, accumulator, runtimeConfig.apiStyle)?.let(onDelta)
                }
            }
            RawPromptExchange(
                requestPayload = requestBody.toString(),
                responsePayload = buildStreamingRawResponse(accumulator)
            )
        }
    }

    internal fun buildGeminiRequestBodyJson(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig
    ): JSONObject {
        return buildGeminiRequestBodyJson(
            promptMessages = promptMessages,
            requestConfig = requestConfig,
            turnOptions = ChatTurnOptions(),
            modelName = ""
        )
    }

    internal fun buildGeminiRequestBodyJson(
        promptMessages: List<PromptMessage>,
        requestConfig: PromptRequestConfig,
        turnOptions: ChatTurnOptions,
        modelName: String
    ): JSONObject {
        val effectiveTurnOptions = coerceSemanticTurnOptionsForModel(
            modelName = modelName,
            requested = turnOptions
        )
        val systemInstruction = promptMessages
            .filter { message -> message.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { message -> message.content.trim() }
            .trim()

        val contents = JSONArray()
        promptMessages
            .filterNot { message -> message.role.equals("system", ignoreCase = true) }
            .forEach { message ->
                contents.put(
                    JSONObject().apply {
                        put("role", mapGeminiRole(message.role))
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("text", message.content)
                                }
                            )
                        )
                    }
                )
            }

        if (contents.length() == 0 && systemInstruction.isNotBlank()) {
            contents.put(
                JSONObject().apply {
                    put("role", "user")
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().apply {
                                put("text", systemInstruction)
                            }
                        )
                    )
                }
            )
        }

        return JSONObject().apply {
            put("contents", contents)
            if (systemInstruction.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("text", systemInstruction)
                                }
                            )
                        )
                    }
                )
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", requestConfig.temperature)
                    put("topP", requestConfig.topP)
                    put("maxOutputTokens", requestConfig.maxTokens)
                    if (resolveSemanticModelControlSupport(modelName).thinkingBackend == SemanticThinkingControlBackend.GEMINI_THINKING_BUDGET) {
                        put(
                            "thinkingConfig",
                            JSONObject().apply {
                                put("thinkingBudget", if (effectiveTurnOptions.thinkingEnabled) 1024 else 0)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun buildGeminiRequestUrl(
        runtimeConfig: ProviderRuntimeConfig,
        stream: Boolean
    ): String {
        val action = if (stream) "streamGenerateContent" else "generateContent"
        val baseUrl = runtimeConfig.endpoint.trimEnd('/')
        val encodedKey = URLEncoder.encode(runtimeConfig.apiKey, Charsets.UTF_8.name())
        val altSuffix = if (stream) "&alt=sse" else ""
        return "$baseUrl/${runtimeConfig.model}:$action?key=$encodedKey$altSuffix"
    }

    private fun mapGeminiRole(role: String): String {
        return when (role.trim().lowercase(Locale.US)) {
            "assistant", "model" -> "model"
            else -> "user"
        }
    }

    internal fun normalizeGeminiResponseBody(rawBody: String): String {
        val root = JSONObject(rawBody)
        val textParts = extractGeminiCandidateText(root.optJSONArray("candidates")?.optJSONObject(0))
        val content = textParts.first.trim()
        val reasoning = normalizeReasoningContent(textParts.second)

        if (content.isBlank()) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            val detail = blockReason.ifBlank { rawBody.take(240) }
            throw IOException("Gemini returned empty content: $detail")
        }

        return JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().apply {
                            put("content", content)
                            reasoning?.let { value -> put("reasoning_content", value) }
                        }
                    )
                )
            )
            normalizeGeminiUsage(root.optJSONObject("usageMetadata"))?.let { usage ->
                put("usage", usage)
            }
        }.toString()
    }

    private fun extractGeminiCandidateText(candidate: JSONObject?): Pair<String, String?> {
        if (candidate == null) {
            return "" to null
        }

        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return "" to null
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.optString("text").takeIf { value -> value.isNotBlank() } ?: continue
            if (part.optBoolean("thought")) {
                reasoningBuilder.append(text)
            } else {
                contentBuilder.append(text)
            }
        }
        val reasoning = reasoningBuilder.toString().takeIf { value -> value.isNotBlank() }
        return contentBuilder.toString() to reasoning
    }

    private fun normalizeGeminiUsage(usageMetadata: JSONObject?): JSONObject? {
        if (usageMetadata == null) {
            return null
        }

        val promptTokens = usageMetadata.optInt("promptTokenCount", -1)
        val completionTokens = usageMetadata.optInt("candidatesTokenCount", -1)
        val totalTokens = usageMetadata.optInt("totalTokenCount", -1)
        if (promptTokens < 0 && completionTokens < 0 && totalTokens < 0) {
            return null
        }

        return JSONObject().apply {
            put("prompt_tokens", promptTokens.coerceAtLeast(0))
            put("completion_tokens", completionTokens.coerceAtLeast(0))
            put(
                "total_tokens",
                if (totalTokens >= 0) totalTokens else {
                    promptTokens.coerceAtLeast(0) + completionTokens.coerceAtLeast(0)
                }
            )
        }
    }

    internal fun consumeStreamingDataLine(
        rawLine: String,
        accumulator: StreamingResponseAccumulator,
        apiStyle: ProviderApiStyle = ProviderApiStyle.OPENAI_CHAT
    ): StreamingTurnDelta? {
        val trimmedLine = rawLine.trim()
        if (trimmedLine.isBlank() || !trimmedLine.startsWith("data:")) {
            return null
        }
        val payload = trimmedLine.removePrefix("data:").trim()
        if (payload.isBlank()) {
            return null
        }
        if (payload == "[DONE]") {
            val searchSummaryContent = extractVisibleSearchSummary(accumulator.contentBuffer.toString())
            val reasoningContent = normalizeReasoningContent(accumulator.reasoningBuffer.toString())
            return publishStreamingTurnDelta(
                accumulator = accumulator,
                assistantReply = extractVisibleAssistantReply(accumulator.contentBuffer.toString()).orEmpty(),
                searchSummaryContent = searchSummaryContent,
                reasoningContent = reasoningContent,
                completed = true
            )
        }

        return when (apiStyle) {
            ProviderApiStyle.OPENAI_CHAT -> consumeOpenAiStreamingPayload(payload, accumulator)
            ProviderApiStyle.GEMINI_GENERATE_CONTENT -> consumeGeminiStreamingPayload(payload, accumulator)
        }
    }

    private fun consumeOpenAiStreamingPayload(
        payload: String,
        accumulator: StreamingResponseAccumulator
    ): StreamingTurnDelta? {
        val chunk = JSONObject(payload)
        val delta = chunk.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: chunk.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")

        delta?.optString("reasoning_content")
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let(accumulator.reasoningBuffer::append)
        delta?.optString("content")
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let(accumulator.contentBuffer::append)

        parseTokenUsage(chunk.optJSONObject("usage"))?.let { usage ->
            accumulator.tokenUsage = usage
        }

        val searchSummaryContent = extractVisibleSearchSummary(accumulator.contentBuffer.toString())
        val reasoningContent = normalizeReasoningContent(accumulator.reasoningBuffer.toString())
        val assistantReply = extractVisibleAssistantPreview(accumulator.contentBuffer.toString()).orEmpty()
        return publishStreamingTurnDelta(
            accumulator = accumulator,
            assistantReply = assistantReply,
            searchSummaryContent = searchSummaryContent,
            reasoningContent = reasoningContent
        )
    }

    private fun consumeGeminiStreamingPayload(
        payload: String,
        accumulator: StreamingResponseAccumulator
    ): StreamingTurnDelta? {
        val chunk = JSONObject(payload)
        val textParts = extractGeminiCandidateText(chunk.optJSONArray("candidates")?.optJSONObject(0))
        textParts.first
            .takeIf { value -> value.isNotEmpty() }
            ?.let(accumulator.contentBuffer::append)
        textParts.second
            ?.takeIf { value -> value.isNotEmpty() }
            ?.let(accumulator.reasoningBuffer::append)

        normalizeGeminiUsage(chunk.optJSONObject("usageMetadata"))?.let { usage ->
            parseTokenUsage(usage)?.let { tokenUsage ->
                accumulator.tokenUsage = tokenUsage
            }
        }

        val searchSummaryContent = extractVisibleSearchSummary(accumulator.contentBuffer.toString())
        val reasoningContent = normalizeReasoningContent(accumulator.reasoningBuffer.toString())
        val assistantReply = extractVisibleAssistantPreview(accumulator.contentBuffer.toString()).orEmpty()
        return publishStreamingTurnDelta(
            accumulator = accumulator,
            assistantReply = assistantReply,
            searchSummaryContent = searchSummaryContent,
            reasoningContent = reasoningContent
        )
    }

    private fun publishStreamingTurnDelta(
        accumulator: StreamingResponseAccumulator,
        assistantReply: String,
        searchSummaryContent: String?,
        reasoningContent: String?,
        completed: Boolean = false
    ): StreamingTurnDelta? {
        var stagedReasoningContent = reasoningContent
        var stagedAssistantReply = assistantReply

        if (!completed && searchSummaryContent.orEmpty().isNotBlank() && accumulator.lastPublishedSearchSummaryContent.isBlank()) {
            stagedReasoningContent = null
            stagedAssistantReply = ""
        } else if (!completed &&
            accumulator.lastPublishedSearchSummaryContent.isNotBlank() &&
            accumulator.lastPublishedReasoningContent.isBlank() &&
            assistantReply.isNotBlank()
        ) {
            stagedAssistantReply = ""
        }

        if (stagedAssistantReply == accumulator.lastPublishedAssistantReply &&
            searchSummaryContent.orEmpty() == accumulator.lastPublishedSearchSummaryContent &&
            stagedReasoningContent.orEmpty() == accumulator.lastPublishedReasoningContent
        ) {
            return null
        }

        accumulator.lastPublishedAssistantReply = stagedAssistantReply
        accumulator.lastPublishedSearchSummaryContent = searchSummaryContent.orEmpty()
        accumulator.lastPublishedReasoningContent = stagedReasoningContent.orEmpty()
        return StreamingTurnDelta(
            assistantReply = stagedAssistantReply,
            searchSummaryContent = searchSummaryContent,
            reasoningContent = stagedReasoningContent,
            completed = completed
        )
    }

    internal fun buildStreamingRawResponse(accumulator: StreamingResponseAccumulator): String {
        val structuredContent = accumulator.contentBuffer.toString().trim()
        if (structuredContent.isBlank()) {
            throw IOException("Semantic streaming response ended without structured content")
        }
        return JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().apply {
                            put("content", structuredContent)
                            normalizeReasoningContent(accumulator.reasoningBuffer.toString())?.let { reasoning ->
                                put("reasoning_content", reasoning)
                            }
                        }
                    )
                )
            )
            accumulator.tokenUsage?.let { usage ->
                put(
                    "usage",
                    JSONObject().apply {
                        put("prompt_tokens", usage.promptTokens)
                        put("completion_tokens", usage.completionTokens)
                        put("total_tokens", usage.totalTokens)
                    }
                )
            }
        }.toString()
    }

    internal fun buildSemanticTurnPacket(
        userMessage: String,
        store: PrototypeStoreData,
        searchPlanBundle: String? = null,
        searchBundle: String? = null,
        toolCapabilityBundle: ToolCapabilityBundle? = null
    ): PromptPacket {
        val currentState = store.resolveCurrentState()
        val historyLines = store.messages
            .filter { it.role != MessageRole.SYSTEM }
            .joinToString("\n") { message ->
            "${message.role.name}: ${message.content}"
        }.ifBlank { "No prior messages." }
        val semanticContextLines = buildSemanticContext(store)
        val reactivationHintLines = buildReactivationHintContext(userMessage, store)
        val memoryBundle = MemoryOrchestrator.buildPassiveEvidence(userMessage, store)?.toPromptSection()
        val personalizationPolicyBundle = buildPersonalizationPolicyBundle(store)
        val includeRichPriorSubset = store.shouldAttachRichPriorSubset()
        val capabilityPriorBundle = (toolCapabilityBundle?.toCapabilityPriorBundle(
            includeRichSubset = includeRichPriorSubset
        ) ?: CapabilityPriorBundle()).toPromptSection()
        val processPriorBundle = store.buildProcessPriorBundle(
            includeRichSubset = includeRichPriorSubset
        ).toPromptSection()
        val toolBundle: String? = null
        val promptExecutionBrief = resolvePromptExecutionBrief(store, currentState)
        val safetyDecision = SafetyBoundaryEngine.assess(
            executionBoundaryPacket = promptExecutionBrief,
            toolCapabilityBundle = toolCapabilityBundle,
            context = SafetyBoundaryContext(
                workflowLane = currentState.workflowLane ?: WorkflowLane.PASSIVE,
                proactiveDeliveryPlan = currentState.pendingProactiveDeliveryPlan,
                personalizationPolicyBundle = personalizationPolicyBundle
            )
        )?.toPromptSection()
        val executionBrief = listOfNotNull(
            promptExecutionBrief?.let(::buildPrimaryExecutionBrief),
            store.pendingProcessRecoveryContext?.let { recovery ->
                buildRecoveryExecutionBrief(
                    recovery = recovery,
                    executionBoundaryPacket = resolvePromptExecutionBrief(store, currentState),
                    semanticIntentState = currentState.currentSemanticIntentState
                )
            }
        ).takeIf { sections -> sections.isNotEmpty() }?.joinToString("\n")

        return PromptCenter.buildSemanticTurnPacket(
            SemanticTurnPromptSpec(
                currentState = currentState,
                historyLines = historyLines,
                semanticContextBundle = semanticContextLines,
                reactivationHintLines = reactivationHintLines,
                executionBrief = executionBrief,
                userMessage = userMessage,
                memoryBundle = memoryBundle ?: "No memory bundle attached yet.",
                personalizationBundle = personalizationPolicyBundle.toPromptSection(),
                searchPlanBundle = searchPlanBundle,
                searchBundle = searchBundle,
                capabilityPriorBundle = capabilityPriorBundle,
                processPriorBundle = processPriorBundle,
                toolBundle = toolBundle ?: "No tool bundle attached yet.",
                safetyDecision = safetyDecision
            )
        )
    }

    internal fun buildSearchPlanPacket(
        userMessage: String,
        store: PrototypeStoreData
    ): PromptPacket {
        val currentState = store.resolveCurrentState()
        val historyLines = store.messages
            .filter { message -> message.role != MessageRole.SYSTEM }
            .joinToString("\n") { message ->
                "${message.role.name}: ${message.content}"
            }.ifBlank { "No prior messages." }
        val semanticContextLines = buildSemanticContext(store)
        val reactivationHintLines = buildReactivationHintContext(userMessage, store)
        val memoryBundle = MemoryOrchestrator.buildPassiveEvidence(userMessage, store)?.toPromptSection()
        val personalizationPolicyBundle = buildPersonalizationPolicyBundle(store)

        return PromptCenter.buildSearchPlanPacket(
            SearchPlanPromptSpec(
                currentState = currentState,
                historyLines = historyLines,
                semanticContextBundle = semanticContextLines,
                reactivationHintLines = reactivationHintLines,
                userMessage = userMessage,
                memoryBundle = memoryBundle ?: "No memory bundle attached yet.",
                personalizationBundle = personalizationPolicyBundle.toPromptSection()
            )
        )
    }

    internal fun parseSearchPlanResponse(raw: String): SearchPlanResponse {
        return parseSearchPlanResponse(
            RawPromptExchange(
                requestPayload = "",
                responsePayload = raw
            )
        )
    }

    internal fun parseSearchPlanResponse(rawExchange: RawPromptExchange): SearchPlanResponse {
        val outer = JSONObject(rawExchange.responsePayload)
        val content = outer
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isBlank()) {
            throw IOException("Semantic provider returned an empty search plan payload")
        }
        val payload = JSONObject(extractFirstJsonObject(sanitizeModelContent(content)) ?: sanitizeModelContent(content))
        val queries = payload.optJSONArray("search_queries")
            ?.let(::jsonArrayToStringList)
            .orEmpty()
        val shouldSearch = payload.optBoolean("should_search", queries.isNotEmpty()) && queries.isNotEmpty()
        return SearchPlanResponse(
            shouldSearch = shouldSearch,
            goalSummary = payload.optString("goal_summary").trim(),
            processSummary = payload.optString("process_summary").trim(),
            searchQueries = queries,
            searchScope = payload.optJSONArray("search_scope")
                ?.let(::jsonArrayToStringList)
                .orEmpty(),
            requestPayload = rawExchange.requestPayload,
            responsePayload = rawExchange.responsePayload
        )
    }

    internal fun parseResponse(
        raw: String,
        store: PrototypeStoreData
    ): SemanticTurnResponse {
        return parseResponse(
            RawPromptExchange(
                requestPayload = "",
                responsePayload = raw
            ),
            store
        )
    }

    internal fun parseResponse(
        rawExchange: RawPromptExchange,
        store: PrototypeStoreData
    ): SemanticTurnResponse {
        val outer = JSONObject(rawExchange.responsePayload)
        val tokenUsage = parseTokenUsage(rawExchange.responsePayload)
        val reasoningContent = normalizeReasoningContent(
            outer
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("reasoning_content")
        )
        val content = outer
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isBlank()) {
            throw IOException("Semantic provider returned an empty content payload")
        }
        val sanitizedContent = sanitizeModelContent(content)
        val payloadText = extractFirstJsonObject(sanitizedContent) ?: sanitizedContent
        val payload = runCatching { JSONObject(payloadText) }.getOrElse { error ->
            return recoverResponseFromMalformedContent(
                content = sanitizedContent,
                store = store,
                cause = error,
                tokenUsage = tokenUsage,
                reasoningContent = reasoningContent
            )
        }
        return buildSemanticTurnResponse(payload, store, tokenUsage, reasoningContent)
            .copy(
                requestPayload = rawExchange.requestPayload,
                responsePayload = rawExchange.responsePayload
            )
    }

    internal fun parseExecutionAssistantReply(raw: String): AssistantReplyResult {
        val outer = JSONObject(raw)
        val tokenUsage = parseTokenUsage(raw)
        val content = outer
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isBlank()) {
            throw IOException("Semantic provider returned an empty content payload")
        }
        val sanitizedContent = sanitizeModelContent(content)
        val payloadText = extractFirstJsonObject(sanitizedContent) ?: sanitizedContent
        val payload = JSONObject(payloadText)
        val assistantReply = payload.optString("assistant_reply").trim()
        if (assistantReply.isBlank()) {
            throw IOException("Execution assistant reply payload omitted assistant_reply")
        }
        return AssistantReplyResult(
            content = assistantReply,
            tokenUsage = tokenUsage
        )
    }

    private fun buildSemanticTurnResponse(
        payload: JSONObject,
        store: PrototypeStoreData,
        tokenUsage: TokenUsage?,
        reasoningContent: String?
    ): SemanticTurnResponse {
        val resolvedState = store.resolveCurrentState()
        val currentStage = resolvedState.stage.normalized()
        val workflowLane = WorkflowLane.fromRaw(payload.optString("workflow_lane")) ?: WorkflowLane.PASSIVE
        val stageOwner = StageOwner.fromRaw(payload.optString("stage_owner")) ?: defaultStageOwnerFor(workflowLane)
        val parsedPassiveTurnControl = parsePassiveTurnControl(
            currentPhaseRaw = payload.optString("current_phase"),
            userRequestSemanticRaw = payload.optString("user_request_semantic"),
            stageTransitionRecommendationRaw = payload.optString("stage_transition_recommendation"),
            transitionIntentRaw = payload.optString("passive_user_transition_intent"),
            progressSignalRaw = payload.optString("passive_user_progress_signal"),
            legacyStageRaw = payload.optString("dialogue_stage"),
            currentStage = currentStage
        )
        val taskDraft = parseTaskDraft(payload)
        val passiveTurnControl = parsedPassiveTurnControl.normalizeForTaskAuthority(
            taskDraft = taskDraft,
            currentTaskRecord = resolvedState.currentTaskRecord
        )
        val stage = passiveTurnControl.userProgressSignal.resolvedStage()
        val semanticIntentState = taskDraft?.toSemanticIntentState(
            stage = stage,
            currentPhase = passiveTurnControl.currentPhase,
            userRequestSemantic = passiveTurnControl.userRequestSemantic,
            stageTransitionRecommendation = passiveTurnControl.stageTransitionRecommendation
        )
        val semanticShadowCandidate = semanticIntentState?.activeIntentShadowCandidate()
        val executionBoundaryPacket = if (passiveTurnControl.userProgressSignal.keepsExecutionPreparation()) {
            resolvePromptExecutionBrief(
                store = store,
                currentState = resolvedState.copy(
                    stage = stage,
                    currentTaskDraft = taskDraft ?: resolvedState.currentTaskDraft,
                    currentTaskRecord = resolvedState.currentTaskRecord,
                    currentSemanticIntentState = semanticIntentState ?: resolvedState.currentSemanticIntentState
                )
            )
        } else {
            null
        }
        return SemanticTurnResponse(
            assistantReply = payload.optString("assistant_reply").ifBlank { "我先继续帮你整理这个需求。" },
            stage = stage,
            currentPhase = passiveTurnControl.currentPhase,
            userRequestSemantic = passiveTurnControl.userRequestSemantic,
            stageTransitionRecommendation = passiveTurnControl.stageTransitionRecommendation,
            workflowLane = workflowLane,
            stageOwner = stageOwner,
            passiveUserTransitionIntent = passiveTurnControl.transitionIntent,
            userProgressSignal = passiveTurnControl.userProgressSignal,
            proactiveOpportunitySignal = ProactiveOpportunitySignal.fromRaw(payload.optString("proactive_opportunity_signal")),
            activeCandidateId = semanticShadowCandidate?.id,
            candidates = listOfNotNull(semanticShadowCandidate),
            semanticIntentState = semanticIntentState,
            taskDraft = taskDraft,
            semanticSummary = payload.optString("semantic_summary"),
            searchSummaryContent = payload.optString("search_summary").trim().takeIf { value -> value.isNotBlank() },
            tokenUsage = tokenUsage,
            reasoningContent = reasoningContent
        ).attachExecutionBoundaryPacket(executionBoundaryPacket)
    }

    private fun buildRecoveryExecutionBrief(
        recovery: com.atombits.pocopaw.process.runtime.ProcessRecoveryContext,
        executionBoundaryPacket: TaskExecutionBoundaryPacket?,
        semanticIntentState: SemanticIntentState?
    ): String {
        val activeIntent = semanticIntentState?.candidateIntents
            ?.firstOrNull { candidate -> candidate.intentId == semanticIntentState.activeIntentId }
            ?: semanticIntentState?.candidateIntents?.firstOrNull()
        return buildString {
            append("pending_execution_recovery=true; ")
            append("objective=${recovery.objective}; ")
            append("failure_summary=${recovery.blockedContext}; ")
            append("recovery_action=${recovery.recoveryAction}; ")
            activeIntent?.canonicalAction?.name?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }?.let { canonicalAction ->
                append("canonical_action=$canonicalAction; ")
            }
            activeIntent?.readiness?.name?.uppercase(Locale.US)?.takeIf { value -> value.isNotBlank() }?.let { readiness ->
                append("intent_readiness=$readiness; ")
            }
            semanticIntentState?.phaseType?.name?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }?.let { phaseType ->
                append("phase_type=$phaseType; ")
            }
            semanticIntentState?.phaseStatus?.name?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }?.let { phaseStatus ->
                append("phase_status=$phaseStatus; ")
            }
            semanticIntentState?.nextMove?.let { nextMove ->
                append("next_move=${nextMove.name.lowercase(Locale.US)}; ")
            }
            executionBoundaryPacket?.objectiveSummary?.takeIf { value -> value.isNotBlank() }
                ?.let { projectionObjective ->
                    append("projected_objective=$projectionObjective; ")
                }
            executionBoundaryPacket?.actionCode?.wireName?.takeIf { value -> value.isNotBlank() && value != ActionCode.UNKNOWN.wireName }
                ?.let { actionIntent ->
                    append("projected_action=$actionIntent; ")
                }
            executionBoundaryPacket?.executionGateFlag?.let { gateFlag ->
                append("execution_gate=${gateFlag.name}; ")
            }
            append("source_trace_id=${recovery.sourceTraceId ?: "none"}")
        }
    }

    private fun recoverResponseFromMalformedContent(
        content: String,
        store: PrototypeStoreData,
        cause: Throwable,
        tokenUsage: TokenUsage?,
        reasoningContent: String?
    ): SemanticTurnResponse {
        val recoveredReply = extractJsonStringField(content, "assistant_reply")
            ?: throw IOException("Semantic provider returned malformed structured content", cause)
        val resolvedState = store.resolveCurrentState()
        val currentStage = resolvedState.stage.normalized()
        val passiveTurnControl = parsePassiveTurnControl(
            currentPhaseRaw = extractJsonStringField(content, "current_phase"),
            userRequestSemanticRaw = extractJsonStringField(content, "user_request_semantic"),
            stageTransitionRecommendationRaw = extractJsonStringField(content, "stage_transition_recommendation"),
            transitionIntentRaw = extractJsonStringField(content, "passive_user_transition_intent"),
            progressSignalRaw = extractJsonStringField(content, "passive_user_progress_signal"),
            legacyStageRaw = extractJsonStringField(content, "dialogue_stage"),
            currentStage = currentStage
        )
        val hintedCandidate = extractJsonStringField(content, "candidate_id")
            ?.let { candidateId ->
                (store.currentDialogueCandidates() + resolvedState.dormantHistoricalCandidates)
                    .firstOrNull { it.id == candidateId }
            }
        val fallbackCandidates = if (passiveTurnControl.userProgressSignal == UserProgressSignal.SWITCH_CONTEXT) {
            emptyList()
        } else if (hintedCandidate != null) {
            listOf(hintedCandidate)
        } else {
            store.activeCandidateContext()
        }
        val recoveredStage = passiveTurnControl.userProgressSignal.resolvedStage()
        val recoveredExecutionBoundaryPacket = if (passiveTurnControl.userProgressSignal.keepsExecutionPreparation()) {
            resolvePromptExecutionBrief(
                store = store,
                currentState = resolvedState.copy(stage = recoveredStage)
            )
        } else {
            null
        }
        return SemanticTurnResponse(
            assistantReply = recoveredReply,
            stage = recoveredStage,
            currentPhase = passiveTurnControl.currentPhase,
            userRequestSemantic = passiveTurnControl.userRequestSemantic,
            stageTransitionRecommendation = passiveTurnControl.stageTransitionRecommendation,
            passiveUserTransitionIntent = passiveTurnControl.transitionIntent,
            userProgressSignal = passiveTurnControl.userProgressSignal,
            activeCandidateId = fallbackCandidates.firstOrNull()?.id,
            candidates = fallbackCandidates,
            taskDraft = null,
            semanticSummary = "Recovered assistant reply from malformed structured content.",
            searchSummaryContent = extractJsonStringField(content, "search_summary"),
            tokenUsage = tokenUsage,
            reasoningContent = reasoningContent
        ).attachExecutionBoundaryPacket(recoveredExecutionBoundaryPacket)
    }

    private fun normalizeReasoningContent(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.replace("\\n", "\n")
            ?.replace("\\t", "\t")
            ?.let(::stripRepeatedNullSuffix)
            ?.takeIf { reasoning -> reasoning.isNotBlank() }
        return normalized
    }

    internal fun sanitizeStreamingTurnDelta(
        delta: StreamingTurnDelta,
        turnOptions: ChatTurnOptions
    ): StreamingTurnDelta {
        if (turnOptions.searchEnabled && delta.searchSummaryContent.isNullOrBlank() && delta.assistantReply.isBlank()) {
            return delta.copy(reasoningContent = null)
        }
        if (turnOptions.thinkingEnabled || turnOptions.searchEnabled) {
            return delta
        }
        return delta.copy(reasoningContent = null)
    }

    internal fun sanitizeSemanticTurnResponse(
        response: SemanticTurnResponse,
        turnOptions: ChatTurnOptions
    ): SemanticTurnResponse {
        if (turnOptions.thinkingEnabled || turnOptions.searchEnabled) {
            return response
        }
        return response.copy(reasoningContent = null)
    }

    private fun extractVisibleSearchSummary(content: String): String? {
        val sanitizedContent = sanitizeModelContent(content)
        return extractJsonStringField(
            content = sanitizedContent,
            fieldName = "search_summary",
            allowPartial = false
        )?.trim()
    }

    private fun extractVisibleAssistantPreview(content: String): String? {
        val sanitizedContent = sanitizeModelContent(content)
        val assistantReply = extractVisibleAssistantReply(sanitizedContent)
        if (assistantReply != null) {
            return assistantReply
        }
        if (sanitizedContent.contains("\"search_summary\"") || sanitizedContent.contains("\"visible_reasoning\"")) {
            return null
        }
        return extractVisibleAssistantReply(sanitizedContent)
            ?: extractFirstJsonStringValue(sanitizedContent, allowPartial = true)
                ?.trim()
                ?.takeIf { preview -> preview.isNotBlank() }
    }

    private fun extractVisibleAssistantReply(content: String): String? {
        val sanitizedContent = sanitizeModelContent(content)
        return extractJsonStringField(
            content = sanitizedContent,
            fieldName = "assistant_reply",
            allowPartial = true
        )?.trim()
    }

    private fun jsonArrayToStringList(array: JSONArray): List<String> {
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index)
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun stripRepeatedNullSuffix(content: String): String {
        return content.replace(Regex("(?:null){3,}\\s*$", RegexOption.IGNORE_CASE), "").trimEnd()
    }

    private fun resolvePromptExecutionBrief(
        store: PrototypeStoreData,
        currentState: LocalConversationState
    ): TaskExecutionBoundaryPacket? {
        if (currentState.currentTaskRecord?.phase != TaskPhase.EXECUTING) {
            return null
        }
        return resolveStoreAwareExecutionBoundaryPacket(store = store, currentState = currentState)
    }

    private fun buildPrimaryExecutionBrief(brief: TaskExecutionBoundaryPacket): String {
        return buildString {
            append("objective=${brief.objectiveSummary}; ")
            append("action=${brief.actionCode.wireName.ifBlank { brief.planSummary.ifBlank { brief.objectiveSummary } }}; ")
            append("plan=${brief.planSummary}; ")
            append("risk=${brief.riskSummary}; ")
            append("missing=${brief.missingInformation.joinToString(", ")}; ")
            append("required=${brief.requiredDetailSlots.joinToString(",") { slot -> slot.contractName }}; ")
            append("can_start=${brief.canStartExecution}")
        }
    }

    private data class PassiveTurnControl(
        val transitionIntent: PassiveUserTransitionIntent,
        val userProgressSignal: UserProgressSignal,
        val currentPhase: CurrentPhase,
        val userRequestSemantic: UserRequestSemantic,
        val stageTransitionRecommendation: StageTransitionRecommendation
    )

    private fun parsePassiveTurnControl(
        currentPhaseRaw: String?,
        userRequestSemanticRaw: String?,
        stageTransitionRecommendationRaw: String?,
        transitionIntentRaw: String?,
        progressSignalRaw: String?,
        legacyStageRaw: String?,
        currentStage: ConversationStage
    ): PassiveTurnControl {
        val parsedCurrentPhase = CurrentPhase.fromRaw(currentPhaseRaw)
            ?: CurrentPhase.fromRaw(legacyStageRaw)
        val parsedRecommendation = StageTransitionRecommendation.fromRaw(stageTransitionRecommendationRaw)
        UserRequestSemantic.fromRaw(userRequestSemanticRaw)?.let { userRequestSemantic ->
            val signal = userRequestSemantic.toProgressSignal(currentStage)
            return PassiveTurnControl(
                transitionIntent = userRequestSemantic.toTransitionIntent(),
                userProgressSignal = signal,
                currentPhase = parsedCurrentPhase ?: signal.resolvedStage().toCurrentPhase(),
                userRequestSemantic = userRequestSemantic,
                stageTransitionRecommendation = parsedRecommendation ?: signal.toStageTransitionRecommendation()
            )
        }
        UserProgressSignal.fromRaw(progressSignalRaw)?.let { signal ->
            return PassiveTurnControl(
                transitionIntent = signal.toTransitionIntent(),
                userProgressSignal = signal,
                currentPhase = parsedCurrentPhase ?: signal.resolvedStage().toCurrentPhase(),
                userRequestSemantic = signal.toUserRequestSemantic(),
                stageTransitionRecommendation = parsedRecommendation ?: signal.toStageTransitionRecommendation()
            )
        }
        PassiveUserTransitionIntent.fromRaw(transitionIntentRaw)?.let { intent ->
            val signal = intent.resolveOperationalSignal(currentStage)
            return PassiveTurnControl(
                transitionIntent = intent,
                userProgressSignal = signal,
                currentPhase = parsedCurrentPhase ?: signal.resolvedStage().toCurrentPhase(),
                userRequestSemantic = signal.toUserRequestSemantic(),
                stageTransitionRecommendation = parsedRecommendation ?: signal.toStageTransitionRecommendation()
            )
        }
        parseLegacyProgressSignal(legacyStageRaw, currentStage)?.let { signal ->
            return PassiveTurnControl(
                transitionIntent = signal.toTransitionIntent(),
                userProgressSignal = signal,
                currentPhase = parsedCurrentPhase ?: signal.resolvedStage().toCurrentPhase(),
                userRequestSemantic = signal.toUserRequestSemantic(),
                stageTransitionRecommendation = parsedRecommendation ?: signal.toStageTransitionRecommendation()
            )
        }
        val defaultSignal = defaultProgressSignal(currentStage)
        return PassiveTurnControl(
            transitionIntent = defaultSignal.toTransitionIntent(),
            userProgressSignal = defaultSignal,
            currentPhase = parsedCurrentPhase ?: currentStage.toCurrentPhase(),
            userRequestSemantic = defaultSignal.toUserRequestSemantic(),
            stageTransitionRecommendation = parsedRecommendation ?: defaultSignal.toStageTransitionRecommendation()
        )
    }

    private fun PassiveTurnControl.normalizeForTaskAuthority(
        taskDraft: TaskDraft?,
        currentTaskRecord: TaskRecord?
    ): PassiveTurnControl {
        if (userProgressSignal != UserProgressSignal.ENTER_EXECUTING) {
            return this
        }
        if (taskDraft != null || currentTaskRecord != null) {
            return this
        }
        return PassiveTurnControl(
            transitionIntent = PassiveUserTransitionIntent.SAME_TOPIC_ACCUMULATE,
            userProgressSignal = UserProgressSignal.CONTINUE_ACCUMULATING,
            currentPhase = CurrentPhase.ACCUMULATION,
            userRequestSemantic = UserRequestSemantic.START_ACCUMULATING,
            stageTransitionRecommendation = StageTransitionRecommendation.SHOULD_ENTER_ACCUMULATING
        )
    }

    private fun parseLegacyProgressSignal(
        rawStage: String?,
        currentStage: ConversationStage
    ): UserProgressSignal? {
        if (rawStage.isNullOrBlank()) {
            return null
        }
        return when (ConversationStage.fromRaw(rawStage).normalized()) {
            ConversationStage.PREPARING -> if (currentStage == ConversationStage.PREPARING) {
                UserProgressSignal.STAY_PREPARING
            } else {
                UserProgressSignal.ENTER_PREPARING
            }

            ConversationStage.EXECUTING -> UserProgressSignal.ENTER_EXECUTING

            else -> if (currentStage == ConversationStage.PREPARING) {
                UserProgressSignal.RETURN_TO_ACCUMULATING
            } else {
                UserProgressSignal.CONTINUE_ACCUMULATING
            }
        }
    }

    private fun defaultProgressSignal(currentStage: ConversationStage): UserProgressSignal {
        return when (currentStage.normalized()) {
            ConversationStage.PREPARING -> UserProgressSignal.STAY_PREPARING
            else -> UserProgressSignal.CONTINUE_ACCUMULATING
        }
    }

    internal fun buildSemanticContext(store: PrototypeStoreData): String {
        val resolvedState = store.resolveCurrentState()
        val unifiedTaskContext = formatTaskContext(
            taskRecord = resolvedState.currentTaskRecord,
            taskDraft = resolvedState.currentTaskDraft
        )
        val semanticIntentContext = formatSemanticIntentContext(resolvedState.currentSemanticIntentState)
        if (unifiedTaskContext.isNotBlank() || semanticIntentContext.isNotBlank()) {
            return buildString {
                if (unifiedTaskContext.isNotBlank()) {
                    append("Unified task state for this turn:\n")
                    append(unifiedTaskContext)
                }
                if (semanticIntentContext.isNotBlank()) {
                    if (isNotEmpty()) {
                        append("\n\n")
                    }
                    append("Semantic intent state for this turn:\n")
                    append(semanticIntentContext)
                }
            }
        }
        val activeCandidateContext = store.activeCandidateContext()
        val currentSection = formatCandidateLines(activeCandidateContext)
            .ifBlank { "No semantic intent context for this turn yet." }

        return buildString {
            append("Legacy candidate fallback for this turn:\n")
            append(currentSection)
        }
    }

    internal fun buildReactivationHintContext(
        userMessage: String,
        store: PrototypeStoreData
    ): String {
        val currentState = store.resolveCurrentState()
        val activeCandidateId = store.resolveCurrentActiveCandidateId()
        val liveCandidates = store.currentDialogueCandidates().filterNot { it.id == activeCandidateId }
        val explicitlyMentionedLiveCandidateIds = collectExplicitReMentionedCandidateIds(userMessage, liveCandidates)
        val explicitlyMentionedLiveCandidates = liveCandidates.filter { candidate ->
            candidate.id in explicitlyMentionedLiveCandidateIds
        }
        val dormantCandidates = currentState.dormantHistoricalCandidates
        val explicitlyMentionedDormantCandidateIds = collectExplicitReMentionedCandidateIds(userMessage, dormantCandidates)
        val explicitlyMentionedDormantCandidates = dormantCandidates.filter { candidate ->
            candidate.id in explicitlyMentionedDormantCandidateIds
        }

        val continuationContext = ContinuationGroundingResolver.buildPromptContext(userMessage, store)

        if (explicitlyMentionedLiveCandidates.isEmpty() && explicitlyMentionedDormantCandidates.isEmpty() && continuationContext == null) {
            return "No explicit reactivation hint."
        }

        return buildString {
            if (explicitlyMentionedLiveCandidates.isNotEmpty()) {
                append("Explicitly re-mentioned local live candidates:\n")
                append(formatCandidateLines(explicitlyMentionedLiveCandidates))
            }
            if (explicitlyMentionedDormantCandidates.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("\n")
                }
                append("Explicitly re-mentioned dormant historical candidates:\n")
                append(formatCandidateLines(explicitlyMentionedDormantCandidates))
            }
            continuationContext?.let { context ->
                if (isNotEmpty()) {
                    append("\n")
                }
                append(context)
            }
        }
    }

    private fun formatCandidateLines(candidates: List<IntentCandidate>): String {
        return candidates.joinToString("\n") { candidate ->
            val slots = if (candidate.detailSlots.isEmpty()) {
                "none"
            } else {
                candidate.detailSlots.joinToString(", ") { slot -> "${slot.key.wireName}=${slot.value}" }
            }
            val missing = if (candidate.missingRequiredSlots.isEmpty()) {
                "none"
            } else {
                candidate.missingRequiredSlots.joinToString(", ") { it.wireName }
            }
            "id=${candidate.id}; anchor=${candidate.anchorObject}; focus=${candidate.focusedObject}; action=${candidate.action}; readiness=${candidate.readiness}; confidence=${candidate.confidence}; slots=$slots; missing=$missing; can_start=${candidate.canStartExecution}"
        }
    }

    private fun formatSemanticIntentContext(semanticIntentState: SemanticIntentState?): String {
        val state = semanticIntentState ?: return ""
        val activeIntent = state.candidateIntents.firstOrNull { candidate ->
            candidate.intentId == state.activeIntentId
        } ?: state.candidateIntents.firstOrNull()
        val stateLine = buildList {
            state.currentPhase?.let { currentPhase -> add("current_phase=${currentPhase.name}") }
            state.userRequestSemantic?.let { userRequestSemantic -> add("user_request_semantic=${userRequestSemantic.name}") }
            state.stageTransitionRecommendation?.let { recommendation -> add("stage_transition_recommendation=${recommendation.name}") }
            add("phase_type=${state.phaseType.name.lowercase(Locale.US)}")
            add("phase_status=${state.phaseStatus.name.lowercase(Locale.US)}")
            state.nextMove?.let { nextMove -> add("next_move=${nextMove.name.lowercase(Locale.US)}") }
            state.activeIntentId?.takeIf { value -> value.isNotBlank() }?.let { intentId -> add("semantic_candidate_id=$intentId") }
        }.joinToString("; ")
        val intentLine = activeIntent?.let { intent ->
            val slots = if (intent.detailSlots.isEmpty()) {
                "none"
            } else {
                intent.detailSlots.joinToString(", ") { slot -> "${slot.key.wireName}=${slot.value}" }
            }
            val constraints = if (intent.constraints.isEmpty()) {
                "none"
            } else {
                intent.constraints.joinToString(", ")
            }
            val routeSummary = buildList {
                intent.capabilityId?.takeIf { value -> value.isNotBlank() }?.let { add("capability_id=$it") }
                intent.capabilityDomain?.let { capabilityDomain -> add("capability_domain=${capabilityDomain.wireName}") }
                intent.processId?.takeIf { value -> value.isNotBlank() }?.let { add("process_id=$it") }
                intent.continuationHint?.takeIf { value -> value.isNotBlank() }?.let { add("continuation=$it") }
            }.ifEmpty { listOf("none") }.joinToString(", ")
            "intent_id=${intent.intentId}; anchor=${intent.anchorObject}; focus=${intent.focusedObject}; action=${intent.canonicalAction?.name?.lowercase(Locale.US) ?: intent.rawActionLabel}; readiness=${intent.readiness ?: "none"}; confidence=${intent.confidence}; stability=${intent.stability}; slots=$slots; constraints=$constraints; route=$routeSummary; can_start=${intent.canStartExecution}"
        } ?: "No active semantic intent candidate recorded yet."
        return listOf(stateLine, intentLine)
            .filter { line -> line.isNotBlank() }
            .joinToString("\n")
    }

    private fun formatTaskContext(
        taskRecord: TaskRecord?,
        taskDraft: TaskDraft?
    ): String {
        val recordLine = taskRecord?.let { record ->
            val detailSlotSummary = record.detailSlots.entries.joinToString(",") { (key, value) ->
                "${key.contractName}=$value"
            }.ifBlank { "none" }
            buildList {
                add("task_id=${record.taskId}")
                add("phase=${record.phase.name.lowercase(Locale.US)}")
                add("action_code=${record.actionCode.wireName}")
                add("target_type=${record.targetType.wireName}")
                add("target_key=${record.targetKey}")
                add("target_label=${record.targetLabel ?: record.targetKey}")
                add("capability_stack=${record.capabilityStack?.name ?: "null"}")
                add("capability_domain=${record.capabilityDomain?.wireName ?: "null"}")
                add("capability_id=${record.capabilityId ?: "null"}")
                add("process_id=${record.processId ?: "null"}")
                add("detail_slots=$detailSlotSummary")
            }.joinToString("; ")
        }
        val draftLine = taskDraft?.let { draft ->
            val detailSlotSummary = draft.detailSlots.entries.joinToString(",") { (key, value) ->
                "${key.contractName}=$value"
            }.ifBlank { "none" }
            buildList {
                add("draft_action_code=${draft.actionCode?.wireName ?: "null"}")
                add("draft_target_type=${draft.targetType?.wireName ?: "null"}")
                add("draft_target_key=${draft.targetKey ?: "null"}")
                add("draft_target_label=${draft.targetLabel ?: "null"}")
                add("draft_capability_stack=${draft.capabilityStack?.name ?: "null"}")
                add("draft_capability_domain=${draft.capabilityDomain?.wireName ?: "null"}")
                add("draft_capability_id=${draft.capabilityId ?: "null"}")
                add("draft_process_id=${draft.processId ?: "null"}")
                add("draft_detail_slots=$detailSlotSummary")
            }.joinToString("; ")
        }
        return listOfNotNull(recordLine, draftLine).joinToString("\n")
    }

    private fun sanitizeModelContent(content: String): String {
        var sanitized = content.trim()
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()
            if (sanitized.endsWith("```")) {
                sanitized = sanitized.dropLast(3).trim()
            }
        }
        return sanitized
    }

    private fun extractFirstJsonObject(content: String): String? {
        var startIndex = -1
        var depth = 0
        var inString = false
        var escaped = false

        content.forEachIndexed { index, char ->
            if (startIndex == -1) {
                if (char == '{') {
                    startIndex = index
                    depth = 1
                }
                return@forEachIndexed
            }

            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (char) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                    }
                }
                return@forEachIndexed
            }

            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return content.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun extractJsonStringField(
        content: String,
        fieldName: String,
        allowPartial: Boolean = false
    ): String? {
        val keyIndex = content.indexOf("\"$fieldName\"")
        if (keyIndex == -1) {
            return null
        }
        val colonIndex = content.indexOf(':', keyIndex + fieldName.length + 2)
        if (colonIndex == -1) {
            return null
        }
        var valueIndex = colonIndex + 1
        while (valueIndex < content.length && content[valueIndex].isWhitespace()) {
            valueIndex += 1
        }
        if (valueIndex >= content.length || content[valueIndex] != '"') {
            return null
        }

        val rawValue = StringBuilder()
        var cursor = valueIndex + 1
        var escaped = false
        while (cursor < content.length) {
            val char = content[cursor]
            if (escaped) {
                rawValue.append('\\')
                rawValue.append(char)
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> return decodeJsonString(rawValue.toString())
                    else -> rawValue.append(char)
                }
            }
            cursor += 1
        }

        if (allowPartial && escaped) {
            rawValue.append('\\')
        }

        return if (allowPartial) decodeJsonString(rawValue.toString()) else null
    }

    private fun extractFirstJsonStringValue(
        content: String,
        allowPartial: Boolean = false
    ): String? {
        val objectStart = content.indexOf('{')
        if (objectStart == -1) {
            return null
        }
        val keyStart = content.indexOf('"', objectStart)
        if (keyStart == -1) {
            return null
        }

        var keyCursor = keyStart + 1
        var keyEscaped = false
        while (keyCursor < content.length) {
            val char = content[keyCursor]
            if (keyEscaped) {
                keyEscaped = false
            } else {
                when (char) {
                    '\\' -> keyEscaped = true
                    '"' -> break
                }
            }
            keyCursor += 1
        }
        if (keyCursor >= content.length) {
            return null
        }

        var valueCursor = keyCursor + 1
        while (valueCursor < content.length && content[valueCursor].isWhitespace()) {
            valueCursor += 1
        }
        if (valueCursor >= content.length || content[valueCursor] != ':') {
            return null
        }

        valueCursor += 1
        while (valueCursor < content.length && content[valueCursor].isWhitespace()) {
            valueCursor += 1
        }
        if (valueCursor >= content.length || content[valueCursor] != '"') {
            return null
        }

        val rawValue = StringBuilder()
        var cursor = valueCursor + 1
        var escaped = false
        while (cursor < content.length) {
            val char = content[cursor]
            if (escaped) {
                rawValue.append('\\')
                rawValue.append(char)
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> return decodeJsonString(rawValue.toString())
                    else -> rawValue.append(char)
                }
            }
            cursor += 1
        }

        if (allowPartial && escaped) {
            rawValue.append('\\')
        }

        return if (allowPartial) decodeJsonString(rawValue.toString()) else null
    }

    private fun decodeJsonString(rawValue: String): String {
        val decoded = StringBuilder()
        var index = 0
        while (index < rawValue.length) {
            val char = rawValue[index]
            if (char != '\\' || index + 1 >= rawValue.length) {
                decoded.append(char)
                index += 1
                continue
            }

            when (val escapedChar = rawValue[index + 1]) {
                '"', '\\', '/' -> decoded.append(escapedChar)
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'u' -> {
                    if (index + 5 >= rawValue.length) {
                        decoded.append(rawValue.substring(index))
                        return decoded.toString()
                    }
                    val hex = rawValue.substring(index + 2, index + 6)
                    val codePoint = hex.toIntOrNull(16)
                    if (codePoint == null) {
                        decoded.append(rawValue.substring(index))
                        return decoded.toString()
                    }
                    decoded.append(codePoint.toChar())
                    index += 4
                }
                else -> decoded.append(escapedChar)
            }
            index += 2
        }
        return decoded.toString()
    }

    private fun parseDetailSlots(array: JSONArray?): List<DetailSlot> {
        if (array == null) {
            return emptyList()
        }
        val uniqueSlots = linkedMapOf<DetailSlotKey, DetailSlot>()
        for (index in 0 until array.length()) {
            val slotNode = array.optJSONObject(index) ?: continue
            val key = DetailSlotKey.fromContractValue(slotNode.optString("slot_key")) ?: continue
            val value = normalizeModelNullableString(slotNode.optString("value")).orEmpty()
            if (value.isBlank()) {
                continue
            }
            uniqueSlots[key] = DetailSlot(
                key = key,
                value = value,
                source = normalizeModelNullableString(slotNode.optString("source")) ?: "USER_CONTEXT"
            )
        }
        return uniqueSlots.values.toList()
    }

    private fun parseRequiredDetailSlots(array: JSONArray?): List<DetailSlotKey> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                DetailSlotKey.fromContractValue(array.optString(index))?.let(::add)
            }
        }
    }

    private fun SemanticIntentState.activeIntentShadowCandidate(): IntentCandidate? {
        val activeIntent = candidateIntents.firstOrNull { candidate ->
            candidate.intentId == activeIntentId
        } ?: candidateIntents.firstOrNull()
        return activeIntent?.toShadowIntentCandidate()
    }

    private fun SemanticIntentCandidate.toShadowIntentCandidate(): IntentCandidate {
        return IntentCandidate(
            id = intentId,
            anchorObject = anchorObject,
            focusedObject = focusedObject,
            action = canonicalAction?.name?.lowercase(Locale.US) ?: rawActionLabel,
            readiness = toShadowCandidateReadiness(),
            confidence = confidence,
            evidence = reasonSummary.orEmpty(),
            rationale = reasonSummary.orEmpty(),
            detailSlots = detailSlots,
            missingRequiredSlots = emptyList(),
            canStartExecution = canStartExecution
        )
    }

    private fun SemanticIntentCandidate.toShadowCandidateReadiness(): CandidateReadiness {
        return when {
            canStartExecution || readiness == SemanticIntentReadiness.READY_FOR_EXECUTION -> CandidateReadiness.READY_TO_START
            readiness == SemanticIntentReadiness.READY_FOR_CONFIRMATION ||
                readiness == SemanticIntentReadiness.READY_FOR_OFFER -> CandidateReadiness.READY_TO_PREPARE
            readiness == SemanticIntentReadiness.CONVERGING -> CandidateReadiness.ACCUMULATING
            else -> CandidateReadiness.EMERGING
        }
    }

    private fun parseTaskDraft(payload: JSONObject): TaskDraft? {
        val draftNode = payload.optJSONObject("task_draft") ?: return null
        val actionCode = ActionCode.fromRaw(draftNode.optString("action_code"))
        val targetType = TargetType.fromRaw(draftNode.optString("target_type"))
        val targetKey = normalizeModelNullableString(draftNode.optString("target_key"))
        val targetLabel = normalizeModelNullableString(draftNode.optString("target_label"))
        val capabilityStack = CapabilityStack.fromRaw(draftNode.optString("capability_stack"))
        val capabilityDomain = CapabilityDomain.fromRaw(draftNode.optString("capability_domain"))
        val parsedDetailSlots = parseTaskDraftDetailSlots(draftNode, capabilityDomain)
        val capabilityId = normalizeModelNullableString(draftNode.optString("capability_id"))
        val processId = normalizeModelNullableString(draftNode.optString("process_id"))
        val reasonSummary = normalizeModelNullableString(draftNode.optString("reason_summary"))
        if (
            actionCode == null &&
            targetType == null &&
            targetKey == null &&
            targetLabel == null &&
            parsedDetailSlots.legacyDetailSlots.isEmpty() &&
            parsedDetailSlots.structuredDetailSlots.isEmpty() &&
            capabilityStack == null &&
            capabilityDomain == null &&
            capabilityId == null &&
            processId == null &&
            reasonSummary == null
        ) {
            return null
        }
        return TaskDraft(
            actionCode = actionCode,
            targetType = targetType,
            targetKey = targetKey,
            targetLabel = targetLabel,
            structuredDetailSlots = parsedDetailSlots.structuredDetailSlots,
            detailSlots = parsedDetailSlots.legacyDetailSlots,
            capabilityStack = capabilityStack,
            capabilityDomain = capabilityDomain,
            capabilityId = capabilityId,
            processId = processId,
            reasonSummary = reasonSummary
        )
    }

    private data class ParsedTaskDetailSlots(
        val structuredDetailSlots: TaskDetailSlots,
        val legacyDetailSlots: Map<DetailSlotKey, String>
    )

    private fun parseTaskDraftDetailSlots(
        node: JSONObject,
        capabilityDomain: CapabilityDomain?
    ): ParsedTaskDetailSlots {
        val objectNode = node.optJSONObject("detail_slots")
        val commonSlots = linkedMapOf<CommonDetailSlotKey, String>()
        val domainSlots = linkedMapOf<String, String>()
        objectNode?.optJSONObject("common")?.keys()?.forEach { rawKey ->
            val key = CommonDetailSlotKey.fromRaw(rawKey) ?: return@forEach
            val normalizedValue = normalizeModelNullableString(objectNode.optJSONObject("common")?.optString(rawKey)) ?: return@forEach
            if (normalizedValue.isNotBlank()) {
                commonSlots[key] = normalizedValue
            }
        }
        val allowedDomainKeys = DomainDetailSlotRegistry.allowedKeys(capabilityDomain)
        if (capabilityDomain != null) {
            objectNode?.optJSONObject("domain")?.keys()?.forEach { rawKey ->
                val normalizedKey = rawKey.trim().lowercase(Locale.US)
                val normalizedValue = normalizeModelNullableString(objectNode.optJSONObject("domain")?.optString(rawKey)) ?: return@forEach
                if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                    return@forEach
                }
                if (allowedDomainKeys.isNotEmpty() && normalizedKey !in allowedDomainKeys) {
                    return@forEach
                }
                domainSlots[normalizedKey] = normalizedValue
            }
        }
        val structuredDetailSlots = TaskDetailSlots(
            common = commonSlots,
            domain = domainSlots
        ).normalize(capabilityDomain)
        val legacyDetailSlots = linkedMapOf<DetailSlotKey, String>()
        parseLegacyTaskDraftDetailSlots(node).forEach { (key, value) ->
            legacyDetailSlots[key] = value
        }
        structuredDetailSlots.toLegacyDetailSlots(capabilityDomain).forEach { (key, value) ->
            legacyDetailSlots[key] = value
        }
        return ParsedTaskDetailSlots(
            structuredDetailSlots = structuredDetailSlots,
            legacyDetailSlots = legacyDetailSlots
        )
    }

    private fun parseLegacyTaskDraftDetailSlots(node: JSONObject): Map<DetailSlotKey, String> {
        val slots = linkedMapOf<DetailSlotKey, String>()
        val objectNode = node.optJSONObject("detail_slots")
        if (objectNode != null) {
            objectNode.keys().forEach { rawKey ->
                val key = DetailSlotKey.fromContractValue(rawKey) ?: return@forEach
                val normalizedValue = normalizeModelNullableString(objectNode.optString(rawKey)) ?: return@forEach
                if (normalizedValue.isNotBlank()) {
                    slots[key] = normalizedValue
                }
            }
        }
        val arrayNode = node.optJSONArray("detail_slots")
        if (arrayNode != null) {
            parseDetailSlots(arrayNode).forEach { slot ->
                val trimmedValue = slot.value.trim()
                if (trimmedValue.isNotBlank()) {
                    slots[slot.key] = trimmedValue
                }
            }
        }
        return slots
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                val normalizedValue = normalizeModelNullableString(value)
                if (normalizedValue != null) {
                    add(normalizedValue)
                }
            }
        }
    }

    internal fun parseTokenUsage(raw: String): TokenUsage? {
        val usageNode = runCatching {
            JsonParser.parseString(raw)
                .asJsonObject
                .getAsJsonObject("usage")
        }.getOrNull() ?: return null

        val promptTokens = usageNode.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        val completionTokens = usageNode.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        val totalTokens = usageNode.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        return buildTokenUsage(promptTokens, completionTokens, totalTokens)
    }

    private fun parseTokenUsage(node: JSONObject?): TokenUsage? {
        if (node == null) {
            return null
        }

        val promptTokens = node.optInt("prompt_tokens", -1)
        val completionTokens = node.optInt("completion_tokens", -1)
        val totalTokens = node.optInt("total_tokens", -1)
        return buildTokenUsage(promptTokens, completionTokens, totalTokens)
    }

    private fun buildTokenUsage(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int
    ): TokenUsage? {
        if (promptTokens < 0 && completionTokens < 0 && totalTokens < 0) {
            return null
        }

        val normalizedPrompt = promptTokens.coerceAtLeast(0)
        val normalizedCompletion = completionTokens.coerceAtLeast(0)
        val normalizedTotal = if (totalTokens >= 0) {
            totalTokens
        } else {
            normalizedPrompt + normalizedCompletion
        }
        return TokenUsage(
            promptTokens = normalizedPrompt,
            completionTokens = normalizedCompletion,
            totalTokens = normalizedTotal
        )
    }

}