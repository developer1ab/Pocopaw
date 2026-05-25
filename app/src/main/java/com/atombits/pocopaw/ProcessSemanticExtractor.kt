package com.atombits.pocopaw

import java.util.Locale

internal data class ReducedCanonicalTrace(
    val steps: List<String>,
    val stepBindings: List<ProcessAssetStepBinding>
)

internal data class ProcessSemanticExtraction(
    val domain: String,
    val appScope: String,
    val processAction: String,
    val semanticDescription: String,
    val stages: List<String>,
    val acceptanceCriteria: List<String>,
    val stepBindings: List<ProcessAssetStepBinding>
)

private val canonicalDomainProcessActions = linkedMapOf(
    "SHOPPING" to linkedMapOf(
        "addtocart" to listOf("加购", "加入购物车", "放到购物车", "放进购物车", "放入购物车", "add to cart", "addtocart", "add_to_cart"),
        "buy" to listOf("购买", "买", "下单", "结算", "付款", "pay", "purchase", "buy", "checkout"),
        "clearcart" to listOf("清空购物车", "清掉购物车", "删除购物车", "移除购物车", "clear cart", "clearcart"),
        "return" to listOf("退款", "退货", "退掉", "refund", "return"),
        "compare" to listOf("比价", "比较", "对比", "compare"),
        "coupon" to listOf("领券", "优惠券", "券", "coupon"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments", "rating", "rate", "待评价", "未评价", "去评价", "好评", "五星好评"),
        "search" to listOf("搜索", "查找", "查一查", "筛选", "search", "browse", "filter")
    ),
    "FOOD" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search", "filter"),
        "order" to listOf("点餐", "下单", "点单", "订餐", "order"),
        "coupon" to listOf("领券", "优惠", "coupon"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments"),
        "cancel" to listOf("取消", "撤销", "cancel")
    ),
    "HOME_LIFE" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "book" to listOf("预约", "预订", "book", "reserve"),
        "schedule" to listOf("安排", "上门", "schedule"),
        "cancel" to listOf("取消", "cancel"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments")
    ),
    "TRANSPORT" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "book" to listOf("打车", "购票", "订票", "预约", "book", "reserve"),
        "navigate" to listOf("导航", "路线", "route", "navigate"),
        "cancel" to listOf("取消", "cancel"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments")
    ),
    "ENTERTAINMENT" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "play" to listOf("播放", "观看", "听", "play", "watch"),
        "buy" to listOf("购买", "buy", "purchase"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments"),
        "share" to listOf("分享", "share")
    ),
    "LOCAL_SERVICE" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "book" to listOf("预约", "预订", "book", "reserve"),
        "cancel" to listOf("取消", "cancel"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments")
    ),
    "INFORMATION" to linkedMapOf(
        "search" to listOf("搜索", "查询", "查找", "search", "query"),
        "compare" to listOf("比较", "对比", "比价", "compare"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments"),
        "share" to listOf("分享", "share")
    ),
    "FINANCE" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search", "query"),
        "pay" to listOf("支付", "付款", "pay", "payment"),
        "transfer" to listOf("转账", "收款", "transfer"),
        "bill" to listOf("账单", "bill")
    ),
    "SYSTEM_CONTROL" to linkedMapOf(
        "open" to listOf("打开", "启动", "open", "launch"),
        "toggle" to listOf("切换", "开关", "开启", "关闭", "toggle", "enable", "disable"),
        "set" to listOf("设置", "调整", "修改", "配置", "set", "adjust", "config"),
        "search" to listOf("搜索", "查找", "search")
    ),
    "COMMUNICATION" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "send" to listOf("发送", "发给", "send"),
        "reply" to listOf("回复", "reply"),
        "call" to listOf("打电话", "拨打", "call"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments")
    ),
    "OTHER" to linkedMapOf(
        "search" to listOf("搜索", "查找", "search"),
        "run" to listOf("执行", "处理", "run", "do"),
        "comments" to listOf("评论", "评价", "review", "comment", "comments")
    )
)

private val processActionAliases = linkedMapOf(
    "addtocart" to setOf("addtocart", "add_to_cart", "cart", "addcart"),
    "buy" to setOf("buy", "purchase", "checkout", "pay"),
    "clearcart" to setOf("clearcart", "clear_cart", "delete_cart", "remove_cart"),
    "return" to setOf("return", "refund"),
    "compare" to setOf("compare"),
    "coupon" to setOf("coupon"),
    "comments" to setOf("comments", "comment", "review", "reviews", "rating", "rate"),
    "search" to setOf("search", "browse", "query", "filter"),
    "order" to setOf("order"),
    "book" to setOf("book", "reserve"),
    "schedule" to setOf("schedule"),
    "cancel" to setOf("cancel"),
    "navigate" to setOf("navigate", "route"),
    "play" to setOf("play", "watch"),
    "share" to setOf("share"),
    "open" to setOf("open", "launch"),
    "toggle" to setOf("toggle", "enable", "disable"),
    "set" to setOf("set", "adjust", "config"),
    "send" to setOf("send"),
    "reply" to setOf("reply"),
    "call" to setOf("call"),
    "run" to setOf("run", "do")
)

private val processActionStopTokens = buildSet {
    addAll(setOf("generic", "process", "flow"))
    CanonicalAppCatalog.allEntries().forEach { entry ->
        add(entry.appId)
        entry.aliasTerms.forEach { alias ->
            addAll(tokenizeProcessStopTerm(alias))
        }
        entry.toolTerms.forEach { term ->
            addAll(tokenizeProcessStopTerm(term))
        }
        entry.packageNames.forEach { packageName ->
            addAll(tokenizeProcessStopTerm(packageName))
        }
    }
}

private val genericToolAppScopes = setOf("browser", "search", "web", "generic", "unknown")

private val defaultDomainProcessActions = mapOf(
    "SHOPPING" to "search",
    "FOOD" to "order",
    "HOME_LIFE" to "book",
    "TRANSPORT" to "book",
    "ENTERTAINMENT" to "play",
    "LOCAL_SERVICE" to "book",
    "INFORMATION" to "search",
    "FINANCE" to "pay",
    "SYSTEM_CONTROL" to "toggle",
    "COMMUNICATION" to "send",
    "OTHER" to "run"
)

internal fun extractProcessSemantics(
    rawMaterial: CanonicalTraceRawMaterial,
    reducedTrace: ReducedCanonicalTrace = reduceCanonicalTrace(rawMaterial)
): ProcessSemanticExtraction {
    val stages = reducedTrace.steps.mapNotNull(::extractCanonicalStageName).distinct()
    val stepBindings = reducedTrace.stepBindings
    return ProcessSemanticExtraction(
        domain = inferCanonicalProcessDomain(
            processId = rawMaterial.processId,
            objective = rawMaterial.objective,
            selectedToolId = rawMaterial.selectedToolId,
            stageNames = stages
        ),
        appScope = deriveCanonicalProcessAppScope(rawMaterial),
        processAction = rawMaterial.resolvedProcessAction(),
        semanticDescription = rawMaterial.objective.trim().ifBlank { rawMaterial.processId },
        stages = stages,
        acceptanceCriteria = reducedTrace.steps.mapNotNull(::extractCanonicalAcceptanceSignal).distinct(),
        stepBindings = stepBindings
    )
}

internal fun canonicalProcessActionsForDomain(domain: String?): Set<String> {
    val canonicalDomain = canonicalizeProcessDomain(domain) ?: return emptySet()
    return canonicalDomainProcessActions[canonicalDomain]?.keys?.toSet().orEmpty()
}

internal fun inferCanonicalProcessAction(
    processId: String,
    objective: String,
    domain: String? = null,
    actionHint: String? = null
): String {
    val resolvedDomain = canonicalizeProcessDomain(domain)
        ?: inferCanonicalProcessDomain(processId = processId, objective = objective)
    val allowedActions = canonicalDomainProcessActions[resolvedDomain].orEmpty()
    inferCanonicalProcessActionFromProcessId(processId, resolvedDomain)?.let { action ->
        return action
    }
    if (resolvedDomain == "SHOPPING") {
        inferShoppingContextualProcessAction(
            processId = processId,
            objective = objective,
            actionHint = actionHint
        )?.let { action ->
            return action
        }
    }
    val normalizedSearchText = listOf(actionHint, processId, objective)
        .joinToString(separator = " ")
        .lowercase(Locale.US)

    allowedActions.entries.firstOrNull { (_, keywords) ->
        keywords.any { keyword -> normalizedSearchText.contains(keyword.lowercase(Locale.US)) }
    }?.key?.let { action ->
        return action
    }

    val normalizedTokens = listOf(actionHint.orEmpty(), processId)
        .joinToString(separator = "_")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .split('_')
        .map { token -> token.trim() }
        .filter { token -> token.isNotBlank() && token !in processActionStopTokens }

    val collapsedTokens = collapseProcessActionTokens(normalizedTokens)
    collapsedTokens.firstNotNullOfOrNull { token ->
        canonicalizeProcessAction(token, resolvedDomain)
    }?.let { action ->
        return action
    }

    return defaultDomainProcessActions[resolvedDomain] ?: "run"
}

private fun inferCanonicalProcessActionFromProcessId(
    processId: String,
    domain: String? = null
): String? {
    val sanitizedProcessId = sanitizeCanonicalProcessId(processId) ?: return null
    val rawTokens = sanitizedProcessId
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .split('_')
        .map { token -> token.trim() }
        .filter { token -> token.isNotBlank() }
    if (rawTokens.size < 2 && !sanitizedProcessId.contains('_') && !sanitizedProcessId.contains('-')) {
        return canonicalizeProcessAction(sanitizedProcessId, domain)
            ?.takeUnless { action -> action == "shopping" }
    }
    val normalizedTokens = rawTokens.filterNot { token -> token in processActionStopTokens }
    if (normalizedTokens.isEmpty()) {
        return null
    }
    return collapseProcessActionTokens(normalizedTokens)
        .firstNotNullOfOrNull { token -> canonicalizeProcessAction(token, domain) }
}

private fun inferShoppingContextualProcessAction(
    processId: String,
    objective: String,
    actionHint: String?
): String? {
    val normalizedText = listOf(processId, objective, actionHint.orEmpty())
        .joinToString(separator = " ")
        .lowercase(Locale.US)

    fun containsAny(vararg keywords: String): Boolean {
        return keywords.any { keyword -> normalizedText.contains(keyword.lowercase(Locale.US)) }
    }

    val hasCartContext = containsAny("cart", "购物车")
    val hasDeleteIntent = containsAny("delete", "remove", "clear", "删除", "移除", "清空")
    val hasAddToCartIntent = containsAny(
        "add to cart",
        "addtocart",
        "add_to_cart",
        "加入购物车",
        "放入购物车",
        "放进购物车",
        "放到购物车",
        "加购"
    )
    return if (hasCartContext && hasDeleteIntent && !hasAddToCartIntent) {
        "clearcart"
    } else {
        null
    }
}

internal fun inferCanonicalProcessDomain(
    processId: String,
    objective: String,
    selectedToolId: String? = null,
    stageNames: List<String> = emptyList()
): String {
    val normalized = listOf(processId, objective, selectedToolId.orEmpty(), stageNames.joinToString(" "))
        .joinToString(separator = " ")
        .lowercase(Locale.US)

    fun containsAny(vararg keywords: String): Boolean {
        return keywords.any { keyword -> normalized.contains(keyword.lowercase(Locale.US)) }
    }

    return when {
        containsAny("settings", "wifi", "bluetooth", "permission", "toggle", "通知", "权限", "设置", "亮度") -> "SYSTEM_CONTROL"
        containsAny("message", "chat", "call", "email", "sms", "wechat", "weixin", "微信", "电话", "短信") -> "COMMUNICATION"
        containsAny("payment", "transfer", "bill", "bank", "wallet", "loan", "insurance", "支付", "付款", "转账", "账单", "银行") -> "FINANCE"
        containsAny("takeout", "meal", "restaurant", "coffee", "food", "外卖", "点餐", "吃饭", "美团", "饿了么") -> "FOOD"
        containsAny("taxi", "ride", "commute", "metro", "bus", "didi", "trip", "打车", "出行", "路线") -> "TRANSPORT"
        containsAny("appointment", "onsite", "provider", "service", "预约", "上门", "本地服务") -> "LOCAL_SERVICE"
        containsAny("clean", "laundry", "repair", "home", "household", "家政", "保洁", "维修") -> "HOME_LIFE"
        containsAny("movie", "music", "video", "game", "entertainment", "影视", "音乐", "视频", "游戏") -> "ENTERTAINMENT"
        containsAny("buy", "cart", "coupon", "refund", "return", "shop", "order", "购物", "下单", "购买", "京东", "淘宝", "拼多多", "亚马逊", "jingdong", "taobao", "pinduoduo", "amazon", "mshop") -> "SHOPPING"
        containsAny("search", "query", "compare", "news", "wiki", "article", "查", "搜索", "信息", "对比") -> "INFORMATION"
        else -> "OTHER"
    }
}

internal fun canonicalizeProcessDomain(domain: String?): String? {
    return CapabilityDomain.fromRaw(domain)?.name
}

internal fun deriveCanonicalProcessAppScope(rawMaterial: CanonicalTraceRawMaterial): String {
    val toolScope = resolveCanonicalProcessAppScopeFromToolId(rawMaterial.selectedToolId)
    if (toolScope != null) {
        return toolScope
    }

    val processPrefix = rawMaterial.processId.substringBefore('_', missingDelimiterValue = "")
        .trim()
        .takeIf { prefix -> prefix.isNotBlank() }
        ?.let(::canonicalizeProcessAppScope)
    return processPrefix ?: "unknown"
}

internal fun CanonicalTraceRawMaterial.resolvedProcessAction(): String {
    val domainHint = inferCanonicalProcessDomain(processId = processId, objective = objective, selectedToolId = selectedToolId)
    return resolveStoredOrInferredProcessAction(
        processId = processId,
        domainHint = domainHint,
        contextText = objective,
        existingAction = processAction
    )
}

internal fun ReadyProcessAsset.resolvedProcessAction(): String {
    return resolveStoredOrInferredProcessAction(
        processId = processId,
        domainHint = domain,
        contextText = semanticDescription,
        existingAction = processAction
    )
}

internal fun ProcessShortcutCandidate.resolvedProcessAction(domainHint: String? = null): String {
    return resolveStoredOrInferredProcessAction(
        processId = processId,
        domainHint = domainHint,
        contextText = screenSignature,
        existingAction = processAction,
        fallbackActionHint = shortcutId
    )
}

internal fun ProcessCandidateRecord.resolvedProcessAction(): String {
    return resolveStoredOrInferredProcessAction(
        processId = processId,
        domainHint = domain,
        contextText = recordName,
        existingAction = processAction
    )
}

private fun resolveStoredOrInferredProcessAction(
    processId: String,
    domainHint: String?,
    contextText: String,
    existingAction: String?,
    fallbackActionHint: String? = null
): String {
    inferCanonicalProcessActionFromProcessId(processId, domainHint)?.let { action ->
        return action
    }
    val normalizedExistingAction = existingAction?.trim()?.takeIf { value -> value.isNotBlank() }
    return normalizedExistingAction?.let { action ->
        canonicalizeProcessAction(action, domainHint) ?: inferCanonicalProcessAction(processId, contextText, domainHint, action)
    } ?: inferCanonicalProcessAction(processId, contextText, domainHint, fallbackActionHint)
}

internal fun resolveCanonicalBoundaryPacketProcessAction(boundaryPacket: TaskExecutionBoundaryPacket, domainHint: String? = null): String? {
    val actionHint = boundaryPacket.actionCode.takeUnless { actionCode -> actionCode == ActionCode.UNKNOWN }?.wireName
    if (boundaryPacket.processId.isNullOrBlank() && boundaryPacket.objectiveSummary.isBlank() && actionHint.isNullOrBlank()) {
        return null
    }
    return inferCanonicalProcessAction(
        processId = boundaryPacket.processId.orEmpty(),
        objective = boundaryPacket.objectiveSummary,
        domain = domainHint,
        actionHint = actionHint
    )
}

internal fun resolveCanonicalProcessAppScopeFromToolId(selectedToolId: String?): String? {
    return extractCanonicalAppScope(selectedToolId)
        ?.trim()
        ?.takeIf { scope -> scope.isNotBlank() }
        ?.let(::canonicalizeProcessAppScope)
        ?.takeUnless { scope -> scope in genericToolAppScopes }
}

internal fun sanitizeCanonicalProcessId(rawProcessId: String?): String? {
    val normalized = rawProcessId?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    val lowered = normalized.lowercase(Locale.US)
    return when {
        lowered == "null" -> null
        lowered == "null_process" -> null
        lowered.endsWith("_null_process") -> null
        lowered.endsWith("_null") -> null
        else -> normalized
    }
}

internal fun deriveCanonicalProcessScope(
    rawProcessId: String?,
    objective: String,
    appScope: String? = null,
    domain: String? = null,
    actionHint: String? = null,
    selectedToolId: String? = null
): String? {
    val sanitizedRawProcessId = sanitizeCanonicalProcessId(rawProcessId)
    val resolvedAppScope = appScope
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?.let(::canonicalizeProcessAppScope)
        ?.takeUnless { scope -> scope in genericToolAppScopes }
        ?: resolveCanonicalProcessAppScopeFromToolId(selectedToolId)
        ?: deriveRawProcessAppScope(sanitizedRawProcessId)
        ?: return null
    val resolvedDomain = canonicalizeProcessDomain(domain)
        ?: inferCanonicalProcessDomain(processId = sanitizedRawProcessId.orEmpty(), objective = objective, selectedToolId = selectedToolId)
    val resolvedAction = canonicalizeProcessAction(actionHint, resolvedDomain)
        ?: inferCanonicalProcessAction(
            processId = sanitizedRawProcessId.orEmpty(),
            objective = objective,
            domain = resolvedDomain,
            actionHint = actionHint
        )
    val normalizedRawProcessScope = normalizeProcessScopeValue(sanitizedRawProcessId)
    if (normalizedRawProcessScope != null && shouldPreserveRawCanonicalProcessScope(
            rawProcessScope = normalizedRawProcessScope,
            appScope = resolvedAppScope,
            domain = resolvedDomain,
            action = resolvedAction
        )
    ) {
        return normalizedRawProcessScope
    }
    return derivePolicyProcessScope(
        appId = resolvedAppScope,
        domain = resolvedDomain,
        action = resolvedAction
    )
}

internal fun canonicalizeProcessAction(rawAction: String?, domain: String? = null): String? {
    val resolvedDomain = canonicalizeProcessDomain(domain)
    val allowedActions = resolvedDomain?.let(::canonicalProcessActionsForDomain).orEmpty()
    SharedActionNormalization.fromRaw(rawAction)?.let { action ->
        SharedActionNormalization.toProcessAction(action, allowedActions)?.let { processAction ->
            return processAction
        }
    }
    val normalized = rawAction?.trim()?.lowercase(Locale.US)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.trim('_')
        ?.takeIf { value -> value.isNotBlank() }
        ?: return null
    processActionAliases.entries.firstOrNull { (_, aliases) -> normalized in aliases }?.key?.let { action ->
        if (allowedActions.isEmpty() || action in allowedActions) {
            return action
        }
    }
    return normalized.takeIf { action -> allowedActions.isEmpty() || action in allowedActions }
}

internal fun canonicalizeProcessAppScope(rawScope: String): String {
    val normalized = rawScope.trim().lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }
    return resolveCanonicalAppScope(rawScope)
        ?: resolveCanonicalAppId(normalized)
        ?: normalized
}

private fun tokenizeProcessStopTerm(value: String): List<String> {
    return value.lowercase(Locale.US)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { token -> token.trim() }
        .filter { token -> token.isNotBlank() }
}

private fun deriveRawProcessAppScope(rawProcessId: String?): String? {
    val normalizedRawProcessId = rawProcessId?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
    resolveCanonicalAppId(normalizedRawProcessId)?.let { appId ->
        return appId
    }
    return normalizedRawProcessId
        .substringBefore('_', missingDelimiterValue = "")
        .trim()
        .takeIf { prefix -> prefix.isNotBlank() }
        ?.let(::canonicalizeProcessAppScope)
}

private fun shouldPreserveRawCanonicalProcessScope(
    rawProcessScope: String,
    appScope: String,
    domain: String?,
    action: String
): Boolean {
    if (!rawProcessScope.endsWith("_process")) {
        return false
    }
    if (rawProcessScope.startsWith("other_") || rawProcessScope.startsWith("generic_")) {
        return false
    }
    if (isAllowedProcessScopeForApp(appScope, rawProcessScope)) {
        return true
    }
    return rawProcessScope == derivePolicyProcessScope(
        appId = appScope,
        domain = domain,
        action = action
    )
}

internal fun reduceCanonicalTrace(rawMaterial: CanonicalTraceRawMaterial): ReducedCanonicalTrace {
    val sourceSteps = rawMaterial.steps
    if (sourceSteps.isEmpty()) {
        return ReducedCanonicalTrace(emptyList(), emptyList())
    }

    val reducedSteps = mutableListOf<String>()
    val reducedBindings = mutableListOf<ProcessAssetStepBinding>()
    var previousStepKey: String? = null

    sourceSteps.forEachIndexed { index, step ->
        val binding = extractCanonicalProcessAssetStepBinding(step)
        val stepKey = buildCanonicalTraceStepKey(step, binding)
        if (stepKey == previousStepKey) {
            return@forEachIndexed
        }
        reducedSteps.add(step)
        binding?.let(reducedBindings::add)
        previousStepKey = stepKey
    }

    return ReducedCanonicalTrace(reducedSteps, reducedBindings)
}

internal fun alignProcessAssetBindingsToTraceSteps(
    steps: List<String>,
    bindings: List<ProcessAssetStepBinding>
): List<ProcessAssetStepBinding?> {
    if (steps.isEmpty() || bindings.isEmpty()) {
        return List(steps.size) { null }
    }

    var bindingIndex = 0
    return steps.map { step ->
        val stepType = extractCanonicalStageName(step)
            ?.trim()
            ?.uppercase(Locale.US)
        val candidate = bindings.getOrNull(bindingIndex)
        if (
            stepType != null &&
            candidate != null &&
            candidate.stepType.trim().uppercase(Locale.US) == stepType
        ) {
            bindingIndex += 1
            candidate
        } else {
            null
        }
    }
}

internal fun buildPayloadPreservingCanonicalTrace(
    rawMaterial: CanonicalTraceRawMaterial
): List<String> {
    val reducedTrace = reduceCanonicalTrace(rawMaterial)
    return reducedTrace.steps.map { step ->
        val binding = extractCanonicalProcessAssetStepBinding(step) ?: return@map step
        serializeCanonicalTraceLineWithBinding(step, binding)
    }
}

internal fun serializeCanonicalTraceLineWithBinding(
    step: String,
    binding: ProcessAssetStepBinding
): String {
    val parts = step.split(" | ").toMutableList()
    while (parts.size < 4) {
        parts += ""
    }
    val baseNote = binding.note?.takeIf { value -> value.isNotBlank() }
        ?: parts[3].takeIf { value -> value.isNotBlank() }
        ?: binding.locatorHint.orEmpty()
    val attributes = buildList {
        binding.locatorHint?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("locator=$value")
        }
        if (binding.targetX != null && binding.targetY != null) {
            add("target=${binding.targetX},${binding.targetY}")
        }
        if (
            binding.swipeFromX != null &&
            binding.swipeFromY != null &&
            binding.swipeToX != null &&
            binding.swipeToY != null
        ) {
            add("swipe=${binding.swipeFromX},${binding.swipeFromY},${binding.swipeToX},${binding.swipeToY}")
        }
        binding.inputText?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("text=$value")
        }
        binding.actionDurationMs?.let { value ->
            add("duration_ms=$value")
        }
        binding.pageSignature?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("page=$value")
        }
    }
    parts[3] = listOf(baseNote, attributes.joinToString(separator = ";").takeIf { value -> value.isNotBlank() })
        .filterNotNull()
        .joinToString(separator = ";")
        .ifBlank { parts[3] }
    return parts.joinToString(separator = " | ")
}

internal fun extractCanonicalStageName(step: String): String? {
    val parts = step.split(" | ")
    return parts.firstOrNull()?.takeIf { part -> part.isNotBlank() }
}

internal fun extractCanonicalAcceptanceSignal(step: String): String? {
    val parts = step.split(" | ")
    return parts.getOrNull(2)?.takeIf { part -> part.isNotBlank() }
}

internal fun extractCanonicalProcessAssetStepBinding(step: String): ProcessAssetStepBinding? {
    val parts = step.split(" | ")
    val stepType = parts.firstOrNull()?.takeIf { part -> part.isNotBlank() } ?: return null
    val expectedOutcome = parts.getOrNull(2)?.takeIf { part -> part.isNotBlank() }
    val note = parts.getOrNull(3)?.takeIf { part -> part.isNotBlank() }
    val hasStructuredPayloadAttributes = note?.let { value ->
        value.contains("locator=") ||
            value.contains("target=") ||
            value.contains("swipe=") ||
            value.contains("text=") ||
            value.contains("input=") ||
            value.contains("duration_ms=") ||
            value.contains("duration=") ||
            value.contains("page=")
    } == true
    if (!hasStructuredPayloadAttributes) {
        return null
    }
    val target = note?.let(::extractCanonicalTargetCoordinates)
    val swipe = note?.let(::extractCanonicalSwipeCoordinates)
    return ProcessAssetStepBinding(
        stepType = stepType,
        actionType = deriveCanonicalProcessAssetActionType(stepType),
        locatorHint = note?.let(::extractCanonicalLocatorHint),
        targetX = target?.first,
        targetY = target?.second,
        inputText = note?.let(::extractCanonicalInputText),
        swipeFromX = swipe?.component1(),
        swipeFromY = swipe?.component2(),
        swipeToX = swipe?.component3(),
        swipeToY = swipe?.component4(),
        actionDurationMs = note?.let(::extractCanonicalActionDurationMs),
        verificationSignals = listOfNotNull(expectedOutcome),
        pageSignature = note?.let(::extractCanonicalPageSignature),
        note = note
    )
}

private fun buildCanonicalTraceStepKey(
    step: String,
    binding: ProcessAssetStepBinding?
): String {
    if (binding == null) {
        return step.trim().lowercase(Locale.US)
    }
    return listOf(
        binding.stepType.trim().lowercase(Locale.US),
        binding.actionType.name,
        binding.locatorHint.orEmpty().trim().lowercase(Locale.US),
        binding.targetX?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.targetY?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.inputText.orEmpty().trim().lowercase(Locale.US),
        binding.swipeFromX?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.swipeFromY?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.swipeToX?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.swipeToY?.let { value -> String.format(Locale.US, "%.3f", value) }.orEmpty(),
        binding.actionDurationMs?.toString().orEmpty(),
        binding.pageSignature.orEmpty().trim().lowercase(Locale.US),
        binding.verificationSignals.joinToString(separator = ",") { signal -> signal.trim().lowercase(Locale.US) }
    ).joinToString(separator = "###")
}

private fun collapseProcessActionTokens(tokens: List<String>): List<String> {
    if (tokens.isEmpty()) {
        return emptyList()
    }
    val collapsed = mutableListOf<String>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val next = tokens.getOrNull(index + 1)
        val nextNext = tokens.getOrNull(index + 2)
        when {
            token == "add" && next == "to" && nextNext == "cart" -> {
                collapsed += "addtocart"
                index += 3
            }
            token == "add" && next == "cart" -> {
                collapsed += "addtocart"
                index += 2
            }
            token == "clear" && next == "cart" -> {
                collapsed += "clearcart"
                index += 2
            }
            token == "delete" && next == "cart" -> {
                collapsed += "clearcart"
                index += 2
            }
            token == "remove" && next == "cart" -> {
                collapsed += "clearcart"
                index += 2
            }
            token == "comment" || token == "comments" || token == "review" || token == "reviews" || token == "rating" || token == "rate" -> {
                collapsed += "comments"
                index += 1
            }
            else -> {
                collapsed += token
                index += 1
            }
        }
    }
    return collapsed
}

private fun deriveCanonicalProcessAssetActionType(stepType: String): VisionActionType {
    val normalizedStepType = stepType.uppercase(Locale.US)
    return when {
        normalizedStepType.contains("INPUT") -> VisionActionType.INPUT
        normalizedStepType.contains("BACK") -> VisionActionType.BACK
        normalizedStepType.contains("WAIT") || normalizedStepType.contains("VERIFY") -> VisionActionType.WAIT
        normalizedStepType.contains("SWIPE") -> VisionActionType.SWIPE
        else -> VisionActionType.TAP
    }
}

private fun extractCanonicalLocatorHint(note: String): String? {
    val match = Regex("(?:^|;)locator=([^;]+)").find(note)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { value -> value.isNotEmpty() }
}

private fun extractCanonicalTargetCoordinates(note: String): Pair<Float, Float>? {
    val match = Regex("(?:^|;)target=([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+)").find(note) ?: return null
    val x = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
    val y = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
    return x to y
}

private fun extractCanonicalSwipeCoordinates(note: String): SwipeCoordinates? {
    val match = Regex("(?:^|;)swipe=([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+),([0-9]*\\.?[0-9]+)").find(note)
        ?: return null
    val fromX = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return null
    val fromY = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: return null
    val toX = match.groupValues.getOrNull(3)?.toFloatOrNull() ?: return null
    val toY = match.groupValues.getOrNull(4)?.toFloatOrNull() ?: return null
    return SwipeCoordinates(fromX, fromY, toX, toY)
}

private fun extractCanonicalActionDurationMs(note: String): Long? {
    val match = Regex("(?:^|;)(?:duration_ms|duration)=([0-9]+)").find(note) ?: return null
    return match.groupValues.getOrNull(1)?.toLongOrNull()
}

private fun extractCanonicalPageSignature(note: String): String? {
    val match = Regex("(?:^|;)page=([^;]+)").find(note)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { value -> value.isNotEmpty() }
}

private fun extractCanonicalInputText(note: String): String? {
    val match = Regex("(?:^|;)(?:text|input)=([^;]+)").find(note)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { value -> value.isNotEmpty() }
}

private data class SwipeCoordinates(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float
)