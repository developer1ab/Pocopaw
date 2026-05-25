package com.atombits.pocopaw

fun normalizeExecutionBoundaryPacketForRuntime(
    boundaryPacket: TaskExecutionBoundaryPacket,
    store: PrototypeStoreData,
    availableCapabilities: List<ToolCapability>
): TaskExecutionBoundaryPacket {
    val canonicalCapabilityId = canonicalizeExecutionCapabilityId(boundaryPacket.capabilityId, availableCapabilities)
    val normalizedBoundaryPacket = when {
        canonicalCapabilityId == boundaryPacket.capabilityId -> boundaryPacket
        else -> boundaryPacket.copy(capabilityId = canonicalCapabilityId)
    }
    val canonicalAppScope = extractCanonicalAppScope(normalizedBoundaryPacket.capabilityId)
    val selectedProcessId = resolveRuntimeProcessId(normalizedBoundaryPacket)?.takeIf { processId ->
        processId.isNotBlank() && hasGroundedExecutionRoute(store, processId, canonicalAppScope)
    }
    return if (selectedProcessId == normalizedBoundaryPacket.processId) {
        normalizedBoundaryPacket
    } else {
        normalizedBoundaryPacket.copy(processId = selectedProcessId)
    }
}

private fun resolveRuntimeProcessId(
    boundaryPacket: TaskExecutionBoundaryPacket
): String? {
    return boundaryPacket.processId?.trim()?.takeIf { value -> value.isNotBlank() }
}

private fun hasGroundedExecutionRoute(
    store: PrototypeStoreData,
    processId: String,
    canonicalAppScope: String?
): Boolean {
    val hasReadyAsset = store.readyProcessAssets.any { asset ->
        asset.processId == processId &&
            (canonicalAppScope == null || asset.appScope.equals(canonicalAppScope, ignoreCase = true))
    }
    if (hasReadyAsset) {
        return true
    }
    return store.processShortcutAtlas.any { candidate ->
        candidate.processId == processId &&
            (canonicalAppScope == null || candidate.appScope.equals(canonicalAppScope, ignoreCase = true))
    }
}

private fun canonicalizeExecutionCapabilityId(
    requestedCapabilityId: String?,
    availableCapabilities: List<ToolCapability>
): String? {
    val rawId = requestedCapabilityId?.trim().orEmpty()
    if (rawId.isBlank()) {
        return null
    }
    availableCapabilities.firstOrNull { capability ->
        capability.capabilityId.equals(rawId, ignoreCase = true) ||
            capability.source.equals(rawId, ignoreCase = true) ||
            capability.invokeUri.equals(rawId, ignoreCase = true)
    }?.let { capability ->
        return capability.capabilityId
    }

    val requestedTokens = expandAliasTokens(tokenizeForLookup(rawId))
    if (requestedTokens.isEmpty()) {
        return null
    }
    val ranked = availableCapabilities
        .map { capability -> capability to capabilityMatchScore(requestedTokens, capability) }
        .filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
    val topScore = ranked.firstOrNull()?.second ?: return null
    val topMatches = ranked.filter { (_, score) -> score == topScore }.map { (capability, _) -> capability }
    return topMatches.singleOrNull()?.capabilityId
}

private fun capabilityMatchScore(
    requestedTokens: Set<String>,
    capability: ToolCapability
): Int {
    val capabilityTokens = expandAliasTokens(
        tokenizeForLookup(
            listOf(
                capability.capabilityId,
                capability.source,
                capability.invokeUri,
                capability.displayName,
                capability.metadata["displayName"],
                capability.metadata["appName"]
            ).filterNotNull().joinToString(" ")
        )
    )
    return requestedTokens.intersect(capabilityTokens).size
}

private fun tokenizeForLookup(value: String): Set<String> {
    return value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(' ')
        .map { token -> token.trim() }
        .filter { token -> token.isNotBlank() }
        .toSet()
}

private fun expandAliasTokens(tokens: Set<String>): Set<String> {
    return expandCanonicalAppTokens(tokens)
}