package com.atombits.pocopaw

import android.content.Context
import org.json.JSONObject

internal const val DEFAULT_QWEN_THINKING_MODE_ENABLED = false
internal const val DEFAULT_QWEN_SEARCH_ENABLED = false

private const val QWEN_THINKING_MODE_PREFS_NAME = "qwen_thinking_mode"
private const val KEY_QWEN_THINKING_MODE_ENABLED = "qwen_thinking_mode_enabled"
private const val KEY_QWEN_SEARCH_ENABLED = "qwen_search_enabled"

object QwenThinkingModeRuntime {
    @Volatile
    private var enabled: Boolean = DEFAULT_QWEN_THINKING_MODE_ENABLED

    fun isEnabled(): Boolean {
        return enabled
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}

object QwenSearchRuntime {
    @Volatile
    private var enabled: Boolean = DEFAULT_QWEN_SEARCH_ENABLED

    fun isEnabled(): Boolean {
        return enabled
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}

class QwenThinkingModeSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        QWEN_THINKING_MODE_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_QWEN_THINKING_MODE_ENABLED,
            DEFAULT_QWEN_THINKING_MODE_ENABLED
        )
    }

    fun writeEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_QWEN_THINKING_MODE_ENABLED, enabled).apply()
        QwenThinkingModeRuntime.setEnabled(enabled)
        return enabled
    }

    fun applyStoredEnabled(): Boolean {
        val storedEnabled = isEnabled()
        QwenThinkingModeRuntime.setEnabled(storedEnabled)
        return storedEnabled
    }
}

class QwenSearchSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        QWEN_THINKING_MODE_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_QWEN_SEARCH_ENABLED,
            DEFAULT_QWEN_SEARCH_ENABLED
        )
    }

    fun writeEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_QWEN_SEARCH_ENABLED, enabled).apply()
        QwenSearchRuntime.setEnabled(enabled)
        return enabled
    }

    fun applyStoredEnabled(): Boolean {
        val storedEnabled = isEnabled()
        QwenSearchRuntime.setEnabled(storedEnabled)
        return storedEnabled
    }
}

internal fun JSONObject.applyQwenThinkingModeSetting(): JSONObject {
    put("enable_thinking", QwenThinkingModeRuntime.isEnabled())
    put("enable_search", QwenSearchRuntime.isEnabled())
    return this
}