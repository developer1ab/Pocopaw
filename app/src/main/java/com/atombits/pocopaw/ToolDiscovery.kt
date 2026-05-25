package com.atombits.pocopaw

import org.json.JSONObject
import java.util.Locale

enum class ToolDomain {
    SYSTEM,
    APP,
    MCP
}

enum class ToolRisk {
    SAFE,
    SENSITIVE,
    RESTRICTED
}

enum class ToolState {
    READY,
    NEEDS_ENRICHMENT,
    REJECTED
}

private fun containsDomainKeyword(text: String, keywords: List<String>): Boolean {
    return keywords.any { keyword -> text.contains(keyword.lowercase(Locale.US)) }
}

data class ToolCapability(
    val capabilityId: String,
    val domain: ToolDomain,
    val source: String,
    val invokeUri: String,
    val risk: ToolRisk,
    val state: ToolState,
    val displayName: String,
    val summary: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("capability_id", capabilityId)
            put("domain", domain.name)
            put("source", source)
            put("invoke_uri", invokeUri)
            put("risk", risk.name)
            put("state", state.name)
            put("display_name", displayName)
            put("summary", summary)
            put("metadata", JSONObject(metadata))
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ToolCapability {
            val metadataJson = json.optJSONObject("metadata") ?: JSONObject()
            val metadata = metadataJson.keys().asSequence().associateWith { key ->
                metadataJson.optString(key, "")
            }
            return ToolCapability(
                capabilityId = json.getString("capability_id"),
                domain = ToolDomain.valueOf(json.optString("domain", ToolDomain.APP.name)),
                source = json.optString("source", ""),
                invokeUri = json.optString("invoke_uri", ""),
                risk = ToolRisk.valueOf(json.optString("risk", ToolRisk.SENSITIVE.name)),
                state = ToolState.valueOf(json.optString("state", ToolState.READY.name)),
                displayName = json.optString(
                    "display_name",
                    metadata["displayName"] ?: metadata["appName"] ?: json.optString("capability_id")
                ),
                summary = json.optString("summary", metadata["summary_en"] ?: ""),
                metadata = metadata
            )
        }
    }
}

data class ToolCapabilityBundle(
    val selectionMode: String,
    val matchedDomains: List<String>,
    val matchedTerms: List<String>,
    val capabilities: List<ToolCapability>
) {
    fun toPromptSection(): String {
        return buildString {
            appendLine("selection_mode=$selectionMode")
            if (matchedDomains.isNotEmpty()) {
                appendLine("matched_domains=${matchedDomains.joinToString(",")}")
            }
            if (matchedTerms.isNotEmpty()) {
                appendLine("matched_terms=${matchedTerms.joinToString(",")}")
            }
            capabilities.forEach { capability ->
                append("- id=${capability.capabilityId}")
                append(" | name=${capability.displayName}")
                append(" | domain=${capability.domain.name}")
                append(" | source=${capability.source}")
                append(" | invoke=${capability.invokeUri}")
                append(" | state=${capability.state.name}")
                append(" | risk=${capability.risk.name}")
                append(" | summary=${capability.summary}")
                append(" | advisory_only=true")
                appendLine()
            }
        }.trim()
    }
}

object ToolCapabilityBundleBuilder {

    private fun ToolCapability.withDomainHint(domainHint: String?): ToolCapability {
        val normalizedDomainHint = domainHint?.trim()?.takeIf { value -> value.isNotBlank() }
        if (normalizedDomainHint == null) {
            return this
        }
        return copy(metadata = metadata + ("domainHint" to normalizedDomainHint))
    }

    private data class ToolCapabilityRule(
        val capability: ToolCapability,
        val taskTerms: List<String>,
        val intentGroups: List<List<String>> = emptyList(),
        val domainHint: String
    )

    private data class CatalogTaskSignalGroup(
        val id: String,
        val taskTerms: List<String>,
        val capabilityTerms: List<String>,
        val intentGroups: List<List<String>> = emptyList()
    )

    private data class CatalogTaskDomainRule(
        val id: String,
        val taskTerms: List<String>,
        val capabilityTerms: List<String>,
        val signalGroups: List<CatalogTaskSignalGroup> = emptyList(),
        val preferredDomains: Set<ToolDomain> = setOf(ToolDomain.SYSTEM, ToolDomain.MCP, ToolDomain.APP)
    )

    private data class CatalogPromptPlan(
        val matchedDomains: List<String>,
        val scoringTerms: Set<String>,
        val explicitServiceTerms: Set<String>,
        val preferredDomains: Set<ToolDomain>
    )

    private val rules = listOf(
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "system.settings.wifi",
                domain = ToolDomain.SYSTEM,
                source = "prototype_catalog",
                invokeUri = "catalog://system.settings.wifi",
                risk = ToolRisk.SENSITIVE,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "Wi-Fi Settings",
                summary = UiStrings.resolve(
                    R.string.tool_route_wifi_summary,
                    "Candidate route for Wi-Fi settings or wireless connectivity tasks."
                )
            ),
            taskTerms = listOf("wifi", "wi-fi", "wlan", "无线网", "wifi设置", "wi-fi settings", "打开wifi设置"),
            intentGroups = listOf(listOf("打开", "wifi"), listOf("无线网", "设置")),
            domainHint = "system_control"
        ),
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "system.settings.bluetooth",
                domain = ToolDomain.SYSTEM,
                source = "prototype_catalog",
                invokeUri = "catalog://system.settings.bluetooth",
                risk = ToolRisk.SENSITIVE,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "Bluetooth Settings",
                summary = UiStrings.resolve(
                    R.string.tool_route_bluetooth_summary,
                    "Candidate route for Bluetooth and nearby device connectivity tasks."
                )
            ),
            taskTerms = listOf("打开蓝牙", "连接蓝牙", "bluetooth settings", "turn on bluetooth"),
            intentGroups = listOf(listOf("打开", "蓝牙"), listOf("连接", "蓝牙")),
            domainHint = "system_control"
        ),
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "app.browser.search",
                domain = ToolDomain.APP,
                source = "prototype_catalog",
                invokeUri = "catalog://app.browser.search",
                risk = ToolRisk.SAFE,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "Browser Search",
                summary = UiStrings.resolve(
                    R.string.tool_route_browser_summary,
                    "Candidate route for search or browser lookup tasks."
                )
            ),
            taskTerms = listOf("网页搜索", "web search", "browser search", "search the web", "open browser", "打开浏览器", "浏览器搜索"),
            intentGroups = listOf(listOf("浏览器", "搜索"), listOf("网页", "搜索"), listOf("open", "browser")),
            domainHint = "information"
        ),
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "app.map.navigation",
                domain = ToolDomain.APP,
                source = "prototype_catalog",
                invokeUri = "catalog://app.map.navigation",
                risk = ToolRisk.SENSITIVE,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "Map Navigation",
                summary = UiStrings.resolve(
                    R.string.tool_route_map_summary,
                    "Candidate route for map, route, ride, or navigation tasks."
                )
            ),
            taskTerms = listOf("打开地图", "地图导航", "open map", "map navigation", "帮我导航", "规划路线", "开始导航", "帮我打车"),
            intentGroups = listOf(listOf("导航", "到"), listOf("导航", "去"), listOf("打车", "到")),
            domainHint = "transport"
        ),
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "system.calendar.create",
                domain = ToolDomain.SYSTEM,
                source = "prototype_catalog",
                invokeUri = "catalog://system.calendar.create",
                risk = ToolRisk.SENSITIVE,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "Calendar Event",
                summary = UiStrings.resolve(
                    R.string.tool_route_calendar_summary,
                    "Candidate route for calendar, reminder, or alarm tasks."
                )
            ),
            taskTerms = listOf("打开日历", "calendar reminder", "帮我设置提醒", "创建提醒", "添加提醒", "设置一个提醒", "设置闹钟", "set alarm"),
            intentGroups = listOf(
                listOf("提醒我", "开会"),
                listOf("提醒我", "喝水"),
                listOf("提醒我", "明早"),
                listOf("明天", "会议"),
                listOf("会议", "提醒"),
                listOf("设置", "闹钟")
            ),
            domainHint = "information"
        ),
        ToolCapabilityRule(
            capability = ToolCapability(
                capabilityId = "system.communication.sms",
                domain = ToolDomain.SYSTEM,
                source = "prototype_catalog",
                invokeUri = "catalog://system.communication.sms",
                risk = ToolRisk.RESTRICTED,
                state = ToolState.NEEDS_ENRICHMENT,
                displayName = "SMS",
                summary = UiStrings.resolve(
                    R.string.tool_route_sms_summary,
                    "Restricted candidate route for explicit SMS composition tasks."
                )
            ),
            taskTerms = listOf("发短信", "发送短信", "sms message", "text message"),
            intentGroups = listOf(listOf("发", "短信"), listOf("发送", "短信"), listOf("sms", "message")),
            domainHint = "communication"
        )
    )

    fun buildForTask(task: String): ToolCapabilityBundle? {
        val normalizedTask = task.trim().lowercase(Locale.US)
        if (normalizedTask.isBlank()) {
            return null
        }

        val matchedRules = rules.filter { rule ->
            rule.taskTerms.any { matchesTaskTerm(normalizedTask, it) } ||
                rule.intentGroups.any { group -> group.all { fragment -> matchesTaskTerm(normalizedTask, fragment) } }
        }
        if (matchedRules.isEmpty()) {
            return null
        }

        return ToolCapabilityBundle(
            selectionMode = "task_filtered_subset",
            matchedDomains = matchedRules.map { rule -> rule.domainHint }.distinct(),
            matchedTerms = matchedRules.flatMap { rule ->
                val matchedPhrases = rule.taskTerms.filter { term -> matchesTaskTerm(normalizedTask, term) }
                val matchedGroups = rule.intentGroups
                    .filter { group -> group.all { fragment -> matchesTaskTerm(normalizedTask, fragment) } }
                    .flatten()
                matchedPhrases + matchedGroups
            }.distinct(),
            capabilities = matchedRules
                .map { rule -> rule.capability.withDomainHint(rule.domainHint) }
                .distinctBy { capability -> capability.capabilityId }
        )
    }

    fun buildForCatalog(task: String, capabilities: List<ToolCapability>, limit: Int = 8): ToolCapabilityBundle? {
        val normalizedTask = task.trim().lowercase(Locale.US)
        if (normalizedTask.isBlank() || capabilities.isEmpty()) {
            return null
        }

        val promptPlan = buildCatalogPromptPlan(normalizedTask)
        val scoredCapabilities = capabilities.mapNotNull { capability ->
            val score = scoreCatalogCapability(capability, promptPlan, normalizedTask)
            if (score > 0) capability to score else null
        }
        if (scoredCapabilities.isEmpty()) {
            return null
        }

        return ToolCapabilityBundle(
            selectionMode = if (promptPlan != null) "task_filtered_subset" else "task_search_subset",
            matchedDomains = promptPlan?.matchedDomains ?: emptyList(),
            matchedTerms = buildMatchedTerms(normalizedTask, promptPlan),
            capabilities = scoredCapabilities
                .sortedWith(compareByDescending<Pair<ToolCapability, Int>> { it.second }.thenBy { it.first.capabilityId })
                .map { pair ->
                    val preferredDomainHint = promptPlan?.matchedDomains?.singleOrNull()
                    pair.first.withDomainHint(preferredDomainHint)
                }
                .take(limit)
        )
    }

    private fun buildCatalogPromptPlan(task: String): CatalogPromptPlan? {
        val profiles = CapabilityDomainProfileRegistry.toolCatalogRules().map { profile ->
            CatalogTaskDomainRule(
                id = profile.domain.wireName,
                taskTerms = profile.taskTerms,
                capabilityTerms = profile.capabilityTerms,
                preferredDomains = profile.preferredDomains,
                signalGroups = profile.signalGroups.map { signalGroup ->
                    CatalogTaskSignalGroup(
                        id = signalGroup.id,
                        taskTerms = signalGroup.taskTerms,
                        capabilityTerms = signalGroup.capabilityTerms,
                        intentGroups = signalGroup.intentGroups
                    )
                }
            )
        }

        val matchedProfiles = profiles.filter { profile ->
            profile.taskTerms.any { term -> matchesTaskTerm(task, term) }
        }

        val explicitServiceTerms = linkedSetOf<String>()
        CanonicalAppCatalog.allEntries().forEach { entry ->
            val candidateTerms = (entry.aliasTerms + entry.toolTerms + setOf(entry.appId))
                .map { term -> term.trim().lowercase(Locale.US) }
                .filter { term -> term.isNotBlank() }
            if (candidateTerms.any { term -> task.contains(term) }) {
                explicitServiceTerms.addAll(candidateTerms)
            }
        }

        if (matchedProfiles.isEmpty() && explicitServiceTerms.isEmpty()) {
            return null
        }

        val scoringTerms = linkedSetOf<String>()
        matchedProfiles.forEach { profile ->
            val matchedSignalGroups = profile.signalGroups.filter { signalGroup ->
                signalGroup.taskTerms.any { term -> matchesTaskTerm(task, term) } ||
                    signalGroup.intentGroups.any { group -> group.all { fragment -> matchesTaskTerm(task, fragment) } }
            }
            val selectedTerms = if (matchedSignalGroups.isNotEmpty()) {
                matchedSignalGroups.flatMap { signalGroup -> signalGroup.capabilityTerms }
            } else {
                profile.capabilityTerms
            }
            scoringTerms.addAll(selectedTerms.map { term -> term.lowercase(Locale.US) })
        }
        scoringTerms.addAll(explicitServiceTerms)

        return CatalogPromptPlan(
            matchedDomains = matchedProfiles.map { profile -> profile.id }.distinct(),
            scoringTerms = scoringTerms,
            explicitServiceTerms = explicitServiceTerms,
            preferredDomains = matchedProfiles.flatMap { profile -> profile.preferredDomains }.toSet()
        )
    }

    private fun scoreCatalogCapability(
        capability: ToolCapability,
        promptPlan: CatalogPromptPlan?,
        normalizedTask: String
    ): Int {
        val searchableText = searchableText(capability)
        var semanticScore = 0
        var preferredDomainBonus = 0

        if (promptPlan != null) {
            promptPlan.explicitServiceTerms.forEach { term ->
                if (searchableText.contains(term)) {
                    semanticScore += 8
                }
            }
            promptPlan.scoringTerms.forEach { term ->
                if (searchableText.contains(term)) {
                    semanticScore += 3
                }
            }
            if (promptPlan.preferredDomains.isNotEmpty() && capability.domain in promptPlan.preferredDomains) {
                preferredDomainBonus = 2
            }
        } else {
            normalizedTask.split(Regex("\\s+"))
                .filter { token -> token.length >= 2 }
                .forEach { token ->
                    if (searchableText.contains(token)) {
                        semanticScore += 2
                    }
                }
        }

        if (semanticScore == 0) return 0

        var score = semanticScore + preferredDomainBonus

        score += when (capability.state) {
            ToolState.READY -> 2
            ToolState.NEEDS_ENRICHMENT -> 0
            ToolState.REJECTED -> -4
        }

        score += when (capability.risk) {
            ToolRisk.SAFE -> 1
            ToolRisk.SENSITIVE -> 0
            ToolRisk.RESTRICTED -> -3
        }

        return score
    }

    private fun buildMatchedTerms(task: String, promptPlan: CatalogPromptPlan?): List<String> {
        if (promptPlan == null) {
            return task.split(Regex("\\s+"))
                .filter { token -> token.length >= 2 }
                .distinct()
        }

        val matchedTerms = linkedSetOf<String>()
        matchedTerms.addAll(promptPlan.explicitServiceTerms.filter { term -> task.contains(term) })
        matchedTerms.addAll(promptPlan.scoringTerms.filter { term -> task.contains(term) })
        return matchedTerms.toList()
    }

    private fun searchableText(capability: ToolCapability): String {
        return buildString {
            append(capability.capabilityId)
            append(' ')
            append(capability.displayName)
            append(' ')
            append(capability.summary)
        }.lowercase(Locale.US)
    }

    private fun matchesTaskTerm(task: String, term: String): Boolean {
        val normalizedTerm = term.trim().lowercase(Locale.US)
        if (normalizedTerm.isBlank()) {
            return false
        }
        return if (normalizedTerm.any { it.code > 127 }) {
            task.contains(normalizedTerm)
        } else {
            Regex("(^|[^a-z0-9])${Regex.escape(normalizedTerm)}([^a-z0-9]|$)").containsMatchIn(task)
        }
    }
}