package com.atombits.pocopaw

import android.content.Context
import org.json.JSONObject

internal const val DEFAULT_VISION_REQUEST_THINKING_ENABLED = false
internal const val DEFAULT_VISION_REQUEST_SEARCH_ENABLED = false

private const val VISION_REQUEST_TUNING_PREFS_NAME = "vision_request_tuning"
private const val KEY_VISION_REQUEST_THINKING_ENABLED = "vision_request_thinking_enabled"
private const val KEY_VISION_REQUEST_SEARCH_ENABLED = "vision_request_search_enabled"
private const val LEGACY_QWEN_REQUEST_OPTIONS_PREFS_NAME = "qwen_thinking_mode"
private const val LEGACY_KEY_QWEN_THINKING_MODE_ENABLED = "qwen_thinking_mode_enabled"
private const val LEGACY_KEY_QWEN_SEARCH_ENABLED = "qwen_search_enabled"

internal object VisionRequestThinkingRuntime {
    @Volatile
    private var enabled: Boolean = DEFAULT_VISION_REQUEST_THINKING_ENABLED

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}

internal object VisionRequestSearchRuntime {
    @Volatile
    private var enabled: Boolean = DEFAULT_VISION_REQUEST_SEARCH_ENABLED

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }
}

class VisionRequestThinkingSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        VISION_REQUEST_TUNING_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean {
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun writeEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_VISION_REQUEST_THINKING_ENABLED, false).apply()
        VisionRequestThinkingRuntime.setEnabled(false)
        return false
    }

    fun applyStoredEnabled(): Boolean {
        prefs.edit().putBoolean(KEY_VISION_REQUEST_THINKING_ENABLED, false).apply()
        VisionRequestThinkingRuntime.setEnabled(false)
        return false
    }
}

class VisionRequestSearchSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        VISION_REQUEST_TUNING_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean {
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun writeEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_VISION_REQUEST_SEARCH_ENABLED, false).apply()
        VisionRequestSearchRuntime.setEnabled(false)
        return false
    }

    fun applyStoredEnabled(): Boolean {
        prefs.edit().putBoolean(KEY_VISION_REQUEST_SEARCH_ENABLED, false).apply()
        VisionRequestSearchRuntime.setEnabled(false)
        return false
    }
}

internal fun VisionProviderKind.supportsVisionRequestTuning(): Boolean {
    return this == VisionProviderKind.QWEN_VISION
}

internal fun JSONObject.applyVisionRequestTuning(provider: VisionProviderKind): JSONObject {
    if (!provider.supportsVisionRequestTuning()) {
        return this
    }
    put("enable_thinking", VisionRequestThinkingRuntime.isEnabled())
    put("enable_search", VisionRequestSearchRuntime.isEnabled())
    return this
}