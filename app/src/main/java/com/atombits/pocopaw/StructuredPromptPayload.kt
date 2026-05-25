package com.atombits.pocopaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun parseStructuredPromptPayloadObject(raw: String): JsonObject {
    return JsonParser.parseString(extractStructuredPromptPayloadText(raw)).asJsonObject
}

internal fun extractStructuredPromptPayloadText(raw: String): String {
    val trimmed = raw.trim()
    val extractedContent = runCatching {
        val root = JsonParser.parseString(trimmed)
        if (!root.isJsonObject) {
            null
        } else {
            root.asJsonObject.getAsJsonArray("choices")
                ?.takeIf { choices -> choices.size() > 0 }
                ?.get(0)
                ?.takeIf { choice -> choice.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.takeIf { content -> !content.isJsonNull }
                ?.asString
        }
    }.getOrNull()

    val sanitizedContent = sanitizeStructuredPromptContent(extractedContent ?: trimmed)
    return extractFirstStructuredJsonObject(sanitizedContent) ?: sanitizedContent
}

private fun sanitizeStructuredPromptContent(content: String): String {
    var sanitized = content.trim()
    if (sanitized.startsWith("```")) {
        sanitized = sanitized.removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .trim()
        if (sanitized.endsWith("```")) {
            sanitized = sanitized.dropLast(3).trim()
        }
    }
    return sanitized
}

private fun extractFirstStructuredJsonObject(content: String): String? {
    var startIndex = -1
    var depth = 0
    var inString = false
    var escaped = false

    content.forEachIndexed { index, char ->
        if (startIndex == -1) {
            if (char == '{') {
                startIndex = index
                depth = 1
            }
            return@forEachIndexed
        }

        if (inString) {
            if (escaped) {
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            }
            return@forEachIndexed
        }

        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) {
                    return content.substring(startIndex, index + 1)
                }
            }
        }
    }

    return null
}