package com.atombits.pocopaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.atombits.pocopaw.AutomationDeviceController
import com.atombits.pocopaw.TextInputExecutionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

internal data class CoordinateSpace(
    val width: Int,
    val height: Int,
    val source: String
)

internal data class MappedPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val pixelX: Float,
    val pixelY: Float
)

internal data class TapStroke(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

internal data class ResolvedTextInputTarget(
    val node: AccessibilityNodeInfo,
    val source: String
)

internal data class TextEntryVerification(
    val matched: Boolean,
    val reason: String
)

internal data class TextInputLayerAttempt(
    val applied: Boolean,
    val reason: String
)

internal data class ImeAffordanceSnapshot(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val boundsTop: Int,
    val boundsBottom: Int,
    val clickable: Boolean = false
)

internal data class ImeAffordanceEvaluation(
    val score: Int,
    val reason: String
)

private data class ImeAffordanceCandidate(
    val node: AccessibilityNodeInfo,
    val evaluation: ImeAffordanceEvaluation
)

internal fun verifyTextEntryReadback(
    currentText: CharSequence?,
    expectedText: String
): TextEntryVerification {
    val normalizedExpected = expectedText.trim()
    val normalizedCurrent = currentText?.toString()?.trim().orEmpty()
    return when {
        normalizedCurrent.isBlank() -> TextEntryVerification(matched = false, reason = "readback_blank")
        normalizedCurrent == normalizedExpected -> TextEntryVerification(matched = true, reason = "readback_exact")
        else -> TextEntryVerification(
            matched = false,
            reason = "readback_mismatch(len=${normalizedCurrent.length}/${normalizedExpected.length})"
        )
    }
}

internal fun evaluateImeAffordanceSnapshot(
    snapshot: ImeAffordanceSnapshot,
    expectedText: String,
    windowTop: Int,
    windowBottom: Int
): ImeAffordanceEvaluation? {
    val normalizedExpected = expectedText.trim().lowercase(Locale.US)
    if (normalizedExpected.isBlank()) {
        return null
    }

    val windowHeight = (windowBottom - windowTop).coerceAtLeast(0)
    if (windowHeight <= 0) {
        return null
    }

    val keyboardAreaThreshold = windowTop + (windowHeight * 0.55f).toInt()
    if (snapshot.boundsBottom < keyboardAreaThreshold) {
        return null
    }

    val normalizedText = snapshot.text?.trim()?.lowercase(Locale.US).orEmpty()
    val normalizedDescription = snapshot.contentDescription?.trim()?.lowercase(Locale.US).orEmpty()
    val normalizedViewId = snapshot.viewId?.trim()?.lowercase(Locale.US).orEmpty()
    val exactTextMatch = normalizedText == normalizedExpected || normalizedDescription == normalizedExpected
    val containsTextMatch = !exactTextMatch && normalizedExpected.length >= 2 && (
        normalizedText.contains(normalizedExpected) || normalizedDescription.contains(normalizedExpected)
    )
    val clipboardHint = containsClipboardKeyword(normalizedText) ||
        containsClipboardKeyword(normalizedDescription) ||
        containsClipboardKeyword(normalizedViewId)

    if (!exactTextMatch && !containsTextMatch && !clipboardHint) {
        return null
    }

    var score = 0
    val reason = when {
        exactTextMatch -> {
            score += 120
            "exact_text"
        }

        containsTextMatch -> {
            score += 90
            "contains_text"
        }

        else -> "clipboard_hint"
    }

    if (clipboardHint) {
        score += 20
    }
    if (snapshot.clickable) {
        score += 5
    }
    score += ((snapshot.boundsBottom - keyboardAreaThreshold).coerceAtLeast(0) / 20)
    return ImeAffordanceEvaluation(score = score, reason = reason)
}

private fun containsClipboardKeyword(value: String): Boolean {
    return value.contains("clipboard") ||
        value.contains("clip") ||
        value.contains("paste") ||
        value.contains("剪贴板") ||
        value.contains("粘贴板") ||
        value.contains("粘贴")
}

internal fun resolveGestureCoordinateSpace(
    captureWidth: Int,
    captureHeight: Int,
    realWidth: Int,
    realHeight: Int
): CoordinateSpace {
    return if (captureWidth > 0 && captureHeight > 0) {
        CoordinateSpace(
            width = captureWidth,
            height = captureHeight,
            source = "capture"
        )
    } else {
        CoordinateSpace(
            width = realWidth.coerceAtLeast(1),
            height = realHeight.coerceAtLeast(1),
            source = "realMetrics"
        )
    }
}

internal fun mapNormalizedPoint(
    normalizedX: Float,
    normalizedY: Float,
    coordinateSpace: CoordinateSpace
): MappedPoint {
    val clampedX = normalizedX.coerceIn(0f, 1f)
    val clampedY = normalizedY.coerceIn(0f, 1f)
    return MappedPoint(
        normalizedX = clampedX,
        normalizedY = clampedY,
        pixelX = coordinateSpace.width * clampedX,
        pixelY = coordinateSpace.height * clampedY
    )
}

internal fun buildGestureMappingLog(
    actionLabel: String,
    coordinateSpace: CoordinateSpace,
    start: MappedPoint,
    end: MappedPoint? = null,
    windowInfo: String
): String {
    return if (end == null) {
        "$actionLabel map: ratio=(${start.normalizedX},${start.normalizedY}) -> px=(${start.pixelX},${start.pixelY}), space=${coordinateSpace.width}x${coordinateSpace.height}, source=${coordinateSpace.source}, window=$windowInfo"
    } else {
        "$actionLabel map: ratio=(${start.normalizedX},${start.normalizedY})->(${end.normalizedX},${end.normalizedY}), px=(${start.pixelX},${start.pixelY})->(${end.pixelX},${end.pixelY}), space=${coordinateSpace.width}x${coordinateSpace.height}, source=${coordinateSpace.source}, window=$windowInfo"
    }
}

internal fun buildTapStroke(
    point: MappedPoint,
    deltaPx: Float = 1f
): TapStroke {
    return TapStroke(
        startX = point.pixelX,
        startY = point.pixelY,
        endX = point.pixelX + deltaPx,
        endY = point.pixelY + deltaPx
    )
}

class PrototypeAccessibilityService : AccessibilityService(), AutomationDeviceController {

    companion object {
        private const val TAG = "Prototype_Accessibility"

        @Volatile
        var instance: PrototypeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RuntimeServiceStatusNotifier.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        RuntimeServiceStatusNotifier.notifyChanged()
        super.onDestroy()
    }

    override suspend fun tap(normalizedX: Float, normalizedY: Float): Boolean {
        val coordinateSpace = getCoordinateSpace()
        val tapPoint = mapNormalizedPoint(normalizedX, normalizedY, coordinateSpace)
        val tapStroke = buildTapStroke(tapPoint)
        Log.d(TAG, buildGestureMappingLog("tap", coordinateSpace, tapPoint, windowInfo = getActiveWindowInfo()))
        val path = Path().apply {
            moveTo(tapStroke.startX, tapStroke.startY)
            lineTo(tapStroke.endX, tapStroke.endY)
        }
        val dispatched = dispatchGesture(path, durationMs = 80L)
        Log.d(
            TAG,
            "Tap result=$dispatched, hitNode=${describeNodeAtPoint(tapPoint.pixelX, tapPoint.pixelY)}, window=${getActiveWindowInfo()}"
        )
        return dispatched
    }

    override suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        val coordinateSpace = getCoordinateSpace()
        val fromPoint = mapNormalizedPoint(fromX, fromY, coordinateSpace)
        val toPoint = mapNormalizedPoint(toX, toY, coordinateSpace)
        Log.d(
            TAG,
            buildGestureMappingLog("swipe", coordinateSpace, fromPoint, toPoint, getActiveWindowInfo())
        )
        val path = Path().apply {
            moveTo(fromPoint.pixelX, fromPoint.pixelY)
            lineTo(toPoint.pixelX, toPoint.pixelY)
        }
        val dispatched = dispatchGesture(path, durationMs = durationMs)
        Log.d(TAG, "Swipe result=$dispatched, window=${getActiveWindowInfo()}")
        return dispatched
    }

    override suspend fun longPress(normalizedX: Float, normalizedY: Float, durationMs: Long): Boolean {
        val coordinateSpace = getCoordinateSpace()
        val pressPoint = mapNormalizedPoint(normalizedX, normalizedY, coordinateSpace)
        Log.d(TAG, buildGestureMappingLog("longPress", coordinateSpace, pressPoint, windowInfo = getActiveWindowInfo()))
        val path = Path().apply {
            moveTo(pressPoint.pixelX, pressPoint.pixelY)
        }
        val dispatched = dispatchGesture(path, durationMs = durationMs)
        Log.d(TAG, "Long press result=$dispatched, window=${getActiveWindowInfo()}")
        return dispatched
    }

    override fun inputText(
        text: String,
        autoDismissKeyboard: Boolean,
        targetX: Float?,
        targetY: Float?
    ): Boolean {
        return inputTextWithResult(text, autoDismissKeyboard, targetX, targetY).applied
    }

    override fun inputTextWithResult(
        text: String,
        autoDismissKeyboard: Boolean,
        targetX: Float?,
        targetY: Float?
    ): TextInputExecutionResult {
        if (text.isBlank()) {
            return TextInputExecutionResult(
                applied = false,
                note = "input | fail=blank_text"
            )
        }

        val root = rootInActiveWindow ?: return TextInputExecutionResult(
            applied = false,
            note = buildInputDiagnosticNote(
                target = null,
                targetX = targetX,
                targetY = targetY,
                layer2 = TextInputLayerAttempt(applied = false, reason = "skipped"),
                layer3 = TextInputLayerAttempt(applied = false, reason = "skipped"),
                failReason = "root_unavailable"
            )
        )
        val resolvedTarget = resolveTextInputTarget(root, targetX, targetY) ?: return TextInputExecutionResult(
            applied = false,
            note = buildInputDiagnosticNote(
                target = null,
                targetX = targetX,
                targetY = targetY,
                layer2 = TextInputLayerAttempt(applied = false, reason = "skipped"),
                layer3 = TextInputLayerAttempt(applied = false, reason = "skipped"),
                failReason = "target_unavailable"
            )
        )

        val semanticAttempt = TextInputLayerAttempt(
            applied = false,
            reason = "skipped"
        )
        Log.d(
            TAG,
            "Text input layer2 semantic result=${semanticAttempt.reason}, target=${resolvedTarget.node.viewIdResourceName}, coords=($targetX,$targetY), window=${getActiveWindowInfo()}"
        )
        val imeAttempt = performImeAssistedTextEntry(resolvedTarget, text, targetX, targetY)
        Log.d(
            TAG,
            "Text input layer3 ime result=${imeAttempt.reason}, target=${resolvedTarget.node.viewIdResourceName}, coords=($targetX,$targetY), window=${getActiveWindowInfo()}"
        )
        val exitReason = if (imeAttempt.applied && autoDismissKeyboard) {
            performImeExitAction(resolvedTarget.node)
        } else {
            null
        }
        return TextInputExecutionResult(
            applied = imeAttempt.applied,
            note = buildInputDiagnosticNote(
                target = resolvedTarget,
                targetX = targetX,
                targetY = targetY,
                layer2 = semanticAttempt,
                layer3 = imeAttempt,
                imeExit = exitReason,
                failReason = if (imeAttempt.applied) {
                    null
                } else {
                    "input_not_applied"
                }
            )
        )
    }

    override fun handleKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            3 -> performGlobalAction(GLOBAL_ACTION_HOME)
            4 -> performGlobalAction(GLOBAL_ACTION_BACK)
            187 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    private suspend fun dispatchGesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            },
            null
        )
        if (!dispatched && continuation.isActive) {
            continuation.resume(false)
        }
    }

    private fun getCoordinateSpace(): CoordinateSpace {
        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return resolveGestureCoordinateSpace(
            captureWidth = CaptureService.captureWidth,
            captureHeight = CaptureService.captureHeight,
            realWidth = metrics.widthPixels,
            realHeight = metrics.heightPixels
        )
    }

    private fun getActiveWindowInfo(): String {
        val root = rootInActiveWindow ?: return "root=null"
        val rect = Rect()
        root.getBoundsInScreen(rect)
        return "pkg=${root.packageName},bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun describeNodeAtPoint(x: Float, y: Float): String {
        val root = rootInActiveWindow ?: return "root=null"
        val match = findNodeAtPoint(root, x.toInt(), y.toInt()) ?: return "none"
        val rect = Rect()
        match.getBoundsInScreen(rect)
        return "class=${match.className},id=${match.viewIdResourceName},clickable=${match.isClickable},bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun findNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) {
            return null
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findNodeAtPoint(child, x, y)
            if (match != null) {
                return match
            }
        }

        return node
    }

    private fun resolveTextInputTarget(
        root: AccessibilityNodeInfo,
        targetX: Float?,
        targetY: Float?
    ): ResolvedTextInputTarget? {
        if (targetX != null && targetY != null) {
            val coordinateSpace = getCoordinateSpace()
            val targetPoint = mapNormalizedPoint(targetX, targetY, coordinateSpace)
            findEditableTargetNear(root, targetPoint.pixelX.toInt(), targetPoint.pixelY.toInt())?.let { return it }
        }
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
            return ResolvedTextInputTarget(node = node, source = "input_focus")
        }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf(::isEditableTarget)?.let { node ->
            return ResolvedTextInputTarget(node = node, source = "accessibility_focus")
        }
        return findEditableNode(root)?.let { node ->
            ResolvedTextInputTarget(node = node, source = "tree_scan")
        }
    }

    private fun findEditableTargetNear(root: AccessibilityNodeInfo, pixelX: Int, pixelY: Int): ResolvedTextInputTarget? {
        val nodeAtPoint = findNodeAtPoint(root, pixelX, pixelY) ?: return null
        findEditableDescendant(nodeAtPoint)?.let { node ->
            return ResolvedTextInputTarget(node = node, source = "coordinate_descendant")
        }
        return findEditableAncestor(nodeAtPoint)?.let { node ->
            ResolvedTextInputTarget(node = node, source = "coordinate_ancestor")
        }
    }

    private fun findEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableTarget(node)) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findEditableDescendant(child)
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun findEditableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (isEditableTarget(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableTarget(node)) {
            return node
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findEditableNode(child)
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun isEditableTarget(node: AccessibilityNodeInfo): Boolean {
        return node.isEditable || (node.isFocusable && node.className?.toString()?.contains("Edit", ignoreCase = true) == true)
    }

    private fun performImeAssistedTextEntry(
        resolvedTarget: ResolvedTextInputTarget,
        text: String,
        targetX: Float?,
        targetY: Float?
    ): TextInputLayerAttempt {
        val focusReason = bindInputFocus(resolvedTarget.node, targetX, targetY)
        val activeTarget = rootInActiveWindow?.let { root ->
            resolveTextInputTarget(root, targetX, targetY)?.node
        } ?: resolvedTarget.node
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return TextInputLayerAttempt(
            applied = false,
            reason = "focus=$focusReason/clipboard_unavailable"
        )
        val previousClip = clipboardManager.primaryClip
        return try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("prototype_input", text))
            val pasted = activeTarget.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            val pasteVerification = if (pasted) {
                verifyTextEntry(resolveCurrentTextInputTarget(resolvedTarget, targetX, targetY), text)
            } else {
                null
            }
            if (pasted) {
                if (pasteVerification?.matched == true) {
                    TextInputLayerAttempt(
                        applied = true,
                        reason = "focus=$focusReason/paste/${pasteVerification.reason}"
                    )
                } else {
                    val pasteReason = "paste/${pasteVerification?.reason ?: "verification_unavailable"}"
                    val affordanceAttempt = attemptImeAffordanceInjection(
                        resolvedTarget = resolvedTarget,
                        expectedText = text,
                        targetX = targetX,
                        targetY = targetY
                    )
                    TextInputLayerAttempt(
                        applied = affordanceAttempt.applied,
                        reason = "focus=$focusReason/$pasteReason/${affordanceAttempt.reason}"
                    )
                }
            } else {
                val affordanceAttempt = attemptImeAffordanceInjection(
                    resolvedTarget = resolvedTarget,
                    expectedText = text,
                    targetX = targetX,
                    targetY = targetY
                )
                TextInputLayerAttempt(
                    applied = affordanceAttempt.applied,
                    reason = "focus=$focusReason/paste_failed/${affordanceAttempt.reason}"
                )
            }
        } finally {
            runCatching {
                if (previousClip != null) {
                    clipboardManager.setPrimaryClip(previousClip)
                }
            }
        }
    }

    private fun resolveCurrentTextInputTarget(
        resolvedTarget: ResolvedTextInputTarget,
        targetX: Float?,
        targetY: Float?
    ): AccessibilityNodeInfo {
        return rootInActiveWindow?.let { root ->
            resolveTextInputTarget(root, targetX, targetY)?.node
        } ?: resolvedTarget.node
    }

    private fun attemptImeAffordanceInjection(
        resolvedTarget: ResolvedTextInputTarget,
        expectedText: String,
        targetX: Float?,
        targetY: Float?
    ): TextInputLayerAttempt {
        val root = rootInActiveWindow ?: return TextInputLayerAttempt(
            applied = false,
            reason = "ime_click=root_unavailable"
        )
        val candidate = findImeAffordanceCandidate(root, expectedText) ?: return TextInputLayerAttempt(
            applied = false,
            reason = "ime_click=not_found"
        )
        val clicked = performClickAction(candidate.node)
        if (!clicked) {
            return TextInputLayerAttempt(
                applied = false,
                reason = "ime_click=${candidate.evaluation.reason}/click_failed"
            )
        }
        val verification = verifyTextEntry(resolveCurrentTextInputTarget(resolvedTarget, targetX, targetY), expectedText)
        return TextInputLayerAttempt(
            applied = verification.matched,
            reason = "ime_click=${candidate.evaluation.reason}/click/${verification.reason}"
        )
    }

    private fun findImeAffordanceCandidate(
        root: AccessibilityNodeInfo,
        expectedText: String
    ): ImeAffordanceCandidate? {
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val candidates = mutableListOf<ImeAffordanceCandidate>()
        collectImeAffordanceCandidates(
            node = root,
            expectedText = expectedText,
            rootBounds = rootBounds,
            candidates = candidates
        )
        return candidates.maxByOrNull { candidate -> candidate.evaluation.score }
    }

    private fun collectImeAffordanceCandidates(
        node: AccessibilityNodeInfo,
        expectedText: String,
        rootBounds: Rect,
        candidates: MutableList<ImeAffordanceCandidate>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        evaluateImeAffordanceSnapshot(
            snapshot = ImeAffordanceSnapshot(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                boundsTop = bounds.top,
                boundsBottom = bounds.bottom,
                clickable = node.isClickable
            ),
            expectedText = expectedText,
            windowTop = rootBounds.top,
            windowBottom = rootBounds.bottom
        )?.let { evaluation ->
            candidates += ImeAffordanceCandidate(node = node, evaluation = evaluation)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectImeAffordanceCandidates(
                node = child,
                expectedText = expectedText,
                rootBounds = rootBounds,
                candidates = candidates
            )
        }
    }

    private fun bindInputFocus(target: AccessibilityNodeInfo, targetX: Float?, targetY: Float?): String {
        if (target.isFocused || target.isAccessibilityFocused) {
            return "already_focused"
        }

        val clicked = performClickAction(target)
        if (!clicked && targetX != null && targetY != null) {
            Log.d(TAG, "IME focus binding fallback uses target coordinates=($targetX,$targetY)")
        }
        val focusedByAction = if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } else {
            false
        }
        return when {
            clicked && focusedByAction -> "click_then_focus"
            clicked -> "click"
            focusedByAction -> "focus_action"
            else -> "focus_unconfirmed"
        }
    }

    private fun performClickAction(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun verifyTextEntry(target: AccessibilityNodeInfo, expectedText: String): TextEntryVerification {
        return verifyTextEntryReadback(target.text, expectedText)
    }

    private fun performImeExitAction(target: AccessibilityNodeInfo): String {
        val imeEnterActionId = resolveImeEnterActionId()
        val imeHandled = imeEnterActionId?.let(target::performAction) == true
        if (!imeHandled) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return "back"
        }
        return "ime_enter"
    }

    private fun buildInputDiagnosticNote(
        target: ResolvedTextInputTarget?,
        targetX: Float?,
        targetY: Float?,
        layer2: TextInputLayerAttempt,
        layer3: TextInputLayerAttempt?,
        imeExit: String? = null,
        failReason: String? = null
    ): String {
        val parts = mutableListOf<String>()
        parts += "input"
        parts += "target=${target?.let(::describeResolvedInputTarget) ?: "none"}"
        parts += "coords=${formatInputCoordinates(targetX, targetY)}"
        parts += "layer2=${layer2.reason}"
        parts += "layer3=${layer3?.reason ?: "skipped"}"
        imeExit?.let { reason ->
            parts += "exit=$reason"
        }
        failReason?.let { reason ->
            parts += "fail=$reason"
        }
        return parts.joinToString(" | ")
    }

    private fun describeResolvedInputTarget(target: ResolvedTextInputTarget): String {
        val node = target.node
        val identity = node.viewIdResourceName?.takeIf { value -> value.isNotBlank() }
            ?: node.className?.toString()?.takeIf { value -> value.isNotBlank() }
            ?: "unknown"
        return "${target.source}:$identity"
    }

    private fun formatInputCoordinates(targetX: Float?, targetY: Float?): String {
        return if (targetX == null || targetY == null) {
            "none"
        } else {
            String.format(java.util.Locale.US, "(%.2f,%.2f)", targetX, targetY)
        }
    }

    private fun resolveImeEnterActionId(): Int? {
        return runCatching {
            val actionClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo\$AccessibilityAction")
            val action = actionClass.getField("ACTION_IME_ENTER").get(null) as AccessibilityNodeInfo.AccessibilityAction
            action.id
        }.getOrNull()
    }
}
