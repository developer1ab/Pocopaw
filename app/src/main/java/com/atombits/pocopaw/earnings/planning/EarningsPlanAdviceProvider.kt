package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.ChatTurnOptions
import com.atombits.pocopaw.EarningsPlanAdvicePromptSpec
import com.atombits.pocopaw.PromptCenter
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.extractStructuredPromptPayloadText
import com.atombits.pocopaw.earnings.EarningsConstants
import com.atombits.pocopaw.earnings.EarningsDateKeys
import com.atombits.pocopaw.earnings.EarningsPolicyMode
import com.atombits.pocopaw.earnings.EarningsTimeWindow
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.ExecutionLedgerEntry
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.PlannerDiagnosticItem
import com.atombits.pocopaw.earnings.RewardLedgerEntry
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface EarningsPlanAdviceProvider {
    suspend fun requestAdvice(
        scanState: FourAppScanState,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): EarningsPlanAdviceResult
}

data class EarningsPlanAdviceResult(
    val advice: EarningsPlanAdvice? = null,
    val diagnostics: List<PlannerDiagnosticItem> = emptyList()
)

data class EarningsPlanAdvice(
    val summary: String? = null,
    val importantAdvice: List<EarningsImportantTaskAdvice> = emptyList(),
    val fillerAdvice: EarningsFillerAdvice = EarningsFillerAdvice(),
    val rejectedAdvice: List<EarningsRejectedPlanAdvice> = emptyList(),
    val responseNotes: List<String> = emptyList()
)

data class EarningsImportantTaskAdvice(
    val appId: EntertainmentAppId,
    val taskKey: String,
    val plannedWindowId: String? = null,
    val windowLabel: String? = null,
    val recommendedRunAt: Long? = null,
    val priorityRank: Int? = null,
    val reason: String? = null,
    val riskNotes: List<String> = emptyList()
)

data class EarningsFillerAdvice(
    val policyMode: EarningsPolicyMode? = null,
    val candidateAppOrder: List<EntertainmentAppId> = emptyList(),
    val candidateTaskOrder: List<EarningsFillerTaskAdvice> = emptyList(),
    val cooldownNotes: List<String> = emptyList(),
    val riskNotes: List<String> = emptyList()
)

data class EarningsFillerTaskAdvice(
    val appId: EntertainmentAppId,
    val taskKey: String,
    val priorityRank: Int? = null,
    val reason: String? = null
)

data class EarningsRejectedPlanAdvice(
    val appId: EntertainmentAppId? = null,
    val taskKey: String? = null,
    val reason: String? = null
)

class ModelEarningsPlanAdviceProvider(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient(),
    private val isConfiguredOverride: (() -> Boolean)? = null,
    private val requestPromptPacketOverride: (suspend (PromptPacket) -> String)? = null
) : EarningsPlanAdviceProvider {
    override suspend fun requestAdvice(
        scanState: FourAppScanState,
        executionLedgerState: ExecutionLedgerState,
        rewardLedgerState: RewardLedgerState,
        now: Long
    ): EarningsPlanAdviceResult {
        if (scanState.acceptedOpportunities.isEmpty()) {
            return EarningsPlanAdviceResult(
                diagnostics = listOf(PlannerDiagnosticItem("NO_ACCEPTED_OPPORTUNITIES", "No accepted earnings opportunities are available."))
            )
        }
        if (!(isConfiguredOverride?.invoke() ?: client.isConfigured())) {
            return EarningsPlanAdviceResult(
                diagnostics = listOf(
                    PlannerDiagnosticItem(
                        code = "MODEL_PLANNER_NOT_CONFIGURED",
                        message = "Semantic model planning advice is required but no semantic provider API key is configured.",
                        severity = "WARN"
                    )
                )
            )
        }
        val packet = PromptCenter.buildEarningsPlanAdvicePacket(
            EarningsPlanAdvicePromptSpec(
                timeAuthorityBundle = buildTimeAuthorityBundle(now),
                opportunityBundle = buildOpportunityBundle(scanState.acceptedOpportunities),
                coverageChecklistBundle = buildCoverageChecklistBundle(scanState.acceptedOpportunities),
                executionLedgerBundle = buildExecutionLedgerBundle(executionLedgerState, now),
                rewardLedgerBundle = buildRewardLedgerBundle(rewardLedgerState, now),
                constraintBundle = buildConstraintBundle(now)
            )
        )
        return withContext(Dispatchers.IO) {
            val raw = runCatching {
                requestPromptPacketOverride?.invoke(packet)
                    ?: client.requestPromptPacket(
                        promptPacket = packet,
                        turnOptions = ChatTurnOptions(thinkingEnabled = false, searchEnabled = false)
                    )
            }.getOrElse { throwable ->
                return@withContext EarningsPlanAdviceResult(
                    diagnostics = listOf(
                        PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_REQUEST_FAILED",
                            message = "Model planning advice request failed: ${throwable.message ?: throwable::class.java.simpleName}",
                            severity = "ERROR"
                        )
                    )
                )
            }
            val content = runCatching { extractStructuredPromptPayloadText(raw) }.getOrElse { throwable ->
                return@withContext EarningsPlanAdviceResult(
                    diagnostics = listOf(
                        PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_RESPONSE_EXTRACT_FAILED",
                            message = "Model planning advice response extraction failed: ${throwable.message ?: throwable::class.java.simpleName}",
                            severity = "ERROR"
                        )
                    )
                )
            }
            val parsed = EarningsPlanAdviceJsonParser.parse(content)
            if (parsed.advice == null) {
                return@withContext parsed
            }
            parsed.copy(
                diagnostics = parsed.diagnostics + PlannerDiagnosticItem(
                    code = "MODEL_PLANNER_ADVICE_RECEIVED",
                    message = parsed.advice.summary?.take(160) ?: "Model planning advice received."
                )
            )
        }
    }

    private fun buildTimeAuthorityBundle(now: Long): String {
        val timeZone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { this.timeZone = timeZone }
        return JSONObject().apply {
            put("currentTimestampMs", now)
            put("activeDateKey", EarningsDateKeys.forTimestamp(now))
            put("currentLocalDateTime", formatter.format(Date(now)))
            put("timeZone", timeZone.id)
        }.toString()
    }

    private fun buildOpportunityBundle(opportunities: List<TaskOpportunity>): String {
        return JSONObject().apply {
            put("opportunityCount", opportunities.size)
            put("items", JSONArray().apply {
                opportunities.forEachIndexed { index, opportunity ->
                    put(JSONObject().apply {
                        put("index", index + 1)
                        put("appId", opportunity.appId.stableKey)
                        put("taskKey", opportunity.taskKey)
                        put("title", opportunity.displayName)
                        put("category", opportunity.category.name)
                        putNullable("action", opportunity.actionText)
                        putNullable("reward", opportunity.rewardText)
                        putNullable("rule", opportunity.repeatRule ?: opportunity.streakRule)
                        putNullable("cooldownMinutes", opportunity.cooldownHintMinutes)
                        put("windows", JSONArray().apply {
                            opportunity.timeWindowHints.forEach { window -> put(window.toJson()) }
                        })
                        putNullable("estimatedSeconds", opportunity.estimatedDurationSeconds)
                        putNullable("estimatedCoins", opportunity.estimatedRewardCoins)
                        put("confidence", opportunity.scanConfidence)
                        putNullable("evidence", opportunity.rawTextSnapshot?.replace(Regex("\\s+"), " ")?.take(160))
                    })
                }
            })
        }.toString()
    }

    private fun buildCoverageChecklistBundle(opportunities: List<TaskOpportunity>): String {
        val importantOpportunities = opportunities.filter { opportunity -> opportunity.category != TaskCategory.FILLER_REPEATABLE_DECAY }
        val fillerOpportunities = opportunities.filter { opportunity -> opportunity.category == TaskCategory.FILLER_REPEATABLE_DECAY }
        return JSONObject().apply {
            put("importantAdviceRequiredCount", importantOpportunities.size)
            put("importantAdviceRequiredKeys", JSONArray().apply {
                importantOpportunities.forEach { opportunity -> put(opportunity.toCoverageRequirementJson("importantAdvice")) }
            })
            put("fillerCandidateTaskOrderRequiredCount", fillerOpportunities.size)
            put("fillerCandidateTaskOrderRequiredKeys", JSONArray().apply {
                fillerOpportunities.forEach { opportunity -> put(opportunity.toCoverageRequirementJson("fillerAdvice.candidateTaskOrder")) }
            })
            put("copyableRequiredResponseSkeleton", buildRequiredResponseSkeleton(importantOpportunities, fillerOpportunities))
        }.toString()
    }

    private fun buildRequiredResponseSkeleton(
        importantOpportunities: List<TaskOpportunity>,
        fillerOpportunities: List<TaskOpportunity>
    ): JSONObject {
        return JSONObject().apply {
            put("summary", "Schedule all required structured items from this skeleton for today's plan.")
            put("importantAdvice", JSONArray().apply {
                importantOpportunities.forEachIndexed { index, opportunity ->
                    put(opportunity.toImportantAdviceSkeletonJson(index + 1))
                }
            })
            put("fillerAdvice", JSONObject().apply {
                put("policyMode", EarningsPolicyMode.STATIC_ROUND_ROBIN.name)
                put("candidateAppOrder", JSONArray(fillerOpportunities.map { opportunity -> opportunity.appId.stableKey }.distinct()))
                put("candidateTaskOrder", JSONArray().apply {
                    fillerOpportunities.forEachIndexed { index, opportunity ->
                        put(opportunity.toFillerTaskSkeletonJson(index + 1))
                    }
                })
                put("cooldownNotes", JSONArray())
                put("riskNotes", JSONArray())
            })
            put("rejectedAdvice", JSONArray())
            put("responseNotes", JSONArray())
        }
    }

    private fun buildExecutionLedgerBundle(executionLedgerState: ExecutionLedgerState, now: Long): String {
        val activeDateKey = EarningsDateKeys.forTimestamp(now)
        return JSONObject().apply {
            put("activeDateKey", activeDateKey)
            put("completedOneTimeKeys", JSONArray(executionLedgerState.completedOneTimeKeys))
            put("todayCompletedOccurrenceIds", JSONArray(executionLedgerState.todayCompletedOccurrenceIds))
            put("todayCompletedWindowIdsByScopedTaskKey", JSONObject(executionLedgerState.todayCompletedWindowIdsByScopedTaskKey))
            put("recentEntries", JSONArray().apply {
                executionLedgerState.entries.takeLast(50).forEach { entry -> put(entry.toJson()) }
            })
        }.toString()
    }

    private fun buildRewardLedgerBundle(rewardLedgerState: RewardLedgerState, now: Long): String {
        return JSONObject().apply {
            put("activeDateKey", EarningsDateKeys.forTimestamp(now))
            put("todayTotalCoins", rewardLedgerState.todayTotalCoins)
            put("recentFillerRewardVelocityByApp", JSONObject(rewardLedgerState.recentFillerRewardVelocityByApp))
            put("todayByApp", JSONArray().apply {
                rewardLedgerState.todayByApp.forEach { summary ->
                    put(JSONObject().apply {
                        put("appId", summary.appId.stableKey)
                        put("totalCoins", summary.totalCoins)
                        put("successCount", summary.successCount)
                        putNullable("fillerCoinsPerMinute", summary.fillerCoinsPerMinute)
                    })
                }
            })
            put("recentEntries", JSONArray().apply {
                rewardLedgerState.entries.takeLast(50).forEach { entry -> put(entry.toJson()) }
            })
        }.toString()
    }

    private fun buildConstraintBundle(now: Long): String {
        return buildString {
            appendLine("Only these app ids are in scope: ${EntertainmentAppId.defaultOrder().joinToString(",") { it.stableKey }}.")
            appendLine("Use accepted opportunities only; rejected, uncertain, blacklisted, game, finance, paid, creator/live, cross-app, and generic entry items must not be scheduled.")
            appendLine("Model output is advice only. Local code validates admission, completion, windows, cooldown, plannedRunAt, finalScore, and persistence.")
            appendLine("ONE_TIME tasks with permanent completion facts must not be recommended again.")
            appendLine("DAILY_ONCE and STREAK_MULTI_DAY tasks completed today must not be recommended again unless explicit opportunity semantics allow repeat.")
            appendLine("DAILY_WINDOWED_REPEAT must use stable model-recognized windows; do not invent windows or use scan index/list position.")
            appendLine("FILLER_REPEATABLE_DECAY goes to filler rotation advice only, not the important schedule.")
            appendLine("Recommended run times must be epoch milliseconds for activeDateKey=${EarningsDateKeys.forTimestamp(now)} and inside the opportunity window when known.")
            append("Execution maximum steps per run: ${EarningsConstants.MAX_EXECUTION_STEPS}.")
        }
    }
}

internal object EarningsPlanAdviceJsonParser {
    fun parse(content: String): EarningsPlanAdviceResult {
        val root = runCatching { JSONObject(content) }.getOrElse { throwable ->
            return EarningsPlanAdviceResult(
                diagnostics = listOf(
                    PlannerDiagnosticItem(
                        code = "MODEL_PLANNER_RESPONSE_INVALID_JSON",
                        message = "Model planning advice was not valid JSON: ${throwable.message ?: throwable::class.java.simpleName}",
                        severity = "ERROR"
                    )
                )
            )
        }
        val diagnostics = mutableListOf<PlannerDiagnosticItem>()
        val importantAdvice = parseImportantAdvice(root, diagnostics)
        val fillerAdvice = parseFillerAdvice(root.optJSONObject("fillerAdvice") ?: root.optJSONObject("filler_advice"), diagnostics)
        val rejectedAdvice = parseRejectedAdvice(root.optJSONArray("rejectedAdvice") ?: root.optJSONArray("rejected_advice"))
        val advice = EarningsPlanAdvice(
            summary = root.optNullableString("summary"),
            importantAdvice = importantAdvice,
            fillerAdvice = fillerAdvice,
            rejectedAdvice = rejectedAdvice,
            responseNotes = root.optStringList("responseNotes") + root.optStringList("response_notes")
        )
        return EarningsPlanAdviceResult(advice = advice, diagnostics = diagnostics)
    }

    private fun parseImportantAdvice(root: JSONObject, diagnostics: MutableList<PlannerDiagnosticItem>): List<EarningsImportantTaskAdvice> {
        val array = root.optJSONArray("importantAdvice") ?: root.optJSONArray("important_advice") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val appId = EntertainmentAppId.fromStableKey(item.optNullableString("appId") ?: item.optNullableString("app_id"))
                val taskKey = item.optNullableString("taskKey") ?: item.optNullableString("task_key")
                if (appId == null || taskKey.isNullOrBlank()) {
                    diagnostics += PlannerDiagnosticItem(
                        code = "MODEL_PLANNER_IGNORED_IMPORTANT_ADVICE",
                        message = "Ignored important advice item $index because appId or taskKey was missing.",
                        severity = "WARN"
                    )
                    continue
                }
                add(
                    EarningsImportantTaskAdvice(
                        appId = appId,
                        taskKey = taskKey,
                        plannedWindowId = item.optNullableString("plannedWindowId") ?: item.optNullableString("planned_window_id"),
                        windowLabel = item.optNullableString("windowLabel") ?: item.optNullableString("window_label"),
                        recommendedRunAt = item.optNullableLong("recommendedRunAt") ?: item.optNullableLong("recommended_run_at"),
                        priorityRank = item.optNullableInt("priorityRank") ?: item.optNullableInt("priority_rank"),
                        reason = item.optNullableString("reason"),
                        riskNotes = item.optStringList("riskNotes") + item.optStringList("risk_notes")
                    )
                )
            }
        }
    }

    private fun parseFillerAdvice(root: JSONObject?, diagnostics: MutableList<PlannerDiagnosticItem>): EarningsFillerAdvice {
        if (root == null) {
            return EarningsFillerAdvice()
        }
        val policyMode = root.optNullableString("policyMode")
            ?.let { value -> runCatching { EarningsPolicyMode.valueOf(value.trim().uppercase(Locale.US)) }.getOrNull() }
        val candidateAppOrder = root.optStringList("candidateAppOrder")
            .ifEmpty { root.optStringList("candidate_app_order") }
            .mapNotNull { appKey ->
                EntertainmentAppId.fromStableKey(appKey).also { appId ->
                    if (appId == null) {
                        diagnostics += PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_IGNORED_FILLER_APP",
                            message = "Ignored filler app advice for unknown appId=$appKey.",
                            severity = "WARN"
                        )
                    }
                }
            }
        val candidateTaskOrderArray = root.optJSONArray("candidateTaskOrder") ?: root.optJSONArray("candidate_task_order")
        val candidateTaskOrder = buildList {
            if (candidateTaskOrderArray != null) {
                for (index in 0 until candidateTaskOrderArray.length()) {
                    val item = candidateTaskOrderArray.optJSONObject(index) ?: continue
                    val appId = EntertainmentAppId.fromStableKey(item.optNullableString("appId") ?: item.optNullableString("app_id"))
                    val taskKey = item.optNullableString("taskKey") ?: item.optNullableString("task_key")
                    if (appId == null || taskKey.isNullOrBlank()) {
                        diagnostics += PlannerDiagnosticItem(
                            code = "MODEL_PLANNER_IGNORED_FILLER_TASK",
                            message = "Ignored filler task advice item $index because appId or taskKey was missing.",
                            severity = "WARN"
                        )
                        continue
                    }
                    add(
                        EarningsFillerTaskAdvice(
                            appId = appId,
                            taskKey = taskKey,
                            priorityRank = item.optNullableInt("priorityRank") ?: item.optNullableInt("priority_rank"),
                            reason = item.optNullableString("reason")
                        )
                    )
                }
            }
        }
        return EarningsFillerAdvice(
            policyMode = policyMode,
            candidateAppOrder = candidateAppOrder,
            candidateTaskOrder = candidateTaskOrder,
            cooldownNotes = root.optStringList("cooldownNotes") + root.optStringList("cooldown_notes"),
            riskNotes = root.optStringList("riskNotes") + root.optStringList("risk_notes")
        )
    }

    private fun parseRejectedAdvice(array: JSONArray?): List<EarningsRejectedPlanAdvice> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    EarningsRejectedPlanAdvice(
                        appId = EntertainmentAppId.fromStableKey(item.optNullableString("appId") ?: item.optNullableString("app_id")),
                        taskKey = item.optNullableString("taskKey") ?: item.optNullableString("task_key"),
                        reason = item.optNullableString("reason")
                    )
                )
            }
        }
    }
}

private fun EarningsTimeWindow.toJson(): JSONObject {
    return JSONObject().apply {
        put("label", label)
        putNullable("startMinuteOfDay", startMinuteOfDay)
        putNullable("endMinuteOfDay", endMinuteOfDay)
    }
}

private fun TaskOpportunity.toCoverageRequirementJson(requiredArray: String): JSONObject {
    return JSONObject().apply {
        put("requiredArray", requiredArray)
        put("appId", appId.stableKey)
        put("taskKey", taskKey)
        put("category", category.name)
        put("title", displayName)
        put("instruction", "Return one structured item with this exact appId and taskKey.")
    }
}

private fun TaskOpportunity.toImportantAdviceSkeletonJson(priorityRank: Int): JSONObject {
    return JSONObject().apply {
        put("appId", appId.stableKey)
        put("taskKey", taskKey)
        putNullable("plannedWindowId", null)
        putNullable("windowLabel", null)
        putNullable("recommendedRunAt", null)
        put("priorityRank", priorityRank)
        put("reason", "Fill model scheduling reason for this required important task.")
        put("riskNotes", JSONArray())
    }
}

private fun TaskOpportunity.toFillerTaskSkeletonJson(priorityRank: Int): JSONObject {
    return JSONObject().apply {
        put("appId", appId.stableKey)
        put("taskKey", taskKey)
        put("priorityRank", priorityRank)
        put("reason", "Fill model rotation reason for this required filler task.")
    }
}

private fun ExecutionLedgerEntry.toJson(): JSONObject {
    return JSONObject().apply {
        put("executionId", executionId)
        putNullable("occurrenceId", occurrenceId)
        put("appId", appId.stableKey)
        put("taskKey", taskKey)
        put("displayName", displayName)
        put("category", category.name)
        putNullable("windowLabel", windowLabel)
        putNullable("plannedWindowId", plannedWindowId)
        put("startedAt", startedAt)
        putNullable("finishedAt", finishedAt)
        put("status", status.name)
        putNullable("terminalReason", terminalReason)
        put("permanentCompletion", permanentCompletion)
    }
}

private fun RewardLedgerEntry.toJson(): JSONObject {
    return JSONObject().apply {
        put("executionId", executionId)
        putNullable("executionLedgerEntryId", executionLedgerEntryId)
        putNullable("occurrenceId", occurrenceId)
        put("appId", appId.stableKey)
        put("taskKey", taskKey)
        put("displayName", displayName)
        put("category", category.name)
        putNullable("windowLabel", windowLabel)
        putNullable("plannedWindowId", plannedWindowId)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        putNullable("actualRewardCoins", actualRewardCoins)
        putNullable("rewardObservationMethod", rewardObservationMethod)
        put("status", status.name)
        putNullable("failureReason", failureReason)
    }
}

private fun JSONObject.putNullable(name: String, value: Any?) {
    put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name).trim().takeIf { value -> value.isNotBlank() && !value.equals("null", ignoreCase = true) }
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val rawValue = opt(name)) {
        is Number -> rawValue.toInt()
        is String -> rawValue.trim().toIntOrNull()
        else -> null
    }
}

private fun JSONObject.optNullableLong(name: String): Long? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val rawValue = opt(name)) {
        is Number -> rawValue.toLong()
        is String -> rawValue.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                add(value)
            }
        }
    }
}
