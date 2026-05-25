package com.atombits.pocopaw

internal const val GENERIC_PROCESS_SCOPE = "generic_process"

internal fun String?.orGenericProcessScope(): String {
    val normalized = this?.trim().orEmpty()
    return normalized.ifBlank { GENERIC_PROCESS_SCOPE }
}
