package com.atombits.pocopaw

internal fun isSystemIntentCapabilityId(capabilityId: String?): Boolean {
    return capabilityId?.startsWith("system.intent.") == true
}

internal fun isSystemIntentExecution(runtimeState: ExecutionRuntimeState): Boolean {
    runtimeState.executionResult.routeEntryType?.let { routeEntryType ->
        return routeEntryType == ExecutionRouteEntryType.SYSTEM_INTENT
    }
    val selectedToolId = runtimeState.executionResult.selectedToolId
        ?: runtimeState.capabilityId
        ?: runtimeState.executionTrace.selectedToolId
    return isSystemIntentCapabilityId(selectedToolId)
}
