package com.atombits.pocopaw

import android.content.Context
import androidx.core.view.isVisible
import com.atombits.pocopaw.databinding.ActivityMainBinding

class PreparingEntrySidecar(
    private val enabled: Boolean = false
) {

    fun render(
        binding: ActivityMainBinding,
        context: Context,
        store: PrototypeStoreData
    ) {
        val resolvedState = store.resolveCurrentState()
        val activeCandidate = if (enabled && resolvedState.stage.normalized() == ConversationStage.ACCUMULATING) {
            store.resolveTaskFirstCandidate()
        } else {
            null
        }
        val showSidecar = activeCandidate != null && resolvedState.executionStartedAt == null
        binding.preparingCard.isVisible = showSidecar
        if (!showSidecar || activeCandidate == null) {
            return
        }
        binding.preparingBriefText.text = formatPreparingBrief(context, activeCandidate)
        binding.startPreparingButton.isEnabled = true
        binding.startPreparingButton.text = context.getString(R.string.start_preparing)
    }

    fun updateLoadingState(
        binding: ActivityMainBinding,
        loading: Boolean,
        store: PrototypeStoreData
    ) {
        binding.startPreparingButton.isEnabled = !loading && isActionable(store)
    }

    private fun isActionable(store: PrototypeStoreData): Boolean {
        val resolvedState = store.resolveCurrentState()
        return enabled &&
            resolvedState.stage.normalized() == ConversationStage.ACCUMULATING &&
            store.resolveTaskFirstCandidate() != null
    }

    private fun formatPreparingBrief(
        context: Context,
        candidate: IntentCandidate
    ): String = buildString {
        append(ConsoleTaskFormatter.formatPreparingBrief(candidate))
    }
}