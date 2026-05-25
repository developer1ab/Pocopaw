package com.atombits.pocopaw

import java.util.Locale

private data class SharedCanonicalActionSpec(
    val action: CanonicalAction,
    val tokens: List<String>,
    val broadTokens: List<String> = emptyList()
)

internal object SharedActionNormalization {
    private val canonicalActionSpecs = listOf(
        SharedCanonicalActionSpec(
            action = CanonicalAction.ADD_TO_CART,
            tokens = listOf("addtocart", "add_to_cart", "add to cart", "加入购物车", "加购")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.PAY,
            tokens = listOf("pay", "payment", "settle", "付款", "支付", "结算")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.SEARCH,
            tokens = listOf("search", "搜索", "查找", "查一下")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.COMPARE,
            tokens = listOf("compare", "比较", "比价")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.COUPON,
            tokens = listOf("coupon", "优惠券", "领券")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.RATING,
            tokens = listOf("review", "rating", "rate", "评价", "评分")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.RETURN,
            tokens = listOf("refund", "return", "退款", "退货")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.DELETE,
            tokens = listOf("delete", "remove", "clear", "删除", "移除", "清空")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.SEND_MESSAGE,
            tokens = listOf("send_message", "message_send", "发消息", "发送消息", "短信"),
            broadTokens = listOf("message")
        ),
        SharedCanonicalActionSpec(
            action = CanonicalAction.BUY,
            tokens = listOf("buy", "purchase", "checkout", "购买", "下单", "买")
        )
    )

    fun fromRaw(
        value: String?,
        allowBroadMessageTokens: Boolean = false
    ): CanonicalAction? {
        val normalized = value?.trim()?.lowercase(Locale.US)?.takeIf { text -> text.isNotBlank() }
            ?: return null
        return canonicalActionSpecs.firstOrNull { spec ->
            spec.tokens.any { token -> normalized.contains(token) } ||
                (allowBroadMessageTokens && spec.broadTokens.any { token -> normalized.contains(token) })
        }?.action
    }

    fun toProcessAction(action: CanonicalAction, allowedActions: Set<String>): String? {
        fun choosePrimary(primary: String, fallback: String? = null): String? = when {
            primary in allowedActions -> primary
            fallback != null && fallback in allowedActions -> fallback
            allowedActions.isEmpty() -> primary
            else -> null
        }

        return when (action) {
            CanonicalAction.ADD_TO_CART -> choosePrimary("addtocart")
            CanonicalAction.BUY -> choosePrimary("buy")
            CanonicalAction.PAY -> choosePrimary("pay", fallback = "buy")
            CanonicalAction.SEARCH -> choosePrimary("search")
            CanonicalAction.COMPARE -> choosePrimary("compare")
            CanonicalAction.COUPON -> choosePrimary("coupon")
            CanonicalAction.RATING -> choosePrimary("comments")
            CanonicalAction.RETURN -> choosePrimary("return")
            CanonicalAction.SEND_MESSAGE -> choosePrimary("send")
            CanonicalAction.DELETE -> null
        }
    }
}