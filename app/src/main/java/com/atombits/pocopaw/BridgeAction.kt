package com.atombits.pocopaw

import org.json.JSONObject

data class BridgeAction(
    val type: String,
    val uri: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val fromX: Float? = null,
    val fromY: Float? = null,
    val toX: Float? = null,
    val toY: Float? = null,
    val duration: Long? = null,
    val keyCode: Int? = null,
    val text: String? = null,
    val autoDismissKeyboard: Boolean = false
) {
    companion object {
        fun fromJson(source: JSONObject): BridgeAction {
            val actionObject = if (source.has("action")) {
                source.optJSONObject("action") ?: JSONObject()
            } else {
                source
            }
            val fromObject = actionObject.optJSONObject("from")
            val toObject = actionObject.optJSONObject("to")
            return BridgeAction(
                type = actionObject.optString("type", "").trim(),
                uri = actionObject.optString("uri").trim().ifBlank { null },
                x = actionObject.optRatioOrNull("x"),
                y = actionObject.optRatioOrNull("y"),
                fromX = fromObject.optRatioOrNull("x"),
                fromY = fromObject.optRatioOrNull("y"),
                toX = toObject.optRatioOrNull("x"),
                toY = toObject.optRatioOrNull("y"),
                duration = actionObject.optLong("duration", -1L).takeIf { value -> value > 0L },
                keyCode = actionObject.optInt("keyCode", -1).takeIf { value -> value >= 0 },
                text = actionObject.optString("text").trim().ifBlank { null },
                autoDismissKeyboard = actionObject.optBoolean("autoDismissKeyboard", false)
            )
        }
    }
}

private fun JSONObject?.optRatioOrNull(key: String): Float? {
    if (this == null) {
        return null
    }
    val value = optDouble(key, Double.NaN)
    return if (value.isNaN()) {
        null
    } else {
        value.toFloat()
    }
}