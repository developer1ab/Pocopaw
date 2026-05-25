package com.atombits.pocopaw

import java.util.Locale

data class CanonicalAppEntry(
    val appId: String,
    val displayName: String,
    val packageNames: Set<String>,
    val aliasTerms: Set<String>,
    val domains: Set<CapabilityDomain>,
    val toolTerms: Set<String> = emptySet()
)

object CanonicalAppCatalog {
    private val entries = listOf(
        CanonicalAppEntry(
            appId = "jd",
            displayName = "京东",
            packageNames = setOf("com.jingdong.app.mall"),
            aliasTerms = setOf("jd", "jingdong", "京东"),
            domains = setOf(CapabilityDomain.SHOPPING)
        ),
        CanonicalAppEntry(
            appId = "amazon",
            displayName = "亚马逊购物",
            packageNames = setOf("cn.amazon.mshop.android"),
            aliasTerms = setOf("amazon", "amazonshopping", "mshop", "亚马逊", "亚马逊购物"),
            domains = setOf(CapabilityDomain.SHOPPING)
        ),
        CanonicalAppEntry(
            appId = "taobao",
            displayName = "淘宝",
            packageNames = setOf("com.taobao.taobao"),
            aliasTerms = setOf("taobao", "淘宝", "tb"),
            domains = setOf(CapabilityDomain.SHOPPING)
        ),
        CanonicalAppEntry(
            appId = "pdd",
            displayName = "拼多多",
            packageNames = setOf("com.xunmeng.pinduoduo"),
            aliasTerms = setOf("pdd", "pinduoduo", "拼多多"),
            domains = setOf(CapabilityDomain.SHOPPING)
        ),
        CanonicalAppEntry(
            appId = "meituan_takeout",
            displayName = "美团外卖",
            packageNames = setOf("com.sankuai.meituan.takeoutnew"),
            aliasTerms = setOf("meituan_takeout", "meituan takeout", "美团外卖", "com.meituan.takeout"),
            domains = setOf(CapabilityDomain.FOOD),
            toolTerms = setOf("takeout", "waimai")
        ),
        CanonicalAppEntry(
            appId = "eleme",
            displayName = "饿了么",
            packageNames = setOf("me.ele"),
            aliasTerms = setOf("eleme", "饿了么"),
            domains = setOf(CapabilityDomain.FOOD)
        ),
        CanonicalAppEntry(
            appId = "jingdong_daojia",
            displayName = "京东到家",
            packageNames = setOf("com.jingdong.pdj"),
            aliasTerms = setOf("jingdong_daojia", "jingdong daojia", "京东到家"),
            domains = setOf(CapabilityDomain.HOME_LIFE)
        ),
        CanonicalAppEntry(
            appId = "meituan",
            displayName = "美团",
            packageNames = setOf("com.sankuai.meituan"),
            aliasTerms = setOf("meituan", "美团", "mt"),
            domains = setOf(CapabilityDomain.HOME_LIFE, CapabilityDomain.LOCAL_SERVICE)
        ),
        CanonicalAppEntry(
            appId = "dianping",
            displayName = "大众点评",
            packageNames = emptySet(),
            aliasTerms = setOf("dianping", "大众点评"),
            domains = setOf(CapabilityDomain.LOCAL_SERVICE)
        ),
        CanonicalAppEntry(
            appId = "wuba",
            displayName = "58同城",
            packageNames = setOf("com.wuba"),
            aliasTerms = setOf("wuba", "58同城", "58"),
            domains = setOf(CapabilityDomain.HOME_LIFE, CapabilityDomain.LOCAL_SERVICE)
        ),
        CanonicalAppEntry(
            appId = "didi",
            displayName = "滴滴出行",
            packageNames = setOf("com.sdu.didi.psnger"),
            aliasTerms = setOf("didi", "滴滴", "com.didi.app"),
            domains = setOf(CapabilityDomain.TRANSPORT),
            toolTerms = setOf("ride", "taxi")
        ),
        CanonicalAppEntry(
            appId = "amap",
            displayName = "高德地图",
            packageNames = setOf("com.autonavi.minimap"),
            aliasTerms = setOf("amap", "高德", "高德地图", "autonavi"),
            domains = setOf(CapabilityDomain.TRANSPORT),
            toolTerms = setOf("navigate", "navigation")
        ),
        CanonicalAppEntry(
            appId = "ctrip",
            displayName = "携程旅行",
            packageNames = setOf("ctrip.android.view"),
            aliasTerms = setOf("ctrip", "携程", "携程旅行"),
            domains = setOf(CapabilityDomain.TRANSPORT)
        ),
        CanonicalAppEntry(
            appId = "qunar",
            displayName = "去哪儿旅行",
            packageNames = emptySet(),
            aliasTerms = setOf("qunar", "去哪儿", "去哪儿旅行"),
            domains = setOf(CapabilityDomain.TRANSPORT)
        ),
        CanonicalAppEntry(
            appId = "wechat",
            displayName = "微信",
            packageNames = setOf("com.tencent.mm"),
            aliasTerms = setOf("wechat", "weixin", "微信"),
            domains = setOf(CapabilityDomain.COMMUNICATION)
        ),
        CanonicalAppEntry(
            appId = "dingtalk",
            displayName = "钉钉",
            packageNames = emptySet(),
            aliasTerms = setOf("dingtalk", "钉钉"),
            domains = setOf(CapabilityDomain.COMMUNICATION)
        ),
        CanonicalAppEntry(
            appId = "alipay",
            displayName = "支付宝",
            packageNames = emptySet(),
            aliasTerms = setOf("alipay", "支付宝"),
            domains = setOf(CapabilityDomain.FINANCE)
        ),
        CanonicalAppEntry(
            appId = "settings",
            displayName = "设置",
            packageNames = setOf("com.android.settings", "android.settings"),
            aliasTerms = setOf("settings", "setting", "设置"),
            domains = setOf(CapabilityDomain.SYSTEM_CONTROL)
        ),
        CanonicalAppEntry(
            appId = "app_store",
            displayName = "软件商店",
            packageNames = setOf("com.heytap.market", "com.android.vending"),
            aliasTerms = setOf("app_store", "app store", "软件商店", "应用商店", "应用市场"),
            domains = setOf(CapabilityDomain.SYSTEM_CONTROL)
        )
    )

    private val entriesById = entries.associateBy(CanonicalAppEntry::appId)

    fun entryFor(appId: String): CanonicalAppEntry? {
        val normalized = resolveCanonicalAppId(appId) ?: appId.trim().lowercase(Locale.US)
        return entriesById[normalized]
    }

    fun appsForDomain(domain: CapabilityDomain): List<CanonicalAppEntry> {
        return entries.filter { entry -> domain in entry.domains }
    }

    internal fun allEntries(): List<CanonicalAppEntry> = entries
}

private data class CanonicalAppCandidate(
    val normalized: String,
    val scope: String,
    val tokens: List<String>
)

private val genericPackagePrefixes = setOf("com", "org", "net", "io", "android")

fun resolveCanonicalAppId(raw: String?): String? {
    val candidate = parseCanonicalAppCandidate(raw) ?: return null
    val exactMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        candidate.scope == entry.appId || candidate.normalized == entry.appId
    }
    if (exactMatches.size == 1) {
        return exactMatches.single().appId
    }
    if (exactMatches.isNotEmpty()) {
        return null
    }

    val packageMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        candidate.scope in entry.packageNames || candidate.normalized in entry.packageNames
    }
    if (packageMatches.size == 1) {
        return packageMatches.single().appId
    }
    if (packageMatches.isNotEmpty()) {
        return null
    }

    val packageTokenMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        entry.packageNames.any { packageName -> containsTokenSequence(candidate.tokens, tokenizeCanonicalAppValue(packageName)) }
    }
    if (packageTokenMatches.size == 1) {
        return packageTokenMatches.single().appId
    }
    if (packageTokenMatches.isNotEmpty()) {
        return null
    }

    val exactAliasMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        candidate.scope in entry.aliasTerms || candidate.normalized in entry.aliasTerms
    }
    if (exactAliasMatches.size == 1) {
        return exactAliasMatches.single().appId
    }
    if (exactAliasMatches.isNotEmpty()) {
        return null
    }

    val aliasSequenceMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        entry.aliasTerms.any { alias ->
            val aliasTokens = tokenizeCanonicalAppValue(alias)
            aliasTokens.size > 1 && containsTokenSequence(candidate.tokens, aliasTokens)
        }
    }
    if (aliasSequenceMatches.size == 1) {
        return aliasSequenceMatches.single().appId
    }
    if (aliasSequenceMatches.isNotEmpty()) {
        return null
    }

    val aliasMatches = CanonicalAppCatalog.allEntries().filter { entry ->
        candidate.tokens.any { token ->
            token == entry.appId || token in entry.aliasTerms || token in entry.toolTerms
        }
    }
    return aliasMatches.singleOrNull()?.appId
}

fun resolveCanonicalAppScope(raw: String?): String? {
    val candidate = parseCanonicalAppCandidate(raw) ?: return null
    resolveCanonicalAppId(raw)?.let { appId ->
        return appId
    }
    if (!candidate.normalized.startsWith("app.")) {
        return candidate.scope.takeIf { scope -> scope.isNotBlank() }
    }
    val firstScopeToken = candidate.scope.substringBefore('.').trim()
    return if (firstScopeToken.isNotBlank() && firstScopeToken !in genericPackagePrefixes) {
        firstScopeToken
    } else {
        candidate.scope.takeIf { scope -> scope.isNotBlank() }
    }
}

fun expandCanonicalAppTokens(tokens: Set<String>): Set<String> {
    if (tokens.isEmpty()) {
        return emptySet()
    }
    val expanded = linkedSetOf<String>()
    expanded.addAll(tokens)
    CanonicalAppCatalog.allEntries().forEach { entry ->
        val tokenMatch = entry.appId in tokens ||
            entry.aliasTerms.any { alias -> alias in tokens } ||
            entry.toolTerms.any { term -> term in tokens }
        val packageMatch = entry.packageNames.any { packageName ->
            tokenizeCanonicalAppValue(packageName).all { token -> token in tokens }
        }
        if (tokenMatch || packageMatch) {
            expanded.add(entry.appId)
            expanded.addAll(entry.aliasTerms)
            expanded.addAll(entry.toolTerms)
            entry.aliasTerms.forEach { alias ->
                expanded.addAll(tokenizeCanonicalAppValue(alias))
            }
            entry.packageNames.forEach { packageName ->
                expanded.addAll(tokenizeCanonicalAppValue(packageName))
            }
        }
    }
    return expanded
}

private fun parseCanonicalAppCandidate(raw: String?): CanonicalAppCandidate? {
    val normalized = raw?.trim()?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() } ?: return null
    val scope = when {
        normalized.startsWith("app.") -> normalized.removePrefix("app.").removeSuffix(".open")
        else -> normalized
    }.trim('.').ifBlank { return null }
    return CanonicalAppCandidate(
        normalized = normalized,
        scope = scope,
        tokens = tokenizeCanonicalAppValue(scope)
    )
}

private fun tokenizeCanonicalAppValue(value: String): List<String> {
    return value.lowercase(Locale.US)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { token -> token.trim() }
        .filter { token -> token.isNotBlank() }
}

private fun containsTokenSequence(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) {
        return false
    }
    return haystack.windowed(needle.size).any { window -> window == needle }
}