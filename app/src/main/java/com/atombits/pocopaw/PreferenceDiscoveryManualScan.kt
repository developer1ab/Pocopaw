package com.atombits.pocopaw

import org.json.JSONObject
import java.util.UUID

data class PreferenceDiscoveryAppTarget(
    val domain: CapabilityDomain,
    val appId: String,
    val displayName: String,
    val packageName: String,
    val pageType: String = "ORDER_HISTORY"
)

data class PreferenceDiscoveryStructuredObservation(
    val anchorObject: String?,
    val slotKey: String,
    val slotValue: String,
    val polarity: String = "PREFER",
    val confidence: Double = 0.75,
    val freshnessHint: String = "RECENT"
)

object PreferenceDiscoveryCatalog {
    fun domains(): List<CapabilityDomain> = CapabilityDomainProfileRegistry.preferenceDiscoveryDomains()

    fun displayName(domain: CapabilityDomain): String {
        return CapabilityDomainProfileRegistry.preferenceDiscoveryDisplayName(domain)
    }

    fun supportedSlotKeys(domain: CapabilityDomain): Set<String> {
        return CapabilityDomainProfileRegistry.preferenceDiscoverySlotKeys(domain)
    }

    fun targetsFor(domain: CapabilityDomain): List<PreferenceDiscoveryAppTarget> {
        return CanonicalAppCatalog.appsForDomain(domain).mapNotNull { entry ->
            val packageName = entry.packageNames.firstOrNull() ?: return@mapNotNull null
            PreferenceDiscoveryAppTarget(
                domain = domain,
                appId = entry.appId,
                displayName = entry.displayName,
                packageName = packageName
            )
        }
    }

    fun installedTargets(
        domain: CapabilityDomain,
        isInstalled: (String) -> Boolean
    ): List<PreferenceDiscoveryAppTarget> {
        return targetsFor(domain).filter { target -> isInstalled(target.packageName) }
    }
}

fun filterPreferenceDiscoveryObservations(
    domain: CapabilityDomain,
    observations: List<PreferenceDiscoveryStructuredObservation>
): List<PreferenceDiscoveryStructuredObservation> {
    return observations.filter { observation ->
        observation.slotKey in PreferenceDiscoveryCatalog.supportedSlotKeys(domain) && observation.slotValue.isNotBlank()
    }.distinctBy { observation ->
        listOf(
            observation.anchorObject.orEmpty(),
            observation.slotKey,
            observation.slotValue,
            observation.polarity
        ).joinToString("|")
    }
}

fun parsePreferenceDiscoveryObservations(content: String): List<PreferenceDiscoveryStructuredObservation> {
    val root = JSONObject(content)
    val observations = root.optJSONArray("observations") ?: return emptyList()
    return buildList {
        for (index in 0 until observations.length()) {
            val item = observations.optJSONObject(index) ?: continue
            val slotKey = item.optString("slot_key").trim().lowercase()
            val slotValue = item.optString("slot_value").trim()
            if (slotKey.isBlank() || slotValue.isBlank()) {
                continue
            }
            add(
                PreferenceDiscoveryStructuredObservation(
                    anchorObject = item.optString("anchor_object").trim().ifBlank { null },
                    slotKey = slotKey,
                    slotValue = slotValue,
                    polarity = item.optString("polarity", "PREFER").trim().uppercase().ifBlank { "PREFER" },
                    confidence = item.optDouble("confidence", 0.75).coerceIn(0.0, 1.0),
                    freshnessHint = item.optString("freshness_hint", "RECENT").trim().uppercase().ifBlank { "RECENT" }
                )
            )
        }
    }
}

fun buildStructuredPreferenceHint(observation: PreferenceDiscoveryStructuredObservation): String {
    return buildString {
        observation.anchorObject?.takeIf { value -> value.isNotBlank() }?.let { anchorObject ->
            append("anchor_object=")
            append(anchorObject)
            append("; ")
        }
        append("slot_key=")
        append(observation.slotKey)
        append("; slot_value=")
        append(observation.slotValue)
        append("; polarity=")
        append(observation.polarity)
        append("; confidence=")
        append("%.2f".format(observation.confidence))
        append("; freshness_hint=")
        append(observation.freshnessHint)
    }
}

fun buildPreferenceDiscoveryPayload(
    target: PreferenceDiscoveryAppTarget,
    observations: List<PreferenceDiscoveryStructuredObservation>,
    capturedAt: Long,
    scanId: String = UUID.randomUUID().toString()
): AppPreferenceScanPayload? {
    val filtered = filterPreferenceDiscoveryObservations(target.domain, observations)
    if (filtered.isEmpty()) {
        return null
    }
    return AppPreferenceScanPayload(
        scanId = scanId,
        sourceApp = target.packageName,
        domain = target.domain.wireName,
        pageType = target.pageType,
        rawHints = filtered.map(::buildStructuredPreferenceHint),
        capturedAt = capturedAt
    )
}