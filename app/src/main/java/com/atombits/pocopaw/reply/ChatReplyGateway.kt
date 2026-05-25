package com.atombits.pocopaw.reply

import com.atombits.pocopaw.AssistantReplyResult
import com.atombits.pocopaw.ContextSubsetPlanner
import com.atombits.pocopaw.ConversationStage
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.ExecutionChatReplyPromptSpec
import com.atombits.pocopaw.MessageRole
import com.atombits.pocopaw.PromptMessage
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.PromptPacketType
import com.atombits.pocopaw.ProviderRuntimeConfigs
import com.atombits.pocopaw.ResponseContractRegistry
import com.atombits.pocopaw.SemanticRuntimePreferences
import com.atombits.pocopaw.TokenBudgetPlanner
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.buildAssistantIdentityInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ChatReplyPromptPacketBuilder {
    fun buildExecutionChatReplyPacket(spec: ExecutionChatReplyPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.executionChatReplyBudget()
        val factsBundle = ContextSubsetPlanner.clipExecutionBrief(spec.factsBundle)
        val historyBundle = ContextSubsetPlanner.buildHistoryBundle(
            historyLines = spec.recentConversationLines,
            reactivationHintLines = "execution_reply_stage=${spec.stageLabel}",
            maxChars = 900
        )
        val systemContract = buildString {
            appendLine("You are rewriting existing execution facts into one assistant reply for the user.")
            appendLine(buildAssistantIdentityInstruction())
            appendLine("Use only facts already present in the execution facts bundle.")
            appendLine("Do not invent new steps, reasons, outcomes, prices, tools, or stage names.")
            appendLine("For success, summarize what was completed.")
            appendLine("For failure, summarize where it got stuck and what user guidance is needed.")
            appendLine("Keep the reply concise and natural.")
            appendLine("Return JSON only.")
        }.trim()
        val responseContract = ResponseContractRegistry.executionChatReplyContract()
        val userPrompt = buildString {
            appendLine("Recent conversation:")
            appendLine(historyBundle)
            appendLine()
            appendLine("Execution facts:")
            appendLine(factsBundle)
        }.trim()
        return PromptPacket(
            packetType = PromptPacketType.EXECUTION_CHAT_REPLY,
            systemContract = systemContract,
            historyBundle = historyBundle,
            activeCandidateBundle = "No active candidate bundle for this packet.",
            memoryBundle = null,
            personalizationBundle = null,
            toolBundle = null,
            executionBrief = factsBundle,
            responseContract = responseContract,
            tokenBudget = tokenBudget,
            activeSections = listOf("execution_facts", "recent_conversation"),
            promptMessages = listOf(
                PromptMessage(role = "system", content = systemContract),
                PromptMessage(role = "user", content = userPrompt)
            )
        )
    }
}

internal class ChatReplyGateway(
    private val client: SemanticPrototypeClient
) {
    suspend fun buildExecutionAssistantReply(
        factsBundle: String,
        store: PrototypeStoreData,
        stage: ConversationStage = ConversationStage.ACCUMULATING
    ): AssistantReplyResult = withContext(Dispatchers.IO) {
        val runtimeConfig = ProviderRuntimeConfigs.semanticRuntimeConfig(
            store.semanticRuntimePreferences ?: SemanticRuntimePreferences()
        )
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }
        val recentConversation = store.messages
            .filter { message -> message.role != MessageRole.SYSTEM }
            .takeLast(8)
            .joinToString("\n") { message ->
                "${message.role.name}: ${message.content}"
            }
            .ifBlank { "No recent user-visible conversation." }
        val packet = ChatReplyPromptPacketBuilder.buildExecutionChatReplyPacket(
            ExecutionChatReplyPromptSpec(
                factsBundle = factsBundle,
                recentConversationLines = recentConversation,
                stageLabel = stage.normalized().name
            )
        )
        val raw = client.requestPromptPacket(
            promptPacket = packet,
            runtimeConfig = runtimeConfig
        )
        client.parseExecutionAssistantReply(raw)
    }
}
