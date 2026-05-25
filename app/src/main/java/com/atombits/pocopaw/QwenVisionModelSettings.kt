package com.atombits.pocopaw

import android.content.Context

internal const val QWEN_VISION_MODEL_35_PLUS = "qwen3.5-plus"
internal const val QWEN_VISION_MODEL_36_PLUS = "qwen3.6-plus"

internal val QWEN_VISION_MODEL_OPTIONS = listOf(
    QWEN_VISION_MODEL_35_PLUS,
    QWEN_VISION_MODEL_36_PLUS
)

private val QWEN_VISION_MODEL_ALIASES = mapOf(
    "qwen-3.5-plus" to QWEN_VISION_MODEL_35_PLUS,
    "qwen5.6-plus" to QWEN_VISION_MODEL_36_PLUS
)

private const val QWEN_VISION_MODEL_PREFS_NAME = "qwen_vision_model"
private const val KEY_QWEN_VISION_MODEL = "qwen_vision_model"

internal object QwenVisionModelRuntime {
    @Volatile
    private var modelName: String = normalizeQwenVisionModel(BuildConfig.QWEN_VISION_MODEL)

    fun modelName(): String {
        return modelName
    }

    fun setModelName(value: String): String {
        val normalized = normalizeQwenVisionModel(value)
        modelName = normalized
        return normalized
    }
}

internal fun normalizeQwenVisionModel(value: String?): String {
    val normalizedValue = value.orEmpty().trim()
    return QWEN_VISION_MODEL_OPTIONS.firstOrNull { option ->
        option.equals(normalizedValue, ignoreCase = true)
    } ?: QWEN_VISION_MODEL_ALIASES[normalizedValue.lowercase()] ?: QWEN_VISION_MODEL_35_PLUS
}

class QwenVisionModelSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        QWEN_VISION_MODEL_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun readModelName(): String {
        return normalizeQwenVisionModel(prefs.getString(KEY_QWEN_VISION_MODEL, null))
    }

    fun writeModelName(modelName: String): String {
        val normalized = normalizeQwenVisionModel(modelName)
        prefs.edit().putString(KEY_QWEN_VISION_MODEL, normalized).apply()
        QwenVisionModelRuntime.setModelName(normalized)
        return normalized
    }

    fun applyStoredModelName(): String {
        val storedModelName = readModelName()
        QwenVisionModelRuntime.setModelName(storedModelName)
        return storedModelName
    }
}