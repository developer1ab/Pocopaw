package com.atombits.pocopaw

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AliAsrClient(
    private val apiKey: String = BuildConfig.ALI_ASR_API_KEY,
    private val model: String = BuildConfig.ALI_ASR_MODEL,
    private val endpoint: String = BuildConfig.ALI_ASR_ENDPOINT
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        return apiKey.isNotBlank() && model.isNotBlank() && endpoint.isNotBlank()
    }

    fun recognizeWav(wavBytes: ByteArray): String {
        require(isConfigured()) { "Ali ASR is not configured." }
        require(wavBytes.isNotEmpty()) { "WAV audio is empty." }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                "voice.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Ali ASR request failed: ${response.code}")
            }
            val root = JSONObject(body)
            val transcript = root.optString("text").trim()
            if (transcript.isBlank()) {
                throw IllegalStateException("Ali ASR returned empty transcript")
            }
            return transcript
        }
    }
}
