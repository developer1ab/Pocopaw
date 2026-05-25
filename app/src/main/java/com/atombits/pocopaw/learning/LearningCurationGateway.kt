package com.atombits.pocopaw.learning

import com.atombits.pocopaw.ContextSubsetPlanner
import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.DialoguePreferenceBacklogBatch
import com.atombits.pocopaw.OfflineDialoguePreferenceExtractionResolveOutcome
import com.atombits.pocopaw.OfflineDialoguePreferencePromptSpec
import com.atombits.pocopaw.OfflineProcessExtractionPromptSpec
import com.atombits.pocopaw.PromptMessage
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.PromptPacketType
import com.atombits.pocopaw.ProcessCurationPromptSpec
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.ResponseContractRegistry
import com.atombits.pocopaw.TokenBudgetPlanner
import com.atombits.pocopaw.parseOfflineDialoguePreferenceExtractionResult
import com.atombits.pocopaw.process.curation.CanonicalProcessTraceBundle
import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessCurationResolver
import com.atombits.pocopaw.process.curation.StructuredProcessDraftResult
import com.atombits.pocopaw.process.curation.appendProcessCurationRepairInstruction
import com.atombits.pocopaw.process.curation.buildProcessCurationJsonRepairMessages
import com.atombits.pocopaw.process.curation.buildProcessCurationRepairInstruction
import com.atombits.pocopaw.process.curation.parseAndValidateStructuredProcessDraft
import com.atombits.pocopaw.process.curation.salvageStructuredProcessDraft
import com.atombits.pocopaw.process.curation.toStructuredProcessDraftResult

internal object LearningPromptPacketBuilder {
    fun buildOfflineProcessExtractionPacket(spec: OfflineProcessExtractionPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.offlineProcessExtractionBudget()
        val rawMaterialBundle = ContextSubsetPlanner.clipMemoryBundle(spec.rawMaterialBundle)
        val existingAssetBundle = spec.existingAssetBundle?.trim()?.takeUnless { it.isBlank() }?.let(ContextSubsetPlanner::clipMemoryBundle)
        val pageEvidenceBundle = spec.pageEvidenceBundle?.trim()?.takeUnless { it.isBlank() }?.let(ContextSubsetPlanner::clipMemoryBundle)
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Offline process extraction packet. Read execution trace raw materials and return strict JSON for a reference-first reusable process asset. Do not treat historical steps as an executable replay script."
            ),
            PromptMessage(
                role = "user",
                content = buildString {
                    appendLine("Process extraction raw material bundle:")
                    appendLine(rawMaterialBundle)
                    existingAssetBundle?.let { bundle ->
                        appendLine()
                        appendLine("Existing ready asset bundle:")
                        appendLine(bundle)
                    }
                    pageEvidenceBundle?.let { bundle ->
                        appendLine()
                        appendLine("Page evidence bundle:")
                        appendLine(bundle)
                    }
                    appendLine()
                    appendLine("Use authoritative_structured_slots and authoritative_resolved_slots to understand stable parameters.")
                    appendLine("Emit slot_hints with canonical namespaced slot_key values whenever the trace shows stable reusable parameters.")
                    append("Project a stable reference-only process asset only when the raw material is mature enough. Prioritize stage references, semantic anchors, verification signals, exemplar actions, and failure patterns. Emit legacy step bindings only when needed for migration.")
                }.trim()
            )
        )
        return PromptPacket(
            packetType = PromptPacketType.OFFLINE_PROCESS_EXTRACTION,
            systemContract = "Offline process extraction packet.",
            historyBundle = rawMaterialBundle,
            activeCandidateBundle = "offline_process_extraction",
            memoryBundle = existingAssetBundle,
            personalizationBundle = null,
            toolBundle = pageEvidenceBundle,
            executionBrief = null,
            responseContract = ResponseContractRegistry.offlineProcessExtractionContract(),
            tokenBudget = tokenBudget,
            activeSections = buildList {
                add("system_contract")
                add("raw_material_bundle")
                if (existingAssetBundle != null) {
                    add("existing_asset_bundle")
                }
                if (pageEvidenceBundle != null) {
                    add("page_evidence_bundle")
                }
                add("response_contract")
            },
            promptMessages = promptMessages
        )
    }

    fun buildProcessCurationPacket(spec: ProcessCurationPromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.offlineProcessExtractionBudget()
        val traceForPrompt = ContextSubsetPlanner.clipMemoryBundle(spec.traceForPrompt)
        val existingAssetBundle = spec.existingAssetBundle?.trim()?.takeUnless { it.isBlank() }?.let(ContextSubsetPlanner::clipMemoryBundle)
        val pageEvidenceBundle = spec.pageEvidenceBundle?.trim()?.takeUnless { it.isBlank() }?.let(ContextSubsetPlanner::clipMemoryBundle)
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Structured process curation packet. Read the canonical execution trace, reconcile it with existing assets, and return strict JSON for a reference-first ready asset. Do not promote executable replay scripts as the primary output."
            ),
            PromptMessage(
                role = "user",
                content = buildString {
                    appendLine("Task:")
                    appendLine(spec.task)
                    appendLine()
                    appendLine("App scope: ${spec.appScope}")
                    appendLine("Process scope: ${spec.processScope}")
                    spec.existingAssetName?.takeIf { it.isNotBlank() }?.let { value ->
                        appendLine()
                        appendLine("Existing asset name:")
                        appendLine(value)
                    }
                    spec.existingDescription?.takeIf { it.isNotBlank() }?.let { value ->
                        appendLine()
                        appendLine("Existing asset description:")
                        appendLine(value)
                    }
                    spec.reviewComment?.takeIf { it.isNotBlank() }?.let { value ->
                        appendLine()
                        appendLine("Review comment:")
                        appendLine(value)
                    }
                    appendLine()
                    appendLine("Canonical trace bundle:")
                    appendLine(traceForPrompt)
                    existingAssetBundle?.let { value ->
                        appendLine()
                        appendLine("Existing ready asset bundle:")
                        appendLine(value)
                    }
                    pageEvidenceBundle?.let { value ->
                        appendLine()
                        appendLine("Page evidence bundle:")
                        appendLine(value)
                    }
                    appendLine()
                    appendLine("Preserve or emit slot_hints when the canonical trace shows stable authoritative parameters that a future reuse candidate should carry.")
                    append("Return process_enum, semantic_description, optimized_business_process, structured_reference_asset, diff_summary, reliability_analysis, decision, and confidence. Keep any legacy trace fields secondary to the reference-first asset.")
                }.trim()
            )
        )
        return PromptPacket(
            packetType = PromptPacketType.PROCESS_CURATION_QUERY,
            systemContract = "Structured process curation packet.",
            historyBundle = traceForPrompt,
            activeCandidateBundle = spec.task,
            memoryBundle = existingAssetBundle,
            personalizationBundle = null,
            toolBundle = pageEvidenceBundle,
            executionBrief = null,
            responseContract = ResponseContractRegistry.processCurationContract(),
            tokenBudget = tokenBudget,
            activeSections = buildList {
                add("system_contract")
                add("trace_bundle")
                if (existingAssetBundle != null) {
                    add("existing_asset_bundle")
                }
                if (pageEvidenceBundle != null) {
                    add("page_evidence_bundle")
                }
                add("response_contract")
            },
            promptMessages = promptMessages
        )
    }

    fun buildOfflineDialoguePreferenceExtractionPacket(spec: OfflineDialoguePreferencePromptSpec): PromptPacket {
        val tokenBudget = TokenBudgetPlanner.offlinePreferenceBudget()
        val backlogBundle = ContextSubsetPlanner.clipMemoryBundle(spec.backlogBundle)
        val memoryBundle = ContextSubsetPlanner.clipMemoryBundle(spec.memoryBundle)
        val promptMessages = listOf(
            PromptMessage(
                role = "system",
                content = "Offline dialogue preference extraction packet. Read backlog evidence and return structured preference_facts, interaction_bias_signals, habit_evidence, and style_evidence JSON only."
            ),
            PromptMessage(
                role = "user",
                content = buildString {
                    appendLine("Preference backlog bundle:")
                    appendLine(backlogBundle)
                    appendLine()
                    appendLine("Current memory bundle:")
                    appendLine(memoryBundle)
                    appendLine()
                    appendLine("Extract stable durable preference facts, interaction bias signals, or habit evidence only when explicitly supported by the backlog.")
                    appendLine("Treat user messages plus authoritative_structured_slots/authoritative_resolved_slots as authoritative evidence.")
                    appendLine("For durable user preferences, emit preference_facts with canonical domain plus raw durable facet_key values such as brand, product_type, cuisine, budget_band, platform, or avoid.")
                    appendLine("For process/page/shortcut tendencies, emit interaction_bias_signals with signal_key limited to preferred_process_id, preferred_page_signature, or preferred_shortcut_screen.")
                    appendLine("Do not promote one-off task parameters, temporary execution slots, message bodies, or ephemeral route values into durable preference facts.")
                    append("Ignore assistant acknowledgements, execution-status narration, and operational summaries.")
                }.trim()
            )
        )
        return PromptPacket(
            packetType = PromptPacketType.OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION,
            systemContract = "Offline dialogue preference extraction packet.",
            historyBundle = backlogBundle,
            activeCandidateBundle = "offline_dialogue_preference_extraction",
            memoryBundle = memoryBundle,
            personalizationBundle = null,
            toolBundle = null,
            executionBrief = null,
            responseContract = ResponseContractRegistry.offlineDialoguePreferenceExtractionContract(),
            tokenBudget = tokenBudget,
            activeSections = listOf("system_contract", "backlog_bundle", "memory_bundle", "response_contract"),
            promptMessages = promptMessages
        )
    }
}

internal class LearningCurationGateway(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient(),
    private val isConfiguredOverride: (() -> Boolean)? = null,
    private val requestPromptPacketOverride: ((PromptPacket) -> String)? = null,
    private val requestPromptMessagesOverride: ((List<PromptMessage>, SemanticPrototypeClient.PromptRequestConfig) -> String)? = null
) {
    private var lastDiagnostics: List<String> = emptyList()

    fun latestDiagnostics(): List<String> = lastDiagnostics

    fun resolveOfflineDialoguePreferenceExtraction(
        packet: PromptPacket,
        backlogBatch: DialoguePreferenceBacklogBatch,
        store: PrototypeStoreData,
        now: Long
    ): OfflineDialoguePreferenceExtractionResolveOutcome? {
        if (!(isConfiguredOverride?.invoke() ?: client.isConfigured())) {
            return null
        }
        val raw = requestPromptPacketOverride?.invoke(packet) ?: client.requestPromptPacket(packet)
        return OfflineDialoguePreferenceExtractionResolveOutcome(
            extractionResult = parseOfflineDialoguePreferenceExtractionResult(raw),
            rawResponse = raw
        )
    }

    fun resolveProcessCuration(
        packet: PromptPacket,
        pendingEntry: ProcessAssetEntry,
        traceBundle: CanonicalProcessTraceBundle,
        fallbackResolver: ProcessCurationResolver?,
        now: Long = System.currentTimeMillis()
    ): StructuredProcessDraftResult {
        val diagnostics = mutableListOf<String>()
        if (!(isConfiguredOverride?.invoke() ?: client.isConfigured())) {
            diagnostics += if (fallbackResolver != null) {
                "Process curation path: semantic model unavailable; local fallback draft applied."
            } else {
                "Process curation path: semantic model unavailable; request not started."
            }
            lastDiagnostics = diagnostics.distinct()
            fallbackResolver?.let { resolver ->
                return resolver.resolve(packet, pendingEntry, traceBundle, now)
            }
            throw IllegalStateException("Process curation model is not configured.")
        }

        val requestConfig = client.resolveRequestConfig(packet)
        return runCatching {
            resolveStructuredProcessDraft(packet, pendingEntry, traceBundle, requestConfig, diagnostics)
        }.getOrElse { throwable ->
            diagnostics += if (fallbackResolver != null) {
                "Process curation path: semantic request failed; local fallback draft applied."
            } else {
                "Process curation path: semantic request failed before fallback."
            }
            lastDiagnostics = diagnostics.distinct()
            fallbackResolver?.let { resolver ->
                return resolver.resolve(packet, pendingEntry, traceBundle, now)
            }
            throw throwable
        }
    }

    private fun resolveStructuredProcessDraft(
        packet: PromptPacket,
        pendingEntry: ProcessAssetEntry,
        traceBundle: CanonicalProcessTraceBundle,
        requestConfig: SemanticPrototypeClient.PromptRequestConfig,
        diagnostics: MutableList<String>
    ): StructuredProcessDraftResult {
        val firstRaw = requestPromptPacketOverride?.invoke(packet)
            ?: client.requestPromptPacket(packet, requestConfig)
        val (firstParsed, firstErrors) = parseAndValidateStructuredProcessDraft(firstRaw)
        if (firstParsed != null) {
            diagnostics += "Process curation path: semantic structured draft succeeded."
            lastDiagnostics = diagnostics.distinct()
            return firstParsed.toStructuredProcessDraftResult(traceBundle)
        }

        val repairInstruction = buildProcessCurationRepairInstruction(
            errors = firstErrors,
            previousRawJson = firstRaw.take(1200)
        )
        val retryPacket = packet.copy(
            promptMessages = appendProcessCurationRepairInstruction(packet.promptMessages, repairInstruction)
        )
        val secondRaw = requestPromptPacketOverride?.invoke(retryPacket)
            ?: client.requestPromptPacket(retryPacket, requestConfig)
        val (secondParsed, secondErrors) = parseAndValidateStructuredProcessDraft(secondRaw)
        if (secondParsed != null) {
            diagnostics += "Process curation path: semantic structured draft succeeded after contract retry."
            lastDiagnostics = diagnostics.distinct()
            return secondParsed.toStructuredProcessDraftResult(traceBundle)
        }

        val mergedErrors = (firstErrors + secondErrors).distinct()
        val rawCandidates = mutableListOf(secondRaw, firstRaw)
        if (mergedErrors.any { error -> error.contains("invalid_json", ignoreCase = true) }) {
            val repairRaw = if (secondRaw.length >= firstRaw.length) secondRaw else firstRaw
            val repairedRaw = requestPromptMessagesOverride?.invoke(
                buildProcessCurationJsonRepairMessages(
                    schemaHint = packet.responseContract,
                    reasons = mergedErrors,
                    rawText = repairRaw
                ),
                requestConfig.copy(requestTag = "structured_process_draft_repair")
            ) ?: client.requestPromptMessages(
                buildProcessCurationJsonRepairMessages(
                    schemaHint = packet.responseContract,
                    reasons = mergedErrors,
                    rawText = repairRaw
                ),
                requestConfig.copy(requestTag = "structured_process_draft_repair")
            )
            rawCandidates.add(0, repairedRaw)
            val (repairedParsed, repairedErrors) = parseAndValidateStructuredProcessDraft(repairedRaw)
            if (repairedParsed != null) {
                diagnostics += "Process curation path: semantic structured draft succeeded after JSON repair."
                lastDiagnostics = diagnostics.distinct()
                return repairedParsed.toStructuredProcessDraftResult(traceBundle)
            }
            diagnostics += "Process curation path: semantic response malformed; local salvage applied."
            lastDiagnostics = diagnostics.distinct()
            return salvageStructuredProcessDraft(
                rawCandidates = rawCandidates,
                pendingEntry = pendingEntry,
                traceBundle = traceBundle,
                violationCodes = (mergedErrors + repairedErrors).distinct()
            )
        }

        diagnostics += "Process curation path: semantic response violated contract; local salvage applied."
        lastDiagnostics = diagnostics.distinct()
        return salvageStructuredProcessDraft(
            rawCandidates = rawCandidates,
            pendingEntry = pendingEntry,
            traceBundle = traceBundle,
            violationCodes = mergedErrors
        )
    }
}
