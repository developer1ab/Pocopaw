package com.atombits.pocopaw

import android.content.Context

fun formatRequestFailureMessage(context: Context, throwable: Throwable): String {
    val rawMessage = throwable.message.orEmpty()
    return when {
        rawMessage.contains("malformed structured content", ignoreCase = true) ||
            rawMessage.contains("empty content payload", ignoreCase = true) -> {
            context.getString(R.string.request_failed_structured)
        }

        rawMessage.contains("Semantic provider request failed", ignoreCase = true) ||
            rawMessage.contains("semantic request failed", ignoreCase = true) -> {
            context.getString(R.string.request_failed_service)
        }

        rawMessage.contains("Search augmentation failed", ignoreCase = true) -> {
            context.getString(R.string.request_failed_search)
        }

        else -> context.getString(R.string.request_failed_generic)
    }
}