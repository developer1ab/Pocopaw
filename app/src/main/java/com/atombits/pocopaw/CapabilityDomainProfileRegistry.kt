package com.atombits.pocopaw

import java.util.Locale

internal data class DomainRuntimeInputVariant(
    val matchTokens: Set<String> = emptySet(),
    val slotKeys: List<String>
) {
    fun matches(capabilityId: String): Boolean {
        return matchTokens.isEmpty() || matchTokens.any { token -> capabilityId.contains(token) }
    }
}

internal data class ToolCatalogSignalGroupSpec(
    val id: String,
    val taskTerms: List<String>,
    val capabilityTerms: List<String>,
    val intentGroups: List<List<String>> = emptyList()
)

internal data class ToolCatalogDomainRuleSpec(
    val domain: CapabilityDomain,
    val taskTerms: List<String>,
    val capabilityTerms: List<String>,
    val preferredDomains: Set<ToolDomain> = setOf(ToolDomain.SYSTEM, ToolDomain.MCP, ToolDomain.APP),
    val signalGroups: List<ToolCatalogSignalGroupSpec> = emptyList()
)

internal data class PreferenceFacetSlotMapping(
    val commonSlotKey: CommonDetailSlotKey? = null,
    val domainSlotKey: String? = null
) {
    fun namespacedKey(domain: CapabilityDomain?): String {
        return commonSlotKey?.let { key ->
            "common.${key.wireName}"
        } ?: "${domain?.wireName ?: CapabilityDomain.OTHER.wireName}.${domainSlotKey.orEmpty()}"
    }
}

internal data class CapabilityDomainProfile(
    val domain: CapabilityDomain,
    val detailSlotKeys: List<String>,
    val primaryTargetSlotKeys: List<String> = emptyList(),
    val runtimeInputVariants: List<DomainRuntimeInputVariant> = emptyList(),
    val preferenceDiscoveryDisplayName: String? = null,
    val preferenceDiscoverySlotKeys: Set<String> = emptySet(),
    val domainRoot: String = domain.wireName,
    val siblingDistances: Map<CapabilityDomain, Int> = emptyMap(),
    val transferableFacetKeys: Set<String> = emptySet(),
    val nonTransferableFacetKeys: Set<String> = emptySet(),
    val semanticExpansionScope: Set<String> = emptySet(),
    val facetSlotMappings: Map<String, PreferenceFacetSlotMapping> = emptyMap()
)

internal object CapabilityDomainProfileRegistry {
    private val profiles = linkedMapOf(
        CapabilityDomain.SHOPPING to CapabilityDomainProfile(
            domain = CapabilityDomain.SHOPPING,
            detailSlotKeys = listOf("product_type", "brand", "spec", "feature", "sku_hint", "delivery_address"),
            primaryTargetSlotKeys = listOf("product_type"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "shopping.product_type",
                        "shopping.category",
                        "shopping.brand",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            ),
            preferenceDiscoveryDisplayName = "衣 (购物)",
            preferenceDiscoverySlotKeys = setOf("brand", "category", "budget_band", "merchant", "style", "size", "color", "time_scope"),
            domainRoot = "lifestyle",
            siblingDistances = mapOf(
                CapabilityDomain.FOOD to 1,
                CapabilityDomain.LOCAL_SERVICE to 2,
                CapabilityDomain.HOME_LIFE to 2
            ),
            transferableFacetKeys = setOf("brand", "budget_band", "price_band", "style", "color", "platform", "avoid"),
            nonTransferableFacetKeys = setOf("size", "spec", "feature", "delivery_address"),
            semanticExpansionScope = setOf("shopping", "product"),
            facetSlotMappings = mapOf(
                "category" to PreferenceFacetSlotMapping(domainSlotKey = "product_type"),
                "product_type" to PreferenceFacetSlotMapping(domainSlotKey = "product_type"),
                "brand" to PreferenceFacetSlotMapping(domainSlotKey = "brand"),
                "budget_band" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PRICE),
                "price_band" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PRICE),
                "platform" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PLATFORM),
                "avoid" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.CONSTRAINT)
            )
        ),
        CapabilityDomain.FOOD to CapabilityDomainProfile(
            domain = CapabilityDomain.FOOD,
            detailSlotKeys = listOf("merchant", "cuisine", "dish_name", "beverage", "delivery_address", "reservation_size"),
            primaryTargetSlotKeys = listOf("merchant", "cuisine"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "food.merchant",
                        "food.cuisine",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            ),
            preferenceDiscoveryDisplayName = "食 (餐饮)",
            preferenceDiscoverySlotKeys = setOf("cuisine", "merchant", "budget_band", "taste", "beverage", "time_scope"),
            domainRoot = "lifestyle",
            siblingDistances = mapOf(
                CapabilityDomain.SHOPPING to 1,
                CapabilityDomain.LOCAL_SERVICE to 1
            ),
            transferableFacetKeys = setOf("merchant", "cuisine", "budget_band", "price_band", "platform", "avoid"),
            nonTransferableFacetKeys = setOf("dish_name", "beverage", "delivery_address", "reservation_size"),
            semanticExpansionScope = setOf("food", "merchant"),
            facetSlotMappings = mapOf(
                "merchant" to PreferenceFacetSlotMapping(domainSlotKey = "merchant"),
                "cuisine" to PreferenceFacetSlotMapping(domainSlotKey = "cuisine"),
                "budget_band" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PRICE),
                "price_band" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PRICE),
                "platform" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PLATFORM),
                "avoid" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.CONSTRAINT)
            )
        ),
        CapabilityDomain.HOME_LIFE to CapabilityDomainProfile(
            domain = CapabilityDomain.HOME_LIFE,
            detailSlotKeys = listOf("service_type", "merchant", "service_location", "district", "address_scope", "service_time", "reservation_size", "service_option", "booking_contact"),
            primaryTargetSlotKeys = listOf("service_type", "merchant"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "home_life.service_type",
                        "home_life.merchant",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            ),
            preferenceDiscoveryDisplayName = "住 (居家)",
            preferenceDiscoverySlotKeys = setOf("service_type", "merchant", "budget_band", "district", "address_scope", "service_time"),
            domainRoot = "service",
            siblingDistances = mapOf(CapabilityDomain.LOCAL_SERVICE to 1),
            transferableFacetKeys = setOf("merchant", "budget_band", "price_band", "platform", "avoid"),
            nonTransferableFacetKeys = setOf("service_location", "address_scope", "service_time", "booking_contact"),
            semanticExpansionScope = setOf("service", "home_life")
        ),
        CapabilityDomain.TRANSPORT to CapabilityDomainProfile(
            domain = CapabilityDomain.TRANSPORT,
            detailSlotKeys = listOf("origin", "destination", "waypoint", "transport_mode", "avoid_option", "pickup_location", "dropoff_location", "pickup_time", "car_type", "passenger_count", "pickup_note"),
            primaryTargetSlotKeys = listOf("destination", "origin", "dropoff_location", "pickup_location"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    matchTokens = setOf("didi", "ride", "taxi"),
                    slotKeys = listOf(
                        "transport.dropoff_location",
                        "transport.pickup_location",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                ),
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "transport.destination",
                        "transport.origin",
                        DetailSlotKey.DESTINATION.contractName,
                        DetailSlotKey.LOCATION.contractName,
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            ),
            preferenceDiscoveryDisplayName = "行 (出行)",
            preferenceDiscoverySlotKeys = setOf("route_preference", "vehicle_type", "platform", "origin_area", "destination_area", "time_scope"),
            domainRoot = "mobility",
            transferableFacetKeys = setOf("platform", "route_preference", "avoid"),
            nonTransferableFacetKeys = setOf("origin_area", "destination_area", "pickup_time", "car_type", "passenger_count"),
            semanticExpansionScope = setOf("transport", "route"),
            facetSlotMappings = mapOf(
                "platform" to PreferenceFacetSlotMapping(commonSlotKey = CommonDetailSlotKey.PLATFORM),
                "route_preference" to PreferenceFacetSlotMapping(domainSlotKey = "avoid_option"),
                "avoid" to PreferenceFacetSlotMapping(domainSlotKey = "avoid_option")
            )
        ),
        CapabilityDomain.ENTERTAINMENT to CapabilityDomainProfile(
            domain = CapabilityDomain.ENTERTAINMENT,
            detailSlotKeys = listOf("title", "artist", "genre", "channel", "showtime", "seat_type"),
            primaryTargetSlotKeys = listOf("title", "artist"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "entertainment.title",
                        "entertainment.artist",
                        "entertainment.genre",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.LOCAL_SERVICE to CapabilityDomainProfile(
            domain = CapabilityDomain.LOCAL_SERVICE,
            detailSlotKeys = listOf("service_type", "merchant_name", "service_location", "reservation_size", "service_option", "booking_contact"),
            primaryTargetSlotKeys = listOf("merchant_name", "service_type"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "local_service.merchant_name",
                        "local_service.service_type",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.INFORMATION to CapabilityDomainProfile(
            domain = CapabilityDomain.INFORMATION,
            detailSlotKeys = listOf("title", "start_time", "end_time", "recurrence", "reminder_offset", "event_location", "note", "document_type", "capture_mode", "page_count", "save_target", "export_format", "query", "subject", "location", "query_location", "forecast_time", "metric", "unit", "source_scope", "required_facts", "sort_order"),
            primaryTargetSlotKeys = listOf("query_location", "title", "document_type", "query", "subject"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    matchTokens = setOf("weather"),
                    slotKeys = listOf(
                        "information.query_location",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                ),
                DomainRuntimeInputVariant(
                    matchTokens = setOf("calendar", "alarm", "remind"),
                    slotKeys = listOf(
                        "information.title",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                ),
                DomainRuntimeInputVariant(
                    matchTokens = setOf("camera", "document", "photo"),
                    slotKeys = listOf(
                        "information.document_type",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                ),
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "information.query",
                        "information.subject",
                        "information.location",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.COMMUNICATION to CapabilityDomainProfile(
            domain = CapabilityDomain.COMMUNICATION,
            detailSlotKeys = listOf("recipient", "message_body", "subject", "channel", "attachment_hint"),
            primaryTargetSlotKeys = listOf("recipient"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "communication.message_body",
                        "communication.recipient",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.FINANCE to CapabilityDomainProfile(
            domain = CapabilityDomain.FINANCE,
            detailSlotKeys = listOf("payee", "bill_type", "bank_name", "account", "amount", "payment_method"),
            primaryTargetSlotKeys = listOf("payee", "bill_type", "bank_name"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "finance.payee",
                        "finance.bill_type",
                        "finance.bank_name",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.SYSTEM_CONTROL to CapabilityDomainProfile(
            domain = CapabilityDomain.SYSTEM_CONTROL,
            detailSlotKeys = listOf("setting_key", "setting_value", "target_state", "subpage", "network_name", "device_name"),
            primaryTargetSlotKeys = listOf("setting_key"),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(
                        "system_control.setting_value",
                        "system_control.target_state",
                        "system_control.setting_key",
                        DetailSlotKey.TARGET_OBJECT.contractName
                    )
                )
            )
        ),
        CapabilityDomain.OTHER to CapabilityDomainProfile(
            domain = CapabilityDomain.OTHER,
            detailSlotKeys = emptyList(),
            runtimeInputVariants = listOf(
                DomainRuntimeInputVariant(
                    slotKeys = listOf(DetailSlotKey.TARGET_OBJECT.contractName)
                )
            )
        )
    )

    private val toolCatalogRules = listOf(
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.SYSTEM_CONTROL,
            taskTerms = listOf("设置", "setting", "settings", "wifi", "wi-fi", "蓝牙", "bluetooth", "定位", "location", "权限", "permission"),
            capabilityTerms = listOf("settings", "wifi", "bluetooth", "location", "application", "sync", "airplane")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.SYSTEM_CONTROL,
            taskTerms = listOf("软件商店", "应用商店", "应用市场", "app store"),
            capabilityTerms = listOf("软件商店", "应用商店", "应用市场", "heytap", "market"),
            preferredDomains = setOf(ToolDomain.APP)
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.INFORMATION,
            taskTerms = listOf("日历", "calendar", "提醒", "remind", "闹钟", "alarm", "timer", "定时"),
            capabilityTerms = listOf("calendar", "alarm", "timer", "show alarms", "set alarm", "set timer")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.COMMUNICATION,
            taskTerms = listOf("电话", "拨号", "call", "sms", "message", "短信", "邮件", "email", "mail", "联系人", "contact"),
            capabilityTerms = listOf("dial", "call", "sms", "mail", "contact"),
            signalGroups = listOf(
                ToolCatalogSignalGroupSpec(
                    id = "send_message",
                    taskTerms = listOf("短信", "sms", "text message", "text"),
                    capabilityTerms = listOf("短信", "sms", "sendto_sms", "smsto", "text"),
                    intentGroups = listOf(
                        listOf("发", "短信"),
                        listOf("发送", "短信"),
                        listOf("text", "message"),
                        listOf("send", "sms")
                    )
                ),
                ToolCatalogSignalGroupSpec(
                    id = "call",
                    taskTerms = listOf("电话", "拨号", "call", "打电话", "通话"),
                    capabilityTerms = listOf("电话", "拨号", "dial", "call", "tel"),
                    intentGroups = listOf(
                        listOf("打", "电话"),
                        listOf("拨", "号")
                    )
                ),
                ToolCatalogSignalGroupSpec(
                    id = "mail",
                    taskTerms = listOf("邮件", "email", "mail", "邮箱"),
                    capabilityTerms = listOf("邮件", "email", "mail", "sendto_mail", "mailto"),
                    intentGroups = listOf(
                        listOf("发", "邮件"),
                        listOf("send", "email")
                    )
                ),
                ToolCatalogSignalGroupSpec(
                    id = "contact_selection",
                    taskTerms = listOf("联系人", "contact", "通讯录"),
                    capabilityTerms = listOf("联系人", "通讯录", "contact", "contact_pick"),
                    intentGroups = listOf(
                        listOf("选", "联系人"),
                        listOf("选择", "联系人")
                    )
                )
            )
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.INFORMATION,
            taskTerms = listOf("拍照", "相机", "camera", "照片", "photo", "视频", "video", "扫码", "scan", "文件", "file", "文档", "document", "上传", "upload", "图片"),
            capabilityTerms = listOf("camera", "image capture", "video capture", "document", "content", "image", "photo", "video")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.INFORMATION,
            taskTerms = listOf("搜索", "search", "查", "query", "网页", "web", "浏览器", "browser", "链接", "link", "网址"),
            capabilityTerms = listOf("web_search", "view", "browser", "chrome", "search")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.INFORMATION,
            taskTerms = listOf("天气", "weather", "forecast", "温度", "气温"),
            capabilityTerms = listOf("weather", "forecast")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.TRANSPORT,
            taskTerms = listOf("地图", "map", "导航", "navigate", "路线", "route", "geo", "位置"),
            capabilityTerms = listOf("map", "geo", "navigate", "route", "地图", "导航", "路线", "amap", "autonavi", "高德")
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.TRANSPORT,
            taskTerms = listOf("打车", "叫车", "ride", "taxi", "滴滴", "didi", "出行"),
            capabilityTerms = listOf("ride", "taxi", "didi", "trip", "打车", "叫车", "滴滴", "出行"),
            preferredDomains = setOf(ToolDomain.APP)
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.LOCAL_SERVICE,
            taskTerms = listOf("外卖", "美食", "餐厅", "餐馆", "饭店", "团购", "点评", "探店", "到店"),
            capabilityTerms = listOf("meituan", "美团", "dianping", "大众点评", "takeout", "restaurant", "food", "团购", "点评"),
            preferredDomains = setOf(ToolDomain.APP)
        ),
        ToolCatalogDomainRuleSpec(
            domain = CapabilityDomain.SHOPPING,
            taskTerms = listOf("京东", "jd", "淘宝", "taobao", "拼多多", "pdd", "买", "购买", "下单", "购物", "商城", "shop", "shopping"),
            capabilityTerms = listOf("jd", "京东", "taobao", "淘宝", "pdd", "拼多多", "mall", "shop"),
            preferredDomains = setOf(ToolDomain.APP)
        )
    )

    fun promptSummary(): String {
        return profiles.values.joinToString(separator = "\n") { profile ->
            "- ${profile.domain.wireName}: ${profile.detailSlotKeys.joinToString("|")}"
        }
    }

    fun allowedDetailSlotKeys(domain: CapabilityDomain?): Set<String> {
        return domain?.let { value -> profiles[value]?.detailSlotKeys?.toSet() }.orEmpty()
    }

    fun primaryTargetCandidates(domain: CapabilityDomain?, domainSlots: Map<String, String>): List<String> {
        val targetSlotKeys = domain?.let { value -> profiles[value]?.primaryTargetSlotKeys }.orEmpty()
        return targetSlotKeys.mapNotNull { key ->
            domainSlots[key]?.trim()?.takeIf { value -> value.isNotBlank() }
        }.distinct()
    }

    fun domainRoot(domain: CapabilityDomain?): String {
        return domain?.let { value -> profiles[value]?.domainRoot } ?: CapabilityDomain.OTHER.wireName
    }

    fun siblingDistance(domain: CapabilityDomain?, other: CapabilityDomain?): Int? {
        if (domain == null || other == null) {
            return null
        }
        if (domain == other) {
            return 0
        }
        if (domainRoot(domain) != domainRoot(other)) {
            return null
        }
        return profiles[domain]?.siblingDistances?.get(other)
            ?: profiles[other]?.siblingDistances?.get(domain)
            ?: 1
    }

    fun transferableFacetKeys(domain: CapabilityDomain?): Set<String> {
        return domain?.let { value -> profiles[value]?.transferableFacetKeys }.orEmpty()
    }

    fun semanticExpansionScope(domain: CapabilityDomain?): Set<String> {
        return domain?.let { value -> profiles[value]?.semanticExpansionScope }.orEmpty()
    }

    fun facetSlotMapping(domain: CapabilityDomain?, facetKey: String): PreferenceFacetSlotMapping? {
        val normalizedFacetKey = facetKey.trim().lowercase(Locale.US)
        if (normalizedFacetKey.isBlank()) {
            return null
        }
        return domain?.let { value -> profiles[value]?.facetSlotMappings?.get(normalizedFacetKey) }
    }

    fun preferredRuntimeInputKeys(domain: CapabilityDomain?, capabilityId: String?): List<String> {
        val runtimeInputVariants = domain?.let { value -> profiles[value]?.runtimeInputVariants }.orEmpty()
        if (runtimeInputVariants.isEmpty()) {
            return listOf(DetailSlotKey.TARGET_OBJECT.contractName)
        }
        val normalizedCapabilityId = capabilityId?.trim()?.lowercase(Locale.US).orEmpty()
        return runtimeInputVariants.firstOrNull { variant -> variant.matches(normalizedCapabilityId) }
            ?.slotKeys
            ?: listOf(DetailSlotKey.TARGET_OBJECT.contractName)
    }

    fun preferenceDiscoveryDomains(): List<CapabilityDomain> {
        return profiles.values
            .filter { profile -> profile.preferenceDiscoverySlotKeys.isNotEmpty() }
            .map(CapabilityDomainProfile::domain)
    }

    fun preferenceDiscoveryDisplayName(domain: CapabilityDomain): String {
        return profiles[domain]?.preferenceDiscoveryDisplayName ?: domain.wireName.uppercase()
    }

    fun preferenceDiscoverySlotKeys(domain: CapabilityDomain): Set<String> {
        return profiles[domain]?.preferenceDiscoverySlotKeys.orEmpty()
    }

    fun toolCatalogRules(): List<ToolCatalogDomainRuleSpec> = toolCatalogRules
}
