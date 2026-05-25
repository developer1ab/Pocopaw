package com.atombits.pocopaw

object ProcessExtractionWritebackBridge {

    fun buildCanonicalTraceMaterial(
        boundaryPacket: TaskExecutionBoundaryPacket,
        executionResult: ExecutionResult,
        executionTrace: ExecutionTrace,
        now: Long = System.currentTimeMillis()
    ): CanonicalTraceRawMaterial? {
        if (executionResult.lifecycleStatus != ExecutionLifecycleStatus.COMPLETED) {
            return null
        }

        val processId = sequenceOf(
            boundaryPacket.processId,
            executionResult.selectedProcessId,
            executionTrace.processId
        ).mapNotNull(::sanitizeCanonicalProcessId)
            .firstOrNull()
            ?: deriveCanonicalProcessScope(
                rawProcessId = null,
                objective = boundaryPacket.objectiveSummary,
                actionHint = boundaryPacket.actionCode.takeUnless { actionCode -> actionCode == ActionCode.UNKNOWN }?.wireName,
                selectedToolId = executionResult.selectedToolId
                    ?: boundaryPacket.capabilityId
                    ?: executionTrace.selectedToolId
            )
            ?: return null

        val structuredBindingsByStep = executionTrace.steps.map(::extractStructuredStepBinding)
        val payloadSteps = executionTrace.steps.mapIndexed { index, step ->
            val baseStep = listOf(
                step.stepType,
                step.groundingMode,
                step.expectedOutcome,
                step.note
            ).filterNot { it.isNullOrBlank() }.joinToString(" | ")
            structuredBindingsByStep[index]?.let { binding ->
                serializeCanonicalTraceLineWithBinding(baseStep, binding)
            } ?: baseStep
        }
        val rawMaterial = CanonicalTraceRawMaterial(
            traceId = executionTrace.traceId,
            candidateId = executionResult.candidateId ?: executionTrace.candidateId,
            selectedToolId = executionResult.selectedToolId
                ?: boundaryPacket.capabilityId
                ?: executionTrace.selectedToolId,
            processId = processId,
            objective = boundaryPacket.objectiveSummary,
            lifecycleStatus = executionResult.lifecycleStatus,
            steps = payloadSteps,
            createdAt = now,
            slotEvidenceSnapshot = boundaryPacket.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")
        )
        return rawMaterial
    }

    private fun extractStructuredStepBinding(step: ExecutionTraceStep): ProcessAssetStepBinding? {
        if (step.stepType.equals("START", ignoreCase = true) || step.stepType.equals("VERIFY", ignoreCase = true)) {
            return null
        }
        val hasStructuredBinding = step.actionType != null ||
            step.locatorHint != null ||
            step.targetX != null ||
            step.targetY != null ||
            step.inputText != null ||
            step.swipeFromX != null ||
            step.swipeFromY != null ||
            step.swipeToX != null ||
            step.swipeToY != null ||
            step.actionDurationMs != null ||
            step.pageSignature != null ||
            step.verificationSignals.isNotEmpty()
        if (!hasStructuredBinding) {
            return null
        }
        return ProcessAssetStepBinding(
            stepType = step.stepType,
            actionType = step.actionType ?: VisionActionType.TAP,
            locatorHint = step.locatorHint,
            targetX = step.targetX,
            targetY = step.targetY,
            inputText = step.inputText,
            swipeFromX = step.swipeFromX,
            swipeFromY = step.swipeFromY,
            swipeToX = step.swipeToX,
            swipeToY = step.swipeToY,
            actionDurationMs = step.actionDurationMs,
            verificationSignals = step.verificationSignals,
            pageSignature = step.pageSignature,
            note = step.note
        )
    }
}