package com.atombits.pocopaw

import com.atombits.pocopaw.process.projection.TaskExecutionStartResolver
import kotlinx.coroutines.delay
import java.util.Locale

enum class ExecutionLaunchDirective {
    REQUEST_SCREEN_CAPTURE_PERMISSION,
    START_AUTOMATION
}

fun resolveExecutionLaunchDirective(
    hasScreenCapturePermission: Boolean,
    requiresScreenCapturePermission: Boolean = true
): ExecutionLaunchDirective {
    return if (!requiresScreenCapturePermission || hasScreenCapturePermission) {
        ExecutionLaunchDirective.START_AUTOMATION
    } else {
        ExecutionLaunchDirective.REQUEST_SCREEN_CAPTURE_PERMISSION
    }
}

fun requiresScreenCapturePermissionForExecutionStart(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): Boolean {
    val shortcutCandidate = resolveProcessShortcutCandidate(store, boundaryPacket)
    val readyProcessAsset = resolveReadyProcessAsset(store, boundaryPacket)
    return shortcutCandidate == null && readyProcessAsset == null
}

fun resolveExecutionStartBoundaryPacketFromStore(
    store: PrototypeStoreData
): TaskExecutionBoundaryPacket? {
    return resolveTaskFirstExecutionBoundaryPacket(
        currentState = store.resolveCurrentState()
    )
}

fun resolveTaskFirstExecutionBoundaryPacket(
    currentState: LocalConversationState
): TaskExecutionBoundaryPacket? {
    val taskDecision = TaskExecutionStartResolver().resolve(
        currentState = currentState
    )
    return if (taskDecision.canStart) {
        taskDecision.executionBoundaryPacket
    } else {
        null
    }
}

fun canAutoStartExecutionFromStore(store: PrototypeStoreData): Boolean {
    val currentState = store.resolveCurrentState()
    if (currentState.stage.normalized() != ConversationStage.EXECUTING) {
        return false
    }
    if (store.resolveCurrentExecutionRuntime() != null || currentState.executionStartedAt != null) {
        return false
    }
    if (!hasStartExecutingRequestFromLatestUserTurn(store)) {
        return false
    }
    if (!hasStructuredExecutionStartSource(store)) {
        return false
    }

    val boundaryPacket = resolveExecutionStartBoundaryPacketFromStore(store) ?: return false
    return boundaryPacket.canStartExecution || boundaryPacket.executionGateFlag == ExecutionGateFlag.READY_TO_START
}

fun canManuallyStartExecutionFromStore(store: PrototypeStoreData): Boolean {
    val currentState = store.resolveCurrentState()
    if (store.resolveCurrentExecutionRuntime() != null || currentState.executionStartedAt != null) {
        return false
    }
    val boundaryPacket = resolveExecutionStartBoundaryPacketFromStore(store) ?: return false
    return boundaryPacket.canStartExecution || boundaryPacket.executionGateFlag == ExecutionGateFlag.READY_TO_START
}

internal fun hasStartExecutingRequestFromLatestUserTurn(store: PrototypeStoreData): Boolean {
    val currentState = store.resolveCurrentState()
    if (currentState.effectiveStageOwner() != StageOwner.USER) {
        return false
    }
    if (currentState.userRequestSemantic != UserRequestSemantic.START_EXECUTING) {
        return false
    }
    val latestUserMessage = store.messages
        .asReversed()
        .firstOrNull { message -> message.role == MessageRole.USER }
        ?.content
        .orEmpty()
    return latestUserMessage.isNotBlank()
}

internal fun isExplicitExecutionRequestText(
    userMessageText: String,
    taskRecord: TaskRecord? = null
): Boolean {
    val normalizedText = userMessageText.trim().lowercase(Locale.US)
    val compactText = normalizedText.replace(Regex("\\s+"), "")
    if (compactText.isBlank()) {
        return false
    }
    if (isPlanOnlyRequest(compactText, normalizedText)) {
        return false
    }
    if (isInformationalQuestion(compactText, normalizedText)) {
        return false
    }
    val actionCode = taskRecord?.actionCode
    if (actionCode == ActionCode.PLAN) {
        return false
    }
    if (matchesActionSpecificExecutionRequest(compactText, normalizedText, actionCode)) {
        return true
    }
    return hasImperativeExecutionCue(compactText, normalizedText) && containsExecutableActionCue(compactText, normalizedText)
}

private fun isPlanOnlyRequest(compactText: String, normalizedText: String): Boolean {
    val asksForPlan = containsAny(
        compactText,
        "出方案",
        "做方案",
        "方案",
        "计划",
        "规划",
        "攻略",
        "行程"
    ) || containsAny(
        normalizedText,
        "make a plan",
        "give me a plan",
        "draft a plan",
        "itinerary"
    )
    if (!asksForPlan) {
        return false
    }
    return !containsAny(
        compactText,
        "开始执行",
        "马上执行",
        "立即执行",
        "执行这个",
        "照这个做",
        "按这个做",
        "去做"
    ) && !containsAny(
        normalizedText,
        "execute it",
        "start it",
        "do it now",
        "run it"
    )
}

private fun isInformationalQuestion(compactText: String, normalizedText: String): Boolean {
    val asksHowOrWhy = containsAny(
        compactText,
        "怎么",
        "如何",
        "为什么",
        "是什么",
        "介绍一下",
        "解释一下",
        "告诉我"
    ) || containsAny(
        normalizedText,
        "how do i",
        "how to",
        "what is",
        "why ",
        "tell me about",
        "explain"
    )
    if (!asksHowOrWhy) {
        return false
    }
    return !hasImperativeExecutionCue(compactText, normalizedText)
}

private fun matchesActionSpecificExecutionRequest(
    compactText: String,
    normalizedText: String,
    actionCode: ActionCode?
): Boolean = when (actionCode) {
    ActionCode.SEND_MESSAGE -> containsAny(compactText, "发短信", "发送短信", "发个短信", "发消息", "发送消息", "发微信", "发邮件") ||
        containsAny(normalizedText, "send sms", "send a text", "text ", "send message", "send an email")

    ActionCode.CALL -> containsAny(compactText, "打电话", "拨号", "拨打") ||
        containsAny(normalizedText, "call ", "dial ")

    ActionCode.OPEN -> containsAny(compactText, "打开", "启动", "进入", "前往") ||
        containsAny(normalizedText, "open ", "launch ", "go to ")

    ActionCode.ENABLE -> containsAny(compactText, "打开", "开启", "启用") ||
        containsAny(normalizedText, "turn on", "enable ")

    ActionCode.DISABLE -> containsAny(compactText, "关闭", "关掉", "停用", "禁用") ||
        containsAny(normalizedText, "turn off", "disable ")

    ActionCode.SEARCH -> isExplicitAppSearchRequest(compactText, normalizedText)

    ActionCode.ADD_TO_CART -> containsAny(compactText, "加入购物车", "加购物车", "加购") ||
        containsAny(normalizedText, "add to cart")

    ActionCode.BUY -> containsAny(compactText, "帮我买", "给我买", "替我买", "去买", "直接买", "马上买", "立即买", "购买", "下单") ||
        compactText.startsWith("买") ||
        containsAny(normalizedText, "buy ", "purchase ", "place an order")

    ActionCode.PAY -> containsAny(compactText, "付款", "支付", "结算") ||
        containsAny(normalizedText, "pay ", "checkout")

    ActionCode.CREATE -> containsAny(compactText, "创建", "新建", "添加", "设置") ||
        containsAny(normalizedText, "create ", "add ", "set up")

    ActionCode.NAVIGATE -> containsAny(compactText, "导航", "带我去", "路线") ||
        containsAny(normalizedText, "navigate", "directions to")

    ActionCode.BOOK -> containsAny(compactText, "帮我订", "给我订", "替我订", "去订", "预约", "预订") ||
        compactText.startsWith("订") ||
        containsAny(normalizedText, "book ", "reserve ")

    ActionCode.DELETE -> containsAny(compactText, "删除", "删掉") ||
        containsAny(normalizedText, "delete ", "remove ")

    ActionCode.PLAN -> false

    else -> false
}

private fun hasImperativeExecutionCue(compactText: String, normalizedText: String): Boolean {
    return containsAny(
        compactText,
        "帮我",
        "给我",
        "替我",
        "帮忙",
        "请",
        "直接",
        "现在",
        "马上",
        "立即",
        "开始",
        "去做",
        "执行"
    ) || containsAny(
        normalizedText,
        "please ",
        "can you ",
        "could you ",
        "do it",
        "start ",
        "execute ",
        "run "
    )
}

private fun containsExecutableActionCue(compactText: String, normalizedText: String): Boolean {
    return containsAny(
        compactText,
        "发短信",
        "发送短信",
        "发消息",
        "打电话",
        "拨号",
        "打开",
        "开启",
        "关闭",
        "启用",
        "停用",
        "加入购物车",
        "下单",
        "购买",
        "预约",
        "预订",
        "导航",
        "创建",
        "新建",
        "删除"
    ) || isExplicitAppSearchRequest(compactText, normalizedText) || containsAny(
        normalizedText,
        "send ",
        "call ",
        "dial ",
        "open ",
        "launch ",
        "turn on",
        "turn off",
        "enable ",
        "disable ",
        "add to cart",
        "buy ",
        "book ",
        "navigate",
        "create ",
        "delete "
    )
}

private fun isExplicitAppSearchRequest(compactText: String, normalizedText: String): Boolean {
    return Regex("(去|到|在).{1,16}(搜|搜索)").containsMatchIn(compactText) ||
        Regex("(search|look up).{1,32}(in|on) ").containsMatchIn(normalizedText) ||
        Regex("(in|on) .{1,32}(search|look up)").containsMatchIn(normalizedText)
}

private fun containsAny(value: String, vararg tokens: String): Boolean {
    return tokens.any { token -> value.contains(token) }
}

private fun hasStructuredExecutionStartSource(store: PrototypeStoreData): Boolean {
    val decision = TaskExecutionStartResolver().resolve(
        currentState = store.resolveCurrentState()
    )
    return decision.canStart && decision.executionBoundaryPacket != null
}

suspend fun awaitScreenCaptureReadiness(
    timeoutMs: Long = 2000L,
    pollIntervalMs: Long = 50L,
    readinessProbe: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    pause: suspend (Long) -> Unit = { delay(it) }
): Boolean {
    if (readinessProbe()) {
        return true
    }
    val deadline = nowMs() + timeoutMs
    while (nowMs() < deadline) {
        val remainingMs = deadline - nowMs()
        pause(minOf(pollIntervalMs, remainingMs))
        if (readinessProbe()) {
            return true
        }
    }
    return readinessProbe()
}