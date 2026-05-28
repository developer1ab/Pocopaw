package com.atombits.pocopaw

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TencentTtsClient(
    private val secretId: String = BuildConfig.TENCENT_ASR_SECRET_ID,
    private val secretKey: String = BuildConfig.TENCENT_ASR_SECRET_KEY,
    private val region: String = BuildConfig.TENCENT_ASR_REGION
) {
    private val host = "tts.tencentcloudapi.com"
    private val endpoint = "https://$host"
    private val service = "tts"
    private val action = "TextToVoice"
    private val version = "2019-08-23"
    private val algorithm = "TC3-HMAC-SHA256"
    private val contentType = "application/json; charset=utf-8"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        return secretId.isNotBlank() && secretKey.isNotBlank() && region.isNotBlank()
    }

    fun synthesizeMp3(text: String, voiceType: Int = 101016): ByteArray {
        require(isConfigured()) { "Tencent TTS is not configured." }
        require(text.isNotBlank()) { "TTS input text is empty." }

        val timestamp = Instant.now().epochSecond
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(timestamp))

        val requestPayload = JSONObject().apply {
            put("Text", text)
            put("SessionId", UUID.randomUUID().toString())
            put("ModelType", 1)
            put("VoiceType", voiceType)
            put("SampleRate", 16000)
            put("Codec", "mp3")
            put("Speed", 0)
            put("Volume", 5)
            put("EnableSubtitle", false)
        }.toString()

        val payloadHash = sha256Hex(requestPayload)
        val canonicalRequest = buildString {
            append("POST\n")
            append("/\n")
            append("\n")
            append("content-type:").append(contentType).append('\n')
            append("host:").append(host).append('\n')
            append("x-tc-action:").append(action.lowercase(Locale.US)).append('\n')
            append('\n')
            append("content-type;host;x-tc-action\n")
            append(payloadHash)
        }

        val credentialScope = "$date/$service/tc3_request"
        val stringToSign = buildString {
            append(algorithm).append('\n')
            append(timestamp).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest))
        }

        val secretDate = hmacSha256(date, ("TC3" + secretKey).toByteArray(Charsets.UTF_8))
        val secretService = hmacSha256(service, secretDate)
        val secretSigning = hmacSha256("tc3_request", secretService)
        val signature = hmacSha256Hex(stringToSign, secretSigning)

        val authorization = "$algorithm Credential=$secretId/$credentialScope, SignedHeaders=content-type;host;x-tc-action, Signature=$signature"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", contentType)
            .addHeader("Host", host)
            .addHeader("X-TC-Action", action)
            .addHeader("X-TC-Timestamp", timestamp.toString())
            .addHeader("X-TC-Version", version)
            .addHeader("X-TC-Region", region)
            .post(requestPayload.toRequestBody(contentType.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Tencent TTS request failed: ${response.code}")
            }
            val root = JSONObject(body)
            val responseObject = root.optJSONObject("Response")
                ?: throw IllegalStateException("Tencent TTS response missing Response field")
            if (responseObject.has("Error")) {
                val err = responseObject.optJSONObject("Error")
                val code = err?.optString("Code").orEmpty()
                val message = err?.optString("Message").orEmpty()
                throw IllegalStateException("Tencent TTS error: $code $message")
            }
            val audioBase64 = responseObject.optString("Audio").trim()
            if (audioBase64.isEmpty()) {
                throw IllegalStateException("Tencent TTS returned empty audio")
            }
            return android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hmacSha256(data: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(data: String, key: ByteArray): String {
        return hmacSha256(data, key).joinToString("") { byte -> "%02x".format(byte) }
    }
}
