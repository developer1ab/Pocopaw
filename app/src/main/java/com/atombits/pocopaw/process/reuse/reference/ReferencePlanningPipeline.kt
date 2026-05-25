package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.IntentCandidate
import com.atombits.pocopaw.PrototypeStoreData

internal class ReferencePlanningPipeline(
    private val catalogRepository: ReferenceCatalogRepository = PrototypeStoreReferenceCatalogRepository
) {
    fun resolve(
        store: PrototypeStoreData,
        activeCandidate: IntentCandidate?,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        previousContext: CandidateProcessReferenceContext? = null,
        selectionResolver: ProcessReferenceSelectionResolver = SemanticProcessReferenceSelectionResolver(),
        now: Long = System.currentTimeMillis()
    ): ExecutionReferenceBundle? {
        val taskIntent = ReferenceIntentAssembler.assemble(
            store = store,
            activeCandidate = activeCandidate,
            boundaryPacket = boundaryPacket
        ) ?: return null
        val guidanceLayer = ExecutionGuidanceAssembler.assemble(
            taskIntent = taskIntent,
            boundaryPacket = boundaryPacket,
            now = now
        ) ?: return null
        val rankedCandidates = ReferenceScorer.rank(
            catalogRows = catalogRepository.listCatalogRows(store, maxProcessCatalogRows),
            taskIntent = taskIntent,
            guidanceLayer = guidanceLayer
        )
        return ReferenceSelector.select(
            taskIntent = taskIntent,
            guidanceLayer = guidanceLayer,
            rankedCandidates = rankedCandidates,
            boundaryPacket = boundaryPacket,
            previousContext = previousContext,
            selectionResolver = selectionResolver,
            now = now
        )
    }
}
