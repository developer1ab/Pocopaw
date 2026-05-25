package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.CanonicalAppCatalog
import com.atombits.pocopaw.CapabilityDomain
import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.allowedProcessScopesForApp
import com.atombits.pocopaw.appProcessCautions
import com.atombits.pocopaw.canonicalizeProcessAction
import com.atombits.pocopaw.derivePolicyProcessScope
import com.atombits.pocopaw.inferCanonicalProcessAction
import com.atombits.pocopaw.inferCanonicalProcessDomain
import com.atombits.pocopaw.isAllowedProcessScopeForApp
import com.atombits.pocopaw.normalizeProcessScopeValue
import com.atombits.pocopaw.resolveCanonicalAppScope
import java.util.Locale

private val shoppingProcessEnums = setOf(
    "shopping",
    "addtocart",
    "buy",
    "return",
    "compare",
    "coupon",
    "clearcart",
    "comments",
    "rating"
)

private val shoppingProcessMentions = shoppingProcessEnums + setOf("delete", "remove")

internal fun resolvePreferredAppScope(vararg candidates: String?): String? {
    return candidates
        .firstNotNullOfOrNull { candidate ->
            resolveCanonicalAppScope(candidate)
                ?: candidate?.trim()?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }
        }
}

internal fun resolvePreferredProcessScope(
    appScope: String?,
    preferredProcessScope: String?,
    primaryProcess: String?
): String? {
    val normalizedAppScope = resolvePreferredAppScope(appScope) ?: return null
    val normalizedPreferredScope = normalizeProcessScopeValue(preferredProcessScope)
    if (isAllowedProcessScopeForApp(normalizedAppScope, normalizedPreferredScope)) {
        return normalizedPreferredScope
    }
    return derivePolicyProcessScope(
        appId = normalizedAppScope,
        domain = inferPreferredPolicyDomain(normalizedAppScope)?.wireName,
        action = primaryProcess
    )
}

internal fun inferDomainScope(appScope: String?, primaryProcess: String?): String? {
    val normalizedAppScope = resolvePreferredAppScope(appScope)
    inferPreferredPolicyDomain(normalizedAppScope)?.let { domain ->
        return domain.wireName
    }
    return when {
        !normalizedAppScope.isNullOrBlank() && CapabilityDomain.SHOPPING in CanonicalAppCatalog.entryFor(normalizedAppScope)?.domains.orEmpty() -> "shopping"
        primaryProcess in shoppingProcessEnums -> "shopping"
        else -> null
    }
}

internal fun resolveBoundaryPacketPrimaryProcess(boundaryPacket: TaskExecutionBoundaryPacket?): String? {
    if (boundaryPacket == null) {
        return null
    }
    return normalizeProcessHint(boundaryPacket.processId)
}

internal fun normalizeProcessHint(value: String?): String? {
    val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() } ?: return null
    val compact = normalized.replace(Regex("[^a-z0-9]+"), "_").trim('_')
    val withoutSuffix = compact.removeSuffix("_process")
    return sequenceOf(
        withoutSuffix,
        withoutSuffix.substringAfterLast('_', withoutSuffix),
        compact,
        normalized.substringAfterLast('-', normalized)
    )
        .map { candidate -> candidate.trim() }
        .filter { candidate -> candidate.isNotBlank() }
        .mapNotNull(::normalizeShoppingProcessAction)
        .firstOrNull()
}

private fun normalizeShoppingProcessAction(value: String?): String? {
    val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() } ?: return null
    val canonical = when (normalized) {
        "shopping" -> "shopping"
        "delete", "remove" -> "clearcart"
        else -> canonicalizeProcessAction(normalized, "SHOPPING")
    }
    return canonical?.takeIf { action -> action in shoppingProcessEnums }
}

internal fun buildAppSpecificCautions(appScope: String?, primaryProcess: String?): List<String> {
    val normalizedAppScope = resolvePreferredAppScope(appScope) ?: return emptyList()
    val resolvedProcessScope = resolvePreferredProcessScope(
        appScope = normalizedAppScope,
        preferredProcessScope = null,
        primaryProcess = primaryProcess
    )
    return appProcessCautions(normalizedAppScope, resolvedProcessScope)
}

private fun inferPreferredPolicyDomain(appScope: String?): CapabilityDomain? {
    val normalizedAppScope = resolvePreferredAppScope(appScope) ?: return null
    val appEntry = CanonicalAppCatalog.entryFor(normalizedAppScope) ?: return null
    return appEntry.domains.singleOrNull()
        ?: if (CapabilityDomain.SHOPPING in appEntry.domains && allowedProcessScopesForApp(normalizedAppScope).isNotEmpty()) {
            CapabilityDomain.SHOPPING
        } else {
            null
        }
}

internal fun extractProcessMentions(text: String): List<String> {
    val normalized = text.lowercase(Locale.US)
    return shoppingProcessMentions
        .filter { processEnum ->
            Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(processEnum)}([^\\p{L}\\p{N}]|$)").containsMatchIn(normalized)
        }
        .mapNotNull(::normalizeProcessHint)
        .distinct()
}

internal fun buildProcessHitTokens(taskIntent: StructuredTaskIntent): Set<String> {
    return buildSet {
        taskIntent.primaryProcess?.let(::add)
        addAll(taskIntent.secondaryMentions)
        taskIntent.segments.forEach { segment ->
            addAll(extractProcessMentions(segment.text))
        }
    }
}

internal fun Sequence<String?>.countDistinctCueMatches(taskText: String): Int {
    return mapNotNull { value ->
        value?.trim()?.lowercase(Locale.US)?.takeIf { cue -> cue.length >= 2 }
    }
        .distinct()
        .count { cue -> taskText.contains(cue) || cue.contains(taskText) }
}

internal fun inferTaskIntentPrimaryProcess(
    objective: String,
    actionIntent: String,
    planSummary: String,
    selectedToolId: String?,
    processId: String?
): String? {
    val normalizedActionIntent = actionIntent.trim().takeIf { value -> value.isNotBlank() } ?: return null
    val processSearchText = listOf(objective.trim(), planSummary.trim())
        .filter { value -> value.isNotBlank() }
        .joinToString(" | ")
        .ifBlank { normalizedActionIntent }
    val domainHint = inferCanonicalProcessDomain(
        processId = processId.orEmpty(),
        objective = processSearchText,
        selectedToolId = selectedToolId
    )
    return normalizeProcessHint(
        inferCanonicalProcessAction(
            processId = processId.orEmpty(),
            objective = processSearchText,
            domain = domainHint,
            actionHint = normalizedActionIntent
        )
    )
}

internal fun formatReuseScore(score: Double): String {
    return String.format(Locale.US, "%.2f", score)
}