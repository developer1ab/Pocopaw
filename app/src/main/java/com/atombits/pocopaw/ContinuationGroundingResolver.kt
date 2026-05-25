package com.atombits.pocopaw

import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState
import com.atombits.pocopaw.process.runtime.ProcessRuntimeStatus
import java.util.Locale

internal data class ContinuationObjectEvidence(
    val objectId: String,
    val objectType: String,
    val displayLabel: String,
    val appScope: String? = null,
    val processId: String? = null,
    val evidenceSummary: String? = null,
    val lastSeenAt: Long = System.currentTimeMillis()
)

internal data class ContinuationCheckpointEvidence(
    val checkpointId: String,
    val stageLabel: String,
    val appScope: String? = null,
    val pageSignature: String? = null,
    val summary: String,
    val recoverable: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

internal data class ContinuationTaskEvidence(
    val activeTaskId: String? = null,
    val summary: String,
    val targetObject: String? = null,
    val preferredAppScope: String? = null,
    val relatedObjects: List<ContinuationObjectEvidence> = emptyList(),
    val checkpoint: ContinuationCheckpointEvidence? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

internal object ContinuationGroundingResolver {

    internal fun prefersExplicitNewTaskOverContinuation(
        userMessage: String,
        currentState: LocalConversationState,
        semanticIntentState: SemanticIntentState? = currentState.currentSemanticIntentState
    ): Boolean {
        val normalizedMessage = normalizeContinuationText(userMessage)
        if (normalizedMessage.isBlank()) {
            return false
        }

        val explicitPlatformTokens = extractExplicitPlatformTokens(normalizedMessage)
        val explicitAnchor = extractExplicitTaskAnchor(normalizedMessage)
        if (explicitPlatformTokens.isEmpty() && explicitAnchor == null) {
            return false
        }

        val contextTerms = buildContinuationContextTerms(currentState, semanticIntentState)
        val contextPlatformTokens = buildContinuationPlatformTokens(currentState, semanticIntentState)
        val platformOverride = explicitPlatformTokens.isNotEmpty() &&
            contextPlatformTokens.isNotEmpty() &&
            explicitPlatformTokens.intersect(contextPlatformTokens).isEmpty()
        val anchorOverride = explicitAnchor != null &&
            contextTerms.isNotEmpty() &&
            contextTerms.none { term ->
                term.length >= 2 && (explicitAnchor.contains(term) || normalizedMessage.contains(term))
            }
        return platformOverride || anchorOverride
    }

    fun buildTaskContinuationEvidence(
        store: PrototypeStoreData,
        semanticIntentState: SemanticIntentState? = store.resolveCurrentState().currentSemanticIntentState,
        now: Long = System.currentTimeMillis()
    ): ContinuationTaskEvidence? {
        val memoryState = store.resolveMemoryState()
        val activeTopicRecord = memoryState?.topicContextStore?.activeTopicRecord
        val currentState = store.resolveCurrentState()
        val currentTaskRecord = currentState.currentTaskRecord
        val currentTaskDraft = currentState.currentTaskDraft
        val runtime = store.resolveCurrentProcessRuntime()
        val recovery = store.resolvePendingProcessRecoveryContext()
        val review = store.resolveLatestCompletedProcessReviewContext()
        val activeIntent = semanticIntentState?.candidateIntents?.firstOrNull { candidate ->
            candidate.intentId == semanticIntentState.activeIntentId
        } ?: semanticIntentState?.candidateIntents?.firstOrNull()
        val currentTaskDraftCapabilityId = currentTaskDraft?.capabilityId?.trim()?.takeIf { value -> value.isNotBlank() }

        val summary = recovery?.objective?.trim().orEmpty()
            .ifBlank { currentTaskRecord?.displayTarget().orEmpty() }
            .ifBlank { currentTaskDraft?.displayTarget().orEmpty() }
            .ifBlank { activeTopicRecord?.summaryText.orEmpty() }
            .ifBlank { review?.finalUserSummary?.trim().orEmpty() }
            .ifBlank { runtime?.finalUserSummary?.trim().orEmpty() }
            .ifBlank { activeIntent?.focusedObject?.trim().orEmpty() }
            .ifBlank { activeIntent?.anchorObject?.trim().orEmpty() }
        if (summary.isBlank()) {
            return null
        }

        val preferredAppScope = extractCanonicalAppScope(recovery?.selectedToolId)
            ?: currentTaskRecord?.resolvePreferredAppScope()
            ?: currentTaskDraftCapabilityId?.let(::extractCanonicalAppScope)
            ?: currentTaskDraftCapabilityId
            ?: activeTopicRecord?.preferredAppScope
            ?: resolveReviewAppScope(review)
        val processId = recovery?.selectedProcessId
            ?: currentTaskRecord?.processId
            ?: currentTaskDraft?.processId
            ?: activeTopicRecord?.action

        val relatedObjects = (listOfNotNull(
            currentTaskRecord?.let { taskRecord ->
                ContinuationObjectEvidence(
                    objectId = taskRecord.targetKey,
                    objectType = taskRecord.targetType.wireName,
                    displayLabel = taskRecord.displayTarget(),
                    appScope = preferredAppScope,
                    processId = processId,
                    evidenceSummary = taskRecord.reasonSummary,
                    lastSeenAt = taskRecord.updatedAt
                )
            },
            currentTaskDraft?.let { taskDraft ->
                val displayLabel = taskDraft.displayTarget()
                val objectId = taskDraft.targetKey?.trim()?.takeIf { value -> value.isNotBlank() } ?: displayLabel
                if (displayLabel == null || objectId == null) {
                    null
                } else {
                    ContinuationObjectEvidence(
                        objectId = objectId,
                        objectType = taskDraft.targetType?.wireName ?: "task_draft",
                        displayLabel = displayLabel,
                        appScope = preferredAppScope,
                        processId = processId,
                        evidenceSummary = taskDraft.reasonSummary,
                        lastSeenAt = now
                    )
                }
            },
            activeTopicRecord?.let { topicRecord ->
                ContinuationObjectEvidence(
                    objectId = topicRecord.topicId,
                    objectType = "topic_context",
                    displayLabel = topicRecord.focusedObject ?: topicRecord.anchorObject ?: topicRecord.summaryText,
                    appScope = topicRecord.preferredAppScope ?: preferredAppScope,
                    processId = topicRecord.action ?: processId,
                    evidenceSummary = topicRecord.summaryText,
                    lastSeenAt = topicRecord.lastTouchedAt
                )
            },
            activeIntent?.let { intent ->
                ContinuationObjectEvidence(
                    objectId = intent.intentId,
                    objectType = "semantic_intent",
                    displayLabel = intent.focusedObject.ifBlank { intent.anchorObject },
                    appScope = preferredAppScope,
                    processId = processId,
                    evidenceSummary = intent.reasonSummary,
                    lastSeenAt = now
                )
            },
            store.resolveTaskFirstCandidate()
                ?.takeIf {
                    currentTaskRecord == null && currentTaskDraft == null && activeIntent == null
                }
                ?.let { candidate ->
                ContinuationObjectEvidence(
                    objectId = candidate.id,
                    objectType = "dialogue_candidate",
                    displayLabel = candidate.focusedObject.ifBlank { candidate.anchorObject },
                    appScope = preferredAppScope,
                    processId = processId,
                    evidenceSummary = candidate.evidence,
                    lastSeenAt = now
                )
            }
        ) + buildMemoryTaskObjects(
            memoryState = memoryState,
            preferredAppScope = preferredAppScope,
            processId = processId
        )).distinctBy { item -> item.objectId }

        val checkpoint = buildCheckpointEvidence(
            taskRecord = currentTaskRecord,
            runtime = runtime,
            recovery = recovery,
            review = review,
            preferredAppScope = preferredAppScope,
            now = now
        )

        return ContinuationTaskEvidence(
            activeTaskId = recovery?.recoveryId
                ?: runtime?.taskContextId
                ?: runtime?.runtimeId
                ?: currentTaskRecord?.taskId
                ?: activeTopicRecord?.topicId
                ?: review?.reviewId,
            summary = summary,
            targetObject = relatedObjects.firstOrNull()?.displayLabel,
            preferredAppScope = preferredAppScope,
            relatedObjects = relatedObjects,
            checkpoint = checkpoint,
            updatedAt = now
        )
    }

    fun resolve(
        userMessage: String,
        store: PrototypeStoreData,
        semanticIntentState: SemanticIntentState? = store.resolveCurrentState().currentSemanticIntentState,
        now: Long = System.currentTimeMillis()
    ): ContinuationGroundingResult {
        val currentState = store.resolveCurrentState()
        if (prefersExplicitNewTaskOverContinuation(userMessage, currentState, semanticIntentState)) {
            return ContinuationGroundingResult(
                mode = ContinuationMode.NEW_TASK,
                reason = "latest_user_message_explicitly_overrides_active_context",
                confidence = 0.91
            )
        }
        val taskEvidence = buildTaskContinuationEvidence(store, semanticIntentState, now)
        val memoryObjectMatch = resolveRelevantObjectMatch(normalizedMessage = normalizeContinuationText(userMessage), relatedObjects = taskEvidence?.relatedObjects.orEmpty())
        val currentTaskRecord = currentState.currentTaskRecord
        val runtime = store.resolveCurrentProcessRuntime()
        val recovery = currentState.pendingExecutionRecovery
        val review = store.resolveLatestCompletedProcessReviewContext()
        val activeIntent = semanticIntentState?.candidateIntents?.firstOrNull { candidate ->
            candidate.intentId == semanticIntentState.activeIntentId
        } ?: semanticIntentState?.candidateIntents?.firstOrNull()
        val continuationHint = activeIntent?.continuationHint?.trim()?.lowercase(Locale.US)
        val normalizedMessage = normalizeContinuationText(userMessage)
        val inferredRequestedAction = inferRequestedAction(normalizedMessage)
        val continuationRequested = looksLikeContinuationRequest(normalizedMessage) ||
            looksLikeHistoricalReference(normalizedMessage) ||
            continuationHint in continuationActiveHints ||
            continuationHint in continuationRepairHints ||
            continuationHint in continuationObjectHints ||
            continuationHint in continuationCheckpointHints

        val intendedAction = inferredRequestedAction
            ?: currentTaskRecord?.actionCode?.wireName
            ?: activeIntent?.canonicalAction?.name?.lowercase(Locale.US)
            ?: activeIntent?.rawActionLabel?.takeIf { value -> value.isNotBlank() }

        if (!continuationRequested) {
            return ContinuationGroundingResult(
                mode = ContinuationMode.NEW_TASK,
                reason = "latest_user_message_does_not_request_continuation",
                confidence = 0.82
            )
        }

        recovery?.let { pendingRecovery ->
            if (looksLikeRepairRequest(normalizedMessage) || continuationHint in continuationRepairHints || taskEvidence != null) {
                return ContinuationGroundingResult(
                    mode = ContinuationMode.REPAIR_PREVIOUS_FAILURE,
                    targetTaskContextId = taskEvidence?.activeTaskId,
                    targetObjectId = taskEvidence?.relatedObjects?.firstOrNull()?.objectId,
                    targetObjectType = taskEvidence?.relatedObjects?.firstOrNull()?.objectType,
                    targetAppScope = taskEvidence?.preferredAppScope ?: extractCanonicalAppScope(pendingRecovery.selectedToolId),
                    targetCheckpointId = taskEvidence?.checkpoint?.checkpointId ?: pendingRecovery.recoveryId,
                    intendedAction = intendedAction,
                    reason = "pending_process_recovery_context_is_active",
                    confidence = 0.93
                )
            }
        }

        if (taskEvidence != null && taskEvidence.checkpoint != null && hasCompletedCheckpointEvidence(currentTaskRecord, runtime, recovery, review) && (
                !looksLikeFollowUpAction(normalizedMessage) && (
                    continuationRequested ||
                    looksLikeCheckpointResumeRequest(normalizedMessage) ||
                    continuationHint in continuationCheckpointHints
                    )
                )) {
            val checkpoint = taskEvidence.checkpoint
            return ContinuationGroundingResult(
                mode = ContinuationMode.RESUME_FROM_CHECKPOINT,
                targetTaskContextId = taskEvidence.activeTaskId,
                targetObjectId = memoryObjectMatch?.objectId ?: taskEvidence.relatedObjects.firstOrNull()?.objectId,
                targetObjectType = memoryObjectMatch?.objectType ?: taskEvidence.relatedObjects.firstOrNull()?.objectType,
                targetAppScope = memoryObjectMatch?.appScope ?: taskEvidence.preferredAppScope,
                targetCheckpointId = checkpoint.checkpointId,
                intendedAction = intendedAction,
                reason = "completed_checkpoint_is_available",
                confidence = 0.84,
                requiresClarification = taskEvidence.preferredAppScope.isNullOrBlank()
            )
        }

        if (memoryObjectMatch != null && (
                looksLikeHistoricalReference(normalizedMessage) ||
                    looksLikeFollowUpAction(normalizedMessage) ||
                    continuationHint in continuationObjectHints
                )) {
            return ContinuationGroundingResult(
                mode = ContinuationMode.FOLLOW_UP_ON_PREVIOUS_OBJECT,
                targetTaskContextId = taskEvidence?.activeTaskId,
                targetObjectId = memoryObjectMatch.objectId,
                targetObjectType = memoryObjectMatch.objectType,
                targetAppScope = memoryObjectMatch.appScope
                    ?: taskEvidence?.preferredAppScope,
                targetCheckpointId = taskEvidence?.checkpoint?.checkpointId,
                intendedAction = intendedAction,
                reason = "matched_previous_object_from_memory",
                confidence = 0.89,
                requiresClarification = memoryObjectMatch.appScope.isNullOrBlank() && taskEvidence?.preferredAppScope.isNullOrBlank()
            )
        }

        if (taskEvidence != null) {
            return ContinuationGroundingResult(
                mode = ContinuationMode.CONTINUE_ACTIVE_TASK,
                targetTaskContextId = taskEvidence.activeTaskId,
                targetObjectId = taskEvidence.relatedObjects.firstOrNull()?.objectId,
                targetObjectType = taskEvidence.relatedObjects.firstOrNull()?.objectType,
                targetAppScope = taskEvidence.preferredAppScope,
                targetCheckpointId = taskEvidence.checkpoint?.checkpointId,
                intendedAction = intendedAction,
                reason = "active_task_context_is_available",
                confidence = 0.86,
                requiresClarification = taskEvidence.checkpoint == null
            )
        }

        return ContinuationGroundingResult(
            mode = ContinuationMode.UNCLEAR,
            intendedAction = intendedAction,
            reason = "continuation_requested_but_no_active_task_context_found",
            confidence = 0.28,
            requiresClarification = true
        )
    }

    fun buildPromptContext(
        userMessage: String,
        store: PrototypeStoreData,
        semanticIntentState: SemanticIntentState? = store.resolveCurrentState().currentSemanticIntentState,
        now: Long = System.currentTimeMillis()
    ): String? {
        val taskEvidence = buildTaskContinuationEvidence(store, semanticIntentState, now)
        val groundingResult = resolve(userMessage, store, semanticIntentState, now)
        if (groundingResult.mode == ContinuationMode.NEW_TASK) {
            return null
        }
        return buildString {
            appendLine("Local continuation evidence (non-authoritative):")
            taskEvidence?.let { context ->
                append("- continuation_evidence")
                context.activeTaskId?.takeIf { value -> value.isNotBlank() }?.let { activeTaskId ->
                    append("; active_task_id=$activeTaskId")
                }
                append("; summary=${context.summary}; preferred_app_scope=${context.preferredAppScope ?: "null"}")
                context.targetObject?.takeIf { value -> value.isNotBlank() }?.let { targetObject ->
                    append("; target_object=$targetObject")
                }
                context.checkpoint?.let { checkpoint ->
                    append("; checkpoint=${checkpoint.checkpointId}; checkpoint_stage=${checkpoint.stageLabel}; checkpoint_summary=${checkpoint.summary}; checkpoint_recoverable=${checkpoint.recoverable}")
                }
                appendLine()
            }
            append("- continuation_signal=${toPromptSignal(groundingResult)}; confidence=${"%.2f".format(Locale.US, groundingResult.confidence)}")
            groundingResult.targetObjectId?.let { objectId -> append("; matched_object_id=$objectId") }
            groundingResult.targetObjectType?.let { objectType -> append("; matched_object_type=$objectType") }
            groundingResult.targetAppScope?.let { appScope -> append("; target_app_scope=$appScope") }
            groundingResult.targetCheckpointId?.let { checkpointId -> append("; target_checkpoint_id=$checkpointId") }
            groundingResult.intendedAction?.let { action -> append("; intended_action=$action") }
            append("; requires_clarification=${groundingResult.requiresClarification}")
            appendLine()
            appendLine("Prompt rule:")
            append("- Treat local continuation evidence as hints only. Use dialogue history and the latest user message as authority; if the target task, object, or checkpoint is not uniquely clear, ask for clarification instead of assuming the current active task.")
        }.trim()
    }

    private fun buildCheckpointEvidence(
        taskRecord: TaskRecord?,
        runtime: ProcessRuntimeState?,
        recovery: ProcessRecoveryContext?,
        review: com.atombits.pocopaw.process.runtime.ProcessReviewContext?,
        preferredAppScope: String?,
        now: Long
    ): ContinuationCheckpointEvidence? {
        recovery?.let {
            return ContinuationCheckpointEvidence(
                checkpointId = it.recoveryId,
                stageLabel = ProcessRuntimeStatus.WAITING_GUIDANCE.name,
                appScope = preferredAppScope ?: extractCanonicalAppScope(it.selectedToolId),
                summary = it.blockedContext,
                recoverable = true,
                createdAt = it.createdAt
            )
        }
        runtime?.let {
            return ContinuationCheckpointEvidence(
                checkpointId = it.taskContextId ?: it.runtimeId,
                stageLabel = it.status.name,
                appScope = preferredAppScope,
                summary = it.blockedContext?.takeIf { value -> value.isNotBlank() }
                    ?: it.finalUserSummary?.takeIf { value -> value.isNotBlank() }
                    ?: taskRecord?.displayTarget()?.takeIf { value -> value.isNotBlank() }
                    ?: "runtime_checkpoint",
                recoverable = it.status in setOf(
                    ProcessRuntimeStatus.READY,
                    ProcessRuntimeStatus.RUNNING,
                    ProcessRuntimeStatus.WAITING_GUIDANCE
                ),
                createdAt = it.updatedAt
            )
        }
        review?.let {
            return ContinuationCheckpointEvidence(
                checkpointId = it.reviewId,
                stageLabel = "REVIEWED",
                appScope = preferredAppScope,
                summary = it.finalUserSummary?.takeIf { value -> value.isNotBlank() }
                    ?: it.verificationSummary?.takeIf { value -> value.isNotBlank() }
                    ?: "completed_task",
                recoverable = false,
                createdAt = it.reviewedAt
            )
        }
        taskRecord?.let {
            return ContinuationCheckpointEvidence(
                checkpointId = it.checkpointId ?: it.taskId,
                stageLabel = it.phase.name,
                appScope = preferredAppScope ?: it.resolvePreferredAppScope(),
                summary = it.displayTarget(),
                recoverable = it.phase in setOf(TaskPhase.PREPARING, TaskPhase.EXECUTING),
                createdAt = it.updatedAt.takeIf { value -> value > 0L } ?: now
            )
        }
        return null
    }

    private fun buildMemoryTaskObjects(
        memoryState: MemoryState?,
        preferredAppScope: String?,
        processId: String?
    ): List<ContinuationObjectEvidence> {
        val currentMemoryState = memoryState ?: return emptyList()
        return (currentMemoryState.activeGroundingStore.map { record ->
            ContinuationObjectEvidence(
                objectId = record.candidateId,
                objectType = "memory_grounding",
                displayLabel = record.focusedObject.ifBlank { record.anchorObject },
                appScope = preferredAppScope,
                processId = processId,
                evidenceSummary = "anchor=${record.anchorObject}; action=${record.action}",
                lastSeenAt = record.lastObservedAt
            )
        } + currentMemoryState.continuationStore.map { record ->
            ContinuationObjectEvidence(
                objectId = record.candidateId,
                objectType = "memory_continuation",
                displayLabel = record.focusedObject.ifBlank { record.anchorObject },
                appScope = preferredAppScope,
                processId = processId,
                evidenceSummary = "anchor=${record.anchorObject}; action=${record.action}",
                lastSeenAt = record.lastObservedAt
            )
        } + currentMemoryState.topicContextStore.silentTopicRecords.map { record ->
            ContinuationObjectEvidence(
                objectId = record.topicId,
                objectType = "silent_topic_context",
                displayLabel = record.focusedObject ?: record.anchorObject ?: record.summaryText,
                appScope = record.preferredAppScope ?: preferredAppScope,
                processId = record.action ?: processId,
                evidenceSummary = record.summaryText,
                lastSeenAt = record.lastTouchedAt
            )
        })
            .sortedByDescending { item -> item.lastSeenAt }
            .distinctBy { item -> item.objectId }
            .take(4)
    }

    private fun resolveRelevantObjectMatch(
        normalizedMessage: String,
        relatedObjects: List<ContinuationObjectEvidence>
    ): ContinuationObjectEvidence? {
        return relatedObjects
            .filter { objectRef -> matchesExplicitObjectReference(normalizedMessage, objectRef) }
            .sortedWith(
                compareByDescending<ContinuationObjectEvidence> { objectRef ->
                    normalizeContinuationText(objectRef.displayLabel).length
                }.thenByDescending { objectRef -> objectRef.lastSeenAt }
            )
            .firstOrNull()
    }

    private fun matchesExplicitObjectReference(
        normalizedMessage: String,
        objectRef: ContinuationObjectEvidence
    ): Boolean {
        val candidateTerms = buildList {
            add(objectRef.displayLabel)
            objectRef.evidenceSummary?.let(::add)
        }.flatMap(::extractReferenceTerms)
            .distinct()
        return candidateTerms.any { term ->
            term.length >= 2 && normalizedMessage.contains(term)
        }
    }

    private fun extractReferenceTerms(value: String): List<String> {
        val normalized = normalizeContinuationText(value)
        if (normalized.isBlank()) {
            return emptyList()
        }
        val tokenized = normalized.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { token -> token.trim() }
            .filter { token -> token.length >= 2 }
        return (tokenized + normalized.takeIf { it.length >= 2 }).filterNotNull().distinct()
    }

    private fun resolveReviewAppScope(review: ProcessReviewContext?): String? {
        if (review == null) {
            return null
        }
        return sequenceOf(
            review.processAssetName,
            review.processAssetEntryId,
            review.verificationSummary
        ).mapNotNull { value -> extractCanonicalAppScope(value) }
            .firstOrNull()
    }

    private fun hasCompletedCheckpointEvidence(
        taskRecord: TaskRecord?,
        runtime: ProcessRuntimeState?,
        recovery: ProcessRecoveryContext?,
        review: ProcessReviewContext?
    ): Boolean {
        if (recovery != null) {
            return false
        }
        if (runtime != null) {
            return runtime.status == ProcessRuntimeStatus.COMPLETED
        }
        if (review != null) {
            return true
        }
        return taskRecord?.phase == TaskPhase.LEARNING
    }

    private fun looksLikeContinuationRequest(normalizedMessage: String): Boolean {
        return continuationCueTokens.any { token -> normalizedMessage.contains(token) }
    }

    private fun looksLikeRepairRequest(normalizedMessage: String): Boolean {
        return repairCueTokens.any { token -> normalizedMessage.contains(token) }
    }

    private fun looksLikeHistoricalReference(normalizedMessage: String): Boolean {
        return historicalReferenceTokens.any { token -> normalizedMessage.contains(token) }
    }

    private fun looksLikeFollowUpAction(normalizedMessage: String): Boolean {
        return followUpActionTokens.any { token -> normalizedMessage.contains(token) }
    }

    private fun looksLikeCheckpointResumeRequest(normalizedMessage: String): Boolean {
        return checkpointResumeTokens.any { token -> normalizedMessage.contains(token) }
    }

    private fun inferRequestedAction(normalizedMessage: String): String? {
        return when {
            normalizedMessage.contains("退") || normalizedMessage.contains("退款") || normalizedMessage.contains("退掉") -> "return"
            normalizedMessage.contains("取消") -> "cancel"
            normalizedMessage.contains("查看") || normalizedMessage.contains("看看") -> "view"
            normalizedMessage.contains("继续") || normalizedMessage.contains("接着") -> "continue"
            else -> null
        }
    }

    private fun toPromptSignal(groundingResult: ContinuationGroundingResult): String {
        return when (groundingResult.mode) {
            ContinuationMode.NEW_TASK -> "new_task"
            ContinuationMode.REPAIR_PREVIOUS_FAILURE -> "repair_previous_failure"
            ContinuationMode.RESUME_FROM_CHECKPOINT -> "resume_from_checkpoint"
            ContinuationMode.FOLLOW_UP_ON_PREVIOUS_OBJECT -> "follow_up_previous_object"
            ContinuationMode.CONTINUE_ACTIVE_TASK -> "possible_active_task_continuation"
            ContinuationMode.UNCLEAR -> "ambiguous_continuation"
        }
    }

    private fun normalizeContinuationText(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private fun buildContinuationContextTerms(
        currentState: LocalConversationState,
        semanticIntentState: SemanticIntentState?
    ): Set<String> {
        val activeIntent = semanticIntentState?.candidateIntents?.firstOrNull { candidate ->
            candidate.intentId == semanticIntentState.activeIntentId
        } ?: semanticIntentState?.candidateIntents?.firstOrNull()
        return buildSet {
            addAll(extractReferenceTerms(currentState.pendingExecutionRecovery?.objective.orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskRecord?.displayTarget().orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskRecord?.targetKey.orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskRecord?.targetLabel.orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskDraft?.displayTarget().orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskDraft?.targetKey.orEmpty()))
            addAll(extractReferenceTerms(currentState.currentTaskDraft?.targetLabel.orEmpty()))
            addAll(extractReferenceTerms(activeIntent?.focusedObject.orEmpty()))
            addAll(extractReferenceTerms(activeIntent?.anchorObject.orEmpty()))
        }.filter { term -> term.length >= 2 }.toSet()
    }

    private fun buildContinuationPlatformTokens(
        currentState: LocalConversationState,
        semanticIntentState: SemanticIntentState?
    ): Set<String> {
        val activeIntent = semanticIntentState?.candidateIntents?.firstOrNull { candidate ->
            candidate.intentId == semanticIntentState.activeIntentId
        } ?: semanticIntentState?.candidateIntents?.firstOrNull()
        return buildSet {
            addAll(canonicalPlatformTokens(currentState.pendingExecutionRecovery?.selectedToolId))
            addAll(canonicalPlatformTokens(currentState.currentTaskRecord?.capabilityId))
            addAll(canonicalPlatformTokens(currentState.currentTaskRecord?.detailSlots?.get(DetailSlotKey.PLATFORM)))
            addAll(canonicalPlatformTokens(currentState.currentTaskDraft?.capabilityId))
            addAll(canonicalPlatformTokens(currentState.currentTaskDraft?.detailSlots?.get(DetailSlotKey.PLATFORM)))
            addAll(canonicalPlatformTokens(activeIntent?.capabilityId))
        }
    }

    private fun extractExplicitPlatformTokens(normalizedMessage: String): Set<String> {
        return buildSet {
            genericPlatformCueTerms.forEach { cue ->
                if (normalizedMessage.contains(cue)) {
                    addAll(canonicalPlatformTokens(cue))
                }
            }
            CanonicalAppCatalog.allEntries().forEach { entry ->
                val normalizedTerms = buildSet {
                    add(entry.appId)
                    add(normalizeContinuationText(entry.displayName))
                    entry.aliasTerms.forEach { alias -> add(normalizeContinuationText(alias)) }
                    entry.toolTerms.forEach { term -> add(normalizeContinuationText(term)) }
                }
                if (normalizedTerms.any { term -> term.length >= 2 && normalizedMessage.contains(term) }) {
                    addAll(canonicalPlatformTokens(entry.appId))
                }
            }
        }
    }

    private fun canonicalPlatformTokens(raw: String?): Set<String> {
        val normalized = normalizeContinuationText(raw.orEmpty())
        if (normalized.isBlank()) {
            return emptySet()
        }
        return buildSet {
            when {
                normalized.contains("oppo应用商店") || normalized.contains("oppo软件商店") || normalized.contains("heytap") -> {
                    add("oppo_app_store")
                    add("app_store")
                }

                normalized.contains("应用商店") || normalized.contains("软件商店") || normalized.contains("app store") -> {
                    add("app_store")
                }
            }
            resolveCanonicalAppId(normalized)?.let { appId ->
                add(appId)
                CanonicalAppCatalog.entryFor(appId)?.let { entry ->
                    add(normalizeContinuationText(entry.displayName))
                    entry.aliasTerms.forEach { alias -> add(normalizeContinuationText(alias)) }
                    entry.toolTerms.forEach { term -> add(normalizeContinuationText(term)) }
                }
            }
        }
    }

    private fun extractExplicitTaskAnchor(normalizedMessage: String): String? {
        val stripped = explicitTaskAnchorNoiseTokens.fold(normalizedMessage) { current, token ->
            current.replace(token, " ")
        }.replace(Regex("\\s+"), " ")
            .trim()
        return stripped.takeIf { anchor ->
            anchor.length >= 2 && anchor.any { character ->
                Character.isLetterOrDigit(character) || Character.UnicodeScript.of(character.code) != Character.UnicodeScript.COMMON
            }
        }
    }

    private val continuationCueTokens = listOf(
        "继续",
        "接着",
        "继续执行",
        "接着执行",
        "前面的任务",
        "前面那个任务",
        "刚才的任务",
        "resume",
        "continue"
    )

    private val repairCueTokens = listOf(
        "失败",
        "修复",
        "重试",
        "retry",
        "recover",
        "恢复"
    )

    private val historicalReferenceTokens = listOf(
        "之前",
        "上次",
        "刚才",
        "刚刚",
        "前面那个",
        "之前买的",
        "那个订单",
        "那个商品",
        "刚完成"
    )

    private val followUpActionTokens = listOf(
        "退",
        "退掉",
        "退款",
        "取消",
        "查看",
        "看看",
        "打开",
        "再买",
        "重买"
    )

    private val checkpointResumeTokens = listOf(
        "订单",
        "流程",
        "刚完成",
        "刚下单",
        "上个",
        "上一单"
    )

    private val genericPlatformCueTerms = listOf(
        "oppo应用商店",
        "oppo软件商店",
        "应用商店",
        "软件商店",
        "app store"
    )

    private val explicitTaskAnchorNoiseTokens = listOf(
        "去给我",
        "帮我去",
        "给我去",
        "帮我",
        "给我",
        "请你",
        "请",
        "再来一下",
        "再来",
        "继续",
        "接着",
        "重试",
        "retry",
        "resume",
        "continue",
        "一下",
        "一个",
        "装一下",
        "装个",
        "搜一下",
        "搜个",
        "查一下",
        "打开",
        "安装",
        "搜索",
        "查看",
        "看看",
        "清空",
        "删除",
        "开启",
        "关闭",
        "app",
        "装",
        "搜",
        "查",
        "去",
        "个",
        "把",
        "先",
        "再",
        "吧",
        "呀",
        "啊",
        "我"
    )

    private val continuationActiveHints = setOf("active_task", "continue_active_task", "resume_task", "current_task")
    private val continuationRepairHints = setOf("repair_failure", "resume_failure", "retry_failed_task", "failed_task")
    private val continuationObjectHints = setOf("follow_up_object", "previous_object", "historical_object")
    private val continuationCheckpointHints = setOf("resume_checkpoint", "completed_checkpoint", "previous_checkpoint")
}