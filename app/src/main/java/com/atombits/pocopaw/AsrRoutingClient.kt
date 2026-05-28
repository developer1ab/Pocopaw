package com.atombits.pocopaw

class AsrRoutingClient(
    private val tencentClient: TencentAsrClient = TencentAsrClient(),
    private val aliClient: AliAsrClient = AliAsrClient()
) {
    fun isConfigured(settings: VoiceRecognitionSettings): Boolean {
        return when (settings.cloudRegion) {
            VoiceCloudRegion.CN -> when (settings.cnProvider) {
                VoiceCnProvider.TENCENT -> tencentClient.isConfigured()
                VoiceCnProvider.ALI -> aliClient.isConfigured()
            }

            VoiceCloudRegion.GLOBAL -> false
        }
    }

    fun recognizeWav(wavBytes: ByteArray, settings: VoiceRecognitionSettings): String {
        return when (settings.cloudRegion) {
            VoiceCloudRegion.CN -> when (settings.cnProvider) {
                VoiceCnProvider.TENCENT -> {
                    if (!tencentClient.isConfigured()) {
                        throw IllegalStateException("Tencent ASR is not configured")
                    }
                    tencentClient.recognizeWav(wavBytes).transcript
                }

                VoiceCnProvider.ALI -> {
                    if (!aliClient.isConfigured()) {
                        throw IllegalStateException("Ali ASR is not configured")
                    }
                    aliClient.recognizeWav(wavBytes)
                }
            }

            VoiceCloudRegion.GLOBAL -> {
                throw IllegalStateException("Global cloud ASR provider is not wired yet")
            }
        }
    }
}
