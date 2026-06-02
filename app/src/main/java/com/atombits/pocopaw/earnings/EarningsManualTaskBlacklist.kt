package com.atombits.pocopaw.earnings

import java.util.Locale

object EarningsManualTaskBlacklist {
    const val rejectionCode: String = "MANUAL_TASK_BLACKLIST"

    private val blockedTaskPhrases = listOf(
        "天天领金币"
    )

    fun matchingPhrase(text: String): String? {
        val normalizedText = normalize(text)
        if (normalizedText.isBlank()) {
            return null
        }
        return blockedTaskPhrases.firstOrNull { phrase ->
            normalizedText.contains(normalize(phrase))
        }
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.US)
    }
}