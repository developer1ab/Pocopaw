package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.ContextSubsetPlanner
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.ProcessReferenceSelectionPromptSpec
import com.atombits.pocopaw.PromptMessage
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.PromptPacketType
import com.atombits.pocopaw.ResponseContractRegistry
import com.atombits.pocopaw.TokenBudgetPlanner

internal object ReferenceSelectionPromptPacketBuilder {
    fun buildProcessReferenceSelectionPacket(spec: ProcessReferenceSelectionPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.offlinePreferenceBudget()
        val taskIntentBundle = ContextSubsetPlanner.clipMemoryBundle(spec.taskIntentBundle)
        val processCatalogBundle = ContextSubsetPlanner.clipMemoryBundle(spec.processCatalogBundle)
        val processGuidanceBundle = spec.processGuidanceBundle
            ?.trim()
            ?.takeUnless { it.isBlank() }
            ?.let(ContextSubsetPlanner::clipMemoryBundle)
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Process reference selection packet. Choose the best reusable process references for the current task as guidance only and return strict JSON only. Do not choose a reference as an executable replay script. preserved_slot_values are authoritative. recommended_slot_values are weak hints only for missing detail slots and must never override preserved_slot_values or explicit user constraints."
            ),
            PromptMessage(
                role = "user",
                content = buildString {
                    appendLine("Task intent bundle:")
                    appendLine(taskIntentBundle)
                    appendLine()
                    appendLine("Process catalog bundle:")
                    appendLine(processCatalogBundle)
                    processGuidanceBundle?.let { value ->
                        appendLine()
                        appendLine("Process guidance bundle:")
                        appendLine(value)
                    }
                    appendLine()
                    append("Return at most ${spec.maxSelectionCount.coerceAtLeast(1)} candidate_references, explain why they help stage understanding, and identify the best selected_reference_entry_id when possible. Treat references as guidance only, not executable routes. Use recommended_slot_values only as weak hints for missing primary filters; never treat them as authority and never use them to override preserved_slot_values or explicit user constraints.")
                }.trim()
            )
        )
        return PromptPacket(
            packetType = PromptPacketType.PROCESS_REFERENCE_SELECTION_QUERY,
            systemContract = "Process reference selection packet.",
            historyBundle = taskIntentBundle,
            activeCandidateBundle = processCatalogBundle,
            memoryBundle = processGuidanceBundle,
            personalizationBundle = null,
            toolBundle = null,
            executionBrief = null,
            responseContract = ResponseContractRegistry.processReferenceSelectionContract(),
            tokenBudget = tokenBudget,
            activeSections = buildList {
                add("system_contract")
                add("task_intent_bundle")
                add("process_catalog_bundle")
                if (processGuidanceBundle != null) {
                    add("process_guidance_bundle")
                }
                add("response_contract")
            },
            promptMessages = promptMessages
        )
    }
}

internal class ReferenceSelectionGateway(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient(),
    private val isConfiguredOverride: (() -> Boolean)? = null,
    private val requestPromptPacketOverride: ((PromptPacket) -> String)? = null
) {
    fun select(
        packet: PromptPacket,
        localRankedCandidates: List<CandidateProcessReference>,
        fallbackResolver: ProcessReferenceSelectionResolver,
        now: Long = System.currentTimeMillis()
    ): ProcessReferenceSelectionOutcome {
        if (!(isConfiguredOverride?.invoke() ?: client.isConfigured())) {
            return fallbackResolver.select(packet, localRankedCandidates, now)
        }

        val raw = runCatching {
            requestPromptPacketOverride?.invoke(packet) ?: client.requestPromptPacket(packet)
        }.getOrNull() ?: return fallbackResolver.select(packet, localRankedCandidates, now)

        return runCatching {
            parseProcessReferenceSelectionResult(raw, localRankedCandidates)
        }.recoverCatching {
            val repairPacket = packet.copy(
                promptMessages = packet.promptMessages + PromptMessage(
                    role = "user",
                    content = "Your previous response violated the JSON contract. Re-send JSON only and match this contract exactly: ${packet.responseContract}"
                )
            )
            val repairedRaw = requestPromptPacketOverride?.invoke(repairPacket) ?: client.requestPromptPacket(repairPacket)
            parseProcessReferenceSelectionResult(repairedRaw, localRankedCandidates)
        }.getOrElse {
            fallbackResolver.select(packet, localRankedCandidates, now)
        }
    }
}
