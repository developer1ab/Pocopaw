package com.atombits.pocopaw.service

import java.util.concurrent.CopyOnWriteArraySet

object RuntimeServiceStatusNotifier {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}