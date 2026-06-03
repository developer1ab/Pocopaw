package com.atombits.pocopaw

interface SpeechOutputCoordinator {
    fun onNewAssistantMessage(message: ChatMessage, store: PrototypeStoreData)
    fun stopPlayback()
    fun replayLatestMessage(store: PrototypeStoreData)
    fun getCurrentPlaybackState(): OverlaySpeechState
    fun getActiveSpeechMessageId(): String?
    fun isAutoSpeakEnabled(): Boolean
    fun setAutoSpeakEnabled(enabled: Boolean)
}

class DefaultSpeechOutputCoordinator(
    private val onPlaybackStateChanged: (OverlaySpeechState, String?) -> Unit = { _, _ -> }
) : SpeechOutputCoordinator {

    private var currentPlaybackState: OverlaySpeechState = OverlaySpeechState.IDLE
    private var activeSpeechMessageId: String? = null
    private var autoSpeakEnabled: Boolean = true
    private var latestAssistantMessageId: String? = null

    private var speechPlaybackDelegate: SpeechPlaybackDelegate? = null

    fun setSpeechPlaybackDelegate(delegate: SpeechPlaybackDelegate?) {
        this.speechPlaybackDelegate = delegate
    }

    override fun onNewAssistantMessage(message: ChatMessage, store: PrototypeStoreData) {
        latestAssistantMessageId = message.id
        
        val overlayState = store.assistantOverlayState
        val shouldAutoSpeak = autoSpeakEnabled && 
            overlayState?.speechMirrorEnabled == true &&
            overlayState.entryState in listOf(
                OverlayEntryState.OVERLAY_RUNNING_COLLAPSED,
                OverlayEntryState.OVERLAY_RUNNING_EXPANDED
            )

        if (shouldAutoSpeak) {
            // Stop current playback if any
            if (currentPlaybackState == OverlaySpeechState.PLAYING || 
                currentPlaybackState == OverlaySpeechState.BUFFERING) {
                speechPlaybackDelegate?.stopCurrentPlayback()
            }
            
            // Start new playback
            updatePlaybackState(OverlaySpeechState.BUFFERING, message.id)
            speechPlaybackDelegate?.speakMessage(message) { success ->
                if (success) {
                    updatePlaybackState(OverlaySpeechState.PLAYING, message.id)
                } else {
                    updatePlaybackState(OverlaySpeechState.FAILED, message.id)
                }
            }
        }
    }

    override fun stopPlayback() {
        speechPlaybackDelegate?.stopCurrentPlayback()
        updatePlaybackState(OverlaySpeechState.IDLE, null)
    }

    override fun replayLatestMessage(store: PrototypeStoreData) {
        val messageId = latestAssistantMessageId ?: return
        val message = store.messages.lastOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (message != null) {
            updatePlaybackState(OverlaySpeechState.BUFFERING, message.id)
            speechPlaybackDelegate?.speakMessage(message) { success ->
                if (success) {
                    updatePlaybackState(OverlaySpeechState.PLAYING, message.id)
                } else {
                    updatePlaybackState(OverlaySpeechState.FAILED, message.id)
                }
            }
        }
    }

    override fun getCurrentPlaybackState(): OverlaySpeechState {
        return currentPlaybackState
    }

    override fun getActiveSpeechMessageId(): String? {
        return activeSpeechMessageId
    }

    override fun isAutoSpeakEnabled(): Boolean {
        return autoSpeakEnabled
    }

    override fun setAutoSpeakEnabled(enabled: Boolean) {
        autoSpeakEnabled = enabled
    }

    fun onPlaybackCompleted() {
        updatePlaybackState(OverlaySpeechState.IDLE, null)
    }

    private fun updatePlaybackState(state: OverlaySpeechState, messageId: String?) {
        currentPlaybackState = state
        activeSpeechMessageId = messageId
        onPlaybackStateChanged(state, messageId)
    }
}

interface SpeechPlaybackDelegate {
    fun speakMessage(message: ChatMessage, onResult: (Boolean) -> Unit)
    fun stopCurrentPlayback()
}