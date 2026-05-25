package com.atombits.pocopaw

object MessageMetaFormatter {

    fun format(
        timestampText: String,
        stageLabel: String?,
        tokenText: String?
    ): String {
        return buildList {
            add(timestampText)
            stageLabel?.takeIf { it.isNotBlank() }?.let(::add)
            tokenText?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
    }
}