package com.atombits.pocopaw.intent

import com.atombits.pocopaw.ChatTurnOptions
import com.atombits.pocopaw.ContextSubsetPlanner
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.PromptCenter
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.SearchPlanPromptSpec
import com.atombits.pocopaw.SearchPlanResponse
import com.atombits.pocopaw.ProviderRuntimeConfigs
import com.atombits.pocopaw.ResponseContractRegistry
import com.atombits.pocopaw.SemanticRuntimePreferences
import com.atombits.pocopaw.SemanticTurnPromptSpec
import com.atombits.pocopaw.SemanticTurnResponse
import com.atombits.pocopaw.ToolCapabilityBundle
import com.atombits.pocopaw.PromptPacketType
import com.atombits.pocopaw.TokenBudgetPlanner
import com.atombits.pocopaw.PrototypeStoreData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object IntentPromptPacketBuilder {
    fun buildSearchPlanPacket(spec: SearchPlanPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.searchPlanBudget()
        val historyBundle = ContextSubsetPlanner.buildHistoryBundle(
            historyLines = spec.historyLines,
            reactivationHintLines = spec.reactivationHintLines
        )
        val memoryBundle = spec.memoryBundle
            .trim()
            .takeUnless { it.isBlank() || it == PromptCenter.defaultMemoryBundlePlaceholder }
        val personalizationBundle = spec.personalizationBundle
            ?.trim()
            ?.takeUnless { it.isBlank() }
        val systemContract = PromptCenter.buildSearchPlanSystemContract()
        val responseContract = ResponseContractRegistry.searchPlanContract()
        val promptMessages = PromptCenter.buildSearchPlanPromptMessages(
            systemContract = systemContract,
            responseContract = responseContract,
            userPrompt = PromptCenter.buildSearchPlanUserPrompt(
                currentState = spec.currentState,
                historyBundle = historyBundle,
                semanticContextBundle = spec.semanticContextBundle.trim(),
                memoryBundle = memoryBundle,
                personalizationBundle = personalizationBundle,
                userMessage = spec.userMessage
            )
        )

        return PromptPacket(
            packetType = PromptPacketType.SEARCH_PLAN_QUERY,
            systemContract = systemContract,
            historyBundle = historyBundle,
            activeCandidateBundle = spec.semanticContextBundle.trim(),
            memoryBundle = memoryBundle,
            personalizationBundle = personalizationBundle,
            toolBundle = null,
            executionBrief = null,
            responseContract = responseContract,
            tokenBudget = tokenBudget,
            activeSections = buildList {
                add("system_contract")
                add("history_bundle")
                add("semantic_context_bundle")
                if (memoryBundle != null) {
                    add("memory_bundle")
                }
                if (personalizationBundle != null) {
                    add("personalization_bundle")
                }
                add("response_contract")
            },
            promptMessages = promptMessages
        )
    }

    fun buildSemanticTurnPacket(spec: SemanticTurnPromptSpec): PromptPacket {
        val searchPlanBundle = spec.searchPlanBundle
            ?.trim()
            ?.takeUnless { it.isBlank() }
        val searchEnhanced = searchPlanBundle != null
        val tokenBudget = TokenBudgetPlanner.semanticTurnBudget(searchEnhanced = searchEnhanced)
        val historyBundle = ContextSubsetPlanner.buildFullHistoryBundle(
            historyLines = spec.historyLines,
            reactivationHintLines = spec.reactivationHintLines
        )
        val semanticContextBundle = spec.semanticContextBundle.trim()
        val memoryBundle = spec.memoryBundle
            .trim()
            .takeUnless { it.isBlank() || it == PromptCenter.defaultMemoryBundlePlaceholder }
        val personalizationBundle = spec.personalizationBundle
            ?.trim()
            ?.takeUnless { it.isBlank() }
        val searchBundle = spec.searchBundle
            ?.trim()
            ?.takeUnless { it.isBlank() }
        val capabilityPriorBundle = spec.capabilityPriorBundle
            .trim()
            .takeUnless { it.isBlank() }
            ?: PromptCenter.defaultCapabilityPriorBundlePlaceholder
        val processPriorBundle = spec.processPriorBundle
            .trim()
            .takeUnless { it.isBlank() }
            ?: PromptCenter.defaultProcessPriorBundlePlaceholder
        val toolBundle: String? = null
        val safetyDecision = spec.safetyDecision
            ?.trim()
            ?.takeUnless { it.isBlank() }
        val executionBrief = spec.executionBrief
            ?.takeUnless { it.isBlank() }
        val systemContract = PromptCenter.buildSemanticIntentSystemContract(searchEnhanced = searchEnhanced)
        val responseContract = ResponseContractRegistry.semanticIntentContract(searchEnhanced = searchEnhanced)
        val promptMessages = PromptCenter.buildSemanticPromptMessages(
            systemContract = systemContract,
            responseContract = responseContract,
            userPrompt = PromptCenter.buildSemanticIntentUserPrompt(
                currentState = spec.currentState,
                historyBundle = historyBundle,
                semanticContextBundle = semanticContextBundle,
                memoryBundle = memoryBundle,
                personalizationBundle = personalizationBundle,
                searchPlanBundle = searchPlanBundle,
                searchBundle = searchBundle,
                capabilityPriorBundle = capabilityPriorBundle,
                processPriorBundle = processPriorBundle,
                safetyDecision = safetyDecision,
                executionBrief = executionBrief,
                userMessage = spec.userMessage
            )
        )

        val activeSections = buildList {
            add("system_contract")
            add("history_bundle")
            add("semantic_context_bundle")
            if (memoryBundle != null) {
                add("memory_bundle")
            }
            if (personalizationBundle != null) {
                add("personalization_bundle")
            }
            if (searchPlanBundle != null) {
                add("search_plan_bundle")
            }
            if (searchBundle != null) {
                add("search_bundle")
            }
            add("capability_prior_bundle")
            add("process_prior_bundle")
            if (toolBundle != null) {
                add("tool_bundle")
            }
            if (safetyDecision != null) {
                add("safety_decision")
            }
            if (executionBrief != null) {
                add("execution_brief")
            }
            add("response_contract")
        }

        return PromptPacket(
            packetType = PromptPacketType.SEMANTIC_TURN,
            systemContract = systemContract,
            historyBundle = historyBundle,
            activeCandidateBundle = semanticContextBundle,
            memoryBundle = memoryBundle,
            personalizationBundle = personalizationBundle,
            searchPlanBundle = searchPlanBundle,
            searchBundle = searchBundle,
            capabilityPriorBundle = capabilityPriorBundle,
            processPriorBundle = processPriorBundle,
            toolBundle = toolBundle,
            executionBrief = executionBrief,
            responseContract = responseContract,
            tokenBudget = tokenBudget,
            activeSections = activeSections,
            promptMessages = promptMessages
        )
    }
}

internal class IntentGateway(
    private val client: SemanticPrototypeClient
) {
    suspend fun planSearchTurn(
        userMessage: String,
        store: PrototypeStoreData
    ): SearchPlanResponse = withContext(Dispatchers.IO) {
        val runtimeConfig = ProviderRuntimeConfigs.semanticRuntimeConfig(
            store.semanticRuntimePreferences ?: SemanticRuntimePreferences()
        )
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }
        val rawExchange = client.requestPromptPacketExchange(
            promptPacket = client.buildSearchPlanPacket(userMessage, store),
            runtimeConfig = runtimeConfig
        )
        client.parseSearchPlanResponse(rawExchange)
    }

    suspend fun planTurn(
        userMessage: String,
        store: PrototypeStoreData,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        searchPlanBundle: String? = null,
        searchBundle: String? = null,
        toolCapabilityBundle: ToolCapabilityBundle? = null
    ): SemanticTurnResponse = withContext(Dispatchers.IO) {
        val runtimeConfig = ProviderRuntimeConfigs.semanticRuntimeConfig(
            store.semanticRuntimePreferences ?: SemanticRuntimePreferences()
        )
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }
        val rawExchange = client.requestPromptPacketExchange(
            promptPacket = client.buildSemanticTurnPacket(
                userMessage = userMessage,
                store = store,
                searchPlanBundle = searchPlanBundle,
                searchBundle = searchBundle,
                toolCapabilityBundle = toolCapabilityBundle
            ),
            runtimeConfig = runtimeConfig,
            turnOptions = turnOptions
        )
        client.sanitizeSemanticTurnResponse(client.parseResponse(rawExchange, store), turnOptions)
    }

    suspend fun streamPlanTurn(
        userMessage: String,
        store: PrototypeStoreData,
        turnOptions: ChatTurnOptions = ChatTurnOptions(),
        searchPlanBundle: String? = null,
        searchBundle: String? = null,
        toolCapabilityBundle: ToolCapabilityBundle? = null,
        onDelta: (SemanticPrototypeClient.StreamingTurnDelta) -> Unit
    ): SemanticTurnResponse = withContext(Dispatchers.IO) {
        val runtimeConfig = ProviderRuntimeConfigs.semanticRuntimeConfig(
            store.semanticRuntimePreferences ?: SemanticRuntimePreferences()
        )
        if (!runtimeConfig.isConfigured()) {
            throw IllegalStateException("Missing semantic provider API key")
        }
        val promptPacket = client.buildSemanticTurnPacket(
            userMessage = userMessage,
            store = store,
            searchPlanBundle = searchPlanBundle,
            searchBundle = searchBundle,
            toolCapabilityBundle = toolCapabilityBundle
        )
        val rawExchange = client.requestPromptPacketStreamingExchange(
            promptPacket = promptPacket,
            runtimeConfig = runtimeConfig,
            turnOptions = turnOptions,
            onDelta = { delta ->
                onDelta(client.sanitizeStreamingTurnDelta(delta, turnOptions))
            }
        )
        client.sanitizeSemanticTurnResponse(client.parseResponse(rawExchange, store), turnOptions)
    }
}
