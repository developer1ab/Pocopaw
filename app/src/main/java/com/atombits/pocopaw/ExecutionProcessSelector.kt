package com.atombits.pocopaw

import com.atombits.pocopaw.process.reuse.SemanticProcessReferenceSelectionResolver
import com.atombits.pocopaw.process.reuse.ProcessReuseResolution
import com.atombits.pocopaw.process.reuse.ProcessReuseRuntime

fun resolveExecutionBoundaryPacketWithReadyProcesses(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): TaskExecutionBoundaryPacket {
    val runtimeSelection = ProcessReuseRuntime.resolve(
        store = store,
        activeCandidate = store.resolveTaskFirstCandidate(),
        boundaryPacket = boundaryPacket,
        selectionResolver = SemanticProcessReferenceSelectionResolver()
    )
    resolveReusableProcessId(
        store = store,
        runtimeSelection = runtimeSelection
    )
        ?.takeIf { value -> value.isNotBlank() }
        ?.let { selectedProcessId ->
            return boundaryPacket.copy(processId = selectedProcessId)
        }
    return boundaryPacket
}

private fun resolveReusableProcessId(
    store: PrototypeStoreData,
    runtimeSelection: ProcessReuseResolution?
): String? {
    val preferredReference = runtimeSelection?.preferredReference ?: return null
    val assetId = preferredReference.assetId.trim()
    if (assetId.startsWith(READY_ASSET_ID_PREFIX)) {
        return assetId.removePrefix(READY_ASSET_ID_PREFIX).takeIf { value -> value.isNotBlank() }
    }
    return store.processAssetEntries.firstOrNull { entry ->
        entry.id == assetId
    }?.let { entry ->
            resolveReadyProcessAssetForEntry(store, entry)?.processId
                ?: sanitizeCanonicalProcessId(entry.processScope)
            ?: preferredReference.processScope?.takeIf { value -> value.isNotBlank() }
            ?: preferredReference.processEnum?.takeIf { value -> value.isNotBlank() }
    }
        ?: preferredReference.processScope?.takeIf { value -> value.isNotBlank() }
        ?: preferredReference.processEnum?.takeIf { value -> value.isNotBlank() }
}

    internal fun resolveReadyProcessAssetForEntry(
        store: PrototypeStoreData,
        entry: com.atombits.pocopaw.process.curation.ProcessAssetEntry
    ): ReadyProcessAsset? {
        return store.readyProcessAssets.firstOrNull { asset -> asset.lineageSourceTraceId == entry.id }
            ?: store.readyProcessAssets.firstOrNull { asset ->
                asset.appScope.equals(entry.appScope, ignoreCase = true) &&
                    sanitizeCanonicalProcessId(asset.processId) == sanitizeCanonicalProcessId(entry.processScope)
            }
    }

private const val READY_ASSET_ID_PREFIX = "ready_asset:"

fun extractCanonicalAppScope(selectedToolId: String?): String? {
    return resolveCanonicalAppScope(selectedToolId)
}