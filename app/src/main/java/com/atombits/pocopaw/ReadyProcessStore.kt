package com.atombits.pocopaw

import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessAssetState
import java.util.Locale

data class ProcessShortcutProjectionOutcome(
    val updatedStore: PrototypeStoreData,
    val applied: Boolean,
    val message: String
)

internal data class ProcessExtractionGroupPlan(
    val materials: List<CanonicalTraceRawMaterial>,
    val selectedMaterial: CanonicalTraceRawMaterial,
    val appScope: String,
    val processAction: String,
    val processId: String,
    val actionable: Boolean
)

fun applyProcessShortcutProjection(
    store: PrototypeStoreData,
    now: Long = System.currentTimeMillis()
): ProcessShortcutProjectionOutcome {
    val readyEntries = store.processAssetEntries.filter { entry -> entry.assetState == ProcessAssetState.READY }
    if (readyEntries.isEmpty()) {
        return ProcessShortcutProjectionOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_shortcut_no_ready_entry,
                "No ready process entry is available for shortcut projection."
            )
        )
    }

    val nextAtlas = store.processShortcutAtlas.toMutableList()
    var projectedCount = 0
    readyEntries.forEach { entry ->
        val processId = entry.processScope.takeIf { value -> value.isNotBlank() } ?: return@forEach
        val processAction = resolveShortcutProjectionProcessAction(entry)
        val pathIndex = resolveShortcutProjectionPathIndex(entry)
        val preferredPageEvidence = resolvePreferredPageEvidenceAsset(
            pageEvidenceAssets = store.pageEvidenceAssets,
            appScope = entry.appScope,
            processId = processId
        )
        val existingIndex = nextAtlas.indexOfFirst { candidate ->
            candidate.resolvedProcessAction() == processAction &&
                candidate.appScope.equals(entry.appScope, ignoreCase = true) &&
                candidate.pathIndex == pathIndex
        }
        val existingCandidate = nextAtlas.getOrNull(existingIndex)
        if (existingCandidate != null && existingCandidate.version >= entry.revision) {
            return@forEach
        }

        val projectedCandidate = ProcessShortcutCandidate(
            shortcutId = buildProcessShortcutId(
                appScope = entry.appScope,
                processAction = processAction,
                pathIndex = pathIndex,
                version = entry.revision
            ),
            appScope = entry.appScope,
            processId = processId,
            processAction = processAction,
            screenSignature = preferredPageEvidence?.pageSignature
                ?: extractPrimaryShortcutScreenSignature(entry)
                ?: "UNKNOWN",
            elementRole = deriveShortcutElementRole(entry),
            verificationHint = preferredPageEvidence?.verificationSignals?.firstOrNull()
                ?: entry.businessAcceptanceCriteria.firstOrNull()
                ?: "shortcut_verified",
            stabilityScore = deriveShortcutStabilityScore(entry, preferredPageEvidence),
            pathIndex = pathIndex,
            version = entry.revision,
            lineageSourceTraceId = entry.originAssetId ?: entry.id,
            lastDerivedAt = now,
            slotHints = resolveReadyProcessAssetForEntry(store, entry)?.slotHints.orEmpty()
        )
        if (existingIndex >= 0) {
            nextAtlas[existingIndex] = projectedCandidate
        } else {
            nextAtlas.add(projectedCandidate)
        }
        projectedCount += 1
    }

    if (projectedCount == 0) {
        return ProcessShortcutProjectionOutcome(
            updatedStore = store,
            applied = false,
            message = UiStrings.resolve(
                R.string.process_shortcut_no_newer_entry,
                "No newer ready process entry required shortcut projection."
            )
        )
    }

    return ProcessShortcutProjectionOutcome(
        updatedStore = store.copy(
            messages = store.messages.toMutableList(),
            snapshots = store.snapshots.toMutableList(),
            executionEvents = store.executionEvents.toMutableList(),
            executionTraces = store.executionTraces.toMutableList(),
            processExtractionRawMaterials = store.processExtractionRawMaterials.toMutableList(),
            readyProcessAssets = store.readyProcessAssets.toMutableList(),
            processAssetEntries = store.processAssetEntries.toMutableList(),
            pageEvidenceAssets = store.pageEvidenceAssets.toMutableList(),
            processShortcutAtlas = nextAtlas,
            processAssetEvents = store.processAssetEvents.toMutableList(),
            processExtractionConsumedIds = store.processExtractionConsumedIds.toMutableList(),
            memoryState = store.memoryState ?: MemoryState()
        ),
        applied = true,
        message = UiStrings.resolve(
            R.string.process_shortcut_projected,
            "Projected %1\$d shortcut candidate(s).",
            projectedCount
        )
    )
}

internal fun buildProcessShortcutId(
    appScope: String,
    processAction: String,
    pathIndex: Int,
    version: Int
): String {
    val normalizedAppScope = appScope.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "generic" }
    val normalizedProcessAction = canonicalizeProcessAction(processAction)
        ?.takeIf { action -> action.isNotBlank() }
        ?: processAction.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "run" }
    return "${normalizedAppScope}:${normalizedProcessAction}:path${pathIndex.coerceAtLeast(1)}:v${version.coerceAtLeast(1)}"
}

private fun deriveShortcutElementRole(asset: ReadyProcessAsset): String {
    return if (asset.domain == "SHOPPING") {
        "primary_entry"
    } else {
        "primary_action"
    }
}

private fun deriveShortcutElementRole(entry: ProcessAssetEntry): String {
    return if (entry.domain.equals("SHOPPING", ignoreCase = true)) {
        "primary_entry"
    } else {
        "primary_action"
    }
}

private fun deriveShortcutStabilityScore(
    asset: ReadyProcessAsset,
    pageEvidenceAsset: PageEvidenceAsset? = null
): Double {
    val stageScore = when {
        asset.stages.size >= 2 -> 0.8
        asset.stages.isNotEmpty() -> 0.7
        else -> 0.5
    }
    val acceptanceScore = if (asset.acceptanceCriteria.isNotEmpty()) 0.1 else 0.0
    val evidenceScore = when {
        pageEvidenceAsset == null -> 0.0
        pageEvidenceAsset.observationCount >= 3 -> 0.1
        pageEvidenceAsset.observationCount >= 2 -> 0.05
        else -> 0.02
    }
    return (stageScore + acceptanceScore + evidenceScore).coerceAtMost(0.95)
}

private fun selectBestProcessExtractionMaterial(
    materials: List<CanonicalTraceRawMaterial>
): CanonicalTraceRawMaterial {
    return materials.maxWithOrNull(
        compareBy<CanonicalTraceRawMaterial>(::scoreProcessExtractionMaterial)
            .thenBy { rawMaterial -> rawMaterial.createdAt }
    ) ?: materials.first()
}

internal fun buildProcessExtractionGroupPlans(
    pendingMaterials: List<CanonicalTraceRawMaterial>
): List<ProcessExtractionGroupPlan> {
    return pendingMaterials
        .groupBy { rawMaterial ->
            Triple(
                deriveCanonicalProcessAppScope(rawMaterial),
                rawMaterial.resolvedProcessAction(),
                isActionableProcessExtractionMaterial(rawMaterial)
            )
        }
        .map { (groupKey, materials) ->
            val selectedMaterial = selectBestProcessExtractionMaterial(materials)
            val resolvedProcessId = deriveCanonicalProcessScope(
                rawProcessId = null,
                objective = selectedMaterial.objective,
                appScope = groupKey.first,
                actionHint = selectedMaterial.processAction ?: groupKey.second,
                selectedToolId = selectedMaterial.selectedToolId
            ) ?: sanitizeCanonicalProcessId(selectedMaterial.processId)
                ?: GENERIC_PROCESS_SCOPE
            ProcessExtractionGroupPlan(
                materials = materials,
                selectedMaterial = selectedMaterial,
                appScope = groupKey.first,
                processAction = groupKey.second,
                processId = resolvedProcessId,
                actionable = groupKey.third
            )
        }
}

private fun buildReadyProcessCandidateAsset(
    selectedMaterial: CanonicalTraceRawMaterial,
    now: Long
): ReadyProcessAsset {
    val semanticExtraction = extractProcessSemantics(selectedMaterial)
    val resolvedProcessId = deriveCanonicalProcessScope(
        rawProcessId = null,
        objective = selectedMaterial.objective,
        appScope = semanticExtraction.appScope,
        domain = semanticExtraction.domain,
        actionHint = selectedMaterial.processAction ?: semanticExtraction.processAction,
        selectedToolId = selectedMaterial.selectedToolId
    ) ?: sanitizeCanonicalProcessId(selectedMaterial.processId)
        ?: GENERIC_PROCESS_SCOPE
    return ReadyProcessAsset(
        processId = resolvedProcessId,
        processAction = semanticExtraction.processAction,
        domain = semanticExtraction.domain,
        appScope = semanticExtraction.appScope,
        semanticDescription = semanticExtraction.semanticDescription,
        stages = semanticExtraction.stages,
        acceptanceCriteria = semanticExtraction.acceptanceCriteria,
        pathIndex = 1,
        version = 1,
        lineageSourceTraceId = selectedMaterial.traceId,
        lastDerivedAt = now,
        slotHints = selectedMaterial.slotEvidenceSnapshot?.toProcessSlotHints().orEmpty()
    )
}

private fun buildOfflineProcessExtractionRawMaterialBundle(
    materials: List<CanonicalTraceRawMaterial>
): String {
    return materials.sortedBy { material -> material.createdAt }
        .joinToString("\n") { material ->
            val reducedTrace = reduceCanonicalTrace(material)
            val semanticExtraction = extractProcessSemantics(material, reducedTrace)
            buildString {
                append("trace_id=")
                append(material.traceId)
                append("; process_id=")
                append(material.processId)
                append("; process_action=")
                append(semanticExtraction.processAction)
                append("; domain=")
                append(semanticExtraction.domain)
                append("; app_scope=")
                append(semanticExtraction.appScope)
                append("; objective=")
                append(material.objective)
                append("; steps=")
                append(reducedTrace.steps.joinToString(" -> "))
                material.slotEvidenceSnapshot?.let { snapshot ->
                    append("\nauthoritative_slot_source=")
                    append(snapshot.sourceLevel)
                    append("\nstructured_detail_slots.common=")
                    append(snapshot.structuredCommonSummary())
                    append("\nstructured_detail_slots.domain=")
                    append(snapshot.structuredDomainSummary())
                    append("\nauthoritative_resolved_slots=")
                    append(snapshot.resolvedSlotSummary())
                }
            }
        }
}

internal fun buildExistingReadyAssetBundle(
    readyProcessAssets: List<ReadyProcessAsset>,
    appScope: String,
    processAction: String
): String? {
    val matchingAssets = readyProcessAssets.filter { asset ->
        asset.appScope.equals(appScope, ignoreCase = true) &&
            asset.resolvedProcessAction() == processAction
    }
    return matchingAssets.takeIf { assets -> assets.isNotEmpty() }?.joinToString("\n") { asset ->
        buildString {
            append("process_id=${asset.processId}; process_action=${asset.resolvedProcessAction()}; path=path${asset.pathIndex.coerceAtLeast(1)}; version=${asset.version}; stages=${asset.stages.joinToString(",")}; acceptance=${asset.acceptanceCriteria.joinToString(",")}")
            if (asset.slotHints.isNotEmpty()) {
                append("; slot_hints=")
                append(asset.slotHints.toPromptSummary())
            }
        }
    }
}

fun resolveReadyProcessAsset(
    store: PrototypeStoreData,
    boundaryPacket: TaskExecutionBoundaryPacket
): ReadyProcessAsset? {
    val explicitAppScope = extractCanonicalAppScope(boundaryPacket.capabilityId)
    val requestedProcessId = boundaryPacket.processId?.takeIf { value -> value.isNotBlank() }
    val requestedAction = resolveCanonicalBoundaryPacketProcessAction(boundaryPacket)
    val scopedAssets = store.readyProcessAssets.asSequence()
        .filter { asset ->
            explicitAppScope == null || asset.appScope.equals(explicitAppScope, ignoreCase = true)
        }
        .toList()
    val exactMatches = requestedProcessId?.let { processId ->
        scopedAssets.filter { asset -> asset.processId == processId }
    }.orEmpty()
    val fallbackMatches = if (exactMatches.isEmpty() && requestedAction != null) {
        scopedAssets.filter { asset -> asset.resolvedProcessAction() == requestedAction }
    } else {
        emptyList()
    }
    return exactMatches.ifEmpty { fallbackMatches }
        .asSequence()
        .map { asset ->
            asset to findPreferredReusableProcessAssetRecord(
                store = store,
                appScope = asset.appScope,
                processAction = asset.resolvedProcessAction(),
                pathIndex = asset.pathIndex
            )
        }
        .filter { (_, record) -> record == null || record.assetState == ProcessAssetState.READY }
        .sortedWith(
            compareByDescending<Pair<ReadyProcessAsset, ReusableProcessAssetRecord?>> { (_, record) ->
                record?.updatedAt ?: Long.MIN_VALUE
            }
                .thenByDescending { (_, record) -> record?.successCount ?: Int.MIN_VALUE }
                .thenByDescending { (_, record) -> record?.revision ?: Int.MIN_VALUE }
                .thenByDescending { (asset, _) -> asset.version }
                .thenByDescending { (asset, _) -> asset.lastDerivedAt }
        )
        .firstOrNull()
        ?.first
}

internal fun buildExistingPageEvidenceBundle(
    pageEvidenceAssets: List<PageEvidenceAsset>,
    appScope: String,
    processId: String
): String? {
    val matchingAssets = pageEvidenceAssets.filter { asset ->
        asset.processId == processId && asset.appScope.equals(appScope, ignoreCase = true)
    }
    return matchingAssets.takeIf { assets -> assets.isNotEmpty() }?.joinToString("\n") { asset ->
        "page_signature=${asset.pageSignature}; verification=${asset.verificationSignals.joinToString(",")}; observations=${asset.observationCount}"
    }
}

private fun isActionableProcessExtractionMaterial(rawMaterial: CanonicalTraceRawMaterial): Boolean {
    val reducedTrace = reduceCanonicalTrace(rawMaterial)
    val evidenceBindings = buildTraceFirstEvidenceBindings(reducedTrace)
    val qualityScore = scoreProcessExtractionMaterial(rawMaterial)
    val distinctStageCount = reducedTrace.steps.mapNotNull(::extractCanonicalStageName).distinct().size
    val actionableBindingCount = evidenceBindings.count { binding ->
        binding.actionType != VisionActionType.WAIT ||
            !binding.locatorHint.isNullOrBlank() ||
            binding.targetX != null ||
            binding.targetY != null
    }
    val pageEvidenceCount = evidenceBindings.mapNotNull { binding -> binding.pageSignature }.distinct().size
    val verificationSignalCount = evidenceBindings.flatMap { binding -> binding.verificationSignals }.distinct().size
    return qualityScore >= 120 && (
        actionableBindingCount >= 2 ||
            (distinctStageCount >= 2 && verificationSignalCount >= 1) ||
            (pageEvidenceCount >= 1 && actionableBindingCount >= 1)
        )
}

private fun scoreProcessExtractionMaterial(rawMaterial: CanonicalTraceRawMaterial): Int {
    val reducedTrace = reduceCanonicalTrace(rawMaterial)
    val evidenceBindings = buildTraceFirstEvidenceBindings(reducedTrace)
    val distinctStageCount = reducedTrace.steps.mapNotNull(::extractCanonicalStageName).distinct().size
    val actionableBindingCount = evidenceBindings.count { binding ->
        binding.actionType != VisionActionType.WAIT ||
            !binding.locatorHint.isNullOrBlank() ||
            binding.targetX != null ||
            binding.targetY != null ||
            !binding.inputText.isNullOrBlank()
    }
    val pageEvidenceCount = evidenceBindings.mapNotNull { binding -> binding.pageSignature }.distinct().size
    val verificationSignalCount = evidenceBindings.flatMap { binding -> binding.verificationSignals }.distinct().size
    val lowSignalPenalty = if (actionableBindingCount == 0 && pageEvidenceCount == 0) 120 else 0
    return distinctStageCount * 25 +
        actionableBindingCount * 30 +
        pageEvidenceCount * 60 +
        verificationSignalCount * 40 +
        reducedTrace.steps.size * 5 -
        lowSignalPenalty
}

private fun buildTraceFirstEvidenceBindings(
    reducedTrace: ReducedCanonicalTrace
): List<ProcessAssetStepBinding> {
    val alignedBindings = alignProcessAssetBindingsToTraceSteps(
        reducedTrace.steps,
        reducedTrace.stepBindings
    )
    return reducedTrace.steps.mapIndexedNotNull { index, step ->
        mergeTraceFirstEvidenceBinding(
            preferredBinding = extractCanonicalProcessAssetStepBinding(step),
            fallbackBinding = alignedBindings.getOrNull(index)
        )
    }.ifEmpty {
        reducedTrace.stepBindings
    }
}

private fun mergeTraceFirstEvidenceBinding(
    preferredBinding: ProcessAssetStepBinding?,
    fallbackBinding: ProcessAssetStepBinding?
): ProcessAssetStepBinding? {
    return preferredBinding ?: fallbackBinding
}

internal fun upsertReadyProcessAsset(
    nextAssets: MutableList<ReadyProcessAsset>,
    candidateAsset: ReadyProcessAsset,
    now: Long
): ReadyProcessAsset {
    val matchingPathIndex = nextAssets.indexOfFirst { asset ->
        asset.appScope.equals(candidateAsset.appScope, ignoreCase = true) &&
            buildReadyProcessLineageKey(asset) == buildReadyProcessLineageKey(candidateAsset)
    }
    val existingAsset = nextAssets.getOrNull(matchingPathIndex)
    if (existingAsset == null) {
        val nextPathIndex = nextAssets.asSequence()
            .filter { asset ->
                asset.appScope.equals(candidateAsset.appScope, ignoreCase = true) &&
                    asset.resolvedProcessAction() == candidateAsset.resolvedProcessAction()
            }
            .map { asset -> asset.pathIndex.coerceAtLeast(1) }
            .maxOrNull()
            ?.plus(1)
            ?: 1
        val storedAsset = candidateAsset.copy(
            pathIndex = nextPathIndex,
            version = 1,
            lastDerivedAt = now
        )
        nextAssets.add(storedAsset)
        return storedAsset
    }
    if (buildReadyProcessFingerprint(existingAsset) == buildReadyProcessFingerprint(candidateAsset)) {
        return existingAsset
    }
    val storedAsset = candidateAsset.copy(
        pathIndex = existingAsset.pathIndex.coerceAtLeast(1),
        version = existingAsset.version + 1,
        lastDerivedAt = now
    )
    nextAssets[matchingPathIndex] = storedAsset
    return storedAsset
}

private fun buildReadyProcessFingerprint(asset: ReadyProcessAsset): String {
    val exemplarFingerprint = asset.exemplarActionSummaries.joinToString("||") { exemplar ->
        listOf(
            exemplar.stageName,
            exemplar.stepType,
            exemplar.actionType.name,
            exemplar.outcomeSignal,
            exemplar.locatorHint,
            exemplar.pageSignature,
            exemplar.note
        ).joinToString("|")
    }
    val semanticAnchorFingerprint = asset.pageSemanticAnchors.joinToString("||") { anchor ->
        listOf(
            anchor.stageName,
            anchor.semanticRole,
            anchor.pageSignature,
            anchor.locatorHints.joinToString(","),
            anchor.verificationSignals.joinToString(","),
            anchor.notes.joinToString(",")
        ).joinToString("|")
    }
    return listOf(
        asset.appScope,
        asset.resolvedProcessAction(),
        asset.domain,
        asset.stages.joinToString(","),
        asset.acceptanceCriteria.joinToString(","),
        exemplarFingerprint,
        semanticAnchorFingerprint
    ).joinToString("###")
}

private fun buildReadyProcessLineageKey(asset: ReadyProcessAsset): String {
    val normalizedStages = buildReadyProcessLineageStages(asset).map { stage ->
        stage.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }.filter { stage -> stage.isNotBlank() }
    return listOf(
        asset.resolvedProcessAction(),
        asset.appScope.trim().lowercase(Locale.US),
        normalizedStages.joinToString("->")
    ).joinToString("###")
}

private fun buildReadyProcessLineageStages(asset: ReadyProcessAsset): List<String> {
    if (asset.stages.isNotEmpty()) {
        return asset.stages
    }

    val exemplarStages = asset.exemplarActionSummaries.mapNotNull { exemplar ->
        exemplar.stageName?.trim()?.takeIf { value -> value.isNotBlank() }
            ?: exemplar.stepType.trim().takeIf { value -> value.isNotBlank() }
            ?: exemplar.locatorHint?.trim()?.takeIf { value -> value.isNotBlank() }
    }
    if (exemplarStages.isNotEmpty()) {
        return exemplarStages
    }

    val semanticStages = asset.pageSemanticAnchors.mapNotNull { anchor ->
        anchor.stageName?.trim()?.takeIf { value -> value.isNotBlank() }
            ?: anchor.semanticRole.trim().takeIf { value -> value.isNotBlank() }
            ?: anchor.locatorHints.firstOrNull()?.trim()?.takeIf { value -> value.isNotBlank() }
    }
    if (semanticStages.isNotEmpty()) {
        return semanticStages
    }

    return emptyList()
}

internal fun upsertPageEvidenceAssets(
    nextPageEvidenceAssets: MutableList<PageEvidenceAsset>,
    materials: List<CanonicalTraceRawMaterial>,
    appScope: String,
    processId: String,
    now: Long
) {
    val evidenceCandidates = materials
        .flatMap { rawMaterial ->
            val reducedTrace = reduceCanonicalTrace(rawMaterial)
            val stepBindings = reducedTrace.stepBindings.ifEmpty {
                reducedTrace.steps.mapNotNull(::extractCanonicalProcessAssetStepBinding)
            }
            stepBindings.mapNotNull { binding ->
                val pageSignature = binding.pageSignature?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
                rawMaterial to binding.copy(pageSignature = pageSignature)
            }
        }
        .groupBy { (_, binding) -> binding.pageSignature!! }

    evidenceCandidates.forEach { (pageSignature, evidenceGroup) ->
        upsertPageEvidenceObservation(
            nextPageEvidenceAssets = nextPageEvidenceAssets,
            observation = PageEvidenceObservation(
                appScope = appScope,
                processId = processId,
                pageSignature = pageSignature,
                verificationSignals = evidenceGroup.flatMap { (_, binding) -> binding.verificationSignals }.distinct(),
                locatorHints = evidenceGroup.mapNotNull { (_, binding) -> binding.locatorHintsOrSingle() }.flatten().distinct(),
                observationCount = evidenceGroup.size,
                lineageSourceTraceIds = evidenceGroup.map { (rawMaterial, _) -> rawMaterial.traceId }.distinct(),
                observedAt = evidenceGroup.maxOf { (rawMaterial, _) -> rawMaterial.createdAt }
            ),
            now = now
        )
    }
}

private fun deriveShortcutStabilityScore(
    entry: ProcessAssetEntry,
    pageEvidenceAsset: PageEvidenceAsset? = null
): Double {
    val stageCount = countBusinessStages(entry.businessStagesJson)
    val stageScore = when {
        stageCount >= 2 -> 0.8
        stageCount == 1 -> 0.7
        else -> 0.5
    }
    val acceptanceScore = if (entry.businessAcceptanceCriteria.isNotEmpty()) 0.1 else 0.0
    val evidenceScore = when {
        pageEvidenceAsset == null -> 0.0
        pageEvidenceAsset.observationCount >= 3 -> 0.1
        pageEvidenceAsset.observationCount >= 2 -> 0.05
        else -> 0.02
    }
    return (stageScore + acceptanceScore + evidenceScore).coerceAtMost(0.95)
}

private fun resolveShortcutProjectionProcessAction(entry: ProcessAssetEntry): String {
    return inferCanonicalProcessAction(
        processId = entry.processScope,
        objective = entry.semanticDescription.ifBlank { entry.taskExample }.ifBlank { entry.assetName },
        domain = entry.domain,
        actionHint = entry.businessProcessName.ifBlank { entry.assetName }
    )
}

private fun resolveShortcutProjectionPathIndex(entry: ProcessAssetEntry): Int {
    return extractShortcutProjectionPathIndex(entry.assetName)?.coerceAtLeast(1) ?: 1
}

private fun extractShortcutProjectionPathIndex(assetName: String): Int? {
    val match = Regex("""(?:^|[^a-z0-9])path(\d+)(?:[^a-z0-9]|$)""", RegexOption.IGNORE_CASE)
        .find(assetName)
        ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
}

private fun extractPrimaryShortcutScreenSignature(entry: ProcessAssetEntry): String? {
    return Regex("\"([^\"]+)\"")
        .find(entry.businessStagesJson)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { value -> value.isNotBlank() }
}

private fun countBusinessStages(stagesJson: String): Int {
    return Regex("\"([^\"]+)\"")
        .findAll(stagesJson)
        .count()
}

private fun ProcessAssetStepBinding.locatorHintsOrSingle(): List<String> {
    return locatorHint?.takeIf { value -> value.isNotBlank() }?.let(::listOf) ?: emptyList()
}