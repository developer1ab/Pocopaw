package com.atombits.pocopaw.process.curation

import com.atombits.pocopaw.orGenericProcessScope
import com.atombits.pocopaw.deriveCanonicalProcessScope
import com.atombits.pocopaw.extractCanonicalAcceptanceSignal
import com.atombits.pocopaw.extractCanonicalProcessAssetStepBinding
import com.atombits.pocopaw.extractCanonicalStageName
import java.util.Locale

internal object ProcessTracePreprocessor {

    private const val maxSummarizedActions = 6
    private const val maxVerificationSignals = 8
    private const val maxSignalsPerLine = 4

    fun preprocess(
        entry: ProcessAssetEntry,
        now: Long = System.currentTimeMillis()
    ): CanonicalProcessTraceBundle {
        val sourceLines = resolveSourceTraceLines(entry)
        val canonicalTrace = mutableListOf<String>()
        var previousFingerprint: String? = null
        var duplicateCollapseCount = 0

        sourceLines.forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) {
                return@forEach
            }
            val fingerprint = buildTraceFingerprint(trimmed, entry)
            if (fingerprint == previousFingerprint) {
                duplicateCollapseCount += 1
                return@forEach
            }
            canonicalTrace += trimmed
            previousFingerprint = fingerprint
        }

        return CanonicalProcessTraceBundle(
            task = entry.taskExample.ifBlank { entry.semanticDescription }.ifBlank { entry.assetName },
            appScope = entry.appScope,
            processScope = deriveCanonicalProcessScope(
                rawProcessId = entry.processScope,
                objective = entry.taskExample.ifBlank { entry.semanticDescription }.ifBlank { entry.assetName },
                appScope = entry.appScope,
                domain = entry.domain,
                actionHint = entry.businessProcessName.ifBlank { entry.assetName }
            ).orGenericProcessScope(),
            canonicalTrace = canonicalTrace,
            summarizedActions = buildSummarizedActions(canonicalTrace),
            verificationSignals = buildVerificationSignals(canonicalTrace, entry.businessAcceptanceCriteria),
            removedNoiseCount = duplicateCollapseCount,
            duplicateCollapseCount = duplicateCollapseCount,
            generatedAt = now
        )
    }

    internal fun compactTraceForPrompt(
        traceBundle: CanonicalProcessTraceBundle,
        maxChars: Int = 3200
    ): String {
        val content = buildString {
            append("trace_task=")
            append(traceBundle.task)
            appendLine()
            append("trace_app_scope=")
            append(traceBundle.appScope)
            appendLine()
            append("trace_flow_scope=")
            append(traceBundle.processScope)
            appendLine()
            append("removed_noise_count=")
            append(traceBundle.removedNoiseCount)
            appendLine()
            append("duplicate_collapse_count=")
            append(traceBundle.duplicateCollapseCount)
            appendLine()
            append("summarized_actions=")
            append(traceBundle.summarizedActions.joinToString(" | "))
            appendLine()
            append("verification_signals=")
            append(traceBundle.verificationSignals.joinToString(" | "))
            appendLine()
            append("canonical_trace:")
            traceBundle.canonicalTrace.forEachIndexed { index, line ->
                appendLine()
                append(line)
            }
        }
        if (content.length <= maxChars) {
            return content
        }
        return content.take(maxChars - 3).trimEnd() + "..."
    }

    private fun resolveSourceTraceLines(entry: ProcessAssetEntry): List<String> {
        val planningTraceLines = entry.planningTrace.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .toList()
        if (planningTraceLines.isNotEmpty()) {
            return planningTraceLines
        }
        val optimizedTrace = entry.optimizedProcessTrace
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
        if (optimizedTrace.isNotEmpty()) {
            return optimizedTrace
        }
        return listOfNotNull(entry.businessProcessName.takeIf { value -> value.isNotBlank() })
    }

    private fun buildTraceFingerprint(rawLine: String, entry: ProcessAssetEntry): String {
        val binding = extractCanonicalProcessAssetStepBinding(rawLine)
        val type = extractJsonStringField(rawLine, "type").ifBlank {
            extractCanonicalStageName(rawLine).orEmpty()
        }
        val uri = extractJsonStringField(rawLine, "uri").ifBlank {
            extractInlineAttribute(rawLine, "uri") ?: extractInlineAttribute(rawLine, "page").orEmpty()
        }
        val text = extractJsonStringField(rawLine, "text").ifBlank {
            extractInlineAttribute(rawLine, "text").orEmpty()
        }
        val message = extractJsonStringField(rawLine, "message").ifBlank {
            extractInlineAttribute(rawLine, "message")
                ?: binding?.note.orEmpty()
        }
        val goal = extractJsonStringField(rawLine, "goal").ifBlank {
            extractInlineAttribute(rawLine, "goal")
                ?: extractCanonicalStageName(rawLine).orEmpty()
        }
        val expectedOutcome = extractJsonStringField(rawLine, "expected_outcome").ifBlank {
            extractCanonicalAcceptanceSignal(rawLine).orEmpty()
        }
        return listOf(
            type.trim().lowercase(Locale.US),
            uri.trim().lowercase(Locale.US),
            text.trim().lowercase(Locale.US),
            message.trim().lowercase(Locale.US),
            goal.trim().lowercase(Locale.US),
            expectedOutcome.trim().lowercase(Locale.US)
        ).joinToString("|")
    }

    private fun buildSummarizedActions(canonicalTrace: List<String>): List<String> {
        return canonicalTrace.asSequence()
            .mapNotNull { line ->
                val stageName = extractCanonicalStageName(line)
                    ?.replace('_', ' ')
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                val expectedOutcome = extractCanonicalAcceptanceSignal(line)
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                when {
                    stageName != null && expectedOutcome != null -> "$stageName -> $expectedOutcome"
                    stageName != null -> stageName
                    else -> null
                }
            }
            .distinct()
            .take(maxSummarizedActions)
            .toList()
    }

    private fun buildVerificationSignals(
        canonicalTrace: List<String>,
        acceptanceCriteria: List<String>
    ): List<String> {
        val verificationSignals = mutableListOf<String>()
        canonicalTrace.forEach { line ->
            val perLineSignals = linkedSetOf<String>()
            extractCanonicalAcceptanceSignal(line)
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let(perLineSignals::add)
            val binding = extractCanonicalProcessAssetStepBinding(line)
            binding?.verificationSignals
                .orEmpty()
                .map { signal -> signal.trim() }
                .filter { signal -> signal.isNotBlank() }
                .forEach(perLineSignals::add)
            binding?.pageSignature
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let(perLineSignals::add)
            perLineSignals.take(maxSignalsPerLine).forEach { signal ->
                verificationSignals.appendDistinctSignal(signal)
            }
        }
        acceptanceCriteria.forEach { signal ->
            verificationSignals.appendDistinctSignal(signal)
        }
        return verificationSignals.take(maxVerificationSignals)
    }

    private fun MutableList<String>.appendDistinctSignal(signal: String) {
        if (size >= maxVerificationSignals) {
            return
        }
        if (none { existing -> existing.equals(signal, ignoreCase = true) }) {
            add(signal)
        }
    }

    private fun extractJsonStringField(rawLine: String, key: String): String {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        val match = regex.find(rawLine) ?: return ""
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractInlineAttribute(rawLine: String, key: String): String? {
        return Regex("(?:^|[;|])\\s*${Regex.escape(key)}=([^;|]+)")
            .find(rawLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
    }
}