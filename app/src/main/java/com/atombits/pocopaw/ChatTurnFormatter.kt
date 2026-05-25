package com.atombits.pocopaw

internal object ChatTurnFormatter {

    fun formatCapabilitySummary(
        turnOptions: ChatTurnOptions?,
        thinkingLabel: String,
        searchLabel: String
    ): String {
        if (turnOptions == null) {
            return ""
        }
        return buildList {
            if (turnOptions.thinkingEnabled) {
                add(thinkingLabel)
            }
            if (turnOptions.searchEnabled) {
                add(searchLabel)
            }
        }.joinToString(separator = " · ")
    }
}