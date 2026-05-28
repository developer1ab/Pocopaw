package com.atombits.pocopaw

import android.content.Context

private const val DEFAULT_TENCENT_TTS_VOICE_TYPE = 101015

enum class VoiceRecognitionMode {
    LOCAL,
    CLOUD
}

enum class VoiceCloudRegion {
    CN,
    GLOBAL
}

enum class VoiceCnProvider {
    TENCENT,
    ALI
}

enum class VoiceGlobalProvider {
    DEEPGRAM,
    GOOGLE
}

data class VoiceRecognitionSettings(
    val mode: VoiceRecognitionMode = VoiceRecognitionMode.CLOUD,
    val cloudRegion: VoiceCloudRegion = VoiceCloudRegion.CN,
    val cnProvider: VoiceCnProvider = VoiceCnProvider.TENCENT,
    val globalProvider: VoiceGlobalProvider = VoiceGlobalProvider.DEEPGRAM,
    val tencentTtsVoiceType: Int = DEFAULT_TENCENT_TTS_VOICE_TYPE,
    val autoSpeakEnabled: Boolean = true
)

class VoiceRecognitionSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pocopaw_voice_recognition_settings",
        Context.MODE_PRIVATE
    )

    fun read(): VoiceRecognitionSettings {
        val mode = prefs.getString(KEY_MODE, VoiceRecognitionMode.CLOUD.name)
            .toEnumOrDefault(VoiceRecognitionMode.CLOUD)
        val region = prefs.getString(KEY_CLOUD_REGION, VoiceCloudRegion.CN.name)
            .toEnumOrDefault(VoiceCloudRegion.CN)
        val cnProvider = prefs.getString(KEY_CN_PROVIDER, VoiceCnProvider.TENCENT.name)
            .toEnumOrDefault(VoiceCnProvider.TENCENT)
        val globalProvider = prefs.getString(KEY_GLOBAL_PROVIDER, VoiceGlobalProvider.DEEPGRAM.name)
            .toEnumOrDefault(VoiceGlobalProvider.DEEPGRAM)
        val storedTencentTtsVoiceType = prefs.getInt(
            KEY_TENCENT_TTS_VOICE_TYPE,
            DEFAULT_TENCENT_TTS_VOICE_TYPE
        )
        val tencentTtsVoiceType = when (storedTencentTtsVoiceType) {
            101001, 101002 -> DEFAULT_TENCENT_TTS_VOICE_TYPE
            else -> storedTencentTtsVoiceType
        }
        val autoSpeakEnabled = prefs.getBoolean(KEY_AUTO_SPEAK_ENABLED, true)
        return VoiceRecognitionSettings(
            mode = mode,
            cloudRegion = region,
            cnProvider = cnProvider,
            globalProvider = globalProvider,
            tencentTtsVoiceType = tencentTtsVoiceType,
            autoSpeakEnabled = autoSpeakEnabled
        )
    }

    fun write(settings: VoiceRecognitionSettings): VoiceRecognitionSettings {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_CLOUD_REGION, settings.cloudRegion.name)
            .putString(KEY_CN_PROVIDER, settings.cnProvider.name)
            .putString(KEY_GLOBAL_PROVIDER, settings.globalProvider.name)
            .putInt(KEY_TENCENT_TTS_VOICE_TYPE, settings.tencentTtsVoiceType)
            .putBoolean(KEY_AUTO_SPEAK_ENABLED, settings.autoSpeakEnabled)
            .apply()
        return read()
    }

    fun writeMode(mode: VoiceRecognitionMode): VoiceRecognitionSettings {
        return write(read().copy(mode = mode))
    }

    fun writeCloudRegion(region: VoiceCloudRegion): VoiceRecognitionSettings {
        return write(read().copy(cloudRegion = region))
    }

    fun writeCnProvider(provider: VoiceCnProvider): VoiceRecognitionSettings {
        return write(read().copy(cnProvider = provider))
    }

    fun writeGlobalProvider(provider: VoiceGlobalProvider): VoiceRecognitionSettings {
        return write(read().copy(globalProvider = provider))
    }

    fun writeTencentTtsVoiceType(voiceType: Int): VoiceRecognitionSettings {
        return write(read().copy(tencentTtsVoiceType = voiceType))
    }

    fun writeAutoSpeakEnabled(enabled: Boolean): VoiceRecognitionSettings {
        return write(read().copy(autoSpeakEnabled = enabled))
    }

    private fun <T : Enum<T>> String?.toEnumOrDefault(defaultValue: T): T {
        if (this.isNullOrBlank()) {
            return defaultValue
        }
        return runCatching {
            java.lang.Enum.valueOf(defaultValue.declaringJavaClass, this)
        }.getOrDefault(defaultValue)
    }

    private companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_CLOUD_REGION = "cloud_region"
        private const val KEY_CN_PROVIDER = "cn_provider"
        private const val KEY_GLOBAL_PROVIDER = "global_provider"
        private const val KEY_TENCENT_TTS_VOICE_TYPE = "tencent_tts_voice_type"
        private const val KEY_AUTO_SPEAK_ENABLED = "auto_speak_enabled"
    }
}
