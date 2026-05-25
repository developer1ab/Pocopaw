package com.atombits.pocopaw.orchestration

import android.content.Context
import android.util.Log
import com.atombits.pocopaw.ChatMessage
import com.atombits.pocopaw.ChatTurnOptions
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.MessageRole
import com.atombits.pocopaw.ProcessFeedbackType
import com.atombits.pocopaw.PrototypeStore
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.R
import com.atombits.pocopaw.SearchAttribution
import com.atombits.pocopaw.SearchAugmentationClient
import com.atombits.pocopaw.SearchAugmentationResult
import com.atombits.pocopaw.SearchEnhancementTurnContext
import com.atombits.pocopaw.SearchPlanResponse
import com.atombits.pocopaw.SemanticRuntimePreferences
import com.atombits.pocopaw.buildSearchDetailContent
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.armProcessFeedbackDraft
import com.atombits.pocopaw.coerceSemanticTurnOptions
import com.atombits.pocopaw.formatRequestFailureMessage
import com.atombits.pocopaw.resolveCurrentExecutionRuntime
import com.atombits.pocopaw.resolveCurrentProcessRuntime
import com.atombits.pocopaw.resolveCurrentState
import com.atombits.pocopaw.resolveExecutionStartBoundaryPacketFromStore
import com.atombits.pocopaw.resolveSemanticRuntimePreferences
import com.atombits.pocopaw.submitProcessFeedback

private const val SUBMIT_FAILURE_TAG = "SubmitMessageFailure"

private data class SearchResolution(
    val provider: String,
    val result: SearchAugmentationResult? = null,
    val failureMessage: String? = null
) {
    fun toSearchDetailContent(
        searchQueries: List<String>,
        searchScope: List<String>
    ): String = buildSearchDetailContent(
        queries = searchQueries,
        searchScope = searchScope,
        queryResults = result?.queryResults.orEmpty(),
        failureMessage = failureMessage
    )

    fun toPromptSection(): String {
        result?.let { return it.toPromptSection() }
        val failureText = failureMessage.orEmpty().ifBlank { "Search evidence was unavailable." }
        val searchStatus = if (failureText.contains("no web results", ignoreCase = true)) {
            "no_result"
        } else {
            "failed"
        }
        return buildString {
            appendLine("provider=$provider")
            appendLine("search_status=$searchStatus")
            append("failure_message=$failureText")
        }
    }
}

data class ChatTurnFeedbackDraftResult(
    val updatedStore: PrototypeStoreData,
    val message: String
)

data class ChatTurnFeedbackSubmitResult(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

internal fun shouldRunInlineSearchForTurn(
    store: PrototypeStoreData,
    turnOptions: ChatTurnOptions
): Boolean {
    if (!turnOptions.searchEnabled) {
        return false
    }
    if (store.resolveCurrentExecutionRuntime() != null || store.resolveCurrentProcessRuntime() != null) {
        return false
    }
    return resolveExecutionStartBoundaryPacketFromStore(store) == null
}

internal fun shouldApplySearchEnhancement(searchPlan: SearchPlanResponse): Boolean {
    return searchPlan.shouldSearch && searchPlan.searchQueries.isNotEmpty()
}

sealed interface ChatTurnSubmitResult {
    data class FeedbackApplied(
        val updatedStore: PrototypeStoreData,
        val message: String
    ) : ChatTurnSubmitResult

    data class MissingConfiguration(
        val restoreInput: String
    ) : ChatTurnSubmitResult

    data class ConversationCompleted(
        val updatedStore: PrototypeStoreData,
        val executionMessage: String?
    ) : ChatTurnSubmitResult

    data class Failure(
        val flow: String,
        val throwable: Throwable,
        val restoreInput: String,
        val pendingMessages: List<ChatMessage> = emptyList(),
        val attemptedExecutionStart: Boolean = false
    ) : ChatTurnSubmitResult
}

class ChatTurnOrchestrator(
    private val context: Context,
    private val prototypeStore: PrototypeStore,
    private val semanticClient: SemanticPrototypeClient,
    private val searchAugmentationClient: SearchAugmentationClient,
    private val toolspaceCatalogManager: ToolspaceCatalogManager,
    private val executionEntryOrchestrator: ExecutionEntryOrchestrator
) {
    suspend fun armCompletedExecutionFeedback(
        feedbackType: ProcessFeedbackType
    ): ChatTurnFeedbackDraftResult {
        val latestStore = prototypeStore.load()
        val feedbackOutcome = armProcessFeedbackDraft(latestStore, feedbackType)
        val persistedStore = if (feedbackOutcome.applied) {
            prototypeStore.replaceStore(feedbackOutcome.updatedStore)
        } else {
            feedbackOutcome.updatedStore
        }
        return ChatTurnFeedbackDraftResult(
            updatedStore = persistedStore,
            message = feedbackOutcome.message
        )
    }

    suspend fun submitCompletedExecutionFeedback(
        feedbackType: ProcessFeedbackType,
        comment: String
    ): ChatTurnFeedbackSubmitResult {
        val latestStore = prototypeStore.load()
        val feedbackOutcome = submitProcessFeedback(
            store = latestStore,
            feedbackType = feedbackType,
            comment = comment
        )
        val persistedStore = if (feedbackOutcome.applied) {
            prototypeStore.replaceStore(feedbackOutcome.updatedStore)
        } else {
            feedbackOutcome.updatedStore
        }
        return ChatTurnFeedbackSubmitResult(
            updatedStore = persistedStore,
            applied = feedbackOutcome.applied,
            message = feedbackOutcome.message
        )
    }

    suspend fun submitMessage(
        submittedInput: String,
        currentStore: PrototypeStoreData,
        turnOptions: ChatTurnOptions,
        onPendingConversationChanged: (List<ChatMessage>) -> Unit
    ): ChatTurnSubmitResult {
        if (!semanticClient.isConfigured()) {
            return ChatTurnSubmitResult.MissingConfiguration(restoreInput = submittedInput)
        }

        val currentPreferences = currentStore.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
        val initialRequestedTurnOptions = coerceSemanticTurnOptions(currentPreferences, turnOptions)
        val initialTurnOptions = initialRequestedTurnOptions.copy(
            searchEnabled = shouldRunInlineSearchForTurn(currentStore, initialRequestedTurnOptions)
        )
        var pendingMessages = buildPendingConversation(currentStore, submittedInput, initialTurnOptions)
        var attemptedExecutionStart = false
        onPendingConversationChanged(pendingMessages)

        return runCatching {
            val latestStore = prototypeStore.load()
            val latestPreferences = latestStore.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
            val constrainedTurnOptions = coerceSemanticTurnOptions(latestPreferences, turnOptions)
            val effectiveTurnOptions = constrainedTurnOptions.copy(
                searchEnabled = shouldRunInlineSearchForTurn(latestStore, constrainedTurnOptions)
            )
            if (effectiveTurnOptions != initialTurnOptions) {
                pendingMessages = buildPendingConversation(latestStore, submittedInput, effectiveTurnOptions)
                onPendingConversationChanged(pendingMessages)
            }
            val toolCapabilityBundle = toolspaceCatalogManager.buildCapabilityBundle(submittedInput)
            var responseTurnOptions = effectiveTurnOptions
            val searchEnhancementContext = if (effectiveTurnOptions.searchEnabled) {
                val searchPlan = semanticClient.planSearchTurn(
                    userMessage = submittedInput,
                    store = latestStore
                )
                val applySearchEnhancement = shouldApplySearchEnhancement(searchPlan)
                responseTurnOptions = effectiveTurnOptions.copy(searchEnabled = applySearchEnhancement)
                if (responseTurnOptions != effectiveTurnOptions) {
                    pendingMessages = buildPendingConversation(latestStore, submittedInput, responseTurnOptions)
                    onPendingConversationChanged(pendingMessages)
                }
                if (applySearchEnhancement) {
                    val initialSearchDetailContent = buildSearchDetailContent(
                        queries = searchPlan.searchQueries,
                        searchScope = searchPlan.searchScope
                    )
                    pendingMessages = applyGoalAndPlanUpdate(
                        pendingMessages = pendingMessages,
                        goalAndPlanContent = searchPlan.goalAndPlanContent
                    )
                    onPendingConversationChanged(pendingMessages)
                    pendingMessages = applySearchDetailUpdate(
                        pendingMessages = pendingMessages,
                        searchDetailContent = initialSearchDetailContent
                    )
                    onPendingConversationChanged(pendingMessages)

                    val searchResolution = resolveSearchResult(searchPlan.searchQueries)
                    val searchDetailContent = searchResolution.toSearchDetailContent(
                        searchQueries = searchPlan.searchQueries,
                        searchScope = searchPlan.searchScope
                    )
                    val detailUpdatedPendingMessages = applySearchDetailUpdate(
                        pendingMessages = pendingMessages,
                        searchDetailContent = searchDetailContent
                    )
                    if (detailUpdatedPendingMessages != pendingMessages) {
                        pendingMessages = detailUpdatedPendingMessages
                        onPendingConversationChanged(pendingMessages)
                    }
                    val searchAttribution = searchResolution.result?.toChatAttribution()
                    pendingMessages = applyPendingSearchAttribution(pendingMessages, searchAttribution)
                    if (searchAttribution != null) {
                        onPendingConversationChanged(pendingMessages)
                    }

                    val response = semanticClient.streamPlanTurn(
                        userMessage = submittedInput,
                        store = latestStore,
                        turnOptions = responseTurnOptions,
                        searchPlanBundle = searchPlan.toPromptSection(),
                        searchBundle = searchResolution.toPromptSection(),
                        toolCapabilityBundle = toolCapabilityBundle,
                        onDelta = { delta ->
                            val updatedPendingMessages = applyStreamingTurnDelta(pendingMessages, delta)
                            if (updatedPendingMessages != pendingMessages) {
                                pendingMessages = updatedPendingMessages
                                onPendingConversationChanged(pendingMessages)
                            }
                        }
                    )

                    SearchEnhancementTurnContext(
                        goalAndPlanContent = searchPlan.goalAndPlanContent,
                        searchQueries = searchPlan.searchQueries,
                        searchScope = searchPlan.searchScope,
                        searchPlanRequestPayload = searchPlan.requestPayload,
                        searchPlanResponsePayload = searchPlan.responsePayload,
                        searchDetailContent = searchDetailContent,
                        searchAttribution = searchAttribution
                    ) to response
                } else {
                    null to semanticClient.streamPlanTurn(
                        userMessage = submittedInput,
                        store = latestStore,
                        turnOptions = responseTurnOptions,
                        toolCapabilityBundle = toolCapabilityBundle,
                        onDelta = { delta ->
                            val updatedPendingMessages = applyStreamingTurnDelta(pendingMessages, delta)
                            if (updatedPendingMessages != pendingMessages) {
                                pendingMessages = updatedPendingMessages
                                onPendingConversationChanged(pendingMessages)
                            }
                        }
                    )
                }
            } else {
                null to semanticClient.streamPlanTurn(
                    userMessage = submittedInput,
                    store = latestStore,
                    turnOptions = responseTurnOptions,
                    toolCapabilityBundle = toolCapabilityBundle,
                    onDelta = { delta ->
                        val updatedPendingMessages = applyStreamingTurnDelta(pendingMessages, delta)
                        if (updatedPendingMessages != pendingMessages) {
                            pendingMessages = updatedPendingMessages
                            onPendingConversationChanged(pendingMessages)
                        }
                    }
                )
            }
            var updatedStore = prototypeStore.appendSemanticTurn(
                submittedInput,
                searchEnhancementContext.second,
                searchEnhancementContext = searchEnhancementContext.first,
                turnOptions = responseTurnOptions
            )
            var executionMessage: String? = null
            if (executionEntryOrchestrator.shouldAutoStart(updatedStore)) {
                attemptedExecutionStart = true
                val executionOutcome = executionEntryOrchestrator.autoStartExecution(updatedStore)
                updatedStore = executionOutcome.updatedStore
                executionMessage = executionOutcome.message
            }
            ChatTurnSubmitResult.ConversationCompleted(
                updatedStore = updatedStore,
                executionMessage = executionMessage
            )
        }.getOrElse { throwable ->
            logSubmitFailure(
                flow = "conversation_turn",
                throwable = throwable,
                submittedInput = submittedInput,
                turnOptions = turnOptions,
                store = currentStore
            )
            ChatTurnSubmitResult.Failure(
                flow = "conversation_turn",
                throwable = throwable,
                restoreInput = submittedInput,
                pendingMessages = buildFailurePendingMessages(
                    pendingMessages = pendingMessages,
                    store = currentStore,
                    turnOptions = turnOptions,
                    throwable = throwable
                ),
                attemptedExecutionStart = attemptedExecutionStart
            )
        }
    }

    private fun buildPendingConversation(
        store: PrototypeStoreData,
        submittedInput: String,
        turnOptions: ChatTurnOptions
    ): List<ChatMessage> {
        val now = System.currentTimeMillis()
        val stage = store.resolveCurrentState().stage.normalized()
        return listOf(
            ChatMessage(
                role = MessageRole.USER,
                content = submittedInput,
                timestamp = now,
                stage = stage
            ),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = if (turnOptions.searchEnabled) "" else buildPendingAssistantPlaceholder(turnOptions),
                goalAndPlanContent = if (turnOptions.searchEnabled) {
                    buildPendingAssistantPlaceholder(turnOptions)
                } else {
                    null
                },
                timestamp = now + 1,
                stage = stage,
                turnOptions = turnOptions
            )
        )
    }

    private fun applyGoalAndPlanUpdate(
        pendingMessages: List<ChatMessage>,
        goalAndPlanContent: String
    ): List<ChatMessage> {
        val userMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.USER
        } ?: return pendingMessages
        val assistantMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.ASSISTANT
        } ?: return pendingMessages
        if (assistantMessage.goalAndPlanContent == goalAndPlanContent) {
            return pendingMessages
        }
        return listOf(
            userMessage,
            assistantMessage.copy(goalAndPlanContent = goalAndPlanContent)
        )
    }

    private fun applyStreamingTurnDelta(
        pendingMessages: List<ChatMessage>,
        delta: SemanticPrototypeClient.StreamingTurnDelta
    ): List<ChatMessage> {
        val userMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.USER
        } ?: return pendingMessages
        val assistantMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.ASSISTANT
        } ?: return pendingMessages
        val updatedSearchSummaryContent = delta.searchSummaryContent ?: assistantMessage.searchSummaryContent
        val updatedReasoningContent = if (
            assistantMessage.turnOptions?.thinkingEnabled == true ||
            assistantMessage.turnOptions?.searchEnabled == true
        ) {
            delta.reasoningContent ?: assistantMessage.reasoningContent
        } else {
            null
        }
        val updatedAssistantReply = delta.assistantReply.ifBlank { assistantMessage.content }
        if (updatedSearchSummaryContent == assistantMessage.searchSummaryContent &&
            updatedReasoningContent == assistantMessage.reasoningContent &&
            updatedAssistantReply == assistantMessage.content
        ) {
            return pendingMessages
        }
        return listOf(
            userMessage,
            assistantMessage.copy(
                content = updatedAssistantReply,
                searchSummaryContent = updatedSearchSummaryContent,
                reasoningContent = updatedReasoningContent
            )
        )
    }

    private fun applySearchDetailUpdate(
        pendingMessages: List<ChatMessage>,
        searchDetailContent: String
    ): List<ChatMessage> {
        val userMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.USER
        } ?: return pendingMessages
        val assistantMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.ASSISTANT
        } ?: return pendingMessages
        if (assistantMessage.searchDetailContent == searchDetailContent) {
            return pendingMessages
        }
        return listOf(
            userMessage,
            assistantMessage.copy(searchDetailContent = searchDetailContent)
        )
    }

    private fun applyPendingSearchAttribution(
        pendingMessages: List<ChatMessage>,
        searchAttribution: SearchAttribution?
    ): List<ChatMessage> {
        if (searchAttribution == null || pendingMessages.isEmpty()) {
            return pendingMessages
        }
        return pendingMessages.map { message ->
            if (message.role == MessageRole.ASSISTANT) {
                message.copy(searchAttribution = searchAttribution)
            } else {
                message
            }
        }
    }

    private suspend fun resolveSearchResult(
        queries: List<String>
    ): SearchResolution {
        val providerId = searchAugmentationClient.currentProviderId()
        return runCatching {
            searchAugmentationClient.search(queries)
        }.fold(
            onSuccess = { result -> SearchResolution(provider = result.provider, result = result) },
            onFailure = { throwable ->
                SearchResolution(
                    provider = providerId,
                    failureMessage = throwable.message ?: throwable::class.java.simpleName
                )
            }
        )
    }

    private fun buildFailurePendingMessages(
        pendingMessages: List<ChatMessage>,
        store: PrototypeStoreData,
        turnOptions: ChatTurnOptions,
        throwable: Throwable
    ): List<ChatMessage> {
        val userMessage = pendingMessages.firstOrNull { message ->
            message.role == MessageRole.USER
        } ?: return emptyList()
        return listOf(
            userMessage,
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = formatRequestFailureMessage(context, throwable),
                timestamp = System.currentTimeMillis(),
                stage = store.resolveCurrentState().stage.normalized(),
                turnOptions = turnOptions
            )
        )
    }

    private fun buildPendingAssistantPlaceholder(turnOptions: ChatTurnOptions): String {
        return when {
            turnOptions.thinkingEnabled && turnOptions.searchEnabled -> {
                context.getString(R.string.pending_assistant_thinking_search)
            }

            turnOptions.thinkingEnabled -> context.getString(R.string.pending_assistant_thinking)
            turnOptions.searchEnabled -> context.getString(R.string.pending_assistant_search)
            else -> context.getString(R.string.pending_assistant_reply)
        }
    }

    private fun logSubmitFailure(
        flow: String,
        throwable: Throwable,
        submittedInput: String,
        turnOptions: ChatTurnOptions,
        store: PrototypeStoreData
    ) {
        val failureMessage = throwable.message.orEmpty().ifBlank { throwable::class.java.simpleName }
        val stage = store.resolveCurrentState().stage.normalized().name
        Log.e(
            SUBMIT_FAILURE_TAG,
            "submitMessage failure flow=$flow stage=$stage inputLength=${submittedInput.length} " +
                "thinkingEnabled=${turnOptions.thinkingEnabled} searchEnabled=${turnOptions.searchEnabled} " +
                "message=$failureMessage",
            throwable
        )
    }
}
