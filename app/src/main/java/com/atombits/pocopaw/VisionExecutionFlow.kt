package com.atombits.pocopaw

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.atombits.pocopaw.service.CaptureService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
 
 data class ScreenCaptureSnapshot(
     val imageDataUrl: String,
     val captureWidth: Int,
     val captureHeight: Int
 )
 
 data class VisionGroundingRequest(
     val objective: String,
     val expectedOutcome: String,
     val imageDataUrl: String,
     val captureWidth: Int,
     val captureHeight: Int,
     val locatorHint: String? = null,
     val verificationSignals: List<String> = emptyList(),
     val stepNote: String? = null,
     val pageSignature: String? = null,
     val processGuidance: String? = null,
     val inputCandidates: Map<String, String> = emptyMap()
 )

enum class VisionActionType {
    NONE,
    TAP,
    INPUT,
    SWIPE,
    BACK,
    WAIT;

    companion object {
        fun fromWire(rawValue: String?): VisionActionType {
            return when (rawValue?.trim()?.uppercase()) {
                "TAP", "CLICK", "NAVIGATE" -> TAP
                "INPUT", "TYPE" -> INPUT
                "SWIPE", "SCROLL" -> SWIPE
                "BACK" -> BACK
                "WAIT" -> WAIT
                else -> NONE
            }
        }
    }
}

enum class VisionContinuationMode {
    STOP,
    VERIFY_ONLY,
    VERIFY_THEN_CONTINUE,
    REGROUND;

    companion object {
        fun fromWire(rawValue: String?): VisionContinuationMode {
            return when (rawValue?.trim()?.uppercase()) {
                "VERIFY_ONLY" -> VERIFY_ONLY
                "VERIFY_THEN_CONTINUE" -> VERIFY_THEN_CONTINUE
                "REGROUND", "RE_GROUND" -> REGROUND
                else -> STOP
            }
        }
    }
}
 
 data class VisionGroundingResult(
     val resolved: Boolean,
     val stepType: String,
    val actionType: VisionActionType = VisionActionType.NONE,
    val targetX: Float? = null,
    val targetY: Float? = null,
    val inputSlotKey: String? = null,
    val inputText: String? = null,
    val swipeFromX: Float? = null,
    val swipeFromY: Float? = null,
    val swipeToX: Float? = null,
    val swipeToY: Float? = null,
    val actionDurationMs: Long? = null,
     val expectedOutcome: String,
    val verificationSignals: List<String> = emptyList(),
    val continuationMode: VisionContinuationMode = VisionContinuationMode.STOP,
    val fallbackPolicy: String = "STOP",
    val riskLevel: String = "LOW",
    val pageSignature: String? = null,
    val locatorHint: String? = null,
     val note: String? = null
 )

data class VisionActionExecutionResult(
    val executed: Boolean,
    val note: String? = null
)

data class VisionVerificationRequest(
    val expectedOutcome: String,
    val verificationSignals: List<String>,
    val imageDataUrl: String,
    val captureWidth: Int,
    val captureHeight: Int,
    val debugToken: String? = null
)

data class ScreenEvidenceVerificationResult(
    val matched: Boolean,
    val matchedSignal: String,
    val note: String? = null
)

private const val VISION_VERIFICATION_DEBUG_TAG = "VisionVerifyDebug"
private const val VISION_VERIFICATION_DEBUG_DIR = "vision_verification_debug"

internal object VisionVerificationDebugRecorder {
    fun nextToken(expectedOutcome: String): String {
        val safeOutcome = expectedOutcome
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "verification" }
            .take(32)
        return "${System.currentTimeMillis()}_$safeOutcome"
    }

    fun recordSnapshotCaptured(token: String, request: VisionVerificationRequest) {
        val snapshotFile = exportSnapshot(token, request.imageDataUrl)
        record(
            stage = "snapshot_captured",
            token = token,
            request = request,
            extras = mapOf(
                "snapshot_file" to snapshotFile,
                "image_size" to "${request.captureWidth}x${request.captureHeight}"
            )
        )
    }

    fun recordRequestStarted(token: String, request: VisionVerificationRequest, model: String, endpoint: String) {
        record(
            stage = "request_started",
            token = token,
            request = request,
            extras = mapOf(
                "model" to model,
                "endpoint" to buildAutomationDebugPreview(endpoint, maxChars = 120)
            )
        )
    }

    fun recordHttpFailure(token: String, request: VisionVerificationRequest, statusCode: Int, bodyPreview: String) {
        record(
            stage = "http_failure",
            token = token,
            request = request,
            extras = mapOf(
                "status_code" to statusCode,
                "body_preview" to buildAutomationDebugPreview(bodyPreview)
            )
        )
    }

    fun recordEmptyResponse(token: String, request: VisionVerificationRequest, bodyPreview: String) {
        record(
            stage = "response_empty",
            token = token,
            request = request,
            extras = mapOf("body_preview" to buildAutomationDebugPreview(bodyPreview))
        )
    }

    fun recordParsedResult(token: String, request: VisionVerificationRequest, result: ScreenEvidenceVerificationResult, contentPreview: String) {
        record(
            stage = "parsed_result",
            token = token,
            request = request,
            extras = mapOf(
                "matched" to result.matched,
                "matched_signal" to result.matchedSignal,
                "note" to result.note,
                "content_preview" to buildAutomationDebugPreview(contentPreview)
            )
        )
    }

    fun recordException(token: String, request: VisionVerificationRequest, throwable: Throwable) {
        record(
            stage = "exception",
            token = token,
            request = request,
            extras = mapOf(
                "exception" to throwable.toAutomationDebugDetail(),
                "message" to throwable.message
            ),
            throwable = throwable
        )
    }

    fun recordConfigurationMissing(token: String, request: VisionVerificationRequest) {
        record(stage = "api_not_configured", token = token, request = request)
    }

    private fun record(
        stage: String,
        token: String,
        request: VisionVerificationRequest,
        extras: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        val payload = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", stage)
            put("token", token)
            put("expected_outcome", request.expectedOutcome)
            put("verification_signals", JSONArray(request.verificationSignals))
            put("capture_width", request.captureWidth)
            put("capture_height", request.captureHeight)
            extras.forEach { (key, value) -> put(key, value) }
        }
        val line = payload.toString()
        val detail = buildAutomationDebugPreview(line, maxChars = 240)
        if (throwable == null) {
            Log.d(VISION_VERIFICATION_DEBUG_TAG, detail)
        } else {
            Log.e(VISION_VERIFICATION_DEBUG_TAG, detail, throwable)
        }
        appendDebugLine(line)
    }

    private fun exportSnapshot(token: String, imageDataUrl: String): String? {
        val imageBytes = decodeImageDataUrl(imageDataUrl) ?: return null
        return runCatching {
            val file = File(resolveDebugDirectory(), "verification_${token}.jpg")
            file.writeBytes(imageBytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun appendDebugLine(line: String) {
        runCatching {
            val file = File(resolveDebugDirectory(), "vision_verification_events.jsonl")
            file.appendText(line + "\n")
        }
    }

    private fun resolveDebugDirectory(): File {
        val context = CaptureService.appContext
        val root = context?.getExternalFilesDir(VISION_VERIFICATION_DEBUG_DIR)
            ?: context?.filesDir?.let { File(it, VISION_VERIFICATION_DEBUG_DIR) }
            ?: File(System.getProperty("java.io.tmpdir") ?: ".", VISION_VERIFICATION_DEBUG_DIR)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun decodeImageDataUrl(imageDataUrl: String): ByteArray? {
        val payload = imageDataUrl.substringAfter(',', "")
        if (payload.isBlank()) {
            return null
        }
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }
}

data class VisionVerificationResult(
    val matched: Boolean,
    val expectedOutcome: String,
    val note: String? = null
)
 
 interface ScreenCaptureCoordinator {
     fun hasPermission(): Boolean
     fun captureSnapshot(timeoutMs: Long = 2000L): ScreenCaptureSnapshot?
 }
 
 interface VisionGroundingResolver {
     suspend fun resolve(request: VisionGroundingRequest): VisionGroundingResult
 }

interface GroundedVisionActionRunner {
    suspend fun runAction(
        runtimeState: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult
    ): VisionActionExecutionResult
}

interface VisionVerificationResolver {
    suspend fun verify(request: VisionVerificationRequest): ScreenEvidenceVerificationResult
}

interface ScreenEvidenceVerifier {
    suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        expectedOutcome: String,
        verificationSignals: List<String>
    ): ScreenEvidenceVerificationResult
}

interface VisionStepVerifier {
    suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult
    ): VisionVerificationResult
}
 
 class PrototypeScreenCaptureManager(private val context: Context) {
     private val projectionManager =
         context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
 
     fun createCaptureIntent(): Intent = projectionManager.createScreenCaptureIntent()
 
     fun savePermission(resultCode: Int, data: Intent?) {
         data ?: return
         val intent = Intent(context, CaptureService::class.java).apply {
             putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
             putExtra(CaptureService.EXTRA_RESULT_DATA, data)
         }
         ContextCompat.startForegroundService(context, intent)
     }
 
     fun hasPermission(): Boolean = CaptureService.isReady
 }
 
 object LocalScreenCaptureCoordinator : ScreenCaptureCoordinator {
     override fun hasPermission(): Boolean = CaptureService.isReady
 
     override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot? {
         val deadline = SystemClock.elapsedRealtime() + timeoutMs
         while (!CaptureService.isReady && SystemClock.elapsedRealtime() < deadline) {
             Thread.sleep(100)
         }
         if (!CaptureService.isReady) {
             return null
         }
 
         val reader: ImageReader = CaptureService.imageReader ?: return null
         val width = CaptureService.captureWidth
         val height = CaptureService.captureHeight
         if (width <= 0 || height <= 0) {
             return null
         }
 
         val imageDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
         while (System.nanoTime() < imageDeadline) {
             val image = try {
                 reader.acquireLatestImage()
             } catch (_: Exception) {
                 null
             }
             if (image != null) {
                 try {
                     val plane = image.planes[0]
                     val buffer = plane.buffer
                     val pixelStride = plane.pixelStride
                     val rowStride = plane.rowStride
                     val rowPadding = rowStride - pixelStride * width
 
                     val bitmap = Bitmap.createBitmap(
                         width + rowPadding / pixelStride,
                         height,
                         Bitmap.Config.ARGB_8888
                     )
                     bitmap.copyPixelsFromBuffer(buffer)
                     val croppedBitmap = if (rowPadding == 0) {
                         bitmap
                     } else {
                         Bitmap.createBitmap(bitmap, 0, 0, width, height)
                     }
 
                    val compressionScale = ScreenCaptureCompressionRuntime.getUploadCompressionScale()
                    val uploadBitmap = if (compressionScale > 1) {
                        Bitmap.createScaledBitmap(
                            croppedBitmap,
                            resolveCaptureScaledDimension(croppedBitmap.width, compressionScale),
                            resolveCaptureScaledDimension(croppedBitmap.height, compressionScale),
                            true
                        )
                    } else {
                        croppedBitmap
                    }

                    val uploadWidth = uploadBitmap.width
                    val uploadHeight = uploadBitmap.height
                    val output = ByteArrayOutputStream()
                    uploadBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        resolveCaptureCompressionQuality(compressionScale),
                        output
                    )
                     val imageDataUrl = "data:image/jpeg;base64," + Base64.encodeToString(
                         output.toByteArray(),
                         Base64.NO_WRAP
                     )
                     output.close()
                    if (uploadBitmap !== croppedBitmap) {
                        uploadBitmap.recycle()
                    }
                     if (croppedBitmap !== bitmap) {
                         croppedBitmap.recycle()
                     }
                     bitmap.recycle()
                     return ScreenCaptureSnapshot(
                         imageDataUrl = imageDataUrl,
                         captureWidth = uploadWidth,
                         captureHeight = uploadHeight
                     )
                 } catch (_: Exception) {
                 } finally {
                     image.close()
                 }
             }
             Thread.sleep(80)
         }
         return null
     }
 }

object LocalGroundedVisionActionRunner : GroundedVisionActionRunner {
    private val bridgeActionExecutor = LocalBridgeActionExecutor(
        launchContextProvider = { com.atombits.pocopaw.service.PrototypeAccessibilityService.instance }
    )

    override suspend fun runAction(
        runtimeState: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult
    ): VisionActionExecutionResult {
        val action = toVisionBridgeAction(groundingResult)
            ?: return VisionActionExecutionResult(
                executed = false,
                note = unsupportedVisionActionNote(groundingResult)
            )
        val executionResult = bridgeActionExecutor.executeWithResult(action)
        return VisionActionExecutionResult(
            executed = executionResult.executed,
            note = if (executionResult.executed) {
                mergeVisionExecutionNote(groundingResult.note, executionResult.note)
            } else {
                executionResult.note
                    ?: blockedBridgeActionNote(action)
                    ?: "vision action failed"
            }
        )
    }
}

internal fun mergeVisionExecutionNote(
    groundingNote: String?,
    executionNote: String?
): String? {
    val normalizedGroundingNote = groundingNote?.trim().orEmpty()
    val normalizedExecutionNote = executionNote?.trim().orEmpty()
    return when {
        normalizedGroundingNote.isBlank() -> normalizedExecutionNote.ifBlank { null }
        normalizedExecutionNote.isBlank() -> normalizedGroundingNote
        normalizedGroundingNote == normalizedExecutionNote -> normalizedGroundingNote
        else -> "$normalizedGroundingNote | $normalizedExecutionNote"
    }
}

internal fun toVisionBridgeAction(groundingResult: VisionGroundingResult): BridgeAction? {
    return when (groundingResult.actionType) {
        VisionActionType.TAP -> {
            if (groundingResult.targetX == null || groundingResult.targetY == null) {
                null
            } else {
                BridgeAction(type = "tap", x = groundingResult.targetX, y = groundingResult.targetY)
            }
        }

        VisionActionType.INPUT -> groundingResult.inputText?.takeIf { text -> text.isNotBlank() }?.let { text ->
            BridgeAction(
                type = "inputText",
                x = groundingResult.targetX,
                y = groundingResult.targetY,
                text = text
            )
        }

        VisionActionType.SWIPE -> {
            if (
                groundingResult.swipeFromX == null ||
                groundingResult.swipeFromY == null ||
                groundingResult.swipeToX == null ||
                groundingResult.swipeToY == null
            ) {
                null
            } else {
                BridgeAction(
                    type = "swipe",
                    fromX = groundingResult.swipeFromX,
                    fromY = groundingResult.swipeFromY,
                    toX = groundingResult.swipeToX,
                    toY = groundingResult.swipeToY,
                    duration = groundingResult.actionDurationMs
                )
            }
        }

        VisionActionType.BACK -> BridgeAction(type = "keyevent", keyCode = 4)
        VisionActionType.WAIT -> BridgeAction(type = "wait", duration = groundingResult.actionDurationMs)
        VisionActionType.NONE -> null
    }
}

internal fun unsupportedVisionActionNote(groundingResult: VisionGroundingResult): String {
    return when (groundingResult.actionType) {
        VisionActionType.TAP -> "vision action target missing"
        VisionActionType.INPUT -> "vision input action missing text payload"
        VisionActionType.SWIPE -> "vision swipe action missing path payload"
        else -> "unsupported vision action"
    }
}

class LocalScreenEvidenceVerifier(
    private val screenCaptureCoordinator: ScreenCaptureCoordinator = LocalScreenCaptureCoordinator,
    private val visionVerificationResolver: VisionVerificationResolver = VisionVerificationResolverClient()
) : ScreenEvidenceVerifier {
    override suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        expectedOutcome: String,
        verificationSignals: List<String>
    ): ScreenEvidenceVerificationResult {
        if (!screenCaptureCoordinator.hasPermission()) {
            return ScreenEvidenceVerificationResult(
                matched = false,
                matchedSignal = expectedOutcome,
                note = "screen capture permission unavailable"
            )
        }
        val snapshot = screenCaptureCoordinator.captureSnapshot()
            ?: return ScreenEvidenceVerificationResult(
                matched = false,
                matchedSignal = expectedOutcome,
                note = "screen capture failed"
            )
        val request = VisionVerificationRequest(
            expectedOutcome = expectedOutcome,
            verificationSignals = verificationSignals,
            imageDataUrl = snapshot.imageDataUrl,
            captureWidth = snapshot.captureWidth,
            captureHeight = snapshot.captureHeight,
            debugToken = VisionVerificationDebugRecorder.nextToken(expectedOutcome)
        )
        request.debugToken?.let { token ->
            VisionVerificationDebugRecorder.recordSnapshotCaptured(token, request)
        }
        return visionVerificationResolver.verify(request)
    }
}

class LocalVisionStepVerifier(
    private val screenEvidenceVerifier: ScreenEvidenceVerifier = LocalScreenEvidenceVerifier()
) : VisionStepVerifier {
    override suspend fun verify(
        runtimeState: ExecutionRuntimeState,
        groundingResult: VisionGroundingResult
    ): VisionVerificationResult {
        val expectedOutcome = groundingResult.verificationSignals.firstOrNull() ?: groundingResult.expectedOutcome
        val verificationResult = screenEvidenceVerifier.verify(
            runtimeState = runtimeState,
            expectedOutcome = expectedOutcome,
            verificationSignals = groundingResult.verificationSignals
        )
        return VisionVerificationResult(
            matched = verificationResult.matched,
            expectedOutcome = verificationResult.matchedSignal,
            note = verificationResult.note
        )
    }
}

fun parseVisionGroundingResult(
    content: String,
    defaultExpectedOutcome: String,
    inputCandidates: Map<String, String> = emptyMap()
): VisionGroundingResult {
    val parsed = JsonParser.parseString(content).asJsonObject
    val fromObject = parsed.optJsonObject("from")
    val toObject = parsed.optJsonObject("to")
    val actionType = VisionActionType.fromWire(parsed.optString("action_type"))
    val normalizedInputCandidates = normalizeVisionInputCandidates(inputCandidates)
    val resolvedAuthoritativeInput = if (actionType == VisionActionType.INPUT) {
        resolveAuthoritativeVisionInput(
            rawSlotKey = parsed.optStringOrNull("input_slot_key"),
            rawInputText = parsed.optStringOrNull("text") ?: parsed.optStringOrNull("input_text"),
            inputCandidates = normalizedInputCandidates
        )
    } else {
        null to (parsed.optStringOrNull("text") ?: parsed.optStringOrNull("input_text"))
    }
    return VisionGroundingResult(
        resolved = parsed.optBoolean("resolved", false),
        stepType = parsed.optString("step_type", "NAVIGATE").ifBlank { "NAVIGATE" },
        actionType = actionType,
        targetX = parsed.optFloatOrNull("target_x"),
        targetY = parsed.optFloatOrNull("target_y"),
        inputSlotKey = resolvedAuthoritativeInput.first,
        inputText = resolvedAuthoritativeInput.second,
        swipeFromX = parsed.optFloatOrNull("from_x") ?: fromObject?.optFloatOrNull("x"),
        swipeFromY = parsed.optFloatOrNull("from_y") ?: fromObject?.optFloatOrNull("y"),
        swipeToX = parsed.optFloatOrNull("to_x") ?: toObject?.optFloatOrNull("x"),
        swipeToY = parsed.optFloatOrNull("to_y") ?: toObject?.optFloatOrNull("y"),
        actionDurationMs = parsed.optLongOrNull("duration_ms") ?: parsed.optLongOrNull("duration"),
        expectedOutcome = parsed.optString("expected_outcome", defaultExpectedOutcome)
            .ifBlank { defaultExpectedOutcome },
        verificationSignals = parsed.optStringList("verification_signals"),
        continuationMode = VisionContinuationMode.fromWire(parsed.optString("continuation_mode")),
        fallbackPolicy = parsed.optString("fallback_policy", "STOP").ifBlank { "STOP" },
        riskLevel = parsed.optString("risk_level", "LOW").ifBlank { "LOW" },
        pageSignature = parsed.optString("page_signature").ifBlank { null },
        locatorHint = parsed.optString("locator_hint").ifBlank { null },
        note = parsed.optString("note").ifBlank { null }
    )
}

private fun normalizeVisionInputCandidates(inputCandidates: Map<String, String>): Map<String, String> {
    val normalizedCandidates = linkedMapOf<String, String>()
    inputCandidates.forEach { (rawKey, rawValue) ->
        val normalizedKey = rawKey.trim().lowercase(Locale.US)
        val normalizedValue = rawValue.trim()
        if (normalizedKey.isBlank() || normalizedValue.isBlank() || normalizedKey in normalizedCandidates) {
            return@forEach
        }
        normalizedCandidates[normalizedKey] = normalizedValue
    }
    return normalizedCandidates
}

private fun resolveAuthoritativeVisionInput(
    rawSlotKey: String?,
    rawInputText: String?,
    inputCandidates: Map<String, String>
): Pair<String?, String?> {
    val normalizedInputText = rawInputText?.trim()?.takeIf { value -> value.isNotBlank() }
    if (inputCandidates.isEmpty()) {
        return null to normalizedInputText
    }

    val normalizedSlotKey = rawSlotKey?.trim()?.lowercase(Locale.US)?.takeIf { value -> value.isNotBlank() }
    normalizedInputText?.let { inputText ->
        inputCandidates.entries.firstOrNull { (_, candidateValue) -> candidateValue == inputText }?.let { entry ->
            return entry.key to entry.value
        }
    }

    normalizedSlotKey?.let { slotKey ->
        inputCandidates[slotKey]?.let { candidateValue ->
            if (isVisionInputConstraintDowngrade(candidateValue, inputCandidates.values)) {
                return null to null
            }
            return slotKey to candidateValue
        }
    }

    if (inputCandidates.size == 1) {
        val onlyCandidate = inputCandidates.entries.first()
        return onlyCandidate.key to onlyCandidate.value
    }

    return null to null
}

private fun isVisionInputConstraintDowngrade(candidateValue: String, activeCandidates: Collection<String>): Boolean {
    return activeCandidates.any { activeCandidate ->
        isVisionInputConstraintDowngrade(candidateValue, activeCandidate)
    }
}

private fun isVisionInputConstraintDowngrade(candidateValue: String, activeConstraint: String): Boolean {
    val normalizedCandidate = normalizeVisionConstraintText(candidateValue)
    val normalizedConstraint = normalizeVisionConstraintText(activeConstraint)
    if (normalizedCandidate.length < 2 || normalizedCandidate == normalizedConstraint) {
        return false
    }
    if (!normalizedConstraint.contains(normalizedCandidate)) {
        return false
    }
    return normalizedConstraint.length - normalizedCandidate.length >= 5
}

private fun normalizeVisionConstraintText(value: String): String {
    return value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.US)
}

fun parseVisionVerificationResult(
    content: String,
    defaultExpectedOutcome: String
): ScreenEvidenceVerificationResult {
    val parsed = JsonParser.parseString(content).asJsonObject
    return ScreenEvidenceVerificationResult(
        matched = parsed.optBoolean("matched", false),
        matchedSignal = parsed.optString("matched_signal", defaultExpectedOutcome)
            .ifBlank { defaultExpectedOutcome },
        note = parsed.optString("note").ifBlank { null }
    )
}

private fun JsonObject.optBoolean(key: String, defaultValue: Boolean): Boolean {
    val element = get(key) ?: return defaultValue
    return runCatching { element.asBoolean }.getOrDefault(defaultValue)
}

private fun JsonObject.optString(key: String, defaultValue: String = ""): String {
    val element = get(key) ?: return defaultValue
    return runCatching { element.asString }.getOrDefault(defaultValue)
}

private fun JsonObject.optFloatOrNull(key: String): Float? {
    val element = get(key) ?: return null
    return runCatching { element.asFloat }.getOrNull()
}

private fun JsonObject.optLongOrNull(key: String): Long? {
    val element = get(key) ?: return null
    return runCatching { element.asLong }.getOrNull()
}

private fun JsonObject.optStringOrNull(key: String): String? {
    return optString(key).ifBlank { null }
}

private fun JsonObject.optJsonObject(key: String): JsonObject? {
    val element = get(key) ?: return null
    return runCatching { element.asJsonObject }.getOrNull()
}

private fun JsonObject.optStringList(key: String): List<String> {
    val array = getAsJsonArray(key) ?: return emptyList()
    val values = mutableListOf<String>()
    array.forEach { element ->
        val value = runCatching { element.asString.trim() }.getOrDefault("")
        if (value.isNotEmpty()) {
            values += value
        }
    }
    return values
}
 
 internal class VisionGroundingResolverClient(
     private val apiKey: String = ProviderRuntimeConfigs.vision.apiKey,
     private val model: String? = null,
     private val endpoint: String = ProviderRuntimeConfigs.vision.endpoint,
     private val provider: VisionProviderKind = ProviderRuntimeConfigs.visionProviderKind()
 ) : VisionGroundingResolver {
     private val httpClient = OkHttpClient.Builder()
         .callTimeout(60, TimeUnit.SECONDS)
         .build()
 
     override suspend fun resolve(request: VisionGroundingRequest): VisionGroundingResult {
         if (apiKey.isBlank()) {
             return VisionGroundingResult(
                 resolved = false,
                 stepType = "NAVIGATE",
                 expectedOutcome = request.expectedOutcome,
                 note = "vision api not configured"
             )
         }
 
         return runCatching {
            val selectedModel = model ?: ProviderRuntimeConfigs.vision.model
            val packet = PromptCenter.buildVisionGroundingPacket(request)
             val requestBody = JSONObject().apply {
                 put("model", selectedModel)
                 put("messages", JSONArray().apply {
                     put(
                         JSONObject().apply {
                             put("role", "system")
                            put("content", packet.promptMessages.first().content + "\n\nResponse contract:\n" + packet.responseContract)
                         }
                     )
                     put(
                         JSONObject().apply {
                             put("role", "user")
                             put("content", JSONArray().apply {
                                 put(
                                     JSONObject().apply {
                                         put(
                                             "type",
                                             "text"
                                         )
                                        put("text", packet.promptMessages.last().content)
                                     }
                                 )
                                 put(
                                     JSONObject().apply {
                                         put("type", "image_url")
                                         put(
                                             "image_url",
                                             JSONObject().apply {
                                                 put("url", request.imageDataUrl)
                                             }
                                         )
                                     }
                                 )
                             })
                         }
                     )
                 })
                 put("response_format", JSONObject().apply { put("type", "json_object") })
             }.applyVisionRequestTuning(provider)
 
             val httpRequest = Request.Builder()
                 .url(endpoint)
                 .addHeader("Authorization", "Bearer $apiKey")
                 .addHeader("Content-Type", "application/json")
                 .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                 .build()
 
             httpClient.newCall(httpRequest).execute().use { response ->
                 if (!response.isSuccessful) {
                     return VisionGroundingResult(
                         resolved = false,
                         stepType = "NAVIGATE",
                         expectedOutcome = request.expectedOutcome,
                         note = "vision request failed: ${response.code}"
                     )
                 }
                 val body = response.body?.string().orEmpty()
                 val content = JSONObject(body)
                     .optJSONArray("choices")
                     ?.optJSONObject(0)
                     ?.optJSONObject("message")
                     ?.optString("content")
                     .orEmpty()
                 if (content.isBlank()) {
                     return VisionGroundingResult(
                         resolved = false,
                         stepType = "NAVIGATE",
                         expectedOutcome = request.expectedOutcome,
                         note = "vision response empty"
                     )
                 }
                 parseVisionGroundingResult(
                     content = content,
                     defaultExpectedOutcome = request.expectedOutcome,
                     inputCandidates = request.inputCandidates
                 )
             }
         }.getOrElse { throwable ->
             VisionGroundingResult(
                 resolved = false,
                 stepType = "NAVIGATE",
                 expectedOutcome = request.expectedOutcome,
                 note = throwable.message ?: "vision grounding failed"
             )
         }
     }
}

internal class VisionVerificationResolverClient(
    private val apiKey: String = ProviderRuntimeConfigs.vision.apiKey,
    private val model: String? = null,
    private val endpoint: String = ProviderRuntimeConfigs.vision.endpoint,
    private val provider: VisionProviderKind = ProviderRuntimeConfigs.visionProviderKind()
) : VisionVerificationResolver {
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun verify(request: VisionVerificationRequest): ScreenEvidenceVerificationResult {
        val debugToken = request.debugToken ?: VisionVerificationDebugRecorder.nextToken(request.expectedOutcome)
        if (apiKey.isBlank()) {
            VisionVerificationDebugRecorder.recordConfigurationMissing(debugToken, request)
            return ScreenEvidenceVerificationResult(
                matched = false,
                matchedSignal = request.expectedOutcome,
                note = "vision api not configured"
            )
        }

        return runCatching {
            val selectedModel = model ?: ProviderRuntimeConfigs.vision.model
            VisionVerificationDebugRecorder.recordRequestStarted(debugToken, request, selectedModel, endpoint)
            val packet = PromptCenter.buildVisionVerificationPacket(request)
            val requestBody = JSONObject().apply {
                put("model", selectedModel)
                put("messages", JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", packet.promptMessages.first().content + "\n\nResponse contract:\n" + packet.responseContract)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("type", "text")
                                        put("text", packet.promptMessages.last().content)
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            JSONObject().apply {
                                                put("url", request.imageDataUrl)
                                            }
                                        )
                                    }
                                )
                            })
                        }
                    )
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }.applyVisionRequestTuning(provider)

            val httpRequest = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    VisionVerificationDebugRecorder.recordHttpFailure(debugToken, request, response.code, body)
                    return ScreenEvidenceVerificationResult(
                        matched = false,
                        matchedSignal = request.expectedOutcome,
                        note = "vision verification failed: ${response.code}"
                    )
                }
                val content = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (content.isBlank()) {
                    VisionVerificationDebugRecorder.recordEmptyResponse(debugToken, request, body)
                    return ScreenEvidenceVerificationResult(
                        matched = false,
                        matchedSignal = request.expectedOutcome,
                        note = "vision verification response empty"
                    )
                }
                val parsedResult = parseVisionVerificationResult(
                    content = content,
                    defaultExpectedOutcome = request.expectedOutcome
                )
                VisionVerificationDebugRecorder.recordParsedResult(debugToken, request, parsedResult, content)
                parsedResult
            }
        }.getOrElse { throwable ->
            VisionVerificationDebugRecorder.recordException(debugToken, request, throwable)
            ScreenEvidenceVerificationResult(
                matched = false,
                matchedSignal = request.expectedOutcome,
                note = throwable.message ?: "vision verification failed"
            )
        }
    }
}