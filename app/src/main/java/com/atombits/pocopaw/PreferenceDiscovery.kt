package com.atombits.pocopaw

import java.util.Locale
import java.util.UUID

data class AppPreferenceScanPayload(
    val scanId: String = UUID.randomUUID().toString(),
    val sourceApp: String,
    val domain: String,
    val pageType: String,
    val rawHints: List<String>,
    val capturedAt: Long = System.currentTimeMillis()
)

data class PreferenceObservation(
    val observationId: String = UUID.randomUUID().toString(),
    val sourceApp: String,
    val domain: String,
    val pageType: String,
    val rawHint: String,
    val capturedAt: Long
)

data class PreferenceDiscoveryWritebackOutcome(
    val updatedStore: PrototypeStoreData,
    val observations: List<PreferenceObservation>,
    val applied: Boolean,
    val message: String
)

data class PreferenceDiscoveryProjectionOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val consumedScanIds: List<String>,
    val message: String
)

private data class PreferencePromotionAggregate(
    val domain: String,
    val anchorObject: String,
    val slotKey: String,
    val slotValue: String,
    val polarity: String,
    val confidence: Double,
    val freshnessHint: String,
    val sourceApp: String?,
    val lastObservedAt: Long,
    val supportingSourceApps: List<String>,
    val promotionSignals: List<String>
)

private const val crossAppConfidenceBonus = 0.12
private const val crossTimeConfidenceBonus = 0.08
private const val crossTimePromotionWindowMs = 7L * 24 * 60 * 60 * 1000L
private const val defaultPreferenceDiscoveryBatchIntervalMs = 15L * 60 * 1000L

private val preferenceDiscoveryBiasKeys = setOf(
    "preferred_process_id",
    "preferred_page_signature",
    "preferred_shortcut_screen"
)

enum class ThirdPartyAppCategory {
    SHOPPING,
    TRANSPORT,
    FOOD_AND_DRINK,
    PRODUCTIVITY,
    OTHER
}

data class ThirdPartyAppUsageRecord(
    val packageName: String,
    val appLabel: String,
    val category: ThirdPartyAppCategory = ThirdPartyAppCategory.OTHER,
    val totalTimeInForegroundMs: Long,
    val lastTimeUsed: Long,
    val isSystemApp: Boolean = false
)

data class PreferenceDiscoveryScheduleOutcome(
    val updatedStore: PrototypeStoreData,
    val scheduled: Boolean,
    val enqueuedScanIds: List<String>,
    val message: String
)

interface AppPreferenceScanner {
    fun scan(store: PrototypeStoreData, now: Long = System.currentTimeMillis()): List<AppPreferenceScanPayload>
}

object LocalAppHistoryPreferenceScanner : AppPreferenceScanner {
    override fun scan(store: PrototypeStoreData, now: Long): List<AppPreferenceScanPayload> {
        val readyAssetIndex = store.readyProcessAssets.associateBy { asset ->
            listOf(asset.appScope.lowercase(), asset.processId).joinToString("|")
        }
        val existingEvidenceFingerprints = buildAppScanEvidenceFingerprints(store.memoryState ?: MemoryState())

        return buildList {
            store.readyProcessAssets
                .sortedByDescending { asset -> asset.lastDerivedAt }
                .forEach { asset ->
                    addIfNotProjected(
                        payload = AppPreferenceScanPayload(
                            sourceApp = asset.appScope,
                            domain = asset.domain,
                            pageType = "READY_PROCESS_HISTORY",
                            rawHints = listOf(
                                "anchor_object=${asset.processId}; slot_key=preferred_process_id; slot_value=${asset.processId}; freshness_hint=LONG_TERM"
                            ),
                            capturedAt = asset.lastDerivedAt
                        ),
                        existingEvidenceFingerprints = existingEvidenceFingerprints,
                        addPayload = ::add
                    )
                }

            store.pageEvidenceAssets
                .sortedByDescending { asset -> asset.lastObservedAt }
                .forEach { asset ->
                    val domain = readyAssetIndex[listOf(asset.appScope.lowercase(), asset.processId).joinToString("|")]?.domain ?: "OTHER"
                    addIfNotProjected(
                        payload = AppPreferenceScanPayload(
                            sourceApp = asset.appScope,
                            domain = domain,
                            pageType = "PAGE_EVIDENCE_HISTORY",
                            rawHints = listOf(
                                "anchor_object=${asset.processId}; slot_key=preferred_page_signature; slot_value=${asset.pageSignature}; freshness_hint=RECENT"
                            ),
                            capturedAt = asset.lastObservedAt
                        ),
                        existingEvidenceFingerprints = existingEvidenceFingerprints,
                        addPayload = ::add
                    )
                }

            store.processShortcutAtlas
                .sortedByDescending { shortcut -> shortcut.lastDerivedAt }
                .filter { shortcut -> shortcut.stabilityScore >= 0.7 }
                .forEach { shortcut ->
                    val domain = readyAssetIndex[listOf(shortcut.appScope.lowercase(), shortcut.processId).joinToString("|")]?.domain ?: "OTHER"
                    addIfNotProjected(
                        payload = AppPreferenceScanPayload(
                            sourceApp = shortcut.appScope,
                            domain = domain,
                            pageType = "SHORTCUT_HISTORY",
                            rawHints = listOf(
                                "anchor_object=${shortcut.processId}; slot_key=preferred_shortcut_screen; slot_value=${shortcut.screenSignature}; freshness_hint=RECENT"
                            ),
                            capturedAt = shortcut.lastDerivedAt
                        ),
                        existingEvidenceFingerprints = existingEvidenceFingerprints,
                        addPayload = ::add
                    )
                }
        }
            .distinctBy { payload ->
                listOf(
                    payload.sourceApp,
                    payload.domain,
                    payload.pageType,
                    payload.rawHints.sorted().joinToString("|")
                ).joinToString("###")
            }
    }
}

fun enqueuePreferenceDiscoveryScan(
    store: PrototypeStoreData,
    payload: AppPreferenceScanPayload
): PrototypeStoreData {
    val memoryState = store.memoryState ?: MemoryState()
    val pendingScans = (listOf(payload) + memoryState.pendingAppPreferenceScans)
        .sortedByDescending { scan -> scan.capturedAt }
        .distinctBy { scan -> buildPendingScanFingerprint(scan) }
        .distinctBy { scan -> scan.scanId }
        .take(24)
    return store.withUpdatedMemoryState(
        memoryState.copy(pendingAppPreferenceScans = pendingScans)
    )
}

fun projectPreferenceDiscoveryScansFromThirdPartyUsage(
    store: PrototypeStoreData,
    usageRecords: List<ThirdPartyAppUsageRecord>,
    now: Long = System.currentTimeMillis(),
    freshnessWindowMs: Long = 3L * 24 * 60 * 60 * 1000L,
    minimumForegroundMs: Long = 5L * 60 * 1000L,
    excludedPackages: Set<String> = emptySet()
): PrototypeStoreData {
    return enqueueProjectedPreferenceDiscoveryScans(
        store = store,
        payloads = buildThirdPartyUsageScanPayloads(
            store = store,
            usageRecords = usageRecords,
            now = now,
            freshnessWindowMs = freshnessWindowMs,
            minimumForegroundMs = minimumForegroundMs,
            excludedPackages = excludedPackages
        )
    )
}

fun projectPreferenceDiscoveryScansFromLocalHistory(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    scanner: AppPreferenceScanner = LocalAppHistoryPreferenceScanner
): PrototypeStoreData {
    return enqueueProjectedPreferenceDiscoveryScans(store, scanner.scan(store, now))
}

fun schedulePreferenceDiscoveryScanBatch(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    scanner: AppPreferenceScanner,
    minimumIntervalMs: Long = defaultPreferenceDiscoveryBatchIntervalMs
): PreferenceDiscoveryScheduleOutcome {
    val memoryState = store.memoryState ?: MemoryState()
    val runtime = memoryState.preferenceDiscoveryRuntime
    val nextEligibleScanAt = runtime.nextEligibleScanAt
    if (nextEligibleScanAt != null && now < nextEligibleScanAt) {
        return PreferenceDiscoveryScheduleOutcome(
            updatedStore = store,
            scheduled = false,
            enqueuedScanIds = emptyList(),
            message = UiStrings.resolve(
                R.string.preference_discovery_cooling_down_until,
                "Preference discovery batch is cooling down until %1\$s.",
                formatPreferenceExtractionTimestamp(nextEligibleScanAt)
            )
        )
    }

    val projectedStore = projectPreferenceDiscoveryScansFromLocalHistory(store, now, scanner)
    val updatedMemoryState = (projectedStore.memoryState ?: MemoryState()).copy(
        preferenceDiscoveryRuntime = (projectedStore.memoryState ?: MemoryState()).preferenceDiscoveryRuntime.copy(
            lastScheduledAt = now,
            nextEligibleScanAt = now + minimumIntervalMs.coerceAtLeast(0L)
        )
    )
    val existingScanIds = memoryState.pendingAppPreferenceScans.map { scan -> scan.scanId }.toSet()
    val enqueuedScanIds = updatedMemoryState.pendingAppPreferenceScans
        .map { scan -> scan.scanId }
        .filterNot(existingScanIds::contains)

    return PreferenceDiscoveryScheduleOutcome(
        updatedStore = projectedStore.withUpdatedMemoryState(updatedMemoryState),
        scheduled = true,
        enqueuedScanIds = enqueuedScanIds,
        message = if (enqueuedScanIds.isEmpty()) {
            UiStrings.resolve(
                R.string.preference_discovery_ran_without_enqueue,
                "Preference discovery batch ran without enqueueing new scans."
            )
        } else {
            UiStrings.resolve(
                R.string.preference_discovery_enqueued_scans,
                "Preference discovery batch enqueued %1\$d scan(s).",
                enqueuedScanIds.size
            )
        }
    )
}

fun applyQueuedPreferenceDiscoveryScans(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis(),
    limit: Int = 3
): PreferenceDiscoveryProjectionOutcome {
    val memoryState = store.memoryState ?: MemoryState()
    val pendingScans = memoryState.pendingAppPreferenceScans
        .sortedByDescending { scan -> scan.capturedAt }
        .take(limit)
    if (pendingScans.isEmpty()) {
        return PreferenceDiscoveryProjectionOutcome(
            updatedStore = store,
            applied = false,
            consumedScanIds = emptyList(),
            message = UiStrings.resolve(
                R.string.preference_discovery_no_pending_scan,
                "No pending app preference scan is available."
            )
        )
    }

    var workingStore = store
    val consumedScanIds = mutableListOf<String>()
    var appliedCount = 0
    pendingScans.forEach { payload ->
        val outcome = applyPreferenceDiscoveryScan(
            store = workingStore,
            payload = payload,
            now = now
        )
        workingStore = outcome.updatedStore
        consumedScanIds.add(payload.scanId)
        if (outcome.applied) {
            appliedCount += 1
        }
    }
    val updatedMemoryState = (workingStore.memoryState ?: MemoryState()).copy(
        preferenceDiscoveryRuntime = (workingStore.memoryState ?: MemoryState()).preferenceDiscoveryRuntime.copy(
            lastConsumedAt = if (consumedScanIds.isEmpty()) {
                (workingStore.memoryState ?: MemoryState()).preferenceDiscoveryRuntime.lastConsumedAt
            } else {
                now
            }
        ),
        pendingAppPreferenceScans = (workingStore.memoryState ?: MemoryState()).pendingAppPreferenceScans.filterNot { scan ->
            scan.scanId in consumedScanIds.toSet()
        }
    )
    return PreferenceDiscoveryProjectionOutcome(
        updatedStore = workingStore.withUpdatedMemoryState(updatedMemoryState),
        applied = appliedCount > 0,
        consumedScanIds = consumedScanIds,
        message = if (appliedCount > 0) {
            UiStrings.resolve(
                R.string.preference_discovery_projected_from_pending,
                "Projected app preference evidence from %1\$d pending scan(s).",
                appliedCount
            )
        } else {
            UiStrings.resolve(
                R.string.preference_discovery_consumed_without_projection,
                "Pending app preference scans were consumed but no stable preference evidence was projected."
            )
        }
    )
}

fun applyPreferenceDiscoveryScan(
    store: PrototypeStoreData,
    payload: AppPreferenceScanPayload,
    now: Long = System.currentTimeMillis()
): PreferenceDiscoveryWritebackOutcome {
    val observations = normalizePreferenceObservations(payload)
    if (observations.isEmpty()) {
        return PreferenceDiscoveryWritebackOutcome(
            updatedStore = store,
            observations = emptyList(),
            applied = false,
            message = UiStrings.resolve(
                R.string.preference_discovery_no_actionable_observation,
                "No actionable app preference observation was normalized."
            )
        )
    }

    val orderHistoryRecords = buildAppOrderHistoryRecords(observations)
    val evidenceRecords = orderHistoryRecords.flatMap { record ->
        PreferenceMemorySignalProjector.project(record).toMemoryPreferenceEvidenceRecords()
    }
    if (evidenceRecords.isEmpty()) {
        return PreferenceDiscoveryWritebackOutcome(
            updatedStore = store,
            observations = observations,
            applied = false,
            message = UiStrings.resolve(
                R.string.preference_discovery_no_stable_evidence,
                "No stable preference evidence was projected from app scan observations."
            )
        )
    }

    val currentMemoryState = store.memoryState ?: MemoryState()
    val promotedAggregates = promotePreferenceDiscoveryEvidence(
        existingRecords = buildAppScanEvidenceRecords(currentMemoryState),
        incomingRecords = evidenceRecords.map { record -> record.copy(lastObservedAt = now) }
    )
    val updatedMemoryState = currentMemoryState.appendPreferenceSignalProjections(
        projections = buildPromotedAppOrderHistoryRecords(promotedAggregates).map(PreferenceMemorySignalProjector::project),
        limit = 12
    )
    return PreferenceDiscoveryWritebackOutcome(
        updatedStore = store.withUpdatedMemoryState(updatedMemoryState),
        observations = observations,
        applied = true,
        message = UiStrings.resolve(
            R.string.preference_discovery_projected_records,
            "Projected %1\$d app preference evidence record(s).",
            evidenceRecords.size
        )
    )
}

private fun normalizePreferenceObservations(payload: AppPreferenceScanPayload): List<PreferenceObservation> {
    val normalizedDomain = payload.domain.trim().uppercase()
    if (normalizedDomain.isBlank()) {
        return emptyList()
    }
    val capabilityDomain = CapabilityDomain.fromRaw(normalizedDomain)
    val allowedSlotKeys = capabilityDomain?.let(CapabilityDomainProfileRegistry::preferenceDiscoverySlotKeys).orEmpty() + preferenceDiscoveryBiasKeys
    return payload.rawHints
        .map { rawHint -> rawHint.trim() }
        .filter { rawHint -> rawHint.isNotEmpty() }
        .mapNotNull { rawHint ->
            val parsedHint = parseStructuredPreferenceHint(rawHint)
            val slotKey = parsedHint["slot_key"].orEmpty().lowercase(Locale.US)
            val slotValue = parsedHint["slot_value"].orEmpty()
            if (slotKey.isBlank() || slotValue.isBlank() || slotKey !in allowedSlotKeys) {
                null
            } else {
                PreferenceObservation(
                    sourceApp = payload.sourceApp,
                    domain = normalizedDomain,
                    pageType = payload.pageType.trim().uppercase().ifBlank { "UNKNOWN" },
                    rawHint = rawHint,
                    capturedAt = payload.capturedAt
                )
            }
        }
        .distinctBy { observation -> listOf(observation.sourceApp, observation.domain, observation.pageType, observation.rawHint).joinToString("|") }
}

private fun parseStructuredPreferenceHint(rawHint: String): Map<String, String> {
    return rawHint.split(';')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = pieces.getOrNull(0)?.trim()?.lowercase().orEmpty()
            val value = pieces.getOrNull(1)?.trim().orEmpty()
            if (key.isBlank() || value.isBlank()) {
                null
            } else {
                key to value
            }
        }
        .toMap()
}

private fun buildThirdPartyUsageScanPayloads(
    store: PrototypeStoreData,
    usageRecords: List<ThirdPartyAppUsageRecord>,
    now: Long,
    freshnessWindowMs: Long,
    minimumForegroundMs: Long,
    excludedPackages: Set<String>
): List<AppPreferenceScanPayload> {
    return usageRecords
        .asSequence()
        .filter { usageRecord ->
            usageRecord.packageName.isNotBlank() &&
                usageRecord.appLabel.isNotBlank() &&
                !usageRecord.isSystemApp &&
                usageRecord.packageName !in excludedPackages &&
                usageRecord.totalTimeInForegroundMs >= minimumForegroundMs &&
                usageRecord.lastTimeUsed >= now - freshnessWindowMs
        }
        .sortedByDescending { usageRecord -> usageRecord.lastTimeUsed }
        .map { usageRecord ->
            val rawHints = buildList {
                addAll(buildThirdPartyHistoryHints(store, usageRecord))
            }.distinct()
            AppPreferenceScanPayload(
                sourceApp = usageRecord.packageName,
                domain = resolveThirdPartyUsageDomain(usageRecord.category),
                pageType = "THIRD_PARTY_USAGE",
                rawHints = rawHints,
                capturedAt = usageRecord.lastTimeUsed
            )
        }
        .distinctBy(::buildPendingScanFingerprint)
        .toList()
}

private fun buildThirdPartyHistoryHints(
    store: PrototypeStoreData,
    usageRecord: ThirdPartyAppUsageRecord
): List<String> {
    val readyAsset = store.readyProcessAssets
        .filter { asset -> matchesThirdPartyUsageAppScope(usageRecord, asset.appScope) }
        .maxByOrNull { asset -> asset.lastDerivedAt }
    val pageAsset = store.pageEvidenceAssets
        .filter { asset -> matchesThirdPartyUsageAppScope(usageRecord, asset.appScope) }
        .maxByOrNull { asset -> asset.lastObservedAt }
    val shortcutAsset = store.processShortcutAtlas
        .filter { shortcut ->
            shortcut.stabilityScore >= 0.7 && matchesThirdPartyUsageAppScope(usageRecord, shortcut.appScope)
        }
        .maxByOrNull { shortcut -> shortcut.lastDerivedAt }

    return buildList {
        readyAsset?.let { asset ->
            add(
                "anchor_object=${asset.processId}; slot_key=preferred_process_id; slot_value=${asset.processId}; freshness_hint=RECENT"
            )
        }
        pageAsset?.let { asset ->
            add(
                "anchor_object=${asset.processId}; slot_key=preferred_page_signature; slot_value=${asset.pageSignature}; freshness_hint=RECENT"
            )
        }
        shortcutAsset?.let { shortcut ->
            add(
                "anchor_object=${shortcut.processId}; slot_key=preferred_shortcut_screen; slot_value=${shortcut.screenSignature}; freshness_hint=RECENT"
            )
        }
    }
}

private fun matchesThirdPartyUsageAppScope(
    usageRecord: ThirdPartyAppUsageRecord,
    appScope: String
): Boolean {
    val normalizedAppScope = normalizeThirdPartyScopeKey(appScope)
    if (normalizedAppScope.isBlank()) {
        return false
    }
    val normalizedPackage = normalizeThirdPartyScopeKey(usageRecord.packageName)
    val normalizedLabel = normalizeThirdPartyScopeKey(usageRecord.appLabel)
    return normalizedPackage.contains(normalizedAppScope) ||
        normalizedAppScope.contains(normalizedPackage) ||
        normalizedLabel.contains(normalizedAppScope) ||
        normalizedAppScope.contains(normalizedLabel)
}

private fun normalizeThirdPartyScopeKey(raw: String): String {
    return raw.lowercase().filter { char -> char.isLetterOrDigit() }
}

private fun resolveThirdPartyUsageDomain(category: ThirdPartyAppCategory): String = when (category) {
    ThirdPartyAppCategory.SHOPPING -> "SHOPPING"
    ThirdPartyAppCategory.TRANSPORT -> "TRANSPORT"
    ThirdPartyAppCategory.FOOD_AND_DRINK -> "FOOD"
    ThirdPartyAppCategory.PRODUCTIVITY -> "PRODUCTIVITY"
    ThirdPartyAppCategory.OTHER -> "OTHER"
}

private fun enqueueProjectedPreferenceDiscoveryScans(
    store: PrototypeStoreData,
    payloads: List<AppPreferenceScanPayload>
): PrototypeStoreData {
    val knownFingerprints = collectKnownPreferenceDiscoveryFingerprints(store).toMutableSet()
    return payloads.fold(store) { currentStore, payload ->
        val payloadFingerprints = buildPayloadEvidenceFingerprints(payload)
        if (payloadFingerprints.isEmpty() || payloadFingerprints.all(knownFingerprints::contains)) {
            currentStore
        } else {
            knownFingerprints.addAll(payloadFingerprints)
            enqueuePreferenceDiscoveryScan(currentStore, payload)
        }
    }
}

private fun collectKnownPreferenceDiscoveryFingerprints(store: PrototypeStoreData): Set<String> {
    val memoryState = store.memoryState ?: MemoryState()
    val evidenceFingerprints = buildAppScanEvidenceFingerprints(memoryState)
    val pendingFingerprints = memoryState.pendingAppPreferenceScans.flatMap(::buildPayloadEvidenceFingerprints)
    return (evidenceFingerprints + pendingFingerprints).toSet()
}

private fun buildPayloadEvidenceFingerprints(payload: AppPreferenceScanPayload): List<String> {
    return payload.rawHints.mapNotNull { rawHint ->
        val parsedHint = parseStructuredPreferenceHint(rawHint)
        val slotKey = parsedHint["slot_key"].orEmpty()
        val slotValue = parsedHint["slot_value"].orEmpty()
        if (slotKey.isBlank() || slotValue.isBlank()) {
            null
        } else {
            buildEvidenceFingerprint(
                sourceApp = payload.sourceApp,
                domain = payload.domain,
                anchorObject = parsedHint["anchor_object"],
                slotKey = slotKey,
                slotValue = slotValue
            )
        }
    }
}

private fun buildPendingScanFingerprint(payload: AppPreferenceScanPayload): String {
    return listOf(
        payload.sourceApp.trim().lowercase(),
        payload.domain.trim().uppercase(),
        payload.pageType.trim().uppercase(),
        payload.rawHints.map(String::trim).sorted().joinToString("|")
    ).joinToString("###")
}

private fun addIfNotProjected(
    payload: AppPreferenceScanPayload,
    existingEvidenceFingerprints: Set<String>,
    addPayload: (AppPreferenceScanPayload) -> Unit
) {
    val rawHint = payload.rawHints.firstOrNull() ?: return
    val parsedHint = parseStructuredPreferenceHint(rawHint)
    val slotKey = parsedHint["slot_key"].orEmpty()
    val slotValue = parsedHint["slot_value"].orEmpty()
    if (slotKey.isBlank() || slotValue.isBlank()) {
        return
    }
    val fingerprint = buildEvidenceFingerprint(
        sourceApp = payload.sourceApp,
        domain = payload.domain,
        anchorObject = parsedHint["anchor_object"],
        slotKey = slotKey,
        slotValue = slotValue
    )
    if (fingerprint !in existingEvidenceFingerprints) {
        addPayload(payload)
    }
}

private fun buildEvidenceFingerprint(
    sourceApp: String,
    domain: String,
    anchorObject: String?,
    slotKey: String,
    slotValue: String
): String {
    return listOf(
        sourceApp.trim().lowercase(),
        domain.trim().uppercase(),
        anchorObject.orEmpty().trim(),
        slotKey.trim().lowercase(),
        slotValue.trim().lowercase()
    ).joinToString("###")
}

private fun promotePreferenceDiscoveryEvidence(
    existingRecords: List<MemoryPreferenceEvidenceRecord>,
    incomingRecords: List<MemoryPreferenceEvidenceRecord>
): List<PreferencePromotionAggregate> {
    val appScanRecords = (existingRecords.filter { record -> record.sourceType == "APP_SCAN" } + incomingRecords)
    return appScanRecords
        .groupBy { record ->
            listOf(
                record.domain.orEmpty().trim().uppercase(),
                record.anchorObject.orEmpty().trim(),
                record.slotKey.trim().lowercase(),
                record.slotValue.trim().lowercase(),
                record.polarity.trim().uppercase()
            ).joinToString("###")
        }
        .values
        .map { group ->
            val latestRecord = group.maxByOrNull { record -> record.lastObservedAt } ?: error("missing latest record")
            val supportingSourceApps = group.mapNotNull { record ->
                record.sourceApp?.trim()?.takeUnless { it.isBlank() }
            }.distinct().sorted()
            val earliestObservedAt = group.minOf { record -> record.lastObservedAt }
            val latestObservedAt = group.maxOf { record -> record.lastObservedAt }
            val hasCrossAppSupport = supportingSourceApps.size >= 2
            val hasCrossTimeSupport = group.size >= 2 && latestObservedAt - earliestObservedAt >= crossTimePromotionWindowMs
            val promotionSignals = buildList {
                if (hasCrossAppSupport) {
                    add("cross_app")
                }
                if (hasCrossTimeSupport) {
                    add("cross_time")
                }
            }
            val promotedConfidence = (
                group.maxOf { record -> record.confidence } +
                    if (hasCrossAppSupport) crossAppConfidenceBonus else 0.0 +
                    if (hasCrossTimeSupport) crossTimeConfidenceBonus else 0.0
                ).coerceIn(0.0, 0.97)
            PreferencePromotionAggregate(
                domain = latestRecord.domain.orEmpty().trim().uppercase().ifBlank { "OTHER" },
                anchorObject = latestRecord.anchorObject.orEmpty().ifBlank { "general_preference" },
                slotKey = latestRecord.slotKey,
                slotValue = latestRecord.slotValue,
                polarity = latestRecord.polarity,
                confidence = promotedConfidence,
                freshnessHint = if (hasCrossAppSupport || hasCrossTimeSupport || group.any { it.freshnessHint == "LONG_TERM" }) {
                    "LONG_TERM"
                } else {
                    latestRecord.freshnessHint
                },
                sourceApp = latestRecord.sourceApp,
                lastObservedAt = latestObservedAt,
                supportingSourceApps = supportingSourceApps,
                promotionSignals = promotionSignals
            )
        }
        .sortedByDescending { aggregate -> aggregate.lastObservedAt }
        .take(12)
}

private fun buildAppScanEvidenceFingerprints(memoryState: MemoryState): Set<String> {
    return buildAppScanEvidenceRecords(memoryState)
        .map { record ->
            buildEvidenceFingerprint(
                sourceApp = record.sourceApp.orEmpty(),
                domain = record.domain.orEmpty(),
                anchorObject = record.anchorObject,
                slotKey = record.slotKey,
                slotValue = record.slotValue
            )
        }
        .toSet()
}

private fun buildAppScanEvidenceRecords(memoryState: MemoryState): List<MemoryPreferenceEvidenceRecord> {
    val factRecords = memoryState.structuredPreferenceMemory.facts
        .filter { fact -> fact.sourceType == "APP_SCAN" }
        .map { fact ->
            MemoryPreferenceEvidenceRecord(
                evidenceId = fact.id,
                domain = fact.domain.uppercase(Locale.US),
                anchorObject = fact.anchorObject,
                slotKey = fact.facetKey,
                slotValue = fact.facetValue,
                polarity = fact.polarity,
                confidence = fact.confidence,
                sourceType = fact.sourceType,
                sourceApp = fact.sourceApp,
                freshnessHint = fact.freshnessHint,
                observationCount = fact.observationCount,
                lastObservedAt = fact.lastObservedAt
            )
        }
    val biasRecords = memoryState.interactionBiasMemory.allRecords()
        .filter { record -> record.sourceType == "APP_SCAN" }
        .map { record ->
            MemoryPreferenceEvidenceRecord(
                evidenceId = record.id,
                domain = record.domain.uppercase(Locale.US),
                anchorObject = record.anchorObject,
                slotKey = record.signalKey,
                slotValue = record.signalValue,
                polarity = "PREFER",
                confidence = record.confidence,
                sourceType = record.sourceType,
                sourceApp = record.sourceApp,
                freshnessHint = record.freshnessHint,
                observationCount = record.observationCount,
                lastObservedAt = record.lastObservedAt
            )
        }
    return (factRecords + biasRecords)
        .sortedByDescending { record -> record.lastObservedAt }
}

private fun buildAppOrderHistoryRecords(observations: List<PreferenceObservation>): List<AppOrderHistoryRecord> {
    return observations
        .groupBy { observation ->
            val parsedHint = parseStructuredPreferenceHint(observation.rawHint)
            listOf(
                observation.sourceApp,
                observation.domain,
                observation.pageType,
                parsedHint["anchor_object"].orEmpty()
            ).joinToString("###")
        }
        .values
        .mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val domain = CapabilityDomain.fromRaw(first.domain)?.wireName
                ?: first.domain.trim().lowercase().ifBlank { CapabilityDomain.OTHER.wireName }
            val domainRoot = CapabilityDomainProfileRegistry.domainRoot(CapabilityDomain.fromRaw(first.domain))
            val seeds = group.mapNotNull { observation ->
                val parsedHint = parseStructuredPreferenceHint(observation.rawHint)
                val slotKey = parsedHint["slot_key"].orEmpty()
                val slotValue = parsedHint["slot_value"].orEmpty()
                if (slotKey.isBlank() || slotValue.isBlank()) {
                    null
                } else {
                    PreferenceFacetSeed(
                        facetKey = slotKey,
                        facetValue = slotValue,
                        polarity = parsedHint["polarity"]?.ifBlank { "PREFER" } ?: "PREFER",
                        confidence = parsedHint["confidence"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.8,
                        freshnessHint = parsedHint["freshness_hint"]?.ifBlank { "RECENT" } ?: "RECENT",
                        sourceApp = observation.sourceApp,
                        supportingSourceApps = listOf(observation.sourceApp)
                    )
                }
            }
            if (seeds.isEmpty()) {
                null
            } else {
                val parsedHint = parseStructuredPreferenceHint(first.rawHint)
                val anchorObject = parsedHint["anchor_object"]?.trim().orEmpty().ifBlank {
                    first.pageType.trim().lowercase()
                }
                AppOrderHistoryRecord(
                    sourceApp = first.sourceApp,
                    domainRoot = domainRoot,
                    domain = domain,
                    anchorObject = anchorObject,
                    normalizedTitle = anchorObject,
                    summaryText = seeds.joinToString("; ") { seed -> "${seed.facetKey}=${seed.facetValue}" },
                    observedAt = group.maxOf { observation -> observation.capturedAt },
                    facetSeeds = seeds
                )
            }
        }
}

private fun buildPromotedAppOrderHistoryRecords(
    promotedAggregates: List<PreferencePromotionAggregate>
): List<AppOrderHistoryRecord> {
    return promotedAggregates
        .groupBy { aggregate ->
            listOf(aggregate.domain, aggregate.anchorObject).joinToString("###")
        }
        .values
        .mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val capabilityDomain = CapabilityDomain.fromRaw(first.domain)
            val domain = capabilityDomain?.wireName ?: first.domain.trim().lowercase().ifBlank { CapabilityDomain.OTHER.wireName }
            val seeds = group.map { aggregate ->
                PreferenceFacetSeed(
                    facetKey = aggregate.slotKey,
                    facetValue = aggregate.slotValue,
                    polarity = aggregate.polarity,
                    confidence = aggregate.confidence,
                    freshnessHint = aggregate.freshnessHint,
                    sourceApp = aggregate.sourceApp,
                    supportingSourceApps = aggregate.supportingSourceApps,
                    promotionSignals = aggregate.promotionSignals
                )
            }
            val summaryText = seeds.joinToString("; ") { seed ->
                buildString {
                    append(seed.facetKey)
                    append('=')
                    append(seed.facetValue)
                    if (seed.promotionSignals.isNotEmpty()) {
                        append(" [")
                        append(seed.promotionSignals.joinToString(","))
                        append(']')
                    }
                }
            }
            AppOrderHistoryRecord(
                sourceApp = seeds.firstNotNullOfOrNull { seed -> seed.sourceApp } ?: first.sourceApp.orEmpty(),
                domainRoot = CapabilityDomainProfileRegistry.domainRoot(capabilityDomain),
                domain = domain,
                anchorObject = first.anchorObject,
                normalizedTitle = first.anchorObject,
                summaryText = summaryText,
                observedAt = group.maxOf { aggregate -> aggregate.lastObservedAt },
                facetSeeds = seeds
            )
        }
}