package com.atombits.pocopaw

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import kotlinx.coroutines.delay

data class TextInputExecutionResult(
    val applied: Boolean,
    val note: String? = null
)

data class BridgeActionExecutionResult(
    val executed: Boolean,
    val note: String? = null
)

interface AutomationDeviceController {
    suspend fun tap(normalizedX: Float, normalizedY: Float): Boolean
    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean

    suspend fun longPress(normalizedX: Float, normalizedY: Float, durationMs: Long): Boolean

    fun inputText(
        text: String,
        autoDismissKeyboard: Boolean,
        targetX: Float? = null,
        targetY: Float? = null
    ): Boolean

    fun inputTextWithResult(
        text: String,
        autoDismissKeyboard: Boolean,
        targetX: Float? = null,
        targetY: Float? = null
    ): TextInputExecutionResult {
        return TextInputExecutionResult(
            applied = inputText(
                text = text,
                autoDismissKeyboard = autoDismissKeyboard,
                targetX = targetX,
                targetY = targetY
            )
        )
    }

    fun handleKeyEvent(keyCode: Int): Boolean
}

interface BridgeActionExecutor {
    suspend fun execute(action: BridgeAction): Boolean

    suspend fun executeWithResult(action: BridgeAction): BridgeActionExecutionResult {
        return BridgeActionExecutionResult(executed = execute(action))
    }
}

internal fun normalizeBridgeLaunchTarget(uri: String?): String? {
    return uri?.trim().orEmpty().removePrefix("app://").ifBlank { null }
}

internal fun resolveBridgeLaunchPackage(uri: String?): String? {
    val target = normalizeBridgeLaunchTarget(uri) ?: return null
    return if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("sys://")) {
        null
    } else {
        target
    }
}

internal fun resolveBridgeLaunchSystemCapabilityId(uri: String?): String? {
    val target = normalizeBridgeLaunchTarget(uri) ?: return null
    if (!target.startsWith("sys://", ignoreCase = true)) {
        return null
    }
    val capabilityId = target
        .substringAfter("sys://", "")
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    return capabilityId.takeIf(::isSystemIntentCapabilityId)
}

internal fun isSupportedBridgeKeyEvent(keyCode: Int?): Boolean {
    return keyCode == 3 || keyCode == 4
}

internal fun blockedBridgeActionNote(action: BridgeAction): String? {
    return if (action.type == "keyevent" && !isSupportedBridgeKeyEvent(action.keyCode)) {
        "unsupported bridge keyevent ${action.keyCode ?: -1}"
    } else {
        null
    }
}

class LocalBridgeActionExecutor private constructor(
    private val launchAction: (String?) -> Boolean,
    private val deviceControllerProvider: () -> AutomationDeviceController?,
    private val pause: suspend (Long) -> Unit
) : BridgeActionExecutor {

    constructor(
        launchContextProvider: () -> Context?,
        deviceControllerProvider: () -> AutomationDeviceController? = { PrototypeAccessibilityService.instance },
        pause: suspend (Long) -> Unit = { delay(it) }
    ) : this(
        launchAction = { uri -> launchBridgeUri(launchContextProvider(), uri) },
        deviceControllerProvider = deviceControllerProvider,
        pause = pause
    )

    internal constructor(
        launchAction: (String?) -> Boolean,
        deviceController: AutomationDeviceController?,
        pause: suspend (Long) -> Unit = { delay(it) },
        testOnly: Boolean = true
    ) : this(
        launchAction = launchAction,
        deviceControllerProvider = { deviceController },
        pause = pause
    )

    override suspend fun execute(action: BridgeAction): Boolean {
        return executeWithResult(action).executed
    }

    override suspend fun executeWithResult(action: BridgeAction): BridgeActionExecutionResult {
        val normalizedType = action.type.trim()
        return when (normalizedType) {
            "appLaunch" -> BridgeActionExecutionResult(executed = launchAction(action.uri))
            "tap" -> BridgeActionExecutionResult(
                executed = deviceControllerProvider()?.tap(
                normalizedX = action.x ?: 0.5f,
                normalizedY = action.y ?: 0.5f
                ) ?: false
            )

            "swipe" -> BridgeActionExecutionResult(
                executed = deviceControllerProvider()?.swipe(
                fromX = action.fromX ?: 0.5f,
                fromY = action.fromY ?: 0.75f,
                toX = action.toX ?: 0.5f,
                toY = action.toY ?: 0.25f,
                durationMs = action.duration ?: 280L
                ) ?: false
            )

            "longPress" -> BridgeActionExecutionResult(
                executed = deviceControllerProvider()?.longPress(
                normalizedX = action.x ?: 0.5f,
                normalizedY = action.y ?: 0.5f,
                durationMs = action.duration ?: 900L
                ) ?: false
            )

            "inputText" -> deviceControllerProvider()?.inputTextWithResult(
                text = action.text.orEmpty(),
                autoDismissKeyboard = action.autoDismissKeyboard,
                targetX = action.x,
                targetY = action.y
            )?.let { result ->
                BridgeActionExecutionResult(
                    executed = result.applied,
                    note = result.note
                )
            } ?: BridgeActionExecutionResult(
                executed = false,
                note = "input device controller unavailable"
            )

            "keyevent" -> if (isSupportedBridgeKeyEvent(action.keyCode)) {
                BridgeActionExecutionResult(
                    executed = deviceControllerProvider()?.handleKeyEvent(action.keyCode ?: -1) ?: false
                )
            } else {
                BridgeActionExecutionResult(
                    executed = false,
                    note = blockedBridgeActionNote(action)
                )
            }

            "wait" -> {
                pause(action.duration ?: 800L)
                BridgeActionExecutionResult(executed = true)
            }

            "done" -> BridgeActionExecutionResult(executed = true)
            else -> BridgeActionExecutionResult(executed = false)
        }
    }
}

private fun launchBridgeUri(context: Context?, uri: String?): Boolean {
    val effectiveContext = context ?: return false
    return launchCapabilityTarget(effectiveContext, uri)
}