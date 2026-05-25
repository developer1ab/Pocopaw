package com.atombits.pocopaw

import java.util.Locale

data class AppProcessPolicy(
    val appId: String,
    val allowedProcessScopes: Set<String> = emptySet(),
    val defaultScopeByDomain: Map<CapabilityDomain, String> = emptyMap(),
    val actionScopeOverrides: Map<String, String> = emptyMap(),
    val cautionsByProcess: Map<String, List<String>> = emptyMap()
)

private fun buildAppProcessPolicy(
    appId: String,
    defaultScopeByDomain: Map<CapabilityDomain, String> = emptyMap(),
    actionScopeOverrides: Map<String, String> = emptyMap(),
    cautionsByProcess: Map<String, List<String>> = emptyMap(),
    extraAllowedProcessScopes: Set<String> = emptySet()
): AppProcessPolicy {
    return AppProcessPolicy(
        appId = appId,
        allowedProcessScopes = (defaultScopeByDomain.values + actionScopeOverrides.values + extraAllowedProcessScopes).toSet(),
        defaultScopeByDomain = defaultScopeByDomain,
        actionScopeOverrides = actionScopeOverrides,
        cautionsByProcess = cautionsByProcess
    )
}

object AppProcessPolicyRegistry {
    private val policies = listOf(
        buildAppProcessPolicy(
            appId = "jd",
            defaultScopeByDomain = mapOf(CapabilityDomain.SHOPPING to "jd_shopping_process"),
            actionScopeOverrides = mapOf(
                "search" to "jd_shopping_process",
                "addtocart" to "jd_addtocart_process",
                "buy" to "jd_buy_process",
                "return" to "jd_return_process",
                "compare" to "jd_compare_process",
                "coupon" to "jd_coupon_process",
                "clearcart" to "jd_clearcart_process",
                "comments" to "jd_rating_process",
                "rating" to "jd_rating_process"
            ),
            cautionsByProcess = mapOf(
                "jd_return_process" to listOf("退款/退货/售后必须走 我的 -> 全部订单 -> 目标订单 -> 订单级退款/退货/售后控件。")
            )
        ),
        buildAppProcessPolicy(
            appId = "taobao",
            defaultScopeByDomain = mapOf(CapabilityDomain.SHOPPING to "taobao_shopping_process"),
            actionScopeOverrides = mapOf(
                "search" to "taobao_shopping_process",
                "coupon" to "taobao_coupon_process"
            ),
            cautionsByProcess = mapOf(
                "taobao_return_process" to listOf("退款/退货/售后必须走 我的 -> 全部订单 -> 目标订单 -> 订单级退款/退货/售后控件。")
            )
        ),
        buildAppProcessPolicy(
            appId = "pdd",
            defaultScopeByDomain = mapOf(CapabilityDomain.SHOPPING to "pdd_shopping_process"),
            actionScopeOverrides = mapOf("search" to "pdd_shopping_process")
        ),
        buildAppProcessPolicy(
            appId = "amazon",
            defaultScopeByDomain = mapOf(CapabilityDomain.SHOPPING to "amazon_shopping_process"),
            actionScopeOverrides = mapOf(
                "search" to "amazon_shopping_process",
                "addtocart" to "amazon_addtocart_process",
                "buy" to "amazon_buy_process",
                "return" to "amazon_return_process",
                "compare" to "amazon_compare_process",
                "coupon" to "amazon_coupon_process",
                "clearcart" to "amazon_clearcart_process",
                "comments" to "amazon_rating_process",
                "rating" to "amazon_rating_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "meituan_takeout",
            defaultScopeByDomain = mapOf(CapabilityDomain.FOOD to "meituan_takeout_food_process"),
            actionScopeOverrides = mapOf(
                "order" to "meituan_takeout_order_process",
                "search" to "meituan_takeout_search_process",
                "coupon" to "meituan_takeout_coupon_process",
                "comments" to "meituan_takeout_comments_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "eleme",
            defaultScopeByDomain = mapOf(CapabilityDomain.FOOD to "eleme_food_process"),
            actionScopeOverrides = mapOf(
                "order" to "eleme_order_process",
                "search" to "eleme_search_process",
                "coupon" to "eleme_coupon_process",
                "comments" to "eleme_comments_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "meituan",
            defaultScopeByDomain = mapOf(
                CapabilityDomain.HOME_LIFE to "meituan_home_life_process",
                CapabilityDomain.LOCAL_SERVICE to "meituan_local_service_process"
            ),
            actionScopeOverrides = mapOf(
                "book" to "meituan_book_process",
                "search" to "meituan_search_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "wuba",
            defaultScopeByDomain = mapOf(
                CapabilityDomain.HOME_LIFE to "wuba_home_life_process",
                CapabilityDomain.LOCAL_SERVICE to "wuba_local_service_process"
            ),
            actionScopeOverrides = mapOf("book" to "wuba_book_process")
        ),
        buildAppProcessPolicy(
            appId = "didi",
            defaultScopeByDomain = mapOf(CapabilityDomain.TRANSPORT to "didi_transport_process"),
            actionScopeOverrides = mapOf(
                "book" to "didi_book_process",
                "search" to "didi_search_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "amap",
            defaultScopeByDomain = mapOf(CapabilityDomain.TRANSPORT to "amap_transport_process"),
            actionScopeOverrides = mapOf(
                "navigate" to "amap_navigate_process",
                "search" to "amap_search_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "ctrip",
            defaultScopeByDomain = mapOf(CapabilityDomain.TRANSPORT to "ctrip_transport_process"),
            actionScopeOverrides = mapOf(
                "book" to "ctrip_book_process",
                "search" to "ctrip_search_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "qunar",
            defaultScopeByDomain = mapOf(CapabilityDomain.TRANSPORT to "qunar_transport_process"),
            actionScopeOverrides = mapOf(
                "book" to "qunar_book_process",
                "search" to "qunar_search_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "wechat",
            defaultScopeByDomain = mapOf(CapabilityDomain.COMMUNICATION to "wechat_communication_process"),
            actionScopeOverrides = mapOf(
                "send" to "wechat_send_process",
                "reply" to "wechat_reply_process",
                "call" to "wechat_call_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "dingtalk",
            defaultScopeByDomain = mapOf(CapabilityDomain.COMMUNICATION to "dingtalk_communication_process"),
            actionScopeOverrides = mapOf(
                "send" to "dingtalk_send_process",
                "reply" to "dingtalk_reply_process",
                "call" to "dingtalk_call_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "alipay",
            defaultScopeByDomain = mapOf(CapabilityDomain.FINANCE to "alipay_finance_process"),
            actionScopeOverrides = mapOf(
                "pay" to "alipay_pay_process",
                "transfer" to "alipay_transfer_process",
                "bill" to "alipay_bill_process"
            )
        ),
        buildAppProcessPolicy(
            appId = "settings",
            defaultScopeByDomain = mapOf(CapabilityDomain.SYSTEM_CONTROL to "settings_system_control_process"),
            actionScopeOverrides = mapOf(
                "toggle" to "settings_toggle_process",
                "open" to "settings_open_process",
                "set" to "settings_set_process"
            )
        )
    )

    private val policiesByAppId = policies.associateBy(AppProcessPolicy::appId)

    fun policyFor(appId: String): AppProcessPolicy? {
        val normalized = resolveCanonicalAppId(appId) ?: appId.trim().lowercase(Locale.US)
        return policiesByAppId[normalized]
    }
}

fun policyFor(appId: String): AppProcessPolicy? = AppProcessPolicyRegistry.policyFor(appId)

internal fun allowedProcessScopesForApp(appId: String?): Set<String> {
    val normalizedAppId = appId?.let(::resolveCanonicalAppId) ?: appId?.trim()?.lowercase(Locale.US)
    return normalizedAppId?.let { normalized -> policyFor(normalized)?.allowedProcessScopes }.orEmpty()
}

internal fun isAllowedProcessScopeForApp(appId: String?, processScope: String?): Boolean {
    val normalizedScope = normalizeProcessScopeValue(processScope) ?: return false
    return normalizedScope in allowedProcessScopesForApp(appId)
}

internal fun appProcessCautions(appId: String?, processScope: String?): List<String> {
    val normalizedScope = normalizeProcessScopeValue(processScope) ?: return emptyList()
    val normalizedAppId = appId?.let(::resolveCanonicalAppId) ?: appId?.trim()?.lowercase(Locale.US) ?: return emptyList()
    return policyFor(normalizedAppId)?.cautionsByProcess?.get(normalizedScope).orEmpty()
}

internal fun derivePolicyProcessScope(
    appId: String,
    domain: String? = null,
    action: String? = null
): String {
    val normalizedAppId = resolveCanonicalAppId(appId) ?: appId.trim().lowercase(Locale.US)
    val normalizedDomain = canonicalizeProcessDomain(domain)?.let(CapabilityDomain::fromRaw)
    val normalizedAction = canonicalizeProcessAction(action, normalizedDomain?.name)
    val policy = policyFor(normalizedAppId)
    policy?.actionScopeOverrides?.get(normalizedAction)?.let { scope ->
        return scope
    }
    policy?.defaultScopeByDomain?.get(normalizedDomain)?.let { scope ->
        return scope
    }
    return buildGenericProcessScope(normalizedAppId, normalizedDomain, normalizedAction)
}

internal fun normalizeProcessScopeValue(value: String?): String? {
    return value
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.trim('_')
        ?.takeIf { normalized -> normalized.isNotBlank() }
}

private fun buildGenericProcessScope(
    appId: String,
    domain: CapabilityDomain?,
    action: String?
): String {
    return when {
        domain == CapabilityDomain.SHOPPING && action == "search" -> "${appId}_shopping_process"
        !action.isNullOrBlank() -> "${appId}_${action}_process"
        domain != null -> "${appId}_${domain.wireName}_process"
        else -> "${appId}_run_process"
    }
}
