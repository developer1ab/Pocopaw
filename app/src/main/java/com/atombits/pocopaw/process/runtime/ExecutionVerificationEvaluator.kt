package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.ExecutionCheck
import com.atombits.pocopaw.ExecutionCheckType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ExecutionCheckFailure(
    val type: ExecutionCheckType,
    val key: String,
    val expectedValue: String? = null,
    val actualValue: String? = null,
    val reason: String
)

data class ExecutionVerificationOutcome(
    val passed: Boolean,
    val failedChecks: List<ExecutionCheckFailure> = emptyList(),
    val summary: String = ""
)

fun evaluateExecutionCompletionAgainstChecks(
    checks: List<ExecutionCheck>,
    terminalSummary: String,
    automationPayload: String?
): ExecutionVerificationOutcome {
    val evidence = listOf(terminalSummary, automationPayload.orEmpty())
        .joinToString("\n")
        .trim()
    val normalizedEvidence = evidence.lowercase(Locale.US)
    val trustedSlotEvidence = buildTrustedSlotEvidence(terminalSummary, automationPayload)
    val normalizedTrustedSlotEvidence = trustedSlotEvidence.text.lowercase(Locale.US)
    val failedChecks = checks.mapNotNull { check ->
        when (check.type) {
            ExecutionCheckType.SLOT_PRESERVED -> {
                if (
                    !check.required ||
                    !check.isTargetObjectSlotPreservationCheck() ||
                    !trustedSlotEvidence.hasStructuredAutomationPayload
                ) {
                    null
                } else {
                    val expected = check.expectedValue?.trim().orEmpty()
                    if (expected.isBlank() || normalizedTrustedSlotEvidence.contains(expected.lowercase(Locale.US))) {
                        null
                    } else {
                        ExecutionCheckFailure(
                            type = check.type,
                            key = check.key,
                            expectedValue = expected,
                            reason = "trusted terminal evidence missing preserved target object"
                        )
                    }
                }
            }

            ExecutionCheckType.RESULT_CONSISTENT -> {
                if (!check.required) {
                    null
                } else {
                    val expected = check.expectedValue?.trim().orEmpty()
                    if (expected.isBlank() || normalizedEvidence.contains(expected.lowercase(Locale.US))) {
                        null
                    } else {
                        ExecutionCheckFailure(
                            type = check.type,
                            key = check.key,
                            expectedValue = expected,
                            reason = "terminal evidence missing required result signal"
                        )
                    }
                }
            }

            ExecutionCheckType.PAGE_SIGNAL_PRESENT -> {
                if (!check.required) {
                    return@mapNotNull null
                }
                val expected = check.expectedValue?.trim().orEmpty()
                if (expected.isBlank() || normalizedEvidence.contains(expected.lowercase(Locale.US))) {
                    null
                } else {
                    ExecutionCheckFailure(
                        type = check.type,
                        key = check.key,
                        expectedValue = expected,
                        reason = "terminal evidence missing required page signal"
                    )
                }
            }

            ExecutionCheckType.PAGE_SIGNAL_ABSENT -> {
                if (!check.required) {
                    return@mapNotNull null
                }
                val expected = check.expectedValue?.trim().orEmpty()
                if (expected.isBlank() || !normalizedEvidence.contains(expected.lowercase(Locale.US))) {
                    null
                } else {
                    ExecutionCheckFailure(
                        type = check.type,
                        key = check.key,
                        expectedValue = expected,
                        reason = "terminal evidence contained forbidden page signal"
                    )
                }
            }
        }
    }
    if (failedChecks.isEmpty()) {
        return ExecutionVerificationOutcome(
            passed = true,
            summary = terminalSummary
        )
    }
    val failureSummary = failedChecks.joinToString("; ") { failure ->
        "${failure.key} expected ${failure.expectedValue}"
    }
    return ExecutionVerificationOutcome(
        passed = false,
        failedChecks = failedChecks,
        summary = com.atombits.pocopaw.UiStrings.resolve(
            com.atombits.pocopaw.R.string.execution_verification_failed,
            "Execution verification failed: %1\$s",
            failureSummary
        )
    )
}

private fun ExecutionCheck.isTargetObjectSlotPreservationCheck(): Boolean {
    if (type != ExecutionCheckType.SLOT_PRESERVED) {
        return false
    }
    val normalizedKey = key.trim().lowercase(Locale.US)
    return normalizedKey == "target_object" || normalizedKey == "targetobject"
}

private data class TrustedSlotEvidence(
    val text: String,
    val hasStructuredAutomationPayload: Boolean
)

private fun buildTrustedSlotEvidence(
    terminalSummary: String,
    automationPayload: String?
): TrustedSlotEvidence {
    val rawPayload = automationPayload?.trim().orEmpty()
    var hasStructuredAutomationPayload = false
    val payloadEvidence = if (rawPayload.startsWith("{")) {
        runCatching {
            val parsed = JSONObject(rawPayload)
            hasStructuredAutomationPayload = true
            buildList {
                parsed.optString("thought", "").trim().takeIf { value -> value.isNotBlank() }?.let(::add)
                parsed.optString("message", "").trim().takeIf { value -> value.isNotBlank() }?.let(::add)
                parsed.optJSONObject("semantic_context")?.let { semanticContext ->
                    semanticContext.optString("expected_outcome", "").trim().takeIf { value -> value.isNotBlank() }?.let(::add)
                    semanticContext.optJSONArray("verification_signals")?.toStringList()?.let { signals ->
                        addAll(signals)
                    }
                }
            }.joinToString("\n")
        }.getOrDefault(rawPayload)
    } else {
        rawPayload
    }
    val text = listOf(terminalSummary.trim(), payloadEvidence.trim())
        .filter { value -> value.isNotBlank() }
        .joinToString("\n")
    return TrustedSlotEvidence(
        text = text,
        hasStructuredAutomationPayload = hasStructuredAutomationPayload
    )
}

private fun JSONArray.toStringList(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            optString(index, "").trim().takeIf { value -> value.isNotBlank() }?.let(::add)
        }
    }
}

