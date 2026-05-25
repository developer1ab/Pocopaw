package com.atombits.pocopaw

import java.util.Locale
import java.util.UUID

enum class CapabilityStack {
    SYSTEM,
    APP,
    MCP;

    companion object {
        fun fromRaw(value: String?): CapabilityStack? = when (value?.trim()?.uppercase(Locale.US)) {
            "SYSTEM" -> SYSTEM
            "APP" -> APP
            "MCP" -> MCP
            else -> null
        }
    }
}

enum class CapabilityDomain(val wireName: String) {
    SHOPPING("shopping"),
    FOOD("food"),
    HOME_LIFE("home_life"),
    TRANSPORT("transport"),
    ENTERTAINMENT("entertainment"),
    LOCAL_SERVICE("local_service"),
    INFORMATION("information"),
    COMMUNICATION("communication"),
    FINANCE("finance"),
    SYSTEM_CONTROL("system_control"),
    OTHER("other");

    companion object {
        private val legacyAliases = mapOf(
            "device_control" to SYSTEM_CONTROL,
            "settings_control" to SYSTEM_CONTROL,
            "commerce_service" to SHOPPING,
            "map_navigation" to TRANSPORT,
            "ride_hailing" to TRANSPORT,
            "calendar_alarm" to INFORMATION,
            "camera_document" to INFORMATION,
            "web_search" to INFORMATION,
            "weather" to INFORMATION
        )

        fun fromRaw(value: String?): CapabilityDomain? {
            val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() }
                ?: return null
            return values().firstOrNull { domain -> domain.wireName == normalized }
                ?: legacyAliases[normalized]
                ?: when {
                    normalized.contains("shopping") || normalized.contains("commerce") || normalized.contains("shop") || normalized.contains("jd") || normalized.contains("taobao") || normalized.contains("pdd") || normalized.contains("amazon") -> SHOPPING
                    normalized.contains("food") || normalized.contains("meal") || normalized.contains("restaurant") || normalized.contains("takeout") || normalized.contains("eleme") || normalized.contains("外卖") || normalized.contains("点餐") -> FOOD
                    normalized.contains("home_life") || normalized.contains("home-life") || normalized.contains("home") || normalized.contains("repair") || normalized.contains("household") || normalized.contains("laundry") || normalized.contains("保洁") || normalized.contains("维修") -> HOME_LIFE
                    normalized.contains("transport") || normalized.contains("ride") || normalized.contains("taxi") || normalized.contains("didi") || normalized.contains("map") || normalized.contains("route") || normalized.contains("navigation") || normalized.contains("geo") -> TRANSPORT
                    normalized.contains("entertainment") || normalized.contains("movie") || normalized.contains("music") || normalized.contains("video") || normalized.contains("game") -> ENTERTAINMENT
                    normalized.contains("local_service") || normalized.contains("meituan") || normalized.contains("dianping") -> LOCAL_SERVICE
                    normalized.contains("communication") || normalized.contains("message") || normalized.contains("sms") || normalized.contains("mail") || normalized.contains("contact") -> COMMUNICATION
                    normalized.contains("finance") || normalized.contains("payment") || normalized.contains("pay") || normalized.contains("transfer") || normalized.contains("bank") || normalized.contains("bill") || normalized.contains("wallet") || normalized.contains("insurance") || normalized.contains("loan") -> FINANCE
                    normalized.contains("system_control") || normalized.contains("settings") || normalized.contains("wifi") || normalized.contains("bluetooth") || normalized.contains("permission") || normalized.contains("toggle") -> SYSTEM_CONTROL
                    normalized.contains("calendar") || normalized.contains("alarm") || normalized.contains("remind") || normalized.contains("camera") || normalized.contains("document") || normalized.contains("photo") || normalized.contains("weather") || normalized.contains("web") || normalized.contains("browser") || normalized.contains("search") -> INFORMATION
                    normalized.contains("other") || normalized.contains("generic") -> OTHER
                    else -> null
                }
        }
    }
}

enum class CapabilityRisk {
    SAFE,
    SENSITIVE,
    RESTRICTED
}

enum class CapabilityState {
    READY,
    NEEDS_ENRICHMENT,
    REJECTED
}

enum class ProcessState {
    CANDIDATE,
    READY,
    RETIRED,
    DISABLED
}

enum class ActionCode(val wireName: String) {
    OPEN("open"),
    SEARCH("search"),
    BUY("buy"),
    ADD_TO_CART("add_to_cart"),
    PAY("pay"),
    COMPARE("compare"),
    COUPON("coupon"),
    RATE("rate"),
    RETURN("return"),
    DELETE("delete"),
    SEND_MESSAGE("send_message"),
    CREATE("create"),
    ENABLE("enable"),
    DISABLE("disable"),
    NAVIGATE("navigate"),
    BOOK("book"),
    CALL("call"),
    PLAN("plan"),
    UNKNOWN("unknown");

    companion object {
        fun fromRaw(value: String?): ActionCode? {
            SharedActionNormalization.fromRaw(value)?.let { canonicalAction ->
                return fromCanonicalAction(canonicalAction)
            }
            val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() }
                ?: return null
            fun containsAny(vararg tokens: String): Boolean = tokens.any { token -> normalized.contains(token) }
            return when {
                containsAny("enable", "turn on", "开启", "启用") -> ENABLE
                containsAny("disable", "turn off", "关闭", "停用") -> DISABLE
                containsAny("navigate", "navigation", "route", "导航", "路线") -> NAVIGATE
                containsAny("book", "reserve", "预约", "预订") -> BOOK
                containsAny("call", "dial", "打电话", "拨号") -> CALL
                containsAny("plan", "organize", "规划", "整理方案", "行程") -> PLAN
                containsAny("open", "launch", "show", "go to", "打开", "进入", "前往") -> OPEN
                containsAny("create", "new", "add", "创建", "新建", "添加", "设置") -> CREATE
                else -> UNKNOWN
            }
        }

        fun fromCanonicalAction(value: CanonicalAction?): ActionCode? = when (value) {
            CanonicalAction.BUY -> BUY
            CanonicalAction.ADD_TO_CART -> ADD_TO_CART
            CanonicalAction.PAY -> PAY
            CanonicalAction.SEARCH -> SEARCH
            CanonicalAction.COMPARE -> COMPARE
            CanonicalAction.COUPON -> COUPON
            CanonicalAction.RATING -> RATE
            CanonicalAction.RETURN -> RETURN
            CanonicalAction.DELETE -> DELETE
            CanonicalAction.SEND_MESSAGE -> SEND_MESSAGE
            null -> null
        }
    }
}

enum class TargetType(val wireName: String) {
    SETTING("setting"),
    APP("app"),
    PRODUCT("product"),
    SERVICE("service"),
    DESTINATION("destination"),
    LOCATION("location"),
    MESSAGE_THREAD("message_thread"),
    CONTACT("contact"),
    CALENDAR_EVENT("calendar_event"),
    REMINDER("reminder"),
    ROUTE("route"),
    TRIP("trip"),
    DOCUMENT("document"),
    GENERIC("generic");

    companion object {
        fun fromRaw(value: String?): TargetType? {
            val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() }
                ?: return null
            return values().firstOrNull { targetType -> targetType.wireName == normalized }
                ?: when {
                    normalized.contains("setting") || normalized.contains("wifi") || normalized.contains("bluetooth") -> SETTING
                    normalized.contains("app") -> APP
                    normalized.contains("product") || normalized.contains("商品") -> PRODUCT
                    normalized.contains("service") || normalized.contains("服务") -> SERVICE
                    normalized.contains("destination") -> DESTINATION
                    normalized.contains("location") || normalized.contains("地址") -> LOCATION
                    normalized.contains("message") || normalized.contains("thread") -> MESSAGE_THREAD
                    normalized.contains("contact") || normalized.contains("联系人") -> CONTACT
                    normalized.contains("calendar") || normalized.contains("event") -> CALENDAR_EVENT
                    normalized.contains("reminder") || normalized.contains("alarm") || normalized.contains("提醒") -> REMINDER
                    normalized.contains("route") || normalized.contains("navigation") -> ROUTE
                    normalized.contains("trip") || normalized.contains("travel") || normalized.contains("行程") -> TRIP
                    normalized.contains("document") || normalized.contains("file") || normalized.contains("文档") -> DOCUMENT
                    else -> GENERIC
                }
        }
    }
}

enum class TaskPhase {
    ACCUMULATING,
    PREPARING,
    EXECUTING,
    LEARNING;

    companion object {
        fun fromPersistedValue(value: String?): TaskPhase? = when (value?.trim()?.uppercase(Locale.US)) {
            "ACCUMULATING" -> ACCUMULATING
            "PREPARING" -> PREPARING
            "EXECUTING" -> EXECUTING
            "LEARNING" -> LEARNING
            "REUSE" -> LEARNING
            "WAITING_RECOVERY" -> EXECUTING
            "COMPLETED", "FAILED" -> LEARNING
            else -> null
        }
    }
}

data class CapabilityCatalogItem(
    val capabilityStack: CapabilityStack,
    val capabilityDomain: CapabilityDomain,
    val capabilityId: String,
    val capabilityInvokeUri: String,
    val capabilityDisplayName: String,
    val capabilitySummary: String,
    val risk: CapabilityRisk,
    val state: CapabilityState
)

data class ProcessCatalogItem(
    val processId: String,
    val processVersion: Int = 1,
    val capabilityStack: CapabilityStack,
    val capabilityDomain: CapabilityDomain,
    val capabilityId: String? = null,
    val actionCode: ActionCode,
    val targetType: TargetType,
    val processDisplayName: String,
    val processSummary: String,
    val state: ProcessState
)

data class CapabilityPriorIndexItem(
    val capabilityStack: CapabilityStack,
    val capabilityDomain: CapabilityDomain,
    val capabilityId: String,
    val capabilityDisplayName: String,
    val capabilitySummary: String,
    val risk: CapabilityRisk,
    val state: CapabilityState
)

data class CapabilityPriorBundle(
    val index: List<CapabilityPriorIndexItem> = emptyList(),
    val richSubset: List<CapabilityCatalogItem> = emptyList()
) {
    fun toPromptSection(): String {
        return buildString {
            appendLine("capability_index_count=${index.size}")
            if (index.isEmpty()) {
                appendLine("capability_index=none")
            } else {
                index.forEach { capability ->
                    append("- capability_id=${capability.capabilityId}")
                    append(" | capability_stack=${capability.capabilityStack.name}")
                    append(" | capability_domain=${capability.capabilityDomain.wireName}")
                    append(" | display_name=${capability.capabilityDisplayName}")
                    append(" | risk=${capability.risk.name}")
                    append(" | state=${capability.state.name}")
                    append(" | summary=${capability.capabilitySummary}")
                    appendLine()
                }
            }
            appendLine("capability_rich_subset_count=${richSubset.size}")
            if (richSubset.isEmpty()) {
                append("capability_rich_subset=none")
            } else {
                richSubset.forEach { capability ->
                    append("- capability_id=${capability.capabilityId}")
                    append(" | capability_stack=${capability.capabilityStack.name}")
                    append(" | capability_domain=${capability.capabilityDomain.wireName}")
                    append(" | display_name=${capability.capabilityDisplayName}")
                    append(" | risk=${capability.risk.name}")
                    append(" | state=${capability.state.name}")
                    append(" | summary=${capability.capabilitySummary}")
                    appendLine()
                }
            }
        }.trim()
    }
}

data class ProcessPriorIndexItem(
    val processId: String,
    val processVersion: Int,
    val capabilityStack: CapabilityStack,
    val capabilityDomain: CapabilityDomain,
    val capabilityId: String? = null,
    val actionCode: ActionCode,
    val targetType: TargetType,
    val processDisplayName: String,
    val processSummary: String,
    val state: ProcessState
)

data class ProcessPriorBundle(
    val index: List<ProcessPriorIndexItem> = emptyList(),
    val richSubset: List<ProcessCatalogItem> = emptyList()
) {
    fun toPromptSection(): String {
        return buildString {
            appendLine("process_index_count=${index.size}")
            if (index.isEmpty()) {
                appendLine("process_index=none")
            } else {
                index.forEach { process ->
                    append("- process_id=${process.processId}")
                    append(" | process_version=${process.processVersion}")
                    append(" | capability_stack=${process.capabilityStack.name}")
                    append(" | capability_domain=${process.capabilityDomain.wireName}")
                    append(" | capability_id=${process.capabilityId ?: "null"}")
                    append(" | action_code=${process.actionCode.wireName}")
                    append(" | target_type=${process.targetType.wireName}")
                    append(" | display_name=${process.processDisplayName}")
                    append(" | state=${process.state.name}")
                    append(" | summary=${process.processSummary}")
                    appendLine()
                }
            }
            appendLine("process_rich_subset_count=${richSubset.size}")
            if (richSubset.isEmpty()) {
                append("process_rich_subset=none")
            } else {
                richSubset.forEach { process ->
                    append("- process_id=${process.processId}")
                    append(" | process_version=${process.processVersion}")
                    append(" | capability_stack=${process.capabilityStack.name}")
                    append(" | capability_domain=${process.capabilityDomain.wireName}")
                    append(" | capability_id=${process.capabilityId ?: "null"}")
                    append(" | action_code=${process.actionCode.wireName}")
                    append(" | target_type=${process.targetType.wireName}")
                    append(" | display_name=${process.processDisplayName}")
                    append(" | state=${process.state.name}")
                    append(" | summary=${process.processSummary}")
                    appendLine()
                }
            }
        }.trim()
    }
}

data class TaskDraft(
    val actionCode: ActionCode? = null,
    val targetType: TargetType? = null,
    val targetKey: String? = null,
    val targetLabel: String? = null,
    val structuredDetailSlots: TaskDetailSlots = TaskDetailSlots(),
    val detailSlots: Map<DetailSlotKey, String> = emptyMap(),
    val capabilityStack: CapabilityStack? = null,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val processId: String? = null,
    val reasonSummary: String? = null
)

data class TaskRecord(
    val taskId: String,
    val sourceTurnId: String,
    val parentTaskId: String? = null,
    val phase: TaskPhase,
    val actionCode: ActionCode,
    val targetType: TargetType,
    val targetKey: String,
    val targetLabel: String? = null,
    val structuredDetailSlots: TaskDetailSlots = TaskDetailSlots(),
    val detailSlots: Map<DetailSlotKey, String> = emptyMap(),
    val capabilityStack: CapabilityStack? = null,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val processId: String? = null,
    val checkpointId: String? = null,
    val resultCode: String? = null,
    val resultSummary: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val reasonSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun TaskDraft.displayTarget(): String? {
    return targetLabel?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: targetKey?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: structuredDetailSlots.primaryTargetCandidates(capabilityDomain).firstOrNull()
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        ?: detailSlots[DetailSlotKey.PRODUCT_TYPE]?.trim()?.takeIf { value -> value.isNotBlank() }
}

fun TaskDraft.displayPlanSummary(): String? {
    val actionSummary = actionCode?.wireName?.takeIf { value -> value.isNotBlank() }
    val targetSummary = displayTarget()
    return listOfNotNull(actionSummary, targetSummary)
        .joinToString(" ")
        .trim()
        .takeIf { value -> value.isNotBlank() }
}

fun TaskRecord.displayTarget(): String {
    return targetLabel?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: targetKey.trim().takeIf { value -> value.isNotBlank() }
        ?: structuredDetailSlots.primaryTargetCandidates(capabilityDomain).firstOrNull()
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        ?: detailSlots[DetailSlotKey.PRODUCT_TYPE]?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: targetKey
}

fun TaskRecord.displayPlanSummary(): String {
    return listOf(actionCode.wireName, displayTarget())
        .filter { value -> value.isNotBlank() }
        .joinToString(" ")
        .ifBlank { displayTarget() }
}

fun TaskRecord.resolvePreferredAppScope(): String? {
    val normalizedCapabilityId = capabilityId?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    return when (capabilityStack) {
        CapabilityStack.APP -> extractCanonicalAppScope(normalizedCapabilityId) ?: normalizedCapabilityId
        CapabilityStack.SYSTEM,
        CapabilityStack.MCP,
        null -> extractCanonicalAppScope(normalizedCapabilityId) ?: normalizedCapabilityId
    }
}

fun ToolDomain.toCapabilityStack(): CapabilityStack = when (this) {
    ToolDomain.SYSTEM -> CapabilityStack.SYSTEM
    ToolDomain.APP -> CapabilityStack.APP
    ToolDomain.MCP -> CapabilityStack.MCP
}

fun ToolRisk.toCapabilityRisk(): CapabilityRisk = when (this) {
    ToolRisk.SAFE -> CapabilityRisk.SAFE
    ToolRisk.SENSITIVE -> CapabilityRisk.SENSITIVE
    ToolRisk.RESTRICTED -> CapabilityRisk.RESTRICTED
}

fun ToolState.toCapabilityState(): CapabilityState = when (this) {
    ToolState.READY -> CapabilityState.READY
    ToolState.NEEDS_ENRICHMENT -> CapabilityState.NEEDS_ENRICHMENT
    ToolState.REJECTED -> CapabilityState.REJECTED
}

fun ToolCapability.toCapabilityCatalogItem(fallbackDomain: CapabilityDomain? = null): CapabilityCatalogItem? {
    val resolvedDomain = metadata["domainHint"]?.let(CapabilityDomain::fromRaw)
        ?: fallbackDomain
        ?: CapabilityDomain.fromRaw(capabilityId)
        ?: CapabilityDomain.fromRaw(summary)
    return resolvedDomain?.let { capabilityDomain ->
        CapabilityCatalogItem(
            capabilityStack = domain.toCapabilityStack(),
            capabilityDomain = capabilityDomain,
            capabilityId = capabilityId,
            capabilityInvokeUri = invokeUri,
            capabilityDisplayName = displayName,
            capabilitySummary = summary,
            risk = risk.toCapabilityRisk(),
            state = state.toCapabilityState()
        )
    }
}

fun CapabilityCatalogItem.toCapabilityPriorIndexItem(): CapabilityPriorIndexItem {
    return CapabilityPriorIndexItem(
        capabilityStack = capabilityStack,
        capabilityDomain = capabilityDomain,
        capabilityId = capabilityId,
        capabilityDisplayName = capabilityDisplayName,
        capabilitySummary = capabilitySummary,
        risk = risk,
        state = state
    )
}

fun ToolCapabilityBundle.toCapabilityPriorBundle(includeRichSubset: Boolean = false): CapabilityPriorBundle {
    val fallbackDomain = matchedDomains.firstOrNull()?.let(CapabilityDomain::fromRaw)
    val catalogItems = capabilities.mapNotNull { capability ->
        capability.toCapabilityCatalogItem(fallbackDomain = fallbackDomain)
    }
    return CapabilityPriorBundle(
        index = catalogItems.map(CapabilityCatalogItem::toCapabilityPriorIndexItem),
        richSubset = if (includeRichSubset) catalogItems else emptyList()
    )
}

fun ReadyProcessAsset.toProcessCatalogItemOrNull(): ProcessCatalogItem? {
    val resolvedDomain = CapabilityDomain.fromRaw(domain) ?: return null
    return ProcessCatalogItem(
        processId = processId,
        processVersion = version,
        capabilityStack = when {
            appScope.equals("system", ignoreCase = true) -> CapabilityStack.SYSTEM
            appScope.equals("mcp", ignoreCase = true) -> CapabilityStack.MCP
            else -> CapabilityStack.APP
        },
        capabilityDomain = resolvedDomain,
        capabilityId = null,
        actionCode = processAction.toProcessCatalogActionCode() ?: ActionCode.UNKNOWN,
        targetType = TargetType.GENERIC,
        processDisplayName = processId,
        processSummary = semanticDescription,
        state = ProcessState.READY
    )
}

private fun String?.toProcessCatalogActionCode(): ActionCode? {
    return when (canonicalizeProcessAction(this)) {
        "addtocart" -> ActionCode.ADD_TO_CART
        "buy" -> ActionCode.BUY
        "pay" -> ActionCode.PAY
        "clearcart", "cancel" -> ActionCode.DELETE
        "return" -> ActionCode.RETURN
        "compare" -> ActionCode.COMPARE
        "coupon" -> ActionCode.COUPON
        "comments" -> ActionCode.RATE
        "search" -> ActionCode.SEARCH
        "order", "book" -> ActionCode.BOOK
        "schedule" -> ActionCode.PLAN
        "open" -> ActionCode.OPEN
        "navigate" -> ActionCode.NAVIGATE
        "send", "reply" -> ActionCode.SEND_MESSAGE
        "call" -> ActionCode.CALL
        else -> ActionCode.fromRaw(this)
    }
}

fun ProcessCatalogItem.toProcessPriorIndexItem(): ProcessPriorIndexItem {
    return ProcessPriorIndexItem(
        processId = processId,
        processVersion = processVersion,
        capabilityStack = capabilityStack,
        capabilityDomain = capabilityDomain,
        capabilityId = capabilityId,
        actionCode = actionCode,
        targetType = targetType,
        processDisplayName = processDisplayName,
        processSummary = processSummary,
        state = state
    )
}

fun PrototypeStoreData.buildProcessPriorBundle(
    includeRichSubset: Boolean = shouldAttachRichPriorSubset()
): ProcessPriorBundle {
    val catalogItems = readyProcessAssets
        .mapNotNull(ReadyProcessAsset::toProcessCatalogItemOrNull)
        .distinctBy { process -> process.processId }
    val currentState = resolveCurrentState()
    return ProcessPriorBundle(
        index = catalogItems.map(ProcessCatalogItem::toProcessPriorIndexItem),
        richSubset = if (includeRichSubset) {
            catalogItems.filter { process -> process.matchesCurrentTaskPrior(currentState) }
        } else {
            emptyList()
        }
    )
}

fun PrototypeStoreData.shouldAttachRichPriorSubset(): Boolean {
    val currentState = resolveCurrentState()
    currentState.currentTaskRecord?.let { taskRecord ->
        return taskRecord.phase == TaskPhase.EXECUTING
    }
    val taskDraft = currentState.currentTaskDraft ?: return false
    return currentState.stage.normalized() in setOf(ConversationStage.PREPARING, ConversationStage.EXECUTING) &&
        taskDraft.actionCode != null &&
        taskDraft.targetType != null &&
        taskDraft.displayTarget() != null
}

private fun ProcessCatalogItem.matchesCurrentTaskPrior(currentState: LocalConversationState): Boolean {
    val taskRecord = currentState.currentTaskRecord
    val taskDraft = currentState.currentTaskDraft
    val taskActionCode = taskRecord?.actionCode ?: taskDraft?.actionCode
    val taskCapabilityDomain = taskRecord?.capabilityDomain ?: taskDraft?.capabilityDomain
    val actionMatches = taskActionCode == null ||
        actionCode == ActionCode.UNKNOWN ||
        actionCode == taskActionCode
    val domainMatches = taskCapabilityDomain == null || capabilityDomain == taskCapabilityDomain
    return actionMatches && domainMatches
}

fun ActionCode.toCanonicalAction(): CanonicalAction? = when (this) {
    ActionCode.BUY -> CanonicalAction.BUY
    ActionCode.ADD_TO_CART -> CanonicalAction.ADD_TO_CART
    ActionCode.PAY -> CanonicalAction.PAY
    ActionCode.SEARCH -> CanonicalAction.SEARCH
    ActionCode.COMPARE -> CanonicalAction.COMPARE
    ActionCode.COUPON -> CanonicalAction.COUPON
    ActionCode.RATE -> CanonicalAction.RATING
    ActionCode.RETURN -> CanonicalAction.RETURN
    ActionCode.DELETE -> CanonicalAction.DELETE
    ActionCode.SEND_MESSAGE -> CanonicalAction.SEND_MESSAGE
    else -> null
}

fun ConversationStage.toTaskPhase(): TaskPhase = when (normalized()) {
    ConversationStage.ACCUMULATING -> TaskPhase.ACCUMULATING
    ConversationStage.PREPARING -> TaskPhase.PREPARING
    ConversationStage.EXECUTING -> TaskPhase.EXECUTING
    else -> TaskPhase.ACCUMULATING
}

fun inferCapabilityStackFromCapabilityId(capabilityId: String?, fallbackDomain: CapabilityDomain? = null): CapabilityStack? {
    val normalized = capabilityId?.trim()?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }
    return when {
        normalized == null && fallbackDomain == null -> null
        normalized?.startsWith("system.") == true || normalized?.startsWith("catalog://system") == true -> CapabilityStack.SYSTEM
        normalized?.startsWith("app.") == true || normalized?.startsWith("catalog://app") == true -> CapabilityStack.APP
        normalized?.startsWith("mcp.") == true || normalized?.startsWith("catalog://mcp") == true -> CapabilityStack.MCP
        fallbackDomain == CapabilityDomain.SYSTEM_CONTROL || fallbackDomain == CapabilityDomain.COMMUNICATION -> CapabilityStack.SYSTEM
        else -> CapabilityStack.APP
    }
}

private fun normalizeTaskString(value: String?): String? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
        return null
    }
    return normalized
}

private fun normalizeLegacyDetailSlots(detailSlots: Map<DetailSlotKey, String>): Map<DetailSlotKey, String> {
    return detailSlots.mapNotNull { (key, rawValue) ->
        val normalizedValue = rawValue.trim()
        if (normalizedValue.isBlank()) {
            null
        } else {
            key to normalizedValue
        }
    }.toMap(linkedMapOf())
}

private fun buildCompatibleLegacyDetailSlots(
    capabilityDomain: CapabilityDomain?,
    detailSlots: Map<DetailSlotKey, String>,
    structuredDetailSlots: TaskDetailSlots
): Map<DetailSlotKey, String> {
    val mergedDetailSlots = linkedMapOf<DetailSlotKey, String>()
    normalizeLegacyDetailSlots(detailSlots).forEach { (key, value) ->
        mergedDetailSlots[key] = value
    }
    structuredDetailSlots.toLegacyDetailSlots(capabilityDomain).forEach { (key, value) ->
        mergedDetailSlots[key] = value
    }
    return mergedDetailSlots
}

private fun mergeTaskDetailSlots(
    base: TaskDetailSlots?,
    overlay: TaskDetailSlots?,
    capabilityDomain: CapabilityDomain?
): TaskDetailSlots {
    val mergedCommon = linkedMapOf<CommonDetailSlotKey, String>()
    val mergedDomain = linkedMapOf<String, String>()
    base?.normalize(capabilityDomain)?.let { normalizedBase ->
        normalizedBase.common.forEach { (key, value) ->
            mergedCommon[key] = value
        }
        normalizedBase.domain.forEach { (key, value) ->
            mergedDomain[key] = value
        }
    }
    overlay?.normalize(capabilityDomain)?.let { normalizedOverlay ->
        normalizedOverlay.common.forEach { (key, value) ->
            mergedCommon[key] = value
        }
        normalizedOverlay.domain.forEach { (key, value) ->
            mergedDomain[key] = value
        }
    }
    return TaskDetailSlots(
        common = mergedCommon,
        domain = mergedDomain
    )
}

internal fun TaskDraft.normalizeModelFields(): TaskDraft? {
    val normalizedTargetKey = normalizeTaskString(targetKey)
    val normalizedTargetLabel = normalizeTaskString(targetLabel)
    val normalizedCapabilityId = normalizeTaskString(capabilityId)
    val normalizedProcessId = normalizeTaskString(processId)
    val normalizedReasonSummary = normalizeTaskString(reasonSummary)
    val normalizedStructuredDetailSlots = structuredDetailSlots.normalize(capabilityDomain)
    val normalizedDetailSlots = buildCompatibleLegacyDetailSlots(
        capabilityDomain = capabilityDomain,
        detailSlots = detailSlots,
        structuredDetailSlots = normalizedStructuredDetailSlots
    )
    if (
        actionCode == null &&
        targetType == null &&
        normalizedTargetKey == null &&
        normalizedTargetLabel == null &&
        normalizedDetailSlots.isEmpty() &&
        normalizedStructuredDetailSlots.isEmpty() &&
        capabilityStack == null &&
        capabilityDomain == null &&
        normalizedCapabilityId == null &&
        normalizedProcessId == null &&
        normalizedReasonSummary == null
    ) {
        return null
    }
    return copy(
        targetKey = normalizedTargetKey,
        targetLabel = normalizedTargetLabel,
        structuredDetailSlots = normalizedStructuredDetailSlots,
        detailSlots = normalizedDetailSlots,
        capabilityId = normalizedCapabilityId,
        processId = normalizedProcessId,
        reasonSummary = normalizedReasonSummary
    )
}

private fun TaskDraft.currentTargetIdentitySignals(): Set<String> {
    return linkedSetOf<String>().apply {
        listOf(
            targetKey,
            detailSlots[DetailSlotKey.TARGET_OBJECT],
            detailSlots[DetailSlotKey.PRODUCT_TYPE],
            targetLabel
        ).mapNotNull(::normalizeTaskString)
            .forEach(::add)
        structuredDetailSlots.primaryTargetCandidates(capabilityDomain)
            .mapNotNull(::normalizeTaskString)
            .forEach(::add)
    }
}

private fun TaskRecord.targetIdentitySignals(): Set<String> {
    return linkedSetOf<String>().apply {
        listOf(
            targetKey,
            targetLabel,
            detailSlots[DetailSlotKey.TARGET_OBJECT],
            detailSlots[DetailSlotKey.PRODUCT_TYPE]
        ).mapNotNull(::normalizeTaskString)
            .forEach(::add)
        structuredDetailSlots.primaryTargetCandidates(capabilityDomain)
            .mapNotNull(::normalizeTaskString)
            .forEach(::add)
    }
}

private fun TaskDraft.canInheritPreviousTaskContext(previousTaskRecord: TaskRecord?): Boolean {
    previousTaskRecord ?: return false
    actionCode?.let { currentActionCode ->
        if (currentActionCode != previousTaskRecord.actionCode) {
            return false
        }
    }
    targetType?.let { currentTargetType ->
        if (currentTargetType != previousTaskRecord.targetType) {
            return false
        }
    }
    normalizeTaskString(capabilityId)?.let { currentCapabilityId ->
        if (currentCapabilityId != normalizeTaskString(previousTaskRecord.capabilityId)) {
            return false
        }
    }
    normalizeTaskString(processId)?.let { currentProcessId ->
        if (currentProcessId != normalizeTaskString(previousTaskRecord.processId)) {
            return false
        }
    }
    normalizeTaskString(
        buildCompatibleLegacyDetailSlots(capabilityDomain, detailSlots, structuredDetailSlots)[DetailSlotKey.PLATFORM]
    )?.let { currentPlatform ->
        normalizeTaskString(previousTaskRecord.detailSlots[DetailSlotKey.PLATFORM])?.let { previousPlatform ->
            if (!currentPlatform.equals(previousPlatform, ignoreCase = true)) {
                return false
            }
        }
    }
    val currentTargetSignals = currentTargetIdentitySignals()
    if (currentTargetSignals.isEmpty()) {
        return true
    }
    val previousTargetSignals = previousTaskRecord.targetIdentitySignals()
    return currentTargetSignals.any { currentSignal ->
        previousTargetSignals.any { previousSignal ->
            previousSignal.equals(currentSignal, ignoreCase = true)
        }
    }
}

private fun TaskRecord.canReuseSameLiveTaskIdentity(): Boolean {
    return phase in setOf(
        TaskPhase.ACCUMULATING,
        TaskPhase.PREPARING,
        TaskPhase.EXECUTING
    ) && resultCode == null && resultSummary == null && failureCode == null && failureMessage == null
}

private fun TaskDraft.resolveCurrentTargetKey(): String? {
    return listOf(
        targetKey,
        detailSlots[DetailSlotKey.TARGET_OBJECT],
        detailSlots[DetailSlotKey.PRODUCT_TYPE],
        targetLabel,
        structuredDetailSlots.primaryTargetCandidates(capabilityDomain).firstOrNull()
    ).mapNotNull(::normalizeTaskString)
        .firstOrNull()
}

private fun TaskDraft.resolveCurrentTargetLabel(resolvedTargetKey: String?): String? {
    return listOf(
        targetLabel,
        detailSlots[DetailSlotKey.TARGET_OBJECT],
        detailSlots[DetailSlotKey.PRODUCT_TYPE],
        structuredDetailSlots.primaryTargetCandidates(capabilityDomain).firstOrNull(),
        resolvedTargetKey
    ).mapNotNull(::normalizeTaskString)
        .firstOrNull()
}

internal fun TaskRecord.normalizeModelFields(): TaskRecord? {
    val normalizedTaskId = normalizeTaskString(taskId) ?: UUID.randomUUID().toString()
    val normalizedSourceTurnId = normalizeTaskString(sourceTurnId) ?: "turn_${createdAt.coerceAtLeast(updatedAt)}"
    val normalizedTargetKey = normalizeTaskString(targetKey) ?: return null
    val normalizedTargetLabel = normalizeTaskString(targetLabel)
    val normalizedCapabilityId = normalizeTaskString(capabilityId)
    val normalizedProcessId = normalizeTaskString(processId)
    val normalizedCheckpointId = normalizeTaskString(checkpointId)
    val normalizedResultCode = normalizeTaskString(resultCode)
    val normalizedResultSummary = normalizeTaskString(resultSummary)
    val normalizedFailureCode = normalizeTaskString(failureCode)
    val normalizedFailureMessage = normalizeTaskString(failureMessage)
    val normalizedReasonSummary = normalizeTaskString(reasonSummary)
    val normalizedParentTaskId = normalizeTaskString(parentTaskId)
    val normalizedStructuredDetailSlots = structuredDetailSlots.normalize(capabilityDomain)
    val normalizedDetailSlots = buildCompatibleLegacyDetailSlots(
        capabilityDomain = capabilityDomain,
        detailSlots = detailSlots,
        structuredDetailSlots = normalizedStructuredDetailSlots
    )
    return copy(
        taskId = normalizedTaskId,
        sourceTurnId = normalizedSourceTurnId,
        parentTaskId = normalizedParentTaskId,
        targetKey = normalizedTargetKey,
        targetLabel = normalizedTargetLabel,
        structuredDetailSlots = normalizedStructuredDetailSlots,
        detailSlots = normalizedDetailSlots,
        capabilityId = normalizedCapabilityId,
        processId = normalizedProcessId,
        checkpointId = normalizedCheckpointId,
        resultCode = normalizedResultCode,
        resultSummary = normalizedResultSummary,
        failureCode = normalizedFailureCode,
        failureMessage = normalizedFailureMessage,
        reasonSummary = normalizedReasonSummary
    )
}

fun TaskDraft.toSemanticIntentState(
    stage: ConversationStage,
    currentPhase: CurrentPhase? = stage.toCurrentPhase(),
    userRequestSemantic: UserRequestSemantic? = stage.toCurrentPhase().let { phase ->
        when (phase) {
            CurrentPhase.PREPARATION -> UserRequestSemantic.START_PREPARING
            CurrentPhase.EXECUTION -> UserRequestSemantic.START_EXECUTING
            CurrentPhase.ACCUMULATION -> UserRequestSemantic.START_ACCUMULATING
        }
    },
    stageTransitionRecommendation: StageTransitionRecommendation? = userRequestSemantic
        ?.toProgressSignal(stage)
        ?.toStageTransitionRecommendation()
): SemanticIntentState? {
    val normalizedDisplayTarget = displayTarget()?.trim()?.takeIf { value -> value.isNotBlank() }
    val resolvedTargetKey = targetKey?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: normalizedDisplayTarget
        ?: capabilityId?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: return null
    val normalizedTargetLabel = targetLabel?.trim()?.takeIf { value -> value.isNotBlank() }
        ?: normalizedDisplayTarget
        ?: resolvedTargetKey
    val mergedSlots = linkedMapOf<DetailSlotKey, String>()
    mergedSlots[DetailSlotKey.TARGET_OBJECT] = normalizedTargetLabel
    detailSlots.forEach { (key, value) ->
        val trimmedValue = value.trim()
        if (trimmedValue.isNotBlank()) {
            mergedSlots[key] = trimmedValue
        }
    }
    val semanticDetailSlots = mergedSlots.map { (key, value) ->
        DetailSlot(
            key = key,
            value = value,
            source = "TASK_DRAFT"
        )
    }
    val normalizedStage = stage.normalized()
    val phaseType = when (normalizedStage) {
        ConversationStage.PREPARING -> SemanticPhaseType.OFFER
        ConversationStage.EXECUTING -> SemanticPhaseType.EXECUTION
        else -> SemanticPhaseType.NONE
    }
    val phaseStatus = when (normalizedStage) {
        ConversationStage.PREPARING,
        ConversationStage.EXECUTING -> SemanticPhaseStatus.ACTIVE
        else -> SemanticPhaseStatus.END
    }
    val readiness = when (normalizedStage) {
        ConversationStage.EXECUTING -> SemanticIntentReadiness.READY_FOR_EXECUTION
        ConversationStage.PREPARING -> SemanticIntentReadiness.READY_FOR_OFFER
        else -> SemanticIntentReadiness.CONVERGING
    }
    val nextMove = when (normalizedStage) {
        ConversationStage.EXECUTING -> SemanticNextMove.START_EXECUTION
        ConversationStage.PREPARING -> SemanticNextMove.UPDATE_ACTIVE_TASK
        else -> SemanticNextMove.ANSWER
    }
    val candidate = SemanticIntentCandidate(
        intentId = "task_draft_${UUID.randomUUID()}",
        anchorObject = normalizedTargetLabel,
        focusedObject = normalizedTargetLabel,
        rawActionLabel = actionCode?.wireName.orEmpty(),
        canonicalAction = actionCode?.toCanonicalAction(),
        readiness = readiness,
        confidence = if (capabilityId != null) 0.95 else 0.7,
        stability = if (normalizedStage == ConversationStage.EXECUTING) 0.9 else 0.6,
        detailSlots = semanticDetailSlots,
        constraints = emptyList(),
        authorizationRequirement = ConfirmRequirement.NONE,
        confirmationPolicy = null,
        executionConstraints = emptyList(),
        executionPreferenceSignals = emptyList(),
        capabilityStack = capabilityStack,
        capabilityDomain = capabilityDomain,
        capabilityId = capabilityId,
        processId = processId,
        continuationHint = null,
        reasonSummary = reasonSummary,
        phaseType = phaseType,
        phaseStatus = phaseStatus,
        nextMove = nextMove,
        canStartExecution = normalizedStage == ConversationStage.EXECUTING
    )
    return SemanticIntentState(
        activeIntentId = candidate.intentId,
        candidateIntents = listOf(candidate),
        currentPhase = currentPhase,
        userRequestSemantic = userRequestSemantic,
        stageTransitionRecommendation = stageTransitionRecommendation,
        nextMove = nextMove,
        phaseType = phaseType,
        phaseStatus = phaseStatus
    )
}

fun TaskDraft.resolveTaskRecord(
    previousTaskRecord: TaskRecord?,
    sourceTurnId: String,
    stage: ConversationStage,
    semanticSummary: String,
    now: Long = System.currentTimeMillis(),
    allowReuseTaskId: Boolean = true
): TaskRecord? {
    val canInheritPreviousContext = canInheritPreviousTaskContext(previousTaskRecord)
    val resolvedActionCode = actionCode
        ?: previousTaskRecord?.actionCode?.takeIf { canInheritPreviousContext }
        ?: return null
    val resolvedTargetType = targetType
        ?: previousTaskRecord?.targetType?.takeIf { canInheritPreviousContext }
        ?: TargetType.GENERIC
    val resolvedCapabilityDomain = capabilityDomain
        ?: previousTaskRecord?.capabilityDomain?.takeIf { canInheritPreviousContext }
    val mergedStructuredDetailSlots = mergeTaskDetailSlots(
        base = previousTaskRecord?.structuredDetailSlots?.takeIf { canInheritPreviousContext },
        overlay = structuredDetailSlots,
        capabilityDomain = resolvedCapabilityDomain
    )
    val resolvedTargetKey = resolveCurrentTargetKey()
        ?: previousTaskRecord?.targetKey?.takeIf { canInheritPreviousContext }
        ?: return null
    val resolvedTargetLabel = resolveCurrentTargetLabel(resolvedTargetKey)
        ?: previousTaskRecord?.targetLabel?.takeIf { canInheritPreviousContext }
        ?: resolvedTargetKey
    val mergedDetailSlots = linkedMapOf<DetailSlotKey, String>()
    previousTaskRecord?.detailSlots?.takeIf { canInheritPreviousContext }?.forEach { (key, value) ->
        val trimmedValue = value.trim()
        if (trimmedValue.isNotBlank()) {
            mergedDetailSlots[key] = trimmedValue
        }
    }
    detailSlots.forEach { (key, value) ->
        val trimmedValue = value.trim()
        if (trimmedValue.isNotBlank()) {
            mergedDetailSlots[key] = trimmedValue
        }
    }
    mergedStructuredDetailSlots.toLegacyDetailSlots(resolvedCapabilityDomain).forEach { (key, value) ->
        mergedDetailSlots[key] = value
    }
    val reuseTaskId = allowReuseTaskId && previousTaskRecord != null &&
        previousTaskRecord.canReuseSameLiveTaskIdentity() &&
        previousTaskRecord.actionCode == resolvedActionCode &&
        previousTaskRecord.targetType == resolvedTargetType &&
        previousTaskRecord.targetKey == resolvedTargetKey
    val parentTaskId = when {
        reuseTaskId -> previousTaskRecord?.parentTaskId
        allowReuseTaskId && canInheritPreviousContext -> previousTaskRecord?.taskId
        else -> null
    }
    return TaskRecord(
        taskId = if (reuseTaskId) previousTaskRecord?.taskId ?: UUID.randomUUID().toString() else UUID.randomUUID().toString(),
        sourceTurnId = sourceTurnId,
        parentTaskId = parentTaskId,
        phase = stage.toTaskPhase(),
        actionCode = resolvedActionCode,
        targetType = resolvedTargetType,
        targetKey = resolvedTargetKey,
        targetLabel = resolvedTargetLabel,
        structuredDetailSlots = mergedStructuredDetailSlots,
        detailSlots = mergedDetailSlots,
        capabilityStack = capabilityStack
            ?: previousTaskRecord?.capabilityStack?.takeIf { canInheritPreviousContext }
            ?: inferCapabilityStackFromCapabilityId(capabilityId, resolvedCapabilityDomain),
        capabilityDomain = resolvedCapabilityDomain,
        capabilityId = capabilityId ?: previousTaskRecord?.capabilityId?.takeIf { canInheritPreviousContext },
        processId = processId ?: previousTaskRecord?.processId?.takeIf { canInheritPreviousContext },
        checkpointId = if (reuseTaskId) previousTaskRecord?.checkpointId else null,
        resultCode = null,
        resultSummary = null,
        failureCode = null,
        failureMessage = null,
        reasonSummary = reasonSummary?.takeIf { value -> value.isNotBlank() }
            ?: previousTaskRecord?.reasonSummary?.takeIf { canInheritPreviousContext }
            ?: semanticSummary.takeIf { value -> value.isNotBlank() },
        createdAt = if (reuseTaskId) previousTaskRecord?.createdAt ?: now else now,
        updatedAt = now
    )
}

fun TaskRecord.toTaskExecutionBoundaryPacket(
    capabilityId: String? = this.capabilityId,
    processId: String? = this.processId,
    verificationChecks: List<ExecutionCheck> = emptyList(),
    missingInformation: List<String> = emptyList(),
    requiredDetailSlots: List<DetailSlotKey> = emptyList(),
    confirmRequirement: ConfirmRequirement = ConfirmRequirement.NONE
): TaskExecutionBoundaryPacket {
    val normalizedStructuredDetailSlots = structuredDetailSlots.normalize(capabilityDomain)
    val normalizedDetailSlots = buildCompatibleLegacyDetailSlots(
        capabilityDomain = capabilityDomain,
        detailSlots = detailSlots,
        structuredDetailSlots = normalizedStructuredDetailSlots
    )
    val resolvedSlots = linkedMapOf<String, String>()
    resolvedSlots[DetailSlotKey.TARGET_OBJECT.contractName] = targetKey
    normalizedDetailSlots.forEach { (key, value) ->
        resolvedSlots[key.contractName] = value
    }
    normalizedStructuredDetailSlots.toNamespacedResolvedSlots(capabilityDomain).forEach { (key, value) ->
        resolvedSlots[key] = value
    }
    val slotChecks = resolvedSlots.map { (slotKey, value) ->
        ExecutionCheck(
            type = ExecutionCheckType.SLOT_PRESERVED,
            key = slotKey,
            expectedValue = value,
            required = slotKey != DetailSlotKey.TARGET_OBJECT.contractName
        )
    }
    val checks = (slotChecks + verificationChecks).distinctBy { check ->
        Triple(check.type, check.key, check.expectedValue)
    }
    return TaskExecutionBoundaryPacket(
        taskId = taskId,
        taskUpdatedAt = updatedAt,
        phase = phase,
        actionCode = actionCode,
        targetType = targetType,
        targetKey = targetKey,
        targetLabel = targetLabel,
        structuredDetailSlots = normalizedStructuredDetailSlots,
        detailSlots = normalizedDetailSlots,
        capabilityStack = capabilityStack,
        capabilityDomain = capabilityDomain,
        capabilityId = capabilityId,
        processId = processId,
        checkpointId = checkpointId,
        reasonSummary = reasonSummary,
        missingInformation = missingInformation,
        requiredDetailSlots = requiredDetailSlots,
        verificationChecks = checks,
        confirmRequirement = confirmRequirement,
        executionGateFlag = when {
            requiredDetailSlots.isNotEmpty() -> ExecutionGateFlag.BLOCKED
            confirmRequirement != ConfirmRequirement.NONE -> ExecutionGateFlag.NEEDS_CONFIRM
            phase == TaskPhase.EXECUTING -> ExecutionGateFlag.READY_TO_START
            else -> ExecutionGateFlag.NO_EXECUTION
        }
    )
}

