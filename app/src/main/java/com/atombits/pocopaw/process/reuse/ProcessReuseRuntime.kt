package com.atombits.pocopaw.process.reuse

import com.atombits.pocopaw.TaskExecutionBoundaryPacket
import com.atombits.pocopaw.IntentCandidate
import com.atombits.pocopaw.PrototypeStoreData

internal data class ProcessReuseResolution(
    val taskIntent: StructuredTaskIntent,
    val guidanceLayer: ProcessGuidanceLayer,
    val candidateContext: CandidateProcessReferenceContext,
    val preferredReference: CandidateProcessReference? = null
) {
    companion object {
        fun fromBundle(bundle: ExecutionReferenceBundle): ProcessReuseResolution {
            return ProcessReuseResolution(
                taskIntent = bundle.taskIntent,
                guidanceLayer = bundle.guidanceLayer,
                candidateContext = bundle.candidateContext,
                preferredReference = bundle.preferredReference
            )
        }
    }
}

internal object ProcessReuseRuntime {
    private val referencePlanningPipeline = ReferencePlanningPipeline()

    fun resolve(
        store: PrototypeStoreData,
        activeCandidate: IntentCandidate?,
        boundaryPacket: TaskExecutionBoundaryPacket?,
        previousContext: CandidateProcessReferenceContext? = null,
        selectionResolver: ProcessReferenceSelectionResolver = SemanticProcessReferenceSelectionResolver(),
        now: Long = System.currentTimeMillis()
    ): ProcessReuseResolution? {
        return referencePlanningPipeline.resolve(
            store = store,
            activeCandidate = activeCandidate,
            boundaryPacket = boundaryPacket,
            previousContext = previousContext,
            selectionResolver = selectionResolver,
            now = now
        )?.let(ProcessReuseResolution.Companion::fromBundle)
    }

    fun buildGuidanceLayer(
        taskIntent: StructuredTaskIntent?,
        boundaryPacket: TaskExecutionBoundaryPacket? = null,
        now: Long = System.currentTimeMillis()
    ): ProcessGuidanceLayer? {
        return ExecutionGuidanceAssembler.assemble(
            taskIntent = taskIntent,
            boundaryPacket = boundaryPacket,
            now = now
        )
    }

    fun resolvePreferredReference(
        references: List<CandidateProcessReference>,
        boundaryPacket: TaskExecutionBoundaryPacket? = null,
        selectedReferenceId: String? = null
    ): CandidateProcessReference? {
        return ReferenceSelector.resolvePreferredReference(
            references = references,
            boundaryPacket = boundaryPacket,
            selectedReferenceId = selectedReferenceId
        )
    }
}
