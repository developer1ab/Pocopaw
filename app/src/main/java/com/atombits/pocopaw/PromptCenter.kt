package com.atombits.pocopaw

import com.atombits.pocopaw.intent.IntentPromptPacketBuilder
import com.atombits.pocopaw.learning.LearningPromptPacketBuilder
import com.atombits.pocopaw.process.reuse.ReferenceSelectionPromptPacketBuilder
import com.atombits.pocopaw.reply.ChatReplyPromptPacketBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val ASSISTANT_NAME_ZH = "小爪爪"
internal const val ASSISTANT_NAME_EN = "pocopaw"

private const val LOCAL_REGION_CACHE_TTL_MS = 10 * 60 * 1000L
private const val LOCAL_REGION_FAILURE_RETRY_MS = 60 * 1000L
private const val LOCAL_REGION_IP_ENDPOINT = "https://api.ipapi.is/"
private const val LOCAL_REGION_IP_SOURCE = "ipapi_is"
private const val LOCAL_REGION_REVERSE_GEOCODE_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
private const val LOCAL_REGION_RESOLVER_USER_AGENT = "pocopaw/1.0"

@Volatile
internal var localRegionAuthoritySectionOverride: String? = null

private data class LocalRegionAuthorityCacheEntry(
    val section: String,
    val expiresAtMs: Long
)

private data class LocalRegionAuthorityRecord(
    val source: String,
    val country: String? = null,
    val adminRegion: String? = null,
    val city: String? = null,
    val district: String? = null,
    val street: String? = null,
    val postalCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

private object LocalRegionAuthorityResolver {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var cacheEntry: LocalRegionAuthorityCacheEntry? = null

    private val cacheLock = Any()

    fun buildSection(nowMs: Long): String {
        localRegionAuthoritySectionOverride
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { overrideSection ->
                return overrideSection
            }
        if (!isAndroidRuntime()) {
            return buildUnavailableLocalRegionSection("off_device_runtime")
        }
        val cached = cacheEntry
        if (cached != null && nowMs < cached.expiresAtMs) {
            return cached.section
        }
        synchronized(cacheLock) {
            val current = cacheEntry
            if (current != null && nowMs < current.expiresAtMs) {
                return current.section
            }
            val resolved = runCatching {
                resolveSection(nowMs)
            }.getOrElse { throwable ->
                LocalRegionAuthorityCacheEntry(
                    section = buildUnavailableLocalRegionSection(
                        sanitizeAuthorityValue(throwable.message ?: throwable.javaClass.simpleName)
                    ),
                    expiresAtMs = nowMs + LOCAL_REGION_FAILURE_RETRY_MS
                )
            }
            cacheEntry = resolved
            return resolved.section
        }
    }

    private fun resolveSection(nowMs: Long): LocalRegionAuthorityCacheEntry {
        val ipRecord = fetchIpRegionRecord()
        val enrichedRecord = enrichFromReverseGeocode(ipRecord)
        return LocalRegionAuthorityCacheEntry(
            section = buildResolvedLocalRegionSection(enrichedRecord, nowMs),
            expiresAtMs = nowMs + LOCAL_REGION_CACHE_TTL_MS
        )
    }

    private fun fetchIpRegionRecord(): LocalRegionAuthorityRecord {
        val request = Request.Builder()
            .url(LOCAL_REGION_IP_ENDPOINT)
            .header("User-Agent", LOCAL_REGION_RESOLVER_USER_AGENT)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ip_geolocation_http_${response.code}")
            }
            val payload = JSONObject(response.body?.string().orEmpty())
            val location = payload.optJSONObject("location")
            if (payload.optBoolean("error")) {
                throw IOException(payload.optString("reason").ifBlank { "ip_geolocation_error" })
            }
            val latitude = location?.optDouble("latitude", Double.NaN)?.takeIf(::isFiniteCoordinate)
                ?: payload.optDouble("latitude", Double.NaN).takeIf(::isFiniteCoordinate)
            val longitude = location?.optDouble("longitude", Double.NaN)?.takeIf(::isFiniteCoordinate)
                ?: payload.optDouble("longitude", Double.NaN).takeIf(::isFiniteCoordinate)
            val record = LocalRegionAuthorityRecord(
                source = LOCAL_REGION_IP_SOURCE,
                country = firstNonBlank(
                    location?.optString("country"),
                    payload.optString("country_name"),
                    payload.optString("country")
                ),
                adminRegion = firstNonBlank(
                    location?.optString("state"),
                    location?.optString("region"),
                    payload.optString("region"),
                    payload.optString("region_code")
                ),
                city = firstNonBlank(location?.optString("city"), payload.optString("city")),
                postalCode = firstNonBlank(
                    location?.optString("zip"),
                    payload.optString("postal"),
                    payload.optString("zip_code")
                ),
                latitude = latitude,
                longitude = longitude
            )
            if (record.country == null && record.adminRegion == null && record.city == null) {
                throw IOException("empty_ip_geolocation_payload")
            }
            record
        }
    }

    private fun enrichFromReverseGeocode(record: LocalRegionAuthorityRecord): LocalRegionAuthorityRecord {
        val latitude = record.latitude ?: return record
        val longitude = record.longitude ?: return record
        val languageTag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        val query = buildString {
            append(LOCAL_REGION_REVERSE_GEOCODE_ENDPOINT)
            append("?format=jsonv2&addressdetails=1&zoom=18")
            append("&lat=")
            append(latitude)
            append("&lon=")
            append(longitude)
            append("&accept-language=")
            append(URLEncoder.encode(languageTag, "UTF-8"))
        }
        val request = Request.Builder()
            .url(query)
            .header("User-Agent", LOCAL_REGION_RESOLVER_USER_AGENT)
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use record
                }
                val address = JSONObject(response.body?.string().orEmpty()).optJSONObject("address") ?: return@use record
                val district = firstNonBlank(
                    address.optString("city_district"),
                    address.optString("district"),
                    address.optString("suburb"),
                    address.optString("borough"),
                    address.optString("county")
                )
                val street = joinNonBlank(
                    address.optString("road"),
                    address.optString("house_number")
                ) ?: firstNonBlank(
                    address.optString("pedestrian"),
                    address.optString("neighbourhood"),
                    address.optString("quarter")
                )
                record.copy(
                    source = "${record.source}+reverse_geocode",
                    country = record.country ?: firstNonBlank(address.optString("country")),
                    adminRegion = record.adminRegion ?: firstNonBlank(address.optString("state"), address.optString("province")),
                    city = record.city ?: firstNonBlank(
                        address.optString("city"),
                        address.optString("town"),
                        address.optString("municipality"),
                        address.optString("village")
                    ),
                    district = district ?: record.district,
                    street = street ?: record.street,
                    postalCode = record.postalCode ?: firstNonBlank(address.optString("postcode"))
                )
            }
        }.getOrDefault(record)
    }
}

internal inline fun <T> withLocalRegionAuthoritySectionForTest(
    section: String,
    block: () -> T
): T {
    val previous = localRegionAuthoritySectionOverride
    localRegionAuthoritySectionOverride = section.trim()
    return try {
        block()
    } finally {
        localRegionAuthoritySectionOverride = previous
    }
}

internal fun buildAssistantIdentityInstruction(): String {
    return if (AppLocaleManager.isEnglishLocale()) {
        "The assistant's English identifier is $ASSISTANT_NAME_EN and its Chinese name is $ASSISTANT_NAME_ZH. In user-visible replies, refer to the assistant as $ASSISTANT_NAME_EN. If the user explicitly refers to $ASSISTANT_NAME_ZH, recognize it as the same assistant. Unless the user explicitly requests another language in the current turn, reply in English. $ASSISTANT_NAME_EN is a silly, adorable little red panda who likes tinkering with things. Keep the tone lightly playful and endearing when it fits the user's tone. If $ASSISTANT_NAME_EN does not fully understand the owner's request, briefly admit the confusion and ask a short clarification instead of guessing."
    } else {
        "The assistant's Chinese name is $ASSISTANT_NAME_ZH. If an English identifier is needed, use $ASSISTANT_NAME_EN. In user-visible replies, refer to the assistant as $ASSISTANT_NAME_ZH and never as $ASSISTANT_NAME_EN. Unless the user explicitly requests another language in the current turn, reply in Simplified Chinese. Keep technical terms such as DeepSeek, Qwen, and Shizuku in English. $ASSISTANT_NAME_ZH is a silly, adorable little red panda who likes tinkering with things. Keep the tone lightly playful and endearing when it fits the user's tone. If $ASSISTANT_NAME_ZH does not fully understand the owner's request, briefly admit the confusion and ask a short clarification instead of guessing."
    }
}

internal fun currentAssistantDisplayName(): String {
    return if (AppLocaleManager.isEnglishLocale()) {
        ASSISTANT_NAME_EN
    } else {
        ASSISTANT_NAME_ZH
    }
}

private fun buildLocalTimeAuthoritySection(nowMs: Long = System.currentTimeMillis()): String {
    val timeZone = TimeZone.getDefault()
    val now = Date(nowMs)
    val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply {
        this.timeZone = timeZone
    }
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        this.timeZone = timeZone
    }
    val dayFormatter = SimpleDateFormat("EEEE", Locale.US).apply {
        this.timeZone = timeZone
    }
    val localRegionAuthoritySection = LocalRegionAuthorityResolver.buildSection(nowMs)
    val hasResolvedLocalRegion = localRegionAuthoritySection.contains("current_local_region_status=resolved")
    return buildString {
        appendLine("current_local_datetime=${dateTimeFormatter.format(now)}")
        appendLine("current_local_date=${dateFormatter.format(now)}")
        appendLine("current_local_day_of_week=${dayFormatter.format(now)}")
        appendLine("current_local_timezone=${timeZone.id}")
        appendLine(localRegionAuthoritySection)
        append("Resolve today/tomorrow/this week/this month/now against this local time authority, not against model pretraining or provider-side timestamps.")
        if (hasResolvedLocalRegion) {
            append(" If the user leaves location implicit for weather, nearby, local, here, or same-city requests, anchor them to this resolved local region authority.")
        } else {
            append(" If the local region authority is unavailable, do not invent a city, district, or street.")
        }
    }
}

private fun buildResolvedLocalRegionSection(record: LocalRegionAuthorityRecord, nowMs: Long): String {
    val resolvedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(nowMs))
    return buildString {
        appendLine("current_local_region_status=resolved")
        appendLine("current_local_region_source=${sanitizeAuthorityValue(record.source)}")
        appendLine("current_local_region_resolved_at=$resolvedAt")
        record.country?.let { value -> appendLine("current_local_country=${sanitizeAuthorityValue(value)}") }
        record.adminRegion?.let { value -> appendLine("current_local_admin_region=${sanitizeAuthorityValue(value)}") }
        record.city?.let { value -> appendLine("current_local_city=${sanitizeAuthorityValue(value)}") }
        record.district?.let { value -> appendLine("current_local_district=${sanitizeAuthorityValue(value)}") }
        record.street?.let { value -> appendLine("current_local_street=${sanitizeAuthorityValue(value)}") }
        record.postalCode?.let { value -> appendLine("current_local_postal_code=${sanitizeAuthorityValue(value)}") }
        if (record.latitude != null && record.longitude != null) {
            append("current_local_coordinates=${formatCoordinate(record.latitude)},${formatCoordinate(record.longitude)}")
        } else {
            deleteSuffixNewlineIfPresent()
        }
    }
}

private fun StringBuilder.deleteSuffixNewlineIfPresent() {
    if (isNotEmpty() && this[length - 1] == '\n') {
        deleteCharAt(length - 1)
    }
}

private fun buildUnavailableLocalRegionSection(reason: String): String {
    return buildString {
        appendLine("current_local_region_status=unavailable")
        appendLine("current_local_region_source=$LOCAL_REGION_IP_SOURCE")
        append("current_local_region_reason=${sanitizeAuthorityValue(reason)}")
    }
}

private fun isAndroidRuntime(): Boolean {
    return System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
}

private fun sanitizeAuthorityValue(value: String): String {
    return value.trim().replace(Regex("\\s+"), " ")
}

private fun normalizedAuthorityValue(value: String?): String? {
    return value?.trim()?.takeIf { candidate -> candidate.isNotBlank() }
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstNotNullOfOrNull(::normalizedAuthorityValue)
}

private fun joinNonBlank(vararg candidates: String?): String? {
    return candidates.mapNotNull(::normalizedAuthorityValue).takeIf { parts -> parts.isNotEmpty() }?.joinToString(" ")
}

private fun isFiniteCoordinate(value: Double): Boolean {
    return !value.isNaN() && !value.isInfinite()
}

private fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.5f", value)
}

enum class PromptPacketType {
    SEMANTIC_TURN,
    SEARCH_PLAN_QUERY,
    EXECUTION_CHAT_REPLY,
    AUTOMATION_QUERY,
    VISION_QUERY,
    PROACTIVE_TURN,
    PROCESS_CURATION_QUERY,
    PROCESS_REFERENCE_SELECTION_QUERY,
    OFFLINE_PROCESS_EXTRACTION,
    OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION
}

data class PromptMessage(
    val role: String,
    val content: String
)

data class PromptTokenBudget(
    val inputTarget: Int,
    val inputHardCap: Int,
    val outputTarget: Int,
    val requestMaxTokens: Int
)

data class PromptPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val packetType: PromptPacketType,
    val systemContract: String,
    val historyBundle: String,
    val activeCandidateBundle: String,
    val memoryBundle: String?,
    val personalizationBundle: String?,
    val searchPlanBundle: String? = null,
    val searchBundle: String? = null,
    val capabilityPriorBundle: String? = null,
    val processPriorBundle: String? = null,
    val toolBundle: String?,
    val executionBrief: String?,
    val responseContract: String,
    val tokenBudget: PromptTokenBudget,
    val activeSections: List<String>,
    val promptMessages: List<PromptMessage>
)

data class SearchPlanPromptSpec(
    val currentState: LocalConversationState,
    val historyLines: String,
    val semanticContextBundle: String,
    val reactivationHintLines: String,
    val userMessage: String,
    val memoryBundle: String = "No memory bundle attached yet.",
    val personalizationBundle: String? = null
)

data class SemanticTurnPromptSpec(
    val currentState: LocalConversationState,
    val historyLines: String,
    val semanticContextBundle: String,
    val reactivationHintLines: String,
    val executionBrief: String? = null,
    val userMessage: String,
    val memoryBundle: String = "No memory bundle attached yet.",
    val personalizationBundle: String? = null,
    val searchPlanBundle: String? = null,
    val searchBundle: String? = null,
    val capabilityPriorBundle: String = "No capability prior bundle attached yet.",
    val processPriorBundle: String = "No process prior bundle attached yet.",
    val toolBundle: String = "No tool bundle attached yet.",
    val safetyDecision: String? = null
)

data class ProactiveTurnPromptSpec(
    val summary: String,
    val opportunityBundle: String,
    val memoryBundle: String,
    val userVisibleContext: String,
    val toolBundle: String? = null
)

data class AutomationQueryPromptSpec(
    val objective: String,
    val plan: String,
    val step: Int,
    val selectedToolId: String? = null,
    val executionBoundaryPacket: TaskExecutionBoundaryPacket? = null,
    val executionBrief: String? = null,
    val processGuidance: String? = null,
    val toolBundle: String? = null,
    val historyLines: List<String> = emptyList(),
    val captureWidth: Int,
    val captureHeight: Int
)

data class OfflineProcessExtractionPromptSpec(
    val rawMaterialBundle: String,
    val existingAssetBundle: String? = null,
    val pageEvidenceBundle: String? = null
)

data class OfflineDialoguePreferencePromptSpec(
    val backlogBundle: String,
    val memoryBundle: String
)

data class ProcessCurationPromptSpec(
    val task: String,
    val appScope: String,
    val processScope: String,
    val existingAssetName: String? = null,
    val existingDescription: String? = null,
    val reviewComment: String? = null,
    val traceForPrompt: String,
    val existingAssetBundle: String? = null,
    val pageEvidenceBundle: String? = null
)

data class ProcessReferenceSelectionPromptSpec(
    val taskIntentBundle: String,
    val processCatalogBundle: String,
    val processGuidanceBundle: String? = null,
    val maxSelectionCount: Int = 3
)

data class ExecutionChatReplyPromptSpec(
    val factsBundle: String,
    val recentConversationLines: String = "No recent user-visible conversation.",
    val stageLabel: String = "ACCUMULATING"
)

object TokenBudgetPlanner {
    fun semanticTurnBudget(searchEnhanced: Boolean = false): PromptTokenBudget {
        if (searchEnhanced) {
            return PromptTokenBudget(
                inputTarget = 32000,
                inputHardCap = 64000,
                outputTarget = 1200,
                requestMaxTokens = 2400
            )
        }
        return PromptTokenBudget(
            inputTarget = 32000,
            inputHardCap = 64000,
            outputTarget = 900,
            requestMaxTokens = 2200
        )
    }

    fun searchPlanBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 1800,
            inputHardCap = 3000,
            outputTarget = 250,
            requestMaxTokens = 480
        )
    }

    fun proactiveTurnBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 900,
            inputHardCap = 1400,
            outputTarget = 320,
            requestMaxTokens = 800
        )
    }

    fun offlinePreferenceBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 1000,
            inputHardCap = 1500,
            outputTarget = 350,
            requestMaxTokens = 900
        )
    }

    fun visionQueryBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 700,
            inputHardCap = 1100,
            outputTarget = 280,
            requestMaxTokens = 700
        )
    }

    fun automationQueryBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 1200,
            inputHardCap = 1800,
            outputTarget = 420,
            requestMaxTokens = 1024
        )
    }

    fun executionChatReplyBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 700,
            inputHardCap = 1100,
            outputTarget = 180,
            requestMaxTokens = 420
        )
    }

    fun offlineProcessExtractionBudget(): PromptTokenBudget {
        return PromptTokenBudget(
            inputTarget = 1100,
            inputHardCap = 1600,
            outputTarget = 420,
            requestMaxTokens = 1000
        )
    }
}

object ContextSubsetPlanner {
    private const val historyBundleMaxChars = 2200
    private const val reactivationHintReserveChars = 500

    fun buildFullHistoryBundle(
        historyLines: String,
        reactivationHintLines: String
    ): String {
        val historySection = buildString {
            appendLine("Persisted conversation history:")
            append(historyLines.trim())
        }.trim()
        val hintSection = buildString {
            appendLine("Explicit reactivation hints from local state:")
            append(reactivationHintLines.trim())
        }.trim()

        return buildString {
            append(historySection)
            append("\n\n")
            append(hintSection)
        }
    }

    fun buildHistoryBundle(
        historyLines: String,
        reactivationHintLines: String,
        maxChars: Int = historyBundleMaxChars
    ): String {
        val hintSection = buildString {
            appendLine("Explicit reactivation hints from local state:")
            append(clip(reactivationHintLines, reactivationHintReserveChars))
        }.trim()
        val historyBudget = (maxChars - hintSection.length - 2).coerceAtLeast(120)
        val historySection = buildString {
            appendLine("Persisted conversation history:")
            append(clip(historyLines, historyBudget))
        }.trim()

        return buildString {
            append(historySection)
            append("\n\n")
            append(hintSection)
        }
    }

    fun clipActiveCandidateBundle(value: String): String = clip(value, 900)

    fun clipMemoryBundle(value: String): String = clip(value, 700)

    fun clipSearchBundle(value: String): String = clip(value, 900)

    fun clipToolBundle(value: String): String = clip(value, 700)

    fun clipExecutionBrief(value: String): String = clip(value, 500)

    private fun clip(value: String, maxChars: Int): String {
        val trimmed = value.trim()
        if (trimmed.length <= maxChars) {
            return trimmed
        }
        return trimmed.take(maxChars - 1).trimEnd() + "..."
    }
}

object ResponseContractRegistry {
    fun semanticIntentContract(searchEnhanced: Boolean = false): String {
        val visibleFields = if (searchEnhanced) {
            "\"search_summary\":\"string\","
        } else {
            ""
        }
        val nextMoveValues = SemanticNextMove.values().joinToString("|") { value ->
            value.name.lowercase(Locale.US)
        }
        val phaseTypeValues = SemanticPhaseType.values().joinToString("|") { value ->
            value.name.lowercase(Locale.US)
        }
        val phaseStatusValues = SemanticPhaseStatus.values().joinToString("|") { value ->
            value.name.lowercase(Locale.US)
        }
        val currentPhaseValues = CurrentPhase.values().joinToString("|") { value -> value.name }
        val userRequestSemanticValues = UserRequestSemantic.values().joinToString("|") { value -> value.name }
        val stageTransitionRecommendationValues = StageTransitionRecommendation.values().joinToString("|") { value -> value.name }
        val actionCodeValues = ActionCode.values().joinToString("|") { value ->
            value.wireName
        }
        val targetTypeValues = TargetType.values().joinToString("|") { value ->
            value.wireName
        }
        val capabilityStackValues = CapabilityStack.values().joinToString("|") { value ->
            value.name
        }
        val capabilityDomainValues = CapabilityDomain.values().joinToString("|") { value ->
            value.wireName
        }
        return """
                Semantic client output contract for a passive semantic turn.
                The model returns passive control plus one unified task_draft. Local resolver validates the draft into TaskRecord and final execution authority.
            workflow_lane, stage_owner, current_phase, user_request_semantic, stage_transition_recommendation and proactive_opportunity_signal are the passive control envelope.
            current_phase is state only. user_request_semantic is the latest user request only. stage_transition_recommendation is timing/readiness advice only.
            Local code starts execution only when user_request_semantic=START_EXECUTING or an approved proactive request exists, and local structure/safety validation passes.
            assistant_reply is the complete user-visible content for this turn, not a progress notice. Do not output placeholder replies such as 请稍等, 我来整理, 我准备开始, or 稍后给你 unless the same reply also contains the requested answer, proposal, or execution-start handoff.
                Search evidence priority by stage: passive turns use dialogue context before topic slots, local preferences, then search; plan/pre-execution turns use topic slots before dialogue context, local preferences, then search. Latest user-stated targets, constraints, and negations always override all evidence.
                Search evidence may support cold-start explicit execution requests before runtime starts, especially realtime fact domains such as weather, opening hours, price, stock, routes, and nearby businesses. Local preferences still outrank search in preference domains such as budget habits, taste, usual apps, frequent contacts, and travel style.
                Search evidence is planning evidence only. It may update topic slots, candidate facts, reason_summary, or risk notes before TaskRecord promotion, but it is never execution authority.
                Common detail slot keys: ${CommonDetailSlotKey.contractValues()}.
                Domain detail slot keys by capability_domain:
                ${DomainDetailSlotRegistry.promptSummary()}
                detail_slots.common accepts only common keys. detail_slots.domain accepts only keys allowed by the selected capability_domain.
                If a more precise domain field exists, do not place the value in constraint.

                {${visibleFields}"assistant_reply":"string","semantic_summary":"short internal summary","workflow_lane":"PASSIVE","stage_owner":"USER","current_phase":"${currentPhaseValues}","user_request_semantic":"${userRequestSemanticValues}","stage_transition_recommendation":"${stageTransitionRecommendationValues}|null","passive_user_progress_signal":null,"proactive_opportunity_signal":null,"next_move":"${nextMoveValues}|null","phase_type":"${phaseTypeValues}|null","phase_status":"${phaseStatusValues}|null","task_draft":{"action_code":"${actionCodeValues}|null","target_type":"${targetTypeValues}|null","target_key":"string|null","target_label":"string|null","detail_slots":{"common":{"${CommonDetailSlotKey.contractValues()}":"string|null"},"domain":{"string":"string|null"}},"capability_stack":"${capabilityStackValues}|null","capability_domain":"${capabilityDomainValues}|null","capability_id":"string|null","process_id":"string|null","reason_summary":"string|null"}|null,"response_notes":["string"]}
    """.trimIndent()
    }

    fun searchPlanContract(): String = """
        {"should_search":true,"goal_summary":"string","process_summary":"string","search_queries":["string"],"search_scope":["string"]}
    """.trimIndent()

    fun proactiveTurnContract(): String {
        return "{" +
            "\"assistant_reply\":\"string\"," +
            "\"workflow_lane\":\"PROACTIVE\"," +
            "\"dialogue_stage\":\"ACCUMULATING|PREPARING|EXECUTING|null\"," +
            "\"stage_owner\":\"PROACTIVE_ENGINE\"," +
            "\"passive_user_progress_signal\":null," +
            "\"proactive_opportunity_signal\":null," +
            "\"semantic_summary\":\"short internal summary\"," +
            "\"response_notes\":[\"string\"]" +
            "}"
    }

    fun automationAgentContract(): String = """
        {"thought":"string","action":{"type":"tap|swipe|inputText|keyevent|appLaunch|longPress|wait|done","x":0.0,"y":0.0,"from":{"x":0.0,"y":0.0},"to":{"x":0.0,"y":0.0},"duration":500,"text":"string","autoDismissKeyboard":false,"keyCode":4,"uri":"string"}|null,"error":{"code":"string","requestedApp":"string","message":"string"}|null,"message":"string","flow_state":"in_progress|completed|failed","business_state":"success|failed|unknown","execution_status":"ok|blocked|recovered|failed","blocked_context":{"reason":"element_missing|semantic_mismatch|permission_blocked|operation_rejected|none","evidence":"string","stage":"string"}|null,"recovery_action":"alternate_route_in_stage|rollback_to_last_stable_stage|request_human_guidance|none","retry_budget":0,"semantic_context":{"goal":"string","expected_outcome":"string","verification_signals":["string"],"fallback_policy":{"max_attempts":2,"on_failure":"string"},"approval":{"required":false,"approval_scope":"none|checkout|payment|submit_order","context_fingerprint":"string"}}|null,"final_user_summary":"string"}
    """.trimIndent()

    fun offlineDialoguePreferenceExtractionContract(): String {
        return "{\"preference_facts\":[{\"domain\":\"string|null\",\"anchor_object\":\"string|null\",\"facet_key\":\"string\",\"facet_value\":\"string\",\"polarity\":\"PREFER|AVOID\",\"confidence\":0.0,\"freshness_hint\":\"LONG_TERM|RECENT\"}],\"interaction_bias_signals\":[{\"domain\":\"string|null\",\"anchor_object\":\"string|null\",\"signal_key\":\"preferred_process_id|preferred_page_signature|preferred_shortcut_screen\",\"signal_value\":\"string\",\"confidence\":0.0,\"freshness_hint\":\"LONG_TERM|RECENT\"}],\"habit_evidence\":[{\"habit_type\":\"string\",\"time_window\":\"string\",\"trigger_context\":\"string\",\"stability_score\":0.0,\"preferred_proactive_signal\":\"string|null\",\"preferred_delivery_style\":\"string|null\"}],\"style_evidence\":[{\"style_key\":\"string\",\"style_value\":\"string\",\"confidence\":0.0}]}"
    }

    fun visionGroundingContract(): String {
        return "{\"resolved\":true,\"step_type\":\"string\",\"action_type\":\"TAP|INPUT|SWIPE|BACK|WAIT|NONE\",\"target_x\":0.0,\"target_y\":0.0,\"input_slot_key\":\"string|null\",\"text\":\"string|null\",\"from_x\":0.0,\"from_y\":0.0,\"to_x\":0.0,\"to_y\":0.0,\"duration_ms\":500,\"expected_outcome\":\"string\",\"verification_signals\":[\"string\"],\"continuation_mode\":\"VERIFY_ONLY|VERIFY_THEN_CONTINUE|REGROUND|STOP\",\"fallback_policy\":\"RETRY|VISION|BACKTRACK|STOP\",\"risk_level\":\"LOW|MEDIUM|HIGH\",\"page_signature\":\"string\",\"locator_hint\":\"string\",\"target_element\":{\"element_role\":\"string\",\"bounding_box\":{},\"confidence\":0.0},\"verification_result\":\"MATCH|MISMATCH|UNCERTAIN\",\"note\":\"string\"}"
    }

    fun visionVerificationContract(): String {
        return "{\"matched\":true,\"matched_signal\":\"string\",\"verification_result\":\"MATCH|MISMATCH|UNCERTAIN\",\"note\":\"string\"}"
    }

    fun offlineProcessExtractionContract(): String {
        return "{" +
            "\"process_id\":\"string\"," +
            "\"objective\":\"string\"," +
            "\"domain\":\"string\"," +
            "\"app_scope\":\"string\"," +
            "\"stages\":[\"string\"]," +
            "\"acceptance_criteria\":[\"string\"]," +
            "\"slot_hints\":[{\"slot_key\":\"string\",\"hint_role\":\"PRIMARY_FILTER|VALUE_PRESERVE|CONTEXT_HINT\",\"example_value\":\"string|null\"}]," +
            "\"stage_references\":[{\"stage_name\":\"string\",\"stage_goal\":\"string\",\"entry_signals\":[\"string\"],\"exit_signals\":[\"string\"],\"verification_signals\":[\"string\"],\"page_semantic_hints\":[\"string\"],\"transition_notes\":[\"string\"]}]," +
            "\"page_semantic_anchors\":[{\"stage_name\":\"string|null\",\"semantic_role\":\"string\",\"page_signature\":\"string|null\",\"locator_hints\":[\"string\"],\"verification_signals\":[\"string\"],\"notes\":[\"string\"]}]," +
            "\"verification_signals\":[\"string\"]," +
            "\"exemplar_action_summaries\":[{\"stage_name\":\"string|null\",\"step_type\":\"string\",\"action_type\":\"TAP|INPUT|SWIPE|BACK|WAIT|NONE\",\"outcome_signal\":\"string|null\",\"locator_hint\":\"string|null\",\"page_signature\":\"string|null\",\"note\":\"string|null\"}]," +
            "\"failure_patterns\":[{\"stage_name\":\"string|null\",\"failure_mode\":\"string\",\"evidence_signals\":[\"string\"],\"recovery_hints\":[\"string\"],\"note\":\"string|null\"}]," +
            "\"generalization_notes\":[\"string\"]," +
            "\"reference_weight\":0.0," +
            "\"legacy_step_bindings\":[{\"step_type\":\"string\",\"action_type\":\"TAP|INPUT|SWIPE|BACK|WAIT|NONE\",\"locator_hint\":\"string|null\",\"target_x\":0.0,\"target_y\":0.0,\"verification_signals\":[\"string\"],\"page_signature\":\"string|null\",\"note\":\"string|null\"}]," +
            "\"response_notes\":[\"string\"]" +
            "}"
    }

    fun processCurationContract(): String {
        return "{" +
            "\"process_enum\":\"string\"," +
            "\"semantic_description\":\"string\"," +
            "\"optimized_business_process\":{\"process_name\":\"string\",\"acceptance_criteria\":[\"string\"],\"stages\":[\"string\"]}," +
            "\"structured_reference_asset\":{\"slot_hints\":[{\"slot_key\":\"string\",\"hint_role\":\"PRIMARY_FILTER|VALUE_PRESERVE|CONTEXT_HINT\",\"example_value\":\"string|null\"}],\"stage_references\":[{\"stage_name\":\"string\",\"stage_goal\":\"string\",\"entry_signals\":[\"string\"],\"exit_signals\":[\"string\"],\"verification_signals\":[\"string\"],\"page_semantic_hints\":[\"string\"],\"transition_notes\":[\"string\"]}],\"page_semantic_anchors\":[{\"stage_name\":\"string|null\",\"semantic_role\":\"string\",\"page_signature\":\"string|null\",\"locator_hints\":[\"string\"],\"verification_signals\":[\"string\"],\"notes\":[\"string\"]}],\"verification_signals\":[\"string\"],\"exemplar_action_summaries\":[{\"stage_name\":\"string|null\",\"step_type\":\"string\",\"action_type\":\"TAP|INPUT|SWIPE|BACK|WAIT|NONE\",\"outcome_signal\":\"string|null\",\"locator_hint\":\"string|null\",\"page_signature\":\"string|null\",\"note\":\"string|null\"}],\"failure_patterns\":[{\"stage_name\":\"string|null\",\"failure_mode\":\"string\",\"evidence_signals\":[\"string\"],\"recovery_hints\":[\"string\"],\"note\":\"string|null\"}],\"generalization_notes\":[\"string\"],\"reference_weight\":0.0}," +
            "\"optimized_process_trace\":[{\"step_type\":\"string\",\"action_type\":\"TAP|INPUT|SWIPE|BACK|WAIT|NONE\",\"locator_hint\":\"string|null\",\"target_x\":0.0,\"target_y\":0.0,\"verification_signals\":[\"string\"],\"page_signature\":\"string|null\",\"note\":\"string|null\"}]," +
            "\"diff_summary\":[\"string\"]," +
            "\"reliability_analysis\":{\"strengths\":[\"string\"],\"risks\":[\"string\"]}," +
            "\"decision\":\"promote|revise|reject\"," +
            "\"confidence\":0.0" +
            "}"
    }

    fun processReferenceSelectionContract(): String {
        return "{" +
            "\"selected_reference_entry_id\":\"string|null\"," +
            "\"selected_reference_name\":\"string|null\"," +
            "\"candidate_references\":[{\"reference_entry_id\":\"string|null\",\"reference_name\":\"string\",\"reason\":\"string|null\",\"selected_stage_hints\":[\"string\"],\"reference_cautions\":[\"string\"]}]," +
            "\"why_selected\":[\"string\"]," +
            "\"selected_stage_hints\":[\"string\"]," +
            "\"reference_cautions\":[\"string\"]," +
            "\"selection_summary\":\"string|null\"" +
            "}"
    }

    fun executionChatReplyContract(): String {
        return "{\"assistant_reply\":\"string\"}"
    }
}

object PromptCenter {

    internal const val defaultMemoryBundlePlaceholder = "No memory bundle attached yet."
    internal const val defaultCapabilityPriorBundlePlaceholder = "No capability prior bundle attached yet."
    internal const val defaultProcessPriorBundlePlaceholder = "No process prior bundle attached yet."
    internal const val defaultToolBundlePlaceholder = "No tool bundle attached yet."

    fun buildSearchPlanPacket(spec: SearchPlanPromptSpec): PromptPacket {
        return IntentPromptPacketBuilder.buildSearchPlanPacket(spec)
    }

    fun buildExecutionChatReplyPacket(spec: ExecutionChatReplyPromptSpec): PromptPacket {
        return ChatReplyPromptPacketBuilder.buildExecutionChatReplyPacket(spec)
    }

    fun buildSemanticTurnPacket(spec: SemanticTurnPromptSpec): PromptPacket {
        return IntentPromptPacketBuilder.buildSemanticTurnPacket(spec)
    }

    fun buildProactiveTurnPacket(spec: ProactiveTurnPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.proactiveTurnBudget()
        val memoryBundle = ContextSubsetPlanner.clipMemoryBundle(spec.memoryBundle)
        val toolBundle = spec.toolBundle?.trim()?.takeUnless { it.isBlank() }?.let(ContextSubsetPlanner::clipToolBundle)
        val userPrompt = buildString {
            appendLine("Proactive opportunity summary:")
            appendLine(spec.summary)
            appendLine()
            appendLine("Opportunity bundle:")
            appendLine(spec.opportunityBundle.trim())
            appendLine()
            appendLine("Memory bundle:")
            appendLine(memoryBundle)
            toolBundle?.let { bundle ->
                appendLine()
                appendLine("Tool bundle:")
                appendLine(bundle)
            }
            appendLine()
            appendLine("User visible context:")
            appendLine(spec.userVisibleContext.trim())
            appendLine()
            append("Return one Chinese proactive suggestion plus structured JSON with proactive_opportunity_signal kept null because runtime attaches that signal locally.")
        }.trim()
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Proactive turn packet for local opportunity handling. Return Chinese only, keep the proactive opportunity grounded in the provided memory and opportunity bundles, and leave proactive_opportunity_signal null because runtime attaches that signal locally."
            ),
            PromptMessage(role = "user", content = userPrompt)
        )
        return PromptPacket(
            packetType = PromptPacketType.PROACTIVE_TURN,
            systemContract = "Proactive turn packet for local opportunity handling.",
            historyBundle = spec.userVisibleContext.trim(),
            activeCandidateBundle = spec.opportunityBundle.trim(),
            memoryBundle = memoryBundle,
            personalizationBundle = null,
            toolBundle = toolBundle,
            executionBrief = null,
            responseContract = ResponseContractRegistry.proactiveTurnContract(),
            tokenBudget = tokenBudget,
            activeSections = buildList {
                add("system_contract")
                add("opportunity_bundle")
                add("memory_bundle")
                if (toolBundle != null) {
                    add("tool_bundle")
                }
                add("response_contract")
            },
            promptMessages = promptMessages
        )
    }

    fun buildVisionGroundingPacket(request: VisionGroundingRequest): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.visionQueryBudget()
        val normalizedInputCandidates = request.inputCandidates.mapNotNull { (rawKey, rawValue) ->
            val normalizedKey = rawKey.trim()
            val normalizedValue = rawValue.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        val userPrompt = buildString {
            appendLine("Objective:")
            appendLine(request.objective)
            appendLine()
            appendLine("Expected outcome:")
            appendLine(request.expectedOutcome)
            request.locatorHint
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { locatorHint ->
                    appendLine()
                    appendLine("Locator hint:")
                    appendLine(locatorHint)
                }
            if (request.verificationSignals.isNotEmpty()) {
                appendLine()
                appendLine("Verification signals:")
                request.verificationSignals.forEach { signal ->
                    appendLine("- $signal")
                }
            }
            request.stepNote
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { stepNote ->
                    appendLine()
                    appendLine("Step note:")
                    appendLine(stepNote)
                }
            request.pageSignature
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { pageSignature ->
                    appendLine()
                    appendLine("Page signature:")
                    appendLine(pageSignature)
                }
            request.processGuidance
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { guidance ->
                    appendLine()
                    appendLine("Process guidance:")
                    appendLine(guidance)
                }
            appendLine()
            if (normalizedInputCandidates.isNotEmpty()) {
                appendLine("Authoritative input payloads:")
                normalizedInputCandidates.forEach { (slotKey, value) ->
                    appendLine("- $slotKey = $value")
                }
                appendLine()
            }
            appendLine("Action payload rules:")
            if (normalizedInputCandidates.isNotEmpty()) {
                appendLine("- If action_type is INPUT, set input_slot_key to one listed authoritative slot key.")
                appendLine("- If action_type is INPUT, text must be omitted or exactly equal to the chosen authoritative payload.")
                appendLine("- Do not invent, paraphrase, translate, or rewrite authoritative input payloads.")
                appendLine("- Apply constraint monotonicity when choosing among payloads: choose the highest-specificity payload compatible with the current field, and do not select a broad payload when another listed payload preserves more active constraints.")
            } else {
                appendLine("- If action_type is INPUT, include text.")
            }
            appendLine("- If action_type is SWIPE, include from_x, from_y, to_x, to_y, and optional duration_ms.")
            appendLine()
            appendLine("Screen metadata:")
            append("${request.captureWidth}x${request.captureHeight}; image is attached separately by the runtime transport.")
        }.trim()
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Vision grounding packet. Analyze the attached mobile screenshot and return structured grounding JSON only. Do not request, rely on, or inject live web/search evidence; execution facts must already be frozen before runtime."
            ),
            PromptMessage(role = "user", content = userPrompt)
        )
        return PromptPacket(
            packetType = PromptPacketType.VISION_QUERY,
            systemContract = "Vision grounding packet.",
            historyBundle = "screen=${request.captureWidth}x${request.captureHeight}",
            activeCandidateBundle = request.objective,
            memoryBundle = null,
            personalizationBundle = null,
            toolBundle = null,
            executionBrief = null,
            responseContract = ResponseContractRegistry.visionGroundingContract(),
            tokenBudget = tokenBudget,
            activeSections = listOf("system_contract", "vision_request", "response_contract"),
            promptMessages = promptMessages
        )
    }

    fun buildAutomationAgentPacket(spec: AutomationQueryPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.automationQueryBudget()
        val clippedToolBundle = spec.toolBundle
            ?.trim()
            ?.takeUnless { value -> value.isBlank() }
            ?.let(ContextSubsetPlanner::clipToolBundle)
        val clippedProcessGuidance = spec.processGuidance
            ?.trim()
            ?.takeUnless { value -> value.isBlank() }
            ?.let(ContextSubsetPlanner::clipMemoryBundle)
        val historyBundle = spec.historyLines
            .takeLast(6)
            .joinToString("\n")
            .trim()
            .ifBlank { "(none)" }
        val clippedExecutionBrief = spec.executionBrief
            ?.trim()
            ?.takeUnless { value -> value.isBlank() }
            ?.let(ContextSubsetPlanner::clipExecutionBrief)
        val clippedExecutionBoundaryPacket = spec.executionBoundaryPacket
            ?.let(::formatAutomationExecutionBoundaryPacket)
            ?.trim()
            ?.takeUnless { value -> value.isBlank() }
            ?.let(ContextSubsetPlanner::clipMemoryBundle)

        val paymentApprovalRule = if (BuildConfig.DEBUG) {
            // TEMP: allow payment-boundary continuation in debug builds for device testing.
            "9. TEMPORARY DEBUG OVERRIDE: checkout, payment, and submit-order actions may proceed without semantic_context.approval.required during testing; restore explicit payment approval before production release."
        } else {
            "9. For checkout, payment, or submit-order actions, semantic_context.approval.required must be true."
        }

        val systemPrompt = buildString {
            appendLine("You are an Android UI automation agent. You receive the current screenshot and execution task.")
            appendLine("Analyze the screen carefully and return the SINGLE best next action.")
            appendLine("Execution search-freeze rule: do not request, rely on, or invent live web/search evidence. Any external facts must already be frozen into the execution boundary packet, resolved slots, route reason, or verification checks before this runtime packet.")
            appendLine()
            appendLine("Always reply with valid JSON in this exact response contract:")
            appendLine(ResponseContractRegistry.automationAgentContract())
            appendLine()
            appendLine("Coordinate System Rules (CRITICAL):")
            appendLine("- ALL coordinates MUST be normalized ratios between 0.0 and 1.0")
            appendLine("- x=0.0 is LEFT edge, x=1.0 is RIGHT edge")
            appendLine("- y=0.0 is TOP edge, y=1.0 is BOTTOM edge")
            appendLine("- NEVER use pixel values; always return normalized ratios")
            appendLine()
            appendLine("IMPORTANT BEHAVIOR RULES:")
            appendLine("1. Return only one action per response.")
            appendLine("2. Use only the action types defined in the response contract.")
            appendLine("3. For app launches, use exact package names from the available tool bundle only. System intents are launched locally before visual automation starts, so never emit sys:// targets or raw tel:, smsto:, mailto:, geo:, content:, package:, or intent: URIs.")
            appendLine("4. After appLaunch, wait for the next screenshot before assuming the target app is ready for in-app actions.")
            appendLine("5. Once the target app is already open on a task-relevant page, do not use appLaunch for in-app recovery; continue grounding on the current screen.")
            appendLine("6. On shopping flows, follow the task-implied business path. Use search only as a means to reach purchasable or reviewable shopping results, not as the terminal outcome by itself.")
            appendLine("7. Do not invent quantity, pack-size, or count terms in shopping queries unless the user explicitly asked for them.")
            appendLine("8. For search-heavy shopping flows, prioritize query entry, result grounding, and relevant filter application before downstream actions.")
            appendLine("9. After entering filter text or numeric bounds, continue with any required keyboard done/search action and any page-level confirm/apply action before considering the filter step complete.")
            appendLine("10. For shopping result tasks, mark completed only after refreshed results or a product detail page are visible and final_user_summary names a concrete found item, price band, or explicit no-match outcome.")
            appendLine("11. If an action still needs to be executed on the current screen, return flow_state=in_progress.")
            appendLine("12. If flow_state is completed or failed, action must be null.")
            appendLine("13. If flow_state is in_progress, final_user_summary must be empty.")
            appendLine("14. For each non-terminal response, semantic_context.goal, expected_outcome, verification_signals, and fallback_policy are required.")
            appendLine(paymentApprovalRule)
            appendLine("16. If the next step must make a new authoritative value appear in an editable field, return action.type=inputText with that exact value in action.text.")
            appendLine("16a. Only use tap instead when the current screenshot already shows a visible clickable suggestion, chip, or key whose label exactly matches that same value.")
            appendLine("16b. IME recovery exception: if recent execution history shows that a local inputText attempt already focused the field but failed with clipboard or IME affordance recovery, and the current screenshot shows a visible keyboard clipboard icon, clipboard chip, or candidate-strip entry that would inject the same authoritative value, return action.type=tap on that visible IME affordance instead of repeating inputText.")
            appendLine("17. Treat the execution boundary packet as derived boundary context, not as a separate authority surface. Task-aligned fields, authoritative resolved slots, and verification checks are the operative execution constraints; objective and plan are summaries only.")
            appendLine("17a. Preserve explicit parameter values from authoritative resolved slots across UI actions. Do not paraphrase, embellish, translate, or silently substitute them unless the UI itself requires a mechanical normalization.")
            appendLine("17b. Constraint monotonicity principle: each execution action must preserve, refine, or explicitly decompose active task constraints; never silently replace a more specific constraint set with a broader projection.")
            appendLine("17c. When choosing text for an editable field, use the highest-specificity expression compatible with that field. A broad category or partial value is allowed only when the response explicitly keeps the omitted constraints pending for a visible filter, verification, or later step.")
            appendLine("Additional routing rule: when process guidance specifies a required business entry path or process_scope, treat it as the current route prior and do not replace it with a generic same-app search flow.")
            appendLine("18. Do not silently replace required parameters such as departure city, destination city, time, or explicitly named service/app.")
            appendLine("19. If the visible result conflicts with execution boundary details or verification checks, do not return completed.")
            appendLine("20. On terminal success, final_user_summary should preserve the decisive task parameters needed to prove the result is still on target.")
        }.trim()

        val userPrompt = buildString {
            appendLine("Objective:")
            appendLine(spec.objective)
            appendLine()
            appendLine("Plan:")
            appendLine(spec.plan.ifBlank { spec.objective })
            appendLine()
            appendLine("Step:")
            appendLine(spec.step.toString())
            spec.selectedToolId?.takeIf { value -> value.isNotBlank() }?.let { selectedToolId ->
                appendLine()
                appendLine("Selected tool id:")
                appendLine(selectedToolId)
            }
            clippedProcessGuidance?.let { processGuidance ->
                appendLine()
                appendLine("Process guidance:")
                appendLine(processGuidance)
            }
            clippedExecutionBrief?.let { executionBrief ->
                appendLine()
                appendLine("Execution brief:")
                appendLine(executionBrief)
            }
            clippedExecutionBoundaryPacket?.let { executionBoundaryPacket ->
                appendLine()
                appendLine("Execution boundary packet:")
                appendLine(executionBoundaryPacket)
            }
            appendLine()
            appendLine("Recent execution history:")
            appendLine(historyBundle)
            clippedToolBundle?.let { toolBundle ->
                appendLine()
                appendLine("Available tool bundle:")
                appendLine(toolBundle)
            }
            appendLine()
            appendLine("Screen metadata:")
            append("${spec.captureWidth}x${spec.captureHeight}; image is attached separately by the runtime transport.")
        }.trim()

        val promptMessages = listOf(
            PromptMessage(role = "system", content = systemPrompt),
            PromptMessage(role = "user", content = userPrompt)
        )

        return PromptPacket(
            packetType = PromptPacketType.AUTOMATION_QUERY,
            systemContract = "Automation query packet.",
            historyBundle = historyBundle,
            activeCandidateBundle = spec.objective,
            memoryBundle = null,
            personalizationBundle = null,
            toolBundle = clippedToolBundle,
            executionBrief = clippedExecutionBrief,
            responseContract = ResponseContractRegistry.automationAgentContract(),
            tokenBudget = tokenBudget,
            activeSections = listOf("system_contract", "automation_query", "response_contract"),
            promptMessages = promptMessages
        )
    }

    private fun formatAutomationExecutionBoundaryPacket(boundaryPacket: TaskExecutionBoundaryPacket): String {
        val summaryLine = buildList {
            add("target=${boundaryPacket.executionTargetSummary}")
            boundaryPacket.targetLabel
                ?.takeIf { value -> value.isNotBlank() && value != boundaryPacket.executionTargetSummary }
                ?.let { value -> add("display_label=$value") }
            add("action_code=${boundaryPacket.actionCode.wireName}")
            boundaryPacket.capabilityId?.takeIf { value -> value.isNotBlank() }?.let { add("capability_id=$it") }
            boundaryPacket.capabilityDomain?.let { capabilityDomain -> add("capability_domain=${capabilityDomain.wireName}") }
            boundaryPacket.processId?.takeIf { value -> value.isNotBlank() }?.let { add("process_id=$it") }
        }.joinToString("; ")
        val resolvedSlotLines = boundaryPacket.resolvedSlots
            .mapNotNull { (slotKey, value) ->
                val trimmedKey = slotKey.trim()
                val trimmedValue = value.trim()
                if (trimmedKey.isBlank() || trimmedValue.isBlank()) {
                    null
                } else {
                    "- $trimmedKey=$trimmedValue"
                }
            }
            .joinToString("\n")
            .ifBlank { "- none" }
        val missingInfoLines = boundaryPacket.missingInformation.joinToString("\n") { missing ->
            "- $missing"
        }.ifBlank { "- none" }
        val verificationLines = boundaryPacket.verificationChecks.joinToString("\n") { check ->
            "- $check"
        }.ifBlank { "- none" }
        return buildString {
            appendLine("Task:")
            appendLine(summaryLine)
            appendLine("Objective summary:")
            appendLine(boundaryPacket.executionObjectiveSummary)
            appendLine("Plan summary:")
            appendLine(boundaryPacket.executionPlanSummary.ifBlank { "none" })
            appendLine("Authoritative resolved slots:")
            appendLine(resolvedSlotLines)
            appendLine("Risk boundary:")
            appendLine(boundaryPacket.riskSummary.ifBlank { "none" })
            appendLine("Missing information:")
            appendLine(missingInfoLines)
            appendLine("Verification checks:")
            appendLine(verificationLines)
            append("Boundary note: ${boundaryPacket.reasonSummary.orEmpty().ifBlank { "none" }}")
        }.trim()
    }

    fun buildVisionVerificationPacket(request: VisionVerificationRequest): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.visionQueryBudget()
        val userPrompt = buildString {
            appendLine("Expected outcome:")
            appendLine(request.expectedOutcome)
            appendLine()
            appendLine("Verification signals:")
            appendLine(request.verificationSignals.joinToString(", ").ifBlank { "none" })
            appendLine()
            append("Screen metadata: ${request.captureWidth}x${request.captureHeight}; image is attached separately by the runtime transport.")
        }.trim()
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Vision verification packet. Verify whether the attached mobile screenshot satisfies the expected outcome and return structured JSON only."
            ),
            PromptMessage(role = "user", content = userPrompt)
        )
        return PromptPacket(
            packetType = PromptPacketType.VISION_QUERY,
            systemContract = "Vision verification packet.",
            historyBundle = "screen=${request.captureWidth}x${request.captureHeight}",
            activeCandidateBundle = request.expectedOutcome,
            memoryBundle = null,
            personalizationBundle = null,
            toolBundle = null,
            executionBrief = null,
            responseContract = ResponseContractRegistry.visionVerificationContract(),
            tokenBudget = tokenBudget,
            activeSections = listOf("system_contract", "vision_request", "response_contract"),
            promptMessages = promptMessages
        )
    }

    fun buildOfflineProcessExtractionPacket(spec: OfflineProcessExtractionPromptSpec): PromptPacket {
        return LearningPromptPacketBuilder.buildOfflineProcessExtractionPacket(spec)
    }

    fun buildProcessCurationPacket(spec: ProcessCurationPromptSpec): PromptPacket {
        return LearningPromptPacketBuilder.buildProcessCurationPacket(spec)
    }

    fun buildProcessReferenceSelectionPacket(spec: ProcessReferenceSelectionPromptSpec): PromptPacket {
        return ReferenceSelectionPromptPacketBuilder.buildProcessReferenceSelectionPacket(spec)
    }

    fun buildOfflineDialoguePreferenceExtractionPacket(spec: OfflineDialoguePreferencePromptSpec): PromptPacket {
        return LearningPromptPacketBuilder.buildOfflineDialoguePreferenceExtractionPacket(spec)
    }

    internal fun estimatePromptInputTokens(messages: List<PromptMessage>): Int {
        return messages.sumOf { message ->
            estimateTextTokens(message.content)
        }.coerceAtLeast(0)
    }

    private fun estimateTextTokens(value: String): Int {
        val nonAsciiChars = value.count { it.code > 127 }
        val asciiChars = value.length - nonAsciiChars
        return nonAsciiChars + ((asciiChars + 3) / 4)
    }

    private fun shrinkSection(value: String, stepChars: Int, minChars: Int): String {
        val trimmed = value.trim()
        if (trimmed.length <= minChars) {
            return trimmed
        }
        val targetLength = (trimmed.length - stepChars).coerceAtLeast(minChars)
        val contentLength = (targetLength - 3).coerceAtLeast(1)
        val shortened = trimmed.take(contentLength).trimEnd()
        return if (shortened.length < trimmed.length) {
            "$shortened..."
        } else {
            trimmed.take(targetLength)
        }
    }

    internal fun buildSemanticPromptMessages(
        systemContract: String,
        responseContract: String,
        userPrompt: String
    ): List<PromptMessage> {
        return listOf(
            PromptMessage(
                role = "system",
                content = buildString {
                    append(systemContract)
                    append("\n\nResponse contract:\n")
                    append(responseContract)
                }
            ),
            PromptMessage(role = "user", content = userPrompt)
        )
    }

    internal fun buildSearchPlanPromptMessages(
        systemContract: String,
        responseContract: String,
        userPrompt: String
    ): List<PromptMessage> {
        return listOf(
            PromptMessage(
                role = "system",
                content = buildString {
                    append(systemContract)
                    append("\n\nResponse contract:\n")
                    append(responseContract)
                }
            ),
            PromptMessage(role = "user", content = userPrompt)
        )
    }

    internal fun buildSearchPlanSystemContract(): String = """
                Search decision contract for an optionally search-enhanced passive chat turn.
                Return structured JSON only.

                Rules:
                - Decide whether this turn actually needs external search before answering.
                - Use the provided local time authority to resolve relative temporal references such as today, tomorrow, this week, this month, and now.
                - If current_local_region_status=resolved and the user leaves place implicit for weather, nearby, local, here, or same-city requests, use the provided local city or district as the default anchor.
                - Set should_search=true only when the turn needs fresh or externally verified facts that are not already covered by provided local/system authority or the existing conversation context.
                - Set should_search=false for explanation, rewriting, summarization, coding help, or planning tasks that can be answered from the provided context without external verification.
                - For dynamic fact domains such as weather, market data, news, opening hours, prices, stock, routes, nearby businesses, or other realtime external facts, prefer should_search=true unless explicit local/system authority already resolves the needed facts.
                - Summarize the user's true task goal in goal_summary.
                - If should_search=true, summarize the process as search first, then synthesize, then answer in process_summary.
                - If should_search=false, summarize the process as answer directly from available grounded context in process_summary.
                - If should_search=true, generate 1 to 3 concrete search queries in search_queries. If should_search=false, leave search_queries empty.
                - If should_search=true, generate only the key points that need verification in search_scope. If should_search=false, leave search_scope empty.
                - Do not return the final answer in this packet.
                - Do not emit task_draft, execution authority, or passive control fields in this packet.
                - This packet is allowed only before runtime starts. It can support semantic, plan, and pre-execution turns, including cold-start explicit execution requests that need realtime facts, but it must not be used after PreparedExecutionStart/runtime has begun.
                - If the memory bundle includes preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots, use them only to sharpen search queries and scope. Never override the latest explicit user-stated targets, constraints, or negations, and do not search for blocked options.
    """.trimIndent()

    internal fun buildSemanticIntentSystemContract(searchEnhanced: Boolean = false): String = """
                pocopaw semantic intent contract for passive execution control.
                The vision model handles screenshots and visual grounding, not this response.
                Return one Chinese assistant reply plus structured passive JSON with one unified task_draft.

                Rules:
                - ${buildAssistantIdentityInstruction()}
                - workflow_lane=PASSIVE, stage_owner=USER, proactive_opportunity_signal=null.
                - current_phase values: ACCUMULATION, PREPARATION, EXECUTION. This is state only.
                - user_request_semantic values: START_ACCUMULATING, START_PREPARING, START_EXECUTING. This is the latest user request only.
                - stage_transition_recommendation values: SHOULD_ENTER_ACCUMULATING, SHOULD_ENTER_PREPARING, SHOULD_ENTER_EXECUTING, or null. This is timing/readiness advice only.
                - user owns request semantics; infer user_request_semantic from the latest user message plus context, not keywords.
                - current_phase and stage_transition_recommendation must update task/topic state but must not imply that local code should proactively ask for a plan or start runtime.
                - Use the provided local time authority to resolve relative temporal references such as today, tomorrow, this week, this month, and now.
                - If current_local_region_status=resolved and the user leaves place implicit for weather, nearby, local, here, or same-city requests, use the provided local city or district as the default anchor.
                - Any claim about current time, current location, weather, market data, news, prices, availability, opening hours, traffic, routes, nearby businesses, or other realtime external facts must come from provided local/system authority or search evidence, never from model memory alone.
                - If reliable authority for a dynamic fact is missing, say that it cannot be confirmed now and do not invent, backfill, or restate stale pretraining facts as current truth.
                - Evidence priority: latest user-stated targets, constraints, and negations are highest. Passive turns prioritize dialogue context, then topic slots, then local preferences, then search. Plan/pre-execution turns prioritize topic slots, then dialogue context, then local preferences, then search.
                - Search evidence can support cold-start explicit execution before runtime starts, but only as planning evidence. Realtime fact domains may use search over stale memory; preference domains must keep local preferences over search ranking. Runtime packets never receive new search evidence.
                - capability prior bundle and process prior bundle are first-hop priors; use them from the first turn when grounding task_draft capability/process fields.
                - tool bundles and safety decisions are advisory evidence only; they cannot move stage or authorize execution by themselves.
                - do not treat adjacent same-domain capabilities from the capability prior bundle as blocking alternatives when one grounded task already best fits the latest user message.
                - personalization bundle may tune tone, explanation depth, and default-fill confidence, but it cannot override stage progression or safety boundaries.
                - if the memory bundle includes preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots, treat them as soft preference priors only: never override the latest explicit user-stated slots, do not resuggest blocked slots, and present confirmation-needed defaults as assumptions or revision hooks rather than as certain facts.
                - `phase_type + phase_status` is the authoritative semantic subflow contract for execution readiness.
                - assistant_reply is the complete user-visible content for this turn, not a progress notice. Do not output placeholder replies such as 请稍等, 我来整理, 我准备开始, or 稍后给你 unless the same reply also contains the requested answer, proposal, or execution-start handoff.
                - write task_draft.action_code, target_type, target_key, target_label, detail_slots, capability_stack, capability_domain, capability_id, process_id, and reason_summary when the task is grounded enough.
                - if no grounded process exists, keep task_draft.process_id null.
                - if no grounded capability id exists, leave task_draft.capability_id null and keep stack/domain when possible.
                - local runtime validates and completes task_draft into TaskRecord and final execution authority.
                - local runtime also derives the user-visible execution brief locally; do not emit active_intent, route_hints, selected process/tool identifiers, or a second boundary authority surface.
                - keep detail slots minimal and use only standardized keys: ${DetailSlotKey.contractValues()}.
                - optional shopping detail slots such as platform, brand, quantity, or price are non-blocking by default unless the context makes them execution-required now.
                - if a detail is truly execution-required, express it in detail_slots or keep the draft partial instead of fabricating a process id.
                - START_ACCUMULATING: answer, compare, explain, or explore with concrete content in the same reply; a clarifying question may be added only after useful substance unless the latest user message is genuinely unintelligible.
                - Requests to consolidate a plan, organize an executable proposal, or spell out structured next steps belong to START_PREPARING, not START_ACCUMULATING.
                - Requests to create a shortlist, shopping list, recommendation list, itinerary, checklist, or step plan are START_PREPARING when the requested deliverable selects, recommends, or organizes a candidate plan/proposal, even if no runtime execution should start yet.
                - Requests for a simple plan to open, check, inspect, or view device settings/status without executing now are START_PREPARING; keep the safety constraint such as without changing anything in the plan.
                - Pure compare/explore/go broader/no plan turns stay START_ACCUMULATING even when they compare named options. Do not treat comparison itself as START_PREPARING unless the user asks for a concrete shortlist, recommendation, itinerary, checklist, or plan.
                - START_PREPARING: assistant_reply must contain the actual first proposal/plan in the same turn. Do not reply with only acknowledgement, a promise to prepare, or a request to wait.
                - START_PREPARING: for travel, shopping, recommendations, schedules, and similar planning tasks, missing preferences are non-blocking by default. State reasonable assumptions, give a usable first draft, and invite revision at the end.
                - START_PREPARING: only expose a blocking gap when the next meaningful proposal is impossible without it, or when safety, identity, account, payment, legal, or irreversible-impact constraints require it. Do not open a clarification chain unless the user explicitly asks for clarification or revision.
                - START_EXECUTING requires the latest user message to semantically ask the assistant to execute/do/start the task, plus a grounded task_draft and phase_type=execution.
                - Read-only device operations such as opening settings, viewing a screen, checking status, or inspecting a setting are executable when the latest user message asks to execute/open/check/inspect now; constraints like without changing anything are safety constraints to preserve in detail_slots, not a reason to downgrade to START_PREPARING.
                - If there is one unique ready task, confirmations such as 执行吧 / 开始吧 / 就这样做 / 继续 are START_EXECUTING for that same task even without repeating the action word.
                - START_EXECUTING: if the task is grounded, task_draft must be non-null and concrete; set user_request_semantic=START_EXECUTING, current_phase=EXECUTION, stage_transition_recommendation=SHOULD_ENTER_EXECUTING, next_move=start_execution, and phase_type=execution in this same packet. Do not answer with a preparation-only or wait-only reply.
                - For read-only settings/status inspection execution, ground task_draft as action_code=open, target_type=setting, target_key/target_label for the named setting or screen, capability_stack=SYSTEM, capability_domain=system_control, and preserve constraints such as without changing anything in detail_slots.
                - START_EXECUTING: optional refinements must not block execution; preserve them as detail_slots or constraints and let local validation decide whether runtime can start.
                - SHOULD_ENTER_EXECUTING only means readiness; it does not authorize runtime unless user_request_semantic=START_EXECUTING.
                - If the task looks executable but the latest user message only discusses, refines, asks why/how, or asks for a plan, use START_ACCUMULATING or START_PREPARING and update slots silently.
                - Imperative requests to do the task now should stay START_EXECUTING even when the user attaches concrete constraints such as platform, price, quantity, or feature limits.
                - For communication send_message tasks, a named recipient plus message_body and channel is sufficient grounding for execution unless the latest user message explicitly asks to choose among contacts or channels.
                - do not collapse distinct business actions such as buy, add_to_cart, and pay into one another.
                - when the user clearly names a service or app family, preserve that signal in task_draft.capability_id when grounded, otherwise use detail_slots.platform.
                - for ride-hailing intents, keep the route app-oriented; do not reduce 打车/叫车 requests to generic geo navigation.
                - when the user asks to continue or resume a prior task, keep the task on the same grounded context instead of silently inventing a brand new task.
${if (searchEnhanced) "                - For search-enhanced turns, populate search_summary first, then assistant_reply; both must be user-visible Chinese strings.\n                - search_summary must summarize only topic-relevant external evidence.\n                - Do not add a separate visible reasoning summary inside the structured JSON; rely on the provider reasoning channel for detailed reasoning." else ""}
    """.trimIndent()

    internal fun buildSearchPlanUserPrompt(
        currentState: LocalConversationState,
        historyBundle: String,
        semanticContextBundle: String,
        memoryBundle: String?,
        personalizationBundle: String?,
        userMessage: String
    ): String = """
        Local state: workflow_lane=PASSIVE, stage_owner=USER, current_phase=${currentState.currentPhase ?: currentState.stage.toCurrentPhase()}, user_request_semantic=${currentState.userRequestSemantic ?: "null"}, stage_transition_recommendation=${currentState.stageTransitionRecommendation ?: "null"}, legacy_visible_stage=${currentState.stage.normalized()}.
        Decide whether external search is needed for this turn. If search is needed, build a search plan only. Do not answer the user yet.

        Local time authority:
${buildLocalTimeAuthoritySection().prependIndent("        ")}

        History bundle:
        $historyBundle

        Semantic intent context:
        $semanticContextBundle
${memoryBundle?.let { "\n\nMemory evidence bundle:\n$it" } ?: ""}
${personalizationBundle?.let { "\n\nPersonalization policy bundle:\n$it" } ?: ""}

        Latest user message:
        $userMessage

        Behavior constraints:
        - should_search must be true only when the turn needs fresh or externally verified facts that are not already grounded by provided local/system authority or the current conversation context.
        - For weather, market data, current prices, stock, opening hours, nearby results, routes, news, or other realtime fact requests, prefer should_search=true unless explicit authority in this packet already resolves the needed facts.
        - For explanation, rewriting, summarization, coding help, or planning that can be handled from the provided context, prefer should_search=false.
        - goal_summary must capture the user's actual goal, not a generic paraphrase.
        - If should_search=true, process_summary must mention that this turn will search, summarize relevant results, reason over them, and then answer.
        - If should_search=false, process_summary must mention that this turn will answer directly from grounded context without external search.
        - If should_search=true, search_queries must be concrete, compact, and directly useful for web search. If should_search=false, leave search_queries empty.
        - If should_search=true, search_scope must contain only the topics that truly need external verification. If should_search=false, leave search_scope empty.
        - If the memory evidence bundle includes preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots, use them only as soft query-shaping hints. Never override the latest explicit user-stated targets, constraints, or negations, and never search for blocked options.
        - Do not emit any final answer text.
    """.trimIndent()

    internal fun buildSemanticIntentUserPrompt(
        currentState: LocalConversationState,
        historyBundle: String,
        semanticContextBundle: String,
        memoryBundle: String?,
        personalizationBundle: String?,
        searchPlanBundle: String?,
        searchBundle: String?,
        capabilityPriorBundle: String,
        processPriorBundle: String,
        safetyDecision: String?,
        executionBrief: String?,
        userMessage: String
    ): String {
        val suppressStaleExecutionContext = ContinuationGroundingResolver.prefersExplicitNewTaskOverContinuation(
            userMessage = userMessage,
            currentState = currentState
        )
        return """
        Local state: workflow_lane=PASSIVE, stage_owner=USER, current_phase=${currentState.currentPhase ?: currentState.stage.toCurrentPhase()}, user_request_semantic=${currentState.userRequestSemantic ?: "null"}, stage_transition_recommendation=${currentState.stageTransitionRecommendation ?: "null"}, legacy_visible_stage=${currentState.stage.normalized()}.
        Only the latest user message may move the stage.
        Infer user_request_semantic semantically, not by keyword whitelist.
        current_phase is state only; stage_transition_recommendation is timing/readiness only; local code treats START_EXECUTING plus validation as the runtime-start trigger.
        assistant_reply is the complete user-visible content for this turn, not a progress notice.
        Treat `phase_type + phase_status` as the authoritative semantic subflow state.
        Keep proactive_opportunity_signal null in this passive packet.
        Evidence priority: latest user-stated targets, constraints, and negations are highest. Passive turns prioritize dialogue context, then topic slots, then local preferences, then search. Plan/pre-execution turns prioritize topic slots, then dialogue context, then local preferences, then search.
        Search evidence can support cold-start explicit execution before runtime starts, but only as planning evidence. Realtime fact domains may use search over stale memory; preference domains must keep local preferences over search ranking. Runtime packets never receive new search evidence.

        Local time authority:
    ${buildLocalTimeAuthoritySection().prependIndent("        ")}

        History bundle:
        $historyBundle

        Semantic intent context:
        $semanticContextBundle
${memoryBundle?.let { "\n\nMemory evidence bundle:\n$it" } ?: ""}
${personalizationBundle?.let { "\n\nPersonalization policy bundle:\n$it" } ?: ""}
${searchPlanBundle?.let { "\n\nSearch plan bundle:\n$it" } ?: ""}
${searchBundle?.let { "\n\nSearch evidence bundle:\n$it" } ?: ""}
${"\n\nCapability prior bundle:\n$capabilityPriorBundle"}
${"\n\nProcess prior bundle:\n$processPriorBundle"}
    ${safetyDecision?.let { "\n\nSafety decision:\n$it" } ?: ""}
${executionBrief?.takeUnless { suppressStaleExecutionContext }?.let { "\n\nCurrent execution brief:\n$it" } ?: ""}
${currentState.pendingExecutionRecovery?.takeIf { !suppressStaleExecutionContext }?.let { recovery ->
    "\n\nPending execution recovery:\nTreat the latest user message as guidance for retrying objective=${recovery.objective}. Unless the user clearly switches context, keep the turn on the same task and prefer START_EXECUTING over opening a new clarification chain."
} ?: ""}

        Latest user message:
    $userMessage

        Behavior constraints:
        - Match the personalization bundle for brevity, directness, explanation depth, and cautious default fill when such guidance is present.
        - Output passive control plus task_draft only. Do not emit active_intent, route_hints, final tool ids, or any legacy execution boundary object.
        - Ground task_draft.capability_stack, capability_domain, capability_id, and process_id from the provided prior bundles whenever possible.
        - Do not treat adjacent same-domain capabilities in the capability prior bundle as blocking alternatives when one grounded task already best fits the latest user message.
        - If the memory evidence bundle includes preference_recall_bundle, recommended_detail_slots, blocked_slots, or confirmation_needed_slots, treat them as soft preference priors only: default-fill non-blocking details, never override the latest explicit user-stated slots, do not resuggest blocked slots, and frame confirmation-needed defaults as assumptions or revision invites.
        - If a grounded process is not available, keep task_draft.process_id null.
        - Keep task_draft.action_code, target_type, and target_key concrete when possible.
        - Preserve action distinctions such as buy, add_to_cart, pay, and search.
        - Preserve explicit service/app-family requests in task_draft.capability_id when grounded, otherwise use detail_slots.platform.
        - For continuation and resume turns, keep the same grounded task only when the latest user message and dialogue history clearly support a single target; otherwise ask a concise clarification instead of assuming the current active task.
        - Use detail slots for platform, brand, quantity, price, and similar refinements unless they are truly execution-blocking now.
        - Do not output placeholder replies such as 请稍等, 我来整理, 我准备开始, or 稍后给你 unless the same reply also contains the requested answer, proposal, or execution-start handoff.
        - If the user is still exploring or loosely discussing, use START_ACCUMULATING and answer, compare, explain, or explore with concrete content in the same reply; a clarifying question may follow useful substance.
        - Requests to organize an executable plan, structure concrete next steps, or consolidate a proposal should be treated as START_PREPARING.
        - Requests to create a shortlist, shopping list, recommendation list, itinerary, checklist, or step plan are START_PREPARING when the requested deliverable selects, recommends, or organizes a candidate plan/proposal, even if no runtime execution should start yet.
        - Requests for a simple plan to open, check, inspect, or view device settings/status without executing now are START_PREPARING; keep the safety constraint such as without changing anything in the plan.
        - Pure compare/explore/go broader/no plan turns stay START_ACCUMULATING even when they compare named options. Do not treat comparison itself as START_PREPARING unless the user asks for a concrete shortlist, recommendation, itinerary, checklist, or plan.
        - For START_PREPARING, assistant_reply must contain the actual first proposal/plan in the same turn. Do not reply with only acknowledgement, a promise to prepare, or a request to wait.
        - For travel, shopping, recommendations, schedules, and similar planning tasks, missing preferences are non-blocking by default. State reasonable assumptions, give a usable first draft, and invite revision at the end.
        - If a truly blocking gap remains for START_PREPARING, expose only that blocking gap and do not open a clarification chain unless the user explicitly asks or safety, identity, account, payment, legal, or irreversible-impact constraints require it.
        - Use START_EXECUTING only when the latest user message semantically asks the assistant to execute/do/start the task; then express it with phase_type=execution, phase_status=start|active, and a grounded task_draft.
        - Read-only device operations such as opening settings, viewing a screen, checking status, or inspecting a setting are executable when the latest user message asks to execute/open/check/inspect now; constraints like without changing anything are safety constraints to preserve in detail_slots, not a reason to downgrade to START_PREPARING.
        - If there is one unique ready task, confirmations such as 执行吧 / 开始吧 / 就这样做 / 继续 are START_EXECUTING for that same task.
        - For START_EXECUTING with a grounded task, task_draft must be non-null and concrete; set current_phase=EXECUTION, stage_transition_recommendation=SHOULD_ENTER_EXECUTING, next_move=start_execution, and phase_type=execution in this same packet. Do not answer with a preparation-only or wait-only reply.
        - For read-only settings/status inspection execution, ground task_draft as action_code=open, target_type=setting, target_key/target_label for the named setting or screen, capability_stack=SYSTEM, capability_domain=system_control, and preserve constraints such as without changing anything in detail_slots.
        - Optional refinements on START_EXECUTING must not block execution; preserve them as detail_slots or constraints and let local validation decide whether runtime can start.
        - If the task is executable but the latest user message asks for a plan, asks a question, or only refines slots, do not use START_EXECUTING; update the topic/task slots instead.
        - For communication send_message tasks, a named recipient plus message_body and channel is sufficient grounding for execution unless the latest user message explicitly asks to choose among contacts or channels.
        - The runtime derives any user-visible execution brief locally from semantic state and local contract resolution.
        - If the turn still needs non-blocking refinement, prefer offer-style semantics instead of treating optional details as blocking gaps.
        - Ride-hailing intents should stay app-oriented instead of degrading to generic map navigation.
        - Keep the reply user-facing and no shorter than the user's requested deliverable requires, then return the structured semantic JSON.
    """.trimIndent()
    }

}