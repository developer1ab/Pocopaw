package com.atombits.pocopaw

import com.atombits.pocopaw.orchestration.ChatTurnOrchestrator
import com.atombits.pocopaw.orchestration.ChatTurnSubmitResult

sealed class UnifiedInputRouteResult {
    data class LocalCommandHandled(
        val updatedStore: PrototypeStoreData,
        val confirmationMessage: String
    ) : UnifiedInputRouteResult()

    data class SemanticChain(val result: ChatTurnSubmitResult) : UnifiedInputRouteResult()
    
    data class EmptyInput(val normalizedInput: String) : UnifiedInputRouteResult()
}

data class LocalCommandResult(
    val updatedStore: PrototypeStoreData,
    val confirmationMessage: String
)

interface LocalControlCommandRouter {
    fun tryHandleCommand(input: String, currentStore: PrototypeStoreData): LocalCommandResult?
}

class UnifiedUserInputRouter(
    private val chatTurnOrchestrator: ChatTurnOrchestrator,
    private val localCommandRouter: LocalControlCommandRouter? = null
) {
    suspend fun routeInput(
        rawInput: String,
        currentStore: PrototypeStoreData,
        turnOptions: ChatTurnOptions,
        onPendingConversationChanged: (List<ChatMessage>) -> Unit
    ): UnifiedInputRouteResult {
        val normalizedInput = normalizeInput(rawInput)
        if (normalizedInput.isBlank()) {
            return UnifiedInputRouteResult.EmptyInput(normalizedInput)
        }

        // Try local control commands first
        val localResult = localCommandRouter?.tryHandleCommand(normalizedInput, currentStore)
        if (localResult != null) {
            return UnifiedInputRouteResult.LocalCommandHandled(
                updatedStore = localResult.updatedStore,
                confirmationMessage = localResult.confirmationMessage
            )
        }

        // Fall back to semantic main chain
        val semanticResult = chatTurnOrchestrator.submitMessage(
            submittedInput = normalizedInput,
            currentStore = currentStore,
            turnOptions = turnOptions,
            onPendingConversationChanged = onPendingConversationChanged
        )
        return UnifiedInputRouteResult.SemanticChain(semanticResult)
    }

    private fun normalizeInput(input: String): String {
        return input.trim().replace(Regex("\\s+"), " ")
    }
}