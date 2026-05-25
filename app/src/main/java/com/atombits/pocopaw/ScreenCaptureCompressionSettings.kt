package com.atombits.pocopaw

import android.content.Context

internal const val MIN_CAPTURE_COMPRESSION_SCALE = 1
internal const val DEFAULT_CAPTURE_COMPRESSION_SCALE = 2

private const val CAPTURE_COMPRESSION_PREFS_NAME = "screen_capture_compression"
private const val KEY_CAPTURE_COMPRESSION_SCALE = "capture_compression_scale"

internal fun normalizeCaptureCompressionScale(rawScale: Int): Int {
    return rawScale.coerceAtLeast(MIN_CAPTURE_COMPRESSION_SCALE)
}

internal fun resolveCaptureCompressionQuality(rawScale: Int): Int {
    return when (normalizeCaptureCompressionScale(rawScale)) {
        1 -> 80
        2 -> 72
        else -> 64
    }
}

internal fun resolveCaptureScaledDimension(dimension: Int, rawScale: Int): Int {
    val safeDimension = dimension.coerceAtLeast(1)
    val scale = normalizeCaptureCompressionScale(rawScale)
    return (safeDimension / scale).coerceAtLeast(1)
}

internal fun formatCaptureCompressionSummary(rawScale: Int): String {
    val scale = normalizeCaptureCompressionScale(rawScale)
    return "${scale}x / JPEG ${resolveCaptureCompressionQuality(scale)}"
}

object ScreenCaptureCompressionRuntime {
    @Volatile
    private var uploadCompressionScale: Int = DEFAULT_CAPTURE_COMPRESSION_SCALE

    fun getUploadCompressionScale(): Int {
        return uploadCompressionScale
    }

    fun setUploadCompressionScale(scale: Int) {
        uploadCompressionScale = normalizeCaptureCompressionScale(scale)
    }
}

class ScreenCaptureCompressionSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        CAPTURE_COMPRESSION_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun readScale(): Int {
        return normalizeCaptureCompressionScale(
            prefs.getInt(KEY_CAPTURE_COMPRESSION_SCALE, DEFAULT_CAPTURE_COMPRESSION_SCALE)
        )
    }

    fun writeScale(scale: Int): Int {
        val normalizedScale = normalizeCaptureCompressionScale(scale)
        prefs.edit().putInt(KEY_CAPTURE_COMPRESSION_SCALE, normalizedScale).apply()
        ScreenCaptureCompressionRuntime.setUploadCompressionScale(normalizedScale)
        return normalizedScale
    }

    fun applyStoredScale(): Int {
        val storedScale = readScale()
        ScreenCaptureCompressionRuntime.setUploadCompressionScale(storedScale)
        return storedScale
    }
}