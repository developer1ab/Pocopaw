package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.SemanticPrototypeClient
import com.atombits.pocopaw.PromptMessage
import com.atombits.pocopaw.PromptPacket
import com.atombits.pocopaw.parseStructuredPromptPayloadObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

internal data class ProcessReferenceSelectionOutcome(
    val selectedReferenceId: String? = null,
    val candidateReferences: List<CandidateProcessReference> = emptyList(),
    val selectionSummary: String? = null,
    val whySelected: List<String> = emptyList(),
    val selectedStageHints: List<String> = emptyList(),
    val referenceCautions: List<String> = emptyList(),
    val selectedByModel: Boolean = false
)

internal interface ProcessReferenceSelectionResolver {
    fun select(
        packet: PromptPacket,
        localRankedCandidates: List<CandidateProcessReference>,
        now: Long = System.currentTimeMillis()
    ): ProcessReferenceSelectionOutcome
}

internal object LocalProcessReferenceSelectionResolver : ProcessReferenceSelectionResolver {
    override fun select(
        packet: PromptPacket,
        localRankedCandidates: List<CandidateProcessReference>,
        now: Long
    ): ProcessReferenceSelectionOutcome {
        val selectedCandidates = localRankedCandidates.take(maxProcessReferenceSelectionCount)
        val preferredCandidate = selectedCandidates.firstOrNull()
        return ProcessReferenceSelectionOutcome(
            selectedReferenceId = preferredCandidate?.assetId,
            candidateReferences = selectedCandidates,
            selectionSummary = preferredCandidate?.let { candidate ->
                "Locally ranked ${selectedCandidates.size} ready candidate(s); selected ${candidate.assetName}."
            },
            whySelected = preferredCandidate?.let { candidate ->
                listOf("local_rank_selected=${candidate.assetName}")
            }.orEmpty(),
            selectedStageHints = preferredCandidate?.stageReferences
                ?.map { reference -> reference.stageName }
                ?.filter { value -> value.isNotBlank() }
                ?.take(3)
                ?.ifEmpty { preferredCandidate.stages.take(3) }
                .orEmpty(),
            selectedByModel = false
        )
    }
}

internal class SemanticProcessReferenceSelectionResolver(
    private val client: SemanticPrototypeClient = SemanticPrototypeClient(),
    private val fallbackResolver: ProcessReferenceSelectionResolver = LocalProcessReferenceSelectionResolver,
    private val isConfiguredOverride: (() -> Boolean)? = null,
    private val requestPromptPacketOverride: ((PromptPacket) -> String)? = null
) : ProcessReferenceSelectionResolver {
    private val gateway by lazy(LazyThreadSafetyMode.NONE) {
        ReferenceSelectionGateway(
            client = client,
            isConfiguredOverride = isConfiguredOverride,
            requestPromptPacketOverride = requestPromptPacketOverride
        )
    }

    override fun select(
        packet: PromptPacket,
        localRankedCandidates: List<CandidateProcessReference>,
        now: Long
    ): ProcessReferenceSelectionOutcome {
        return gateway.select(packet, localRankedCandidates, fallbackResolver, now)
    }
}

internal fun parseProcessReferenceSelectionResult(
    raw: String,
    localRankedCandidates: List<CandidateProcessReference>
): ProcessReferenceSelectionOutcome {
    val payload = parseStructuredPromptPayloadObject(raw)
    val candidatesById = localRankedCandidates.associateBy { candidate -> candidate.assetId }
    val candidatesByName = localRankedCandidates.associateBy { candidate -> candidate.assetName.lowercase(Locale.US) }
    val parsedCandidates = payload.getJsonArrayOrEmpty("candidate_references")
        .mapNotNull { item ->
            item.asJsonObjectOrNull()?.toSelectedCandidate(candidatesById, candidatesByName)
        }
        .distinctBy { candidate -> candidate.assetId }
        .take(maxProcessReferenceSelectionCount)
    val selectedReferenceId = payload.getFirstStringOrNull(
        selectedReferenceIdFieldAliases
    )
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?.takeIf { value -> value in candidatesById }
        ?: payload.getFirstStringOrNull(
            selectedReferenceNameFieldAliases
        )
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?.lowercase(Locale.US)
            ?.let { value -> candidatesByName[value]?.assetId }
        ?: parsedCandidates.firstOrNull()?.assetId
    val finalCandidates = when {
        parsedCandidates.isNotEmpty() -> parsedCandidates
        selectedReferenceId != null -> listOfNotNull(candidatesById[selectedReferenceId])
        else -> emptyList()
    }
    if (selectedReferenceId == null && finalCandidates.isEmpty()) {
        throw IllegalArgumentException("Process reference selection response did not resolve to a local candidate.")
    }
    return ProcessReferenceSelectionOutcome(
        selectedReferenceId = selectedReferenceId,
        candidateReferences = finalCandidates,
        selectionSummary = payload.getStringOrNull("selection_summary")
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() },
        whySelected = payload.getStringList("why_selected"),
        selectedStageHints = payload.getStringList("selected_stage_hints")
            .ifEmpty {
                parsedCandidates.flatMap { candidate -> candidate.stages }
            }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .take(3),
        referenceCautions = buildList {
            addAll(payload.getStringList("reference_cautions"))
            payload.getJsonArrayOrEmpty("candidate_references").forEach { item ->
                item.asJsonObjectOrNull()?.getStringList("reference_cautions")?.let(::addAll)
            }
        }.filter { value -> value.isNotBlank() }
            .distinct(),
        selectedByModel = true
    )
}

private fun JsonObject.toSelectedCandidate(
    candidatesById: Map<String, CandidateProcessReference>,
    candidatesByName: Map<String, CandidateProcessReference>
): CandidateProcessReference? {
    val entryId = getFirstStringOrNull(candidateReferenceIdFieldAliases)
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
    val assetName = getFirstStringOrNull(candidateReferenceNameFieldAliases)
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
    return when {
        entryId != null && entryId in candidatesById -> candidatesById[entryId]
        assetName != null -> candidatesByName[assetName.lowercase(Locale.US)]
        else -> null
    }
}

private fun JsonObject.getJsonArrayOrEmpty(memberName: String): JsonArray {
    val value = get(memberName)
    return if (value != null && value.isJsonArray) {
        value.asJsonArray
    } else {
        JsonArray()
    }
}

private fun JsonObject.getStringOrNull(memberName: String): String? {
    val value = get(memberName) ?: return null
    return if (value.isJsonNull) null else runCatching { value.asString }.getOrNull()
}

private fun JsonObject.getFirstStringOrNull(memberNames: List<String>): String? {
    return memberNames.firstNotNullOfOrNull(::getStringOrNull)
}

private fun JsonObject.getStringList(memberName: String): List<String> {
    return getJsonArrayOrEmpty(memberName)
        .mapNotNull { element ->
            if (element.isJsonNull) {
                null
            } else {
                runCatching { element.asString }.getOrNull()
            }
        }
        .map { value -> value.trim() }
        .filter { value -> value.isNotBlank() }
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private val selectedReferenceIdFieldAliases = listOf(
    "selected_reference_entry_id"
)

private val selectedReferenceNameFieldAliases = listOf(
    "selected_reference_name"
)

private val candidateReferenceIdFieldAliases = listOf(
    "reference_entry_id"
)

private val candidateReferenceNameFieldAliases = listOf(
    "reference_name",
    "asset_name"
)
