package com.atombits.pocopaw

import android.content.Context
import android.util.Log
import org.json.JSONObject

internal const val DEFAULT_VISION_REQUEST_THINKING_ENABLED = false
internal const val DEFAULT_VISION_REQUEST_SEARCH_ENABLED = false

private const val VISION_REQUEST_TUNING_PREFS_NAME = "vision_request_tuning"
private const val KEY_VISION_REQUEST_THINKING_ENABLED = "vision_request_thinking_enabled"
private const val KEY_VISION_REQUEST_SEARCH_ENABLED = "vision_request_search_enabled"
private const val LEGACY_QWEN_REQUEST_OPTIONS_PREFS_NAME = "qwen_thinking_mode"
private const val LEGACY_KEY_QWEN_THINKING_MODE_ENABLED = "qwen_thinking_mode_enabled"
private const val LEGACY_KEY_QWEN_SEARCH_ENABLED = "qwen_search_enabled"
private const val TOGGLE_TRACE_TAG = "ToggleTrace"

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
        val persisted = prefs.getBoolean(
            KEY_VISION_REQUEST_THINKING_ENABLED,
            DEFAULT_VISION_REQUEST_THINKING_ENABLED
        )
        VisionRequestThinkingRuntime.setEnabled(persisted)
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionThinking isEnabled persisted=$persisted runtime=${VisionRequestThinkingRuntime.isEnabled()} return=$persisted"
        )
        return persisted
    }

    fun writeEnabled(enabled: Boolean): Boolean {
        val before = prefs.getBoolean(
            KEY_VISION_REQUEST_THINKING_ENABLED,
            DEFAULT_VISION_REQUEST_THINKING_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionThinking write request=$enabled before=$before"
        )
        prefs.edit().putBoolean(KEY_VISION_REQUEST_THINKING_ENABLED, enabled).apply()
        VisionRequestThinkingRuntime.setEnabled(enabled)
        val after = prefs.getBoolean(
            KEY_VISION_REQUEST_THINKING_ENABLED,
            DEFAULT_VISION_REQUEST_THINKING_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionThinking write after=$after runtime=${VisionRequestThinkingRuntime.isEnabled()} return=$after"
        )
        return after
    }

    fun applyStoredEnabled(): Boolean {
        val before = prefs.getBoolean(
            KEY_VISION_REQUEST_THINKING_ENABLED,
            DEFAULT_VISION_REQUEST_THINKING_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionThinking apply before=$before"
        )
        VisionRequestThinkingRuntime.setEnabled(before)
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionThinking apply after=$before runtime=${VisionRequestThinkingRuntime.isEnabled()} return=$before"
        )
        return before
    }
}

class VisionRequestSearchSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        VISION_REQUEST_TUNING_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(): Boolean {
        val persisted = prefs.getBoolean(
            KEY_VISION_REQUEST_SEARCH_ENABLED,
            DEFAULT_VISION_REQUEST_SEARCH_ENABLED
        )
        VisionRequestSearchRuntime.setEnabled(persisted)
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionSearch isEnabled persisted=$persisted runtime=${VisionRequestSearchRuntime.isEnabled()} return=$persisted"
        )
        return persisted
    }

    fun writeEnabled(enabled: Boolean): Boolean {
        val before = prefs.getBoolean(
            KEY_VISION_REQUEST_SEARCH_ENABLED,
            DEFAULT_VISION_REQUEST_SEARCH_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionSearch write request=$enabled before=$before"
        )
        prefs.edit().putBoolean(KEY_VISION_REQUEST_SEARCH_ENABLED, enabled).apply()
        VisionRequestSearchRuntime.setEnabled(enabled)
        val after = prefs.getBoolean(
            KEY_VISION_REQUEST_SEARCH_ENABLED,
            DEFAULT_VISION_REQUEST_SEARCH_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionSearch write after=$after runtime=${VisionRequestSearchRuntime.isEnabled()} return=$after"
        )
        return after
    }

    fun applyStoredEnabled(): Boolean {
        val before = prefs.getBoolean(
            KEY_VISION_REQUEST_SEARCH_ENABLED,
            DEFAULT_VISION_REQUEST_SEARCH_ENABLED
        )
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionSearch apply before=$before"
        )
        VisionRequestSearchRuntime.setEnabled(before)
        Log.d(
            TOGGLE_TRACE_TAG,
            "store visionSearch apply after=$before runtime=${VisionRequestSearchRuntime.isEnabled()} return=$before"
        )
        return before
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