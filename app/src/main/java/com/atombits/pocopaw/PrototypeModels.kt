package com.atombits.pocopaw

import android.content.Context
import com.atombits.pocopaw.earnings.EarningsHubState
import com.atombits.pocopaw.process.curation.ProcessAssetEntry
import com.atombits.pocopaw.process.curation.ProcessAssetEvent
import com.atombits.pocopaw.process.curation.ProcessCurationSummary
import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
import com.atombits.pocopaw.process.runtime.PreparedExecutionStart
import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
import com.atombits.pocopaw.process.runtime.ProcessReviewContext
import com.atombits.pocopaw.process.runtime.ProcessRuntimeState
import com.google.gson.annotations.SerializedName
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM;

    fun label(context: Context): String = when (this) {
        USER -> context.getString(R.string.message_role_user)
        ASSISTANT -> context.getString(R.string.message_role_assistant)
        SYSTEM -> context.getString(R.string.message_role_system)
    }
}

enum class ConversationStage {
    DISCUSSING,
    ACCUMULATING,
    PREPARING,
    EXECUTING;

    fun normalized(): ConversationStage = when (this) {
        DISCUSSING -> ACCUMULATING
        else -> this
    }

    fun label(context: Context): String = when (normalized()) {
        ACCUMULATING -> context.getString(R.string.stage_accumulating)
        PREPARING -> context.getString(R.string.stage_preparing)
        EXECUTING -> context.getString(R.string.stage_executing)
        else -> context.getString(R.string.stage_accumulating)
    }

    companion object {
        fun fromRaw(value: String?): ConversationStage = when (value?.trim()?.uppercase()) {
            "DISCUSSING" -> ACCUMULATING
            "ACCUMULATING" -> ACCUMULATING
            "PREPARING" -> PREPARING
            "EXECUTING" -> EXECUTING
            else -> ACCUMULATING
        }
    }
}

enum class CurrentPhase {
    ACCUMULATION,
    PREPARATION,
    EXECUTION;

    fun toConversationStage(): ConversationStage = when (this) {
        ACCUMULATION -> ConversationStage.ACCUMULATING
        PREPARATION -> ConversationStage.PREPARING
        EXECUTION -> ConversationStage.EXECUTING
    }

    companion object {
        fun fromRaw(value: String?): CurrentPhase? = when (value?.trim()?.uppercase(Locale.US)) {
            "ACCUMULATION", "ACCUMULATING", "DISCUSSING" -> ACCUMULATION
            "PREPARATION", "PREPARING" -> PREPARATION
            "EXECUTION", "EXECUTING" -> EXECUTION
            else -> null
        }
    }
}

fun ConversationStage.toCurrentPhase(): CurrentPhase = when (normalized()) {
    ConversationStage.PREPARING -> CurrentPhase.PREPARATION
    ConversationStage.EXECUTING -> CurrentPhase.EXECUTION
    else -> CurrentPhase.ACCUMULATION
}

enum class UserRequestSemantic {
    START_ACCUMULATING,
    START_PREPARING,
    START_EXECUTING;

    fun toProgressSignal(currentStage: ConversationStage): UserProgressSignal = when (this) {
        START_ACCUMULATING -> when (currentStage.normalized()) {
            ConversationStage.PREPARING,
            ConversationStage.EXECUTING -> UserProgressSignal.RETURN_TO_ACCUMULATING

            else -> UserProgressSignal.CONTINUE_ACCUMULATING
        }

        START_PREPARING -> if (currentStage.normalized() == ConversationStage.PREPARING) {
            UserProgressSignal.STAY_PREPARING
        } else {
            UserProgressSignal.ENTER_PREPARING
        }

        START_EXECUTING -> UserProgressSignal.ENTER_EXECUTING
    }

    fun toTransitionIntent(): PassiveUserTransitionIntent = when (this) {
        START_ACCUMULATING -> PassiveUserTransitionIntent.SAME_TOPIC_ACCUMULATE
        START_PREPARING -> PassiveUserTransitionIntent.SAME_TOPIC_PREPARE
        START_EXECUTING -> PassiveUserTransitionIntent.SAME_TOPIC_EXECUTE
    }

    companion object {
        fun fromRaw(value: String?): UserRequestSemantic? = when (value?.trim()?.uppercase(Locale.US)) {
            "START_ACCUMULATING", "START_ACCUMULATION", "SAME_TOPIC_ACCUMULATE", "CONTINUE_ACCUMULATING", "RETURN_TO_ACCUMULATING", "SWITCH_CONTEXT" -> START_ACCUMULATING
            "START_PREPARING", "START_PREPARATION", "SAME_TOPIC_PREPARE", "ENTER_PREPARING", "STAY_PREPARING" -> START_PREPARING
            "START_EXECUTING", "START_EXECUTION", "SAME_TOPIC_EXECUTE", "ENTER_EXECUTING" -> START_EXECUTING
            else -> null
        }
    }
}

enum class StageTransitionRecommendation {
    SHOULD_ENTER_ACCUMULATING,
    SHOULD_ENTER_PREPARING,
    SHOULD_ENTER_EXECUTING;

    companion object {
        fun fromRaw(value: String?): StageTransitionRecommendation? = when (value?.trim()?.uppercase(Locale.US)) {
            "SHOULD_ENTER_ACCUMULATING", "SHOULD_ENTER_ACCUMULATION", "CONTINUE_ACCUMULATING", "RETURN_TO_ACCUMULATING", "SAME_TOPIC_ACCUMULATE" -> SHOULD_ENTER_ACCUMULATING
            "SHOULD_ENTER_PREPARING", "SHOULD_ENTER_PREPARATION", "ENTER_PREPARING", "STAY_PREPARING", "SAME_TOPIC_PREPARE" -> SHOULD_ENTER_PREPARING
            "SHOULD_ENTER_EXECUTING", "SHOULD_ENTER_EXECUTION", "ENTER_EXECUTING", "SAME_TOPIC_EXECUTE" -> SHOULD_ENTER_EXECUTING
            else -> null
        }
    }
}

enum class WorkflowLane {
    PASSIVE,
    PROACTIVE;

    companion object {
        fun fromRaw(value: String?): WorkflowLane? = when (value?.trim()?.uppercase()) {
            "PASSIVE" -> PASSIVE
            "PROACTIVE" -> PROACTIVE
            else -> null
        }
    }
}

enum class StageOwner {
    USER,
    PROACTIVE_ENGINE;

    companion object {
        fun fromRaw(value: String?): StageOwner? = when (value?.trim()?.uppercase()) {
            "USER" -> USER
            "PROACTIVE_ENGINE" -> PROACTIVE_ENGINE
            else -> null
        }
    }
}

enum class ProactiveOpportunitySignal {
    OBSERVE_OPPORTUNITY,
    PREPARE_OPPORTUNITY,
    ISSUE_PROACTIVE_HINT,
    REQUEST_PROACTIVE_CONFIRM,
    ENTER_PROACTIVE_EXECUTING,
    DEFER_OPPORTUNITY,
    CANCEL_OPPORTUNITY;

    companion object {
        fun fromRaw(value: String?): ProactiveOpportunitySignal? = when (value?.trim()?.uppercase()) {
            "OBSERVE_OPPORTUNITY" -> OBSERVE_OPPORTUNITY
            "PREPARE_OPPORTUNITY" -> PREPARE_OPPORTUNITY
            "ISSUE_PROACTIVE_HINT" -> ISSUE_PROACTIVE_HINT
            "REQUEST_PROACTIVE_CONFIRM" -> REQUEST_PROACTIVE_CONFIRM
            "ENTER_PROACTIVE_EXECUTING" -> ENTER_PROACTIVE_EXECUTING
            "DEFER_OPPORTUNITY" -> DEFER_OPPORTUNITY
            "CANCEL_OPPORTUNITY" -> CANCEL_OPPORTUNITY
            else -> null
        }
    }
}

enum class PassiveUserTransitionIntent {
    SAME_TOPIC_ACCUMULATE,
    SAME_TOPIC_PREPARE,
    SAME_TOPIC_EXECUTE,
    SWITCH_CONTEXT;

    fun resolveOperationalSignal(currentStage: ConversationStage): UserProgressSignal {
        return when (this) {
            SAME_TOPIC_ACCUMULATE -> when (currentStage.normalized()) {
                ConversationStage.PREPARING,
                ConversationStage.EXECUTING -> UserProgressSignal.RETURN_TO_ACCUMULATING

                else -> UserProgressSignal.CONTINUE_ACCUMULATING
            }

            SAME_TOPIC_PREPARE -> if (currentStage.normalized() == ConversationStage.PREPARING) {
                UserProgressSignal.STAY_PREPARING
            } else {
                UserProgressSignal.ENTER_PREPARING
            }

            SAME_TOPIC_EXECUTE -> UserProgressSignal.ENTER_EXECUTING
            SWITCH_CONTEXT -> UserProgressSignal.SWITCH_CONTEXT
        }
    }

    fun label(context: Context): String = when (this) {
        SAME_TOPIC_ACCUMULATE -> context.getString(R.string.transition_intent_same_topic_accumulate)
        SAME_TOPIC_PREPARE -> context.getString(R.string.transition_intent_same_topic_prepare)
        SAME_TOPIC_EXECUTE -> context.getString(R.string.transition_intent_same_topic_execute)
        SWITCH_CONTEXT -> context.getString(R.string.transition_intent_switch_context)
    }

    companion object {
        fun fromRaw(value: String?): PassiveUserTransitionIntent? = when (value?.trim()?.uppercase()) {
            "SAME_TOPIC_ACCUMULATE" -> SAME_TOPIC_ACCUMULATE
            "SAME_TOPIC_PREPARE" -> SAME_TOPIC_PREPARE
            "SAME_TOPIC_EXECUTE" -> SAME_TOPIC_EXECUTE
            "SWITCH_CONTEXT" -> SWITCH_CONTEXT
            else -> null
        }
    }
}

enum class UserProgressSignal {
    CONTINUE_ACCUMULATING,
    ENTER_PREPARING,
    STAY_PREPARING,
    ENTER_EXECUTING,
    RETURN_TO_ACCUMULATING,
    SWITCH_CONTEXT;

    fun resolvedStage(): ConversationStage = when (this) {
        ENTER_PREPARING,
        STAY_PREPARING -> ConversationStage.PREPARING

        ENTER_EXECUTING -> ConversationStage.EXECUTING

        CONTINUE_ACCUMULATING,
        RETURN_TO_ACCUMULATING,
        SWITCH_CONTEXT -> ConversationStage.ACCUMULATING
    }

    fun keepsExecutionPreparation(): Boolean = when (this) {
        ENTER_PREPARING,
        STAY_PREPARING,
        ENTER_EXECUTING -> true

        else -> false
    }

    fun label(context: Context): String = when (this) {
        CONTINUE_ACCUMULATING -> context.getString(R.string.progress_signal_continue_accumulating)
        ENTER_PREPARING -> context.getString(R.string.progress_signal_enter_preparing)
        STAY_PREPARING -> context.getString(R.string.progress_signal_stay_preparing)
        ENTER_EXECUTING -> context.getString(R.string.progress_signal_enter_executing)
        RETURN_TO_ACCUMULATING -> context.getString(R.string.progress_signal_return_to_accumulating)
        SWITCH_CONTEXT -> context.getString(R.string.progress_signal_switch_context)
    }

    companion object {
        fun fromRaw(value: String?): UserProgressSignal? = when (value?.trim()?.uppercase()) {
            "CONTINUE_ACCUMULATING" -> CONTINUE_ACCUMULATING
            "ENTER_PREPARING" -> ENTER_PREPARING
            "STAY_PREPARING" -> STAY_PREPARING
            "ENTER_EXECUTING" -> ENTER_EXECUTING
            "RETURN_TO_ACCUMULATING" -> RETURN_TO_ACCUMULATING
            "SWITCH_CONTEXT" -> SWITCH_CONTEXT
            else -> null
        }
    }
}

typealias PassiveUserProgressSignal = UserProgressSignal

fun UserProgressSignal.toTransitionIntent(): PassiveUserTransitionIntent = when (this) {
    UserProgressSignal.CONTINUE_ACCUMULATING,
    UserProgressSignal.RETURN_TO_ACCUMULATING -> PassiveUserTransitionIntent.SAME_TOPIC_ACCUMULATE

    UserProgressSignal.ENTER_PREPARING,
    UserProgressSignal.STAY_PREPARING -> PassiveUserTransitionIntent.SAME_TOPIC_PREPARE

    UserProgressSignal.ENTER_EXECUTING -> PassiveUserTransitionIntent.SAME_TOPIC_EXECUTE
    UserProgressSignal.SWITCH_CONTEXT -> PassiveUserTransitionIntent.SWITCH_CONTEXT
}

fun UserProgressSignal.toUserRequestSemantic(): UserRequestSemantic = when (this) {
    UserProgressSignal.ENTER_PREPARING,
    UserProgressSignal.STAY_PREPARING -> UserRequestSemantic.START_PREPARING

    UserProgressSignal.ENTER_EXECUTING -> UserRequestSemantic.START_EXECUTING

    UserProgressSignal.CONTINUE_ACCUMULATING,
    UserProgressSignal.RETURN_TO_ACCUMULATING,
    UserProgressSignal.SWITCH_CONTEXT -> UserRequestSemantic.START_ACCUMULATING
}

fun UserProgressSignal.toStageTransitionRecommendation(): StageTransitionRecommendation = when (this) {
    UserProgressSignal.ENTER_PREPARING,
    UserProgressSignal.STAY_PREPARING -> StageTransitionRecommendation.SHOULD_ENTER_PREPARING

    UserProgressSignal.ENTER_EXECUTING -> StageTransitionRecommendation.SHOULD_ENTER_EXECUTING

    UserProgressSignal.CONTINUE_ACCUMULATING,
    UserProgressSignal.RETURN_TO_ACCUMULATING,
    UserProgressSignal.SWITCH_CONTEXT -> StageTransitionRecommendation.SHOULD_ENTER_ACCUMULATING
}

enum class CandidateReadiness {
    EMERGING,
    ACCUMULATING,
    READY_TO_PREPARE,
    READY_TO_START;

    companion object {
        fun fromRaw(value: String?): CandidateReadiness = when (value?.trim()?.uppercase()) {
            "ACCUMULATING" -> ACCUMULATING
            "PREPARING_READY" -> READY_TO_PREPARE
            "READY_TO_PREPARE" -> READY_TO_PREPARE
            "EXECUTION_READY" -> READY_TO_START
            "READY_TO_START" -> READY_TO_START
            else -> EMERGING
        }
    }
}

enum class SemanticNextMove {
    ANSWER,
    CLARIFY,
    TOOL_INFO,
    START_EXECUTION,
    UPDATE_ACTIVE_TASK;

    companion object {
        fun fromRaw(value: String?): SemanticNextMove? = when (value?.trim()?.lowercase(Locale.US)) {
            "answer" -> ANSWER
            "clarify" -> CLARIFY
            "tool_info" -> TOOL_INFO
            "start_execution" -> START_EXECUTION
            "update_active_task" -> UPDATE_ACTIVE_TASK
            else -> null
        }
    }
}

enum class SemanticPhaseType {
    NONE,
    CLARIFY,
    OFFER,
    CONFIRM,
    EXECUTION;

    companion object {
        fun fromRaw(value: String?): SemanticPhaseType? = when (value?.trim()?.lowercase(Locale.US)) {
            "none" -> NONE
            "clarify" -> CLARIFY
            "offer" -> OFFER
            "confirm" -> CONFIRM
            "execution" -> EXECUTION
            else -> null
        }
    }
}

enum class SemanticPhaseStatus {
    START,
    ACTIVE,
    END;

    companion object {
        fun fromRaw(value: String?): SemanticPhaseStatus? = when (value?.trim()?.lowercase(Locale.US)) {
            "start" -> START
            "active" -> ACTIVE
            "end" -> END
            else -> null
        }
    }
}

enum class SemanticIntentReadiness {
    EMERGING,
    CONVERGING,
    NEEDS_CLARIFICATION,
    READY_FOR_OFFER,
    READY_FOR_CONFIRMATION,
    READY_FOR_EXECUTION;

    companion object {
        fun fromRaw(value: String?): SemanticIntentReadiness? = when (value?.trim()?.uppercase(Locale.US)) {
            "EMERGING" -> EMERGING
            "CONVERGING" -> CONVERGING
            "NEEDS_CLARIFICATION" -> NEEDS_CLARIFICATION
            "READY_FOR_OFFER" -> READY_FOR_OFFER
            "READY_FOR_CONFIRMATION" -> READY_FOR_CONFIRMATION
            "READY_FOR_EXECUTION" -> READY_FOR_EXECUTION
            else -> null
        }
    }
}

enum class CanonicalAction {
    BUY,
    ADD_TO_CART,
    PAY,
    SEARCH,
    COMPARE,
    COUPON,
    RATING,
    RETURN,
    DELETE,
    SEND_MESSAGE;

    companion object {
        fun fromModelLabel(value: String?): CanonicalAction? =
            SharedActionNormalization.fromRaw(value, allowBroadMessageTokens = true)
    }
}

enum class ConfirmRequirement {
    NONE,
    SOFT,
    HARD;

    companion object {
        fun fromRaw(value: String?): ConfirmRequirement = when (value?.trim()?.uppercase()) {
            "SOFT" -> SOFT
            "HARD",
            "EXPLICIT_CONFIRMATION",
            "REQUIRES_CONFIRMATION",
            "NEEDS_CONFIRM" -> HARD
            else -> NONE
        }
    }
}

enum class ExecutionGateFlag {
    NO_EXECUTION,
    NEEDS_CONFIRM,
    READY_TO_START,
    BLOCKED;

    companion object {
        fun fromRaw(value: String?): ExecutionGateFlag = when (value?.trim()?.uppercase()) {
            "NEEDS_CONFIRM" -> NEEDS_CONFIRM
            "READY_TO_START" -> READY_TO_START
            "BLOCKED" -> BLOCKED
            else -> NO_EXECUTION
        }
    }
}

enum class ExecutionLifecycleStatus {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromRaw(value: String?): ExecutionLifecycleStatus = when (value?.trim()?.uppercase()) {
            "RUNNING" -> RUNNING
            "COMPLETED" -> COMPLETED
            "FAILED" -> FAILED
            else -> NOT_STARTED
        }
    }
}

enum class ExecutionEventPhase {
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    INFO
}

enum class CommonDetailSlotKey(val wireName: String) {
    TIME("time"),
    PRICE("price"),
    QUANTITY("quantity"),
    PLATFORM("platform"),
    CONSTRAINT("constraint");

    companion object {
        fun contractValues(): String = values().joinToString("|") { key -> key.wireName }

        fun fromRaw(value: String?): CommonDetailSlotKey? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                return null
            }
            return values().firstOrNull { key -> key.wireName.equals(normalized, ignoreCase = true) }
        }
    }
}

object DomainDetailSlotRegistry {
    fun promptSummary(): String {
        return CapabilityDomainProfileRegistry.promptSummary()
    }

    fun allowedKeys(domain: CapabilityDomain?): Set<String> {
        return CapabilityDomainProfileRegistry.allowedDetailSlotKeys(domain)
    }
}

private fun domainSlotToLegacyDetailSlotKey(
    capabilityDomain: CapabilityDomain?,
    rawKey: String
): DetailSlotKey? {
    val normalizedKey = rawKey.trim().lowercase(Locale.US)
    return when (capabilityDomain) {
        CapabilityDomain.SHOPPING -> when (normalizedKey) {
            "product_type" -> DetailSlotKey.PRODUCT_TYPE
            "brand" -> DetailSlotKey.BRAND
            "spec" -> DetailSlotKey.SPEC
            "feature" -> DetailSlotKey.FEATURE
            else -> null
        }
        CapabilityDomain.TRANSPORT -> when (normalizedKey) {
            "destination" -> DetailSlotKey.DESTINATION
            "origin" -> DetailSlotKey.LOCATION
            else -> null
        }
        else -> null
    }
}

data class TaskDetailSlots(
    val common: Map<CommonDetailSlotKey, String> = emptyMap(),
    val domain: Map<String, String> = emptyMap()
) {
    fun isEmpty(): Boolean = common.isEmpty() && domain.isEmpty()

    fun normalize(capabilityDomain: CapabilityDomain?): TaskDetailSlots {
        val normalizedCommon = common.mapNotNull { (key, rawValue) ->
            val normalizedValue = rawValue.trim()
            if (normalizedValue.isBlank()) {
                null
            } else {
                key to normalizedValue
            }
        }.toMap(linkedMapOf())

        val allowedDomainKeys = DomainDetailSlotRegistry.allowedKeys(capabilityDomain)
        val normalizedDomain = if (capabilityDomain == null) {
            emptyMap()
        } else {
            domain.mapNotNull { (rawKey, rawValue) ->
                val normalizedKey = rawKey.trim().lowercase(Locale.US)
                val normalizedValue = rawValue.trim()
                if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                    null
                } else if (allowedDomainKeys.isNotEmpty() && normalizedKey !in allowedDomainKeys) {
                    null
                } else {
                    normalizedKey to normalizedValue
                }
            }.toMap(linkedMapOf())
        }

        return TaskDetailSlots(
            common = normalizedCommon,
            domain = normalizedDomain
        )
    }

    fun toLegacyDetailSlots(capabilityDomain: CapabilityDomain?): Map<DetailSlotKey, String> {
        val normalized = normalize(capabilityDomain)
        val legacySlots = linkedMapOf<DetailSlotKey, String>()

        normalized.common.forEach { (key, value) ->
            when (key) {
                CommonDetailSlotKey.TIME -> legacySlots[DetailSlotKey.TIME] = value
                CommonDetailSlotKey.PRICE -> legacySlots[DetailSlotKey.PRICE] = value
                CommonDetailSlotKey.QUANTITY -> legacySlots[DetailSlotKey.QUANTITY] = value
                CommonDetailSlotKey.PLATFORM -> legacySlots[DetailSlotKey.PLATFORM] = value
                CommonDetailSlotKey.CONSTRAINT -> legacySlots[DetailSlotKey.CONSTRAINT] = value
            }
        }

        normalized.domain.forEach { (key, value) ->
            domainSlotToLegacyDetailSlotKey(capabilityDomain, key)?.let { legacyKey ->
                legacySlots[legacyKey] = value
            }
        }

        return legacySlots
    }

    fun toNamespacedResolvedSlots(capabilityDomain: CapabilityDomain?): Map<String, String> {
        val normalized = normalize(capabilityDomain)
        val resolvedSlots = linkedMapOf<String, String>()
        normalized.common.forEach { (key, value) ->
            resolvedSlots["common.${key.wireName}"] = value
        }
        capabilityDomain?.let { domainKey ->
            normalized.domain.forEach { (key, value) ->
                resolvedSlots["${domainKey.wireName}.$key"] = value
            }
        }
        return resolvedSlots
    }

    fun primaryTargetCandidates(capabilityDomain: CapabilityDomain?): List<String> {
        val normalized = normalize(capabilityDomain)
        return CapabilityDomainProfileRegistry.primaryTargetCandidates(capabilityDomain, normalized.domain)
    }
}

enum class DetailSlotKey(val wireName: String) {
    TARGET_OBJECT("target_object"),
    PRODUCT_TYPE("product_type"),
    BRAND("brand"),
    SPEC("spec"),
    PRICE("price"),
    FEATURE("feature"),
    QUANTITY("quantity"),
    PLATFORM("platform"),
    DESTINATION("destination"),
    LOCATION("location"),
    TIME("time"),
    CONSTRAINT("constraint");

    val contractName: String
        get() = name

    fun label(): String = when (this) {
        TARGET_OBJECT -> "target object"
        PRODUCT_TYPE -> "product type"
        BRAND -> "brand"
        SPEC -> "spec"
        PRICE -> "price"
        FEATURE -> "feature"
        QUANTITY -> "quantity"
        PLATFORM -> "platform"
        DESTINATION -> "destination"
        LOCATION -> "location"
        TIME -> "time"
        CONSTRAINT -> "constraint"
    }

    companion object {
        fun contractValues(): String = values().joinToString("|") { it.contractName }

        fun fromWireName(value: String?): DetailSlotKey? = values().firstOrNull { it.wireName == value }

        fun fromContractValue(value: String?): DetailSlotKey? {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                return null
            }
            return values().firstOrNull { key ->
                key.contractName.equals(normalized, ignoreCase = true) ||
                    key.wireName.equals(normalized, ignoreCase = true)
            }
        }
    }
}

data class DetailSlot(
    val key: DetailSlotKey,
    val value: String,
    val source: String,
    val updatedAt: Long = System.currentTimeMillis()
)

data class IntentCandidate(
    val id: String = UUID.randomUUID().toString(),
    val anchorObject: String,
    val focusedObject: String,
    val action: String,
    val readiness: CandidateReadiness,
    val confidence: Double,
    val evidence: String,
    val rationale: String,
    val detailSlots: List<DetailSlot> = emptyList(),
    val missingRequiredSlots: List<DetailSlotKey> = emptyList(),
    val canStartExecution: Boolean = false
) {
    val anchoredLabel: String
        get() = when {
            focusedObject.isBlank() -> anchorObject
            anchorObject.isBlank() -> focusedObject
            focusedObject.startsWith(anchorObject) -> focusedObject
            else -> "$anchorObject -> $focusedObject"
        }
}

enum class ContinuationMode {
    NEW_TASK,
    CONTINUE_ACTIVE_TASK,
    FOLLOW_UP_ON_PREVIOUS_OBJECT,
    RESUME_FROM_CHECKPOINT,
    REPAIR_PREVIOUS_FAILURE,
    UNCLEAR
}

data class ContinuationGroundingResult(
    val mode: ContinuationMode,
    val targetTaskContextId: String? = null,
    val targetObjectId: String? = null,
    val targetObjectType: String? = null,
    val targetAppScope: String? = null,
    val targetCheckpointId: String? = null,
    val intendedAction: String? = null,
    val reason: String,
    val confidence: Double = 0.0,
    val requiresClarification: Boolean = false
)

data class SemanticIntentCandidate(
    val intentId: String = UUID.randomUUID().toString(),
    val anchorObject: String,
    val focusedObject: String,
    val rawActionLabel: String,
    val canonicalAction: CanonicalAction? = CanonicalAction.fromModelLabel(rawActionLabel),
    val readiness: SemanticIntentReadiness? = null,
    val confidence: Double = 0.0,
    val stability: Double = 0.0,
    val detailSlots: List<DetailSlot> = emptyList(),
    val constraints: List<String> = emptyList(),
    val authorizationRequirement: ConfirmRequirement = ConfirmRequirement.NONE,
    val confirmationPolicy: String? = null,
    val executionConstraints: List<String> = emptyList(),
    val executionPreferenceSignals: List<String> = emptyList(),
    val capabilityStack: CapabilityStack? = null,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val processId: String? = null,
    val continuationHint: String? = null,
    val reasonSummary: String? = null,
    val phaseType: SemanticPhaseType = SemanticPhaseType.NONE,
    val phaseStatus: SemanticPhaseStatus = SemanticPhaseStatus.END,
    val nextMove: SemanticNextMove? = null,
    val canStartExecution: Boolean = false
)

data class SemanticIntentState(
    val activeIntentId: String? = null,
    val candidateIntents: List<SemanticIntentCandidate> = emptyList(),
    val currentPhase: CurrentPhase? = null,
    val userRequestSemantic: UserRequestSemantic? = null,
    val stageTransitionRecommendation: StageTransitionRecommendation? = null,
    val nextMove: SemanticNextMove? = null,
    val phaseType: SemanticPhaseType = SemanticPhaseType.NONE,
    val phaseStatus: SemanticPhaseStatus = SemanticPhaseStatus.END,
    val updatedAt: Long = System.currentTimeMillis()
)

data class TaskExecutionBoundaryPacket(
    val taskId: String,
    val taskUpdatedAt: Long,
    val phase: TaskPhase,
    val actionCode: ActionCode,
    val targetType: TargetType,
    val targetKey: String,
    val targetLabel: String? = null,
    val structuredDetailSlots: TaskDetailSlots = TaskDetailSlots(),
    val detailSlots: Map<DetailSlotKey, String> = emptyMap(),
    val capabilityStack: CapabilityStack? = null,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val processId: String? = null,
    val checkpointId: String? = null,
    val reasonSummary: String? = null,
    val missingInformation: List<String> = emptyList(),
    val requiredDetailSlots: List<DetailSlotKey> = emptyList(),
    val verificationChecks: List<ExecutionCheck> = emptyList(),
    val confirmRequirement: ConfirmRequirement = ConfirmRequirement.NONE,
    val executionGateFlag: ExecutionGateFlag = if (requiredDetailSlots.isNotEmpty()) {
        ExecutionGateFlag.BLOCKED
    } else if (confirmRequirement != ConfirmRequirement.NONE) {
        ExecutionGateFlag.NEEDS_CONFIRM
    } else if (phase == TaskPhase.EXECUTING) {
        ExecutionGateFlag.READY_TO_START
    } else {
        ExecutionGateFlag.NO_EXECUTION
    }
) {
    val objectiveSummary: String
        get() = targetLabel?.takeIf { value -> value.isNotBlank() } ?: targetKey

    val executionTargetSummary: String
        get() = targetKey.trim().ifBlank { targetLabel?.trim().orEmpty() }

    val executionObjectiveSummary: String
        get() = executionTargetSummary

    val workflowLane: WorkflowLane
        get() = WorkflowLane.PASSIVE

    val actionSummary: String
        get() = actionCode.wireName

    val planSummary: String
        get() = listOf(actionCode.wireName, objectiveSummary)
            .filter { value -> value.isNotBlank() }
            .joinToString(" ")
            .ifBlank { objectiveSummary }

    val executionPlanSummary: String
        get() = listOf(actionCode.wireName, executionObjectiveSummary)
            .filter { value -> value.isNotBlank() }
            .joinToString(" ")
            .ifBlank { executionObjectiveSummary }

    val riskSummary: String
        get() = ""

    val canStartExecution: Boolean
        get() = executionGateFlag == ExecutionGateFlag.READY_TO_START

    val selectedAppScope: String?
        get() = extractCanonicalAppScope(capabilityId)

    val requiredSlots: List<String>
        get() = requiredDetailSlots.map(DetailSlotKey::contractName)

    val resolvedSlots: Map<String, String>
        get() = buildMap {
            put(DetailSlotKey.TARGET_OBJECT.contractName, targetKey)
            detailSlots.forEach { (key, value) ->
                val trimmedValue = value.trim()
                if (trimmedValue.isNotBlank()) {
                    put(key.contractName, trimmedValue)
                }
            }
            structuredDetailSlots.toNamespacedResolvedSlots(capabilityDomain).forEach { (key, value) ->
                put(key, value)
            }
        }

    val resolvedDetailSlots: List<DetailSlot>
        get() = resolvedSlots.mapNotNull { (slotKey, value) ->
            val key = DetailSlotKey.fromContractValue(slotKey) ?: return@mapNotNull null
            val trimmedValue = value.trim()
            if (trimmedValue.isBlank()) {
                null
            } else {
                DetailSlot(
                    key = key,
                    value = trimmedValue,
                    source = "TASK_RECORD"
                )
            }
        }
}

data class TaskSlotEvidenceSnapshot(
    val sourceLevel: String,
    val capabilityDomain: CapabilityDomain? = null,
    val capabilityId: String? = null,
    val targetKey: String? = null,
    val targetLabel: String? = null,
    val structuredDetailSlots: TaskDetailSlots = TaskDetailSlots(),
    val resolvedSlots: Map<String, String> = emptyMap(),
    val capturedAt: Long = System.currentTimeMillis()
)

data class ProcessSlotHint(
    val slotKey: String,
    val hintRole: String,
    val exampleValue: String? = null
)

private fun Map<String, String>.toCompactSlotSummary(): String {
    if (isEmpty()) {
        return "none"
    }
    return entries.joinToString(",") { (key, value) ->
        "${key.trim()}=${value.trim()}"
    }
}

fun TaskSlotEvidenceSnapshot.structuredCommonSummary(): String {
    val normalized = structuredDetailSlots.normalize(capabilityDomain)
    return normalized.common.entries
        .joinToString(",") { (key, value) -> "${key.wireName}=${value.trim()}" }
        .ifBlank { "none" }
}

fun TaskSlotEvidenceSnapshot.structuredDomainSummary(): String {
    val normalized = structuredDetailSlots.normalize(capabilityDomain)
    return normalized.domain.entries
        .joinToString(",") { (key, value) -> "${key.trim()}=${value.trim()}" }
        .ifBlank { "none" }
}

fun TaskSlotEvidenceSnapshot.resolvedSlotSummary(): String = resolvedSlots.toCompactSlotSummary()

fun List<ProcessSlotHint>.toPromptSummary(): String {
    if (isEmpty()) {
        return "none"
    }
    return joinToString(",") { hint ->
        val exampleValue = hint.exampleValue?.trim().orEmpty()
        if (exampleValue.isBlank()) {
            "${hint.slotKey}:${hint.hintRole}"
        } else {
            "${hint.slotKey}:${hint.hintRole}=${exampleValue}"
        }
    }
}

fun buildAuthoritativeResolvedSlots(
    targetKey: String?,
    structuredDetailSlots: TaskDetailSlots,
    capabilityDomain: CapabilityDomain?
): Map<String, String> {
    val normalizedStructuredSlots = structuredDetailSlots.normalize(capabilityDomain)
    return linkedMapOf<String, String>().apply {
        targetKey?.trim()?.takeIf { value -> value.isNotBlank() }?.let { value ->
            put(DetailSlotKey.TARGET_OBJECT.wireName, value)
        }
        normalizedStructuredSlots.toNamespacedResolvedSlots(capabilityDomain).forEach { (slotKey, value) ->
            val trimmedValue = value.trim()
            if (trimmedValue.isNotBlank()) {
                put(slotKey, trimmedValue)
            }
        }
    }
}

fun TaskExecutionBoundaryPacket.toTaskSlotEvidenceSnapshot(
    sourceLevel: String = "EXECUTION_BOUNDARY"
): TaskSlotEvidenceSnapshot? {
    val normalizedStructuredSlots = structuredDetailSlots.normalize(capabilityDomain)
    val authoritativeResolvedSlots = buildAuthoritativeResolvedSlots(
        targetKey = targetKey,
        structuredDetailSlots = normalizedStructuredSlots,
        capabilityDomain = capabilityDomain
    )
    if (normalizedStructuredSlots.isEmpty() && authoritativeResolvedSlots.isEmpty()) {
        return null
    }
    return TaskSlotEvidenceSnapshot(
        sourceLevel = sourceLevel,
        capabilityDomain = capabilityDomain,
        capabilityId = capabilityId,
        targetKey = targetKey.takeIf { value -> value.isNotBlank() },
        targetLabel = targetLabel?.takeIf { value -> value.isNotBlank() },
        structuredDetailSlots = normalizedStructuredSlots,
        resolvedSlots = authoritativeResolvedSlots,
        capturedAt = taskUpdatedAt.takeIf { value -> value > 0L } ?: System.currentTimeMillis()
    )
}

internal fun PrototypeStoreData.resolveCurrentTaskSlotEvidenceSnapshot(): TaskSlotEvidenceSnapshot? {
    resolveCurrentExecutionBoundaryPacket()?.toTaskSlotEvidenceSnapshot("EXECUTION_BOUNDARY")?.let { snapshot ->
        return snapshot
    }
    resolveCurrentState().currentTaskRecord
        ?.toTaskExecutionBoundaryPacket()
        ?.toTaskSlotEvidenceSnapshot("TASK_RECORD")
        ?.let { snapshot ->
            return snapshot
        }
    return null
}

fun TaskSlotEvidenceSnapshot.toProcessSlotHints(): List<ProcessSlotHint> {
    return resolvedSlots.entries.mapNotNull { (slotKey, rawValue) ->
        val value = rawValue.trim()
        if (slotKey.isBlank() || value.isBlank()) {
            null
        } else {
            val hintRole = when {
                slotKey == DetailSlotKey.TARGET_OBJECT.wireName -> "PRIMARY_FILTER"
                slotKey.startsWith("common.quantity") ||
                    slotKey.startsWith("common.price") ||
                    slotKey.startsWith("common.time") ||
                    slotKey.startsWith("common.platform") -> "VALUE_PRESERVE"

                slotKey.startsWith("common.constraint") -> "CONTEXT_HINT"
                slotKey.startsWith("common.") -> "CONTEXT_HINT"
                else -> "PRIMARY_FILTER"
            }
            ProcessSlotHint(
                slotKey = slotKey,
                hintRole = hintRole,
                exampleValue = value
            )
        }
    }
}

enum class ExecutionCoreSlotKey {
    TARGET_OBJECT,
    PRODUCT_TYPE,
    BRAND,
    SPEC,
    PRICE,
    FEATURE,
    QUANTITY,
    PLATFORM,
    DESTINATION,
    LOCATION,
    TIME
}

enum class ExecutionValueSource {
    USER_EXPLICIT,
    SEMANTIC_MODEL,
    LOCAL_DERIVATION,
    RECOVERY_CONTEXT,
    COMPATIBILITY_BRIDGE
}

enum class SlotBindingStrength {
    HARD,
    SOFT,
    ADVISORY
}

data class ExecutionSlotBinding(
    val value: String,
    val source: ExecutionValueSource = ExecutionValueSource.SEMANTIC_MODEL,
    val binding: SlotBindingStrength = SlotBindingStrength.SOFT,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ExecutionConstraint(
    val key: String,
    val value: String,
    val source: ExecutionValueSource = ExecutionValueSource.SEMANTIC_MODEL,
    val binding: SlotBindingStrength = SlotBindingStrength.HARD,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ExecutionCheck(
    val type: ExecutionCheckType,
    val key: String,
    val expectedValue: String? = null,
    val required: Boolean = true
)

enum class ExecutionCheckType {
    SLOT_PRESERVED,
    PAGE_SIGNAL_PRESENT,
    PAGE_SIGNAL_ABSENT,
    RESULT_CONSISTENT
}

fun DetailSlotKey.toExecutionCoreSlotKey(): ExecutionCoreSlotKey? = when (this) {
    DetailSlotKey.TARGET_OBJECT -> ExecutionCoreSlotKey.TARGET_OBJECT
    DetailSlotKey.PRODUCT_TYPE -> ExecutionCoreSlotKey.PRODUCT_TYPE
    DetailSlotKey.BRAND -> ExecutionCoreSlotKey.BRAND
    DetailSlotKey.SPEC -> ExecutionCoreSlotKey.SPEC
    DetailSlotKey.PRICE -> ExecutionCoreSlotKey.PRICE
    DetailSlotKey.FEATURE -> ExecutionCoreSlotKey.FEATURE
    DetailSlotKey.QUANTITY -> ExecutionCoreSlotKey.QUANTITY
    DetailSlotKey.PLATFORM -> ExecutionCoreSlotKey.PLATFORM
    DetailSlotKey.DESTINATION -> ExecutionCoreSlotKey.DESTINATION
    DetailSlotKey.LOCATION -> ExecutionCoreSlotKey.LOCATION
    DetailSlotKey.TIME -> ExecutionCoreSlotKey.TIME
    DetailSlotKey.CONSTRAINT -> null
}

fun ExecutionCoreSlotKey.toDetailSlotKey(): DetailSlotKey = when (this) {
    ExecutionCoreSlotKey.TARGET_OBJECT -> DetailSlotKey.TARGET_OBJECT
    ExecutionCoreSlotKey.PRODUCT_TYPE -> DetailSlotKey.PRODUCT_TYPE
    ExecutionCoreSlotKey.BRAND -> DetailSlotKey.BRAND
    ExecutionCoreSlotKey.SPEC -> DetailSlotKey.SPEC
    ExecutionCoreSlotKey.PRICE -> DetailSlotKey.PRICE
    ExecutionCoreSlotKey.FEATURE -> DetailSlotKey.FEATURE
    ExecutionCoreSlotKey.QUANTITY -> DetailSlotKey.QUANTITY
    ExecutionCoreSlotKey.PLATFORM -> DetailSlotKey.PLATFORM
    ExecutionCoreSlotKey.DESTINATION -> DetailSlotKey.DESTINATION
    ExecutionCoreSlotKey.LOCATION -> DetailSlotKey.LOCATION
    ExecutionCoreSlotKey.TIME -> DetailSlotKey.TIME
}

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

enum class SemanticModelTier {
    FAST,
    EXPERT
}

data class SemanticRuntimePreferences(
    val modelTier: SemanticModelTier = SemanticModelTier.FAST
)

data class ChatTurnOptions(
    val thinkingEnabled: Boolean = false,
    val searchEnabled: Boolean = false
)

data class AssistantReplyResult(
    val content: String,
    val tokenUsage: TokenUsage? = null
)

data class SearchPlanResponse(
    val goalSummary: String,
    val processSummary: String,
    val searchQueries: List<String>,
    val searchScope: List<String>,
    val shouldSearch: Boolean = searchQueries.isNotEmpty(),
    val requestPayload: String? = null,
    val responsePayload: String? = null
) {
    val goalAndPlanContent: String
        get() = listOf(goalSummary, processSummary)
            .filter { value -> value.isNotBlank() }
            .joinToString("\n")

    fun toPromptSection(): String {
        return buildString {
            appendLine("should_search=$shouldSearch")
            appendLine("goal_summary=$goalSummary")
            appendLine("process_summary=$processSummary")
            appendLine("search_query_count=${searchQueries.size}")
            searchQueries.forEachIndexed { index, query ->
                appendLine("- query_rank=${index + 1} | query=$query")
            }
            appendLine("search_scope_count=${searchScope.size}")
            searchScope.forEachIndexed { index, scope ->
                appendLine("- scope_rank=${index + 1} | scope=$scope")
            }
        }.trim()
    }
}

data class SearchAttributionSource(
    val title: String,
    val url: String,
    val snippet: String? = null
)

data class SearchAttribution(
    val provider: String,
    val query: String,
    val sources: List<SearchAttributionSource> = emptyList()
)

data class SearchEnhancementTurnContext(
    val goalAndPlanContent: String? = null,
    val searchQueries: List<String> = emptyList(),
    val searchScope: List<String> = emptyList(),
    val searchPlanRequestPayload: String? = null,
    val searchPlanResponsePayload: String? = null,
    val searchDetailContent: String? = null,
    val searchAttribution: SearchAttribution? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val stage: ConversationStage? = null,
    val goalAndPlanContent: String? = null,
    val searchDetailContent: String? = null,
    val searchSummaryContent: String? = null,
    val tokenUsage: TokenUsage? = null,
    val reasoningContent: String? = null,
    val searchAttribution: SearchAttribution? = null,
    val turnOptions: ChatTurnOptions? = null
)

data class TurnSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val stage: ConversationStage,
    val workflowLane: WorkflowLane? = WorkflowLane.PASSIVE,
    val stageOwner: StageOwner? = StageOwner.USER,
    val passiveUserTransitionIntent: PassiveUserTransitionIntent? = null,
    val passiveUserProgressSignal: PassiveUserProgressSignal? = null,
    val currentPhase: CurrentPhase? = null,
    val userRequestSemantic: UserRequestSemantic? = null,
    val stageTransitionRecommendation: StageTransitionRecommendation? = null,
    val proactiveOpportunitySignal: ProactiveOpportunitySignal? = null,
    val activeCandidateId: String?,
    val assistantReply: String,
    val candidates: List<IntentCandidate>,
    val semanticIntentState: SemanticIntentState? = null,
    val taskDraft: TaskDraft? = null,
    val taskRecord: TaskRecord? = null,
    val goalAndPlanContent: String? = null,
    val searchQueries: List<String> = emptyList(),
    val searchScope: List<String> = emptyList(),
    val searchPlanRequestPayload: String? = null,
    val searchPlanResponsePayload: String? = null,
    val semanticRequestPayload: String? = null,
    val semanticResponsePayload: String? = null,
    val persistedAt: Long = System.currentTimeMillis()
)

data class ExecutionEvent(
    val id: String = UUID.randomUUID().toString(),
    val candidateId: String?,
    val phase: ExecutionEventPhase = ExecutionEventPhase.INFO,
    val lifecycleStatus: ExecutionLifecycleStatus? = null,
    val summary: String,
    val keyInfo: String? = null,
    val automationResponsePayload: String? = null,
    val startedAt: Long = System.currentTimeMillis()
)

fun resolveExecutionEventPhase(
    phase: ExecutionEventPhase?,
    lifecycleStatus: ExecutionLifecycleStatus?,
    summary: String
): ExecutionEventPhase {
    if (phase != null) {
        return phase
    }
    return when {
        summary.contains("started", ignoreCase = true) -> ExecutionEventPhase.STARTING
        lifecycleStatus == ExecutionLifecycleStatus.COMPLETED -> ExecutionEventPhase.COMPLETED
        lifecycleStatus == ExecutionLifecycleStatus.FAILED -> ExecutionEventPhase.FAILED
        lifecycleStatus == ExecutionLifecycleStatus.RUNNING -> ExecutionEventPhase.RUNNING
        else -> ExecutionEventPhase.INFO
    }
}

fun TaskExecutionBoundaryPacket.toExecutionKeyInfo(routeInfo: String? = null): String {
    return toExecutionKeyInfo(
        routeInfo = routeInfo,
        routeDecisionRecord = null,
        routeDecisionHistory = emptyList(),
        preferenceRecallDebugSnapshot = null,
        preferenceMappingTrace = null
    )
}

fun TaskExecutionBoundaryPacket.toExecutionKeyInfo(
    routeInfo: String? = null,
    routeDecisionRecord: RouteDecisionRecord? = null,
    routeDecisionHistory: List<RouteDecisionRecord> = emptyList(),
    preferenceRecallDebugSnapshot: PreferenceRecallDebugSnapshot? = null,
    preferenceMappingTrace: PreferenceSlotMappingTrace? = null
): String {
    val baseSegments = listOf(
        "task=${taskId.ifBlank { "-" }}",
        "action_code=${actionCode.wireName.ifBlank { "-" }}",
        "target=${executionObjectiveSummary.ifBlank { "-" }}",
        "capability=${capabilityId.orEmpty().ifBlank { "-" }}",
        "process=${processId.orEmpty().ifBlank { "-" }}",
        "missing=${missingInformation.joinToString(",").ifBlank { "-" }}"
    )
    val routeSegment = routeInfo?.trim()?.takeIf { value -> value.isNotBlank() }?.let { value ->
        "route=${sanitizeExecutionKeyInfoValue(value)}"
    }
    val effectiveRouteDecisionHistory = routeDecisionHistory.ifEmpty { listOfNotNull(routeDecisionRecord) }
    val routeDecisionSegments = routeDecisionRecord?.toExecutionKeyInfoSegments().orEmpty()
    val routeHistorySegments = effectiveRouteDecisionHistory.toRouteHistoryKeyInfoSegments()
    val preferenceSegments = listOfNotNull(
        preferenceRecallDebugSnapshot?.let { snapshot ->
            "preference_recall=${sanitizeExecutionKeyInfoValue(snapshot.summaryLine())}"
        },
        preferenceMappingTrace?.let { trace ->
            "preference_mapping=${sanitizeExecutionKeyInfoValue(trace.summaryLine())}"
        }
    )
    return (baseSegments + listOfNotNull(routeSegment) + routeDecisionSegments + routeHistorySegments + preferenceSegments).joinToString(" | ")
}

fun ExecutionRuntimeState.toExecutionKeyInfo(): String {
    val baseSegments = listOf(
        "task=${taskId.ifBlank { "-" }}",
        "capability=${capabilityId.orEmpty().ifBlank { "-" }}",
        "process=${processId.orEmpty().ifBlank { "-" }}"
    )
    val routeSegment = executionResult.routeInfo?.trim()?.takeIf { value -> value.isNotBlank() }?.let { value ->
        "route=${sanitizeExecutionKeyInfoValue(value)}"
    }
    val effectiveRouteDecisionHistory = routeDecisionHistorySnapshot()
    val routeDecisionSegments = routeDecisionRecord?.toExecutionKeyInfoSegments().orEmpty()
    val routeHistorySegments = effectiveRouteDecisionHistory.toRouteHistoryKeyInfoSegments()
    val preferenceSegments = listOfNotNull(
        preferenceRecallDebugSnapshot?.let { snapshot ->
            "preference_recall=${sanitizeExecutionKeyInfoValue(snapshot.summaryLine())}"
        },
        preferenceMappingTrace?.let { trace ->
            "preference_mapping=${sanitizeExecutionKeyInfoValue(trace.summaryLine())}"
        }
    )
    return (baseSegments + listOfNotNull(routeSegment) + routeDecisionSegments + routeHistorySegments + preferenceSegments).joinToString(" | ")
}

private fun sanitizeExecutionKeyInfoValue(value: String): String {
    return value.replace("|", "/").replace('\n', ' ').trim()
}

data class RouteDecisionRecord(
    val decisionId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val traceId: String? = null,
    val attemptIndex: Int = 1,
    val selectedRoute: String,
    val routeLabel: String,
    val routeEntryType: ExecutionRouteEntryType? = null,
    val selectedToolId: String? = null,
    val selectedProcessId: String? = null,
    val candidateSummary: String? = null,
    val reasonSummary: String? = null,
    val routeInfo: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

internal fun RouteDecisionRecord.summaryLine(): String {
    return buildString {
        append("attempt=")
        append(attemptIndex)
        append("; label=")
        append(routeLabel)
        append("; selected=")
        append(selectedRoute)
        selectedProcessId?.takeIf { value -> value.isNotBlank() }?.let { value ->
            append("; process=")
            append(value)
        }
        candidateSummary?.takeIf { value -> value.isNotBlank() }?.let { value ->
            append("; candidate=")
            append(value)
        }
        reasonSummary?.takeIf { value -> value.isNotBlank() }?.let { value ->
            append("; reason=")
            append(value)
        }
    }
}

internal fun List<RouteDecisionRecord>.historySummaryLine(limit: Int = 3): String {
    if (isEmpty()) {
        return "none"
    }
    val sortedRecords = sortedWith(compareBy<RouteDecisionRecord> { record -> record.attemptIndex }.thenBy { record -> record.createdAt })
    val visibleRecords = sortedRecords.takeLast(limit)
    val visibleSummary = visibleRecords.joinToString(" -> ") { record ->
        "#${record.attemptIndex}:${record.routeLabel}/${record.selectedRoute}"
    }
    return if (sortedRecords.size > visibleRecords.size) {
        "... -> $visibleSummary"
    } else {
        visibleSummary
    }
}

internal fun ExecutionRuntimeState.routeDecisionHistorySnapshot(): List<RouteDecisionRecord> {
    return routeDecisionHistory.ifEmpty { listOfNotNull(routeDecisionRecord) }
}

private fun RouteDecisionRecord.toExecutionKeyInfoSegments(): List<String> {
    return buildList {
        add("route_decision_id=${sanitizeExecutionKeyInfoValue(decisionId)}")
        add("route_attempt=$attemptIndex")
        add("route_label=${sanitizeExecutionKeyInfoValue(routeLabel)}")
        add("route_selected=${sanitizeExecutionKeyInfoValue(selectedRoute)}")
        candidateSummary?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("route_candidate=${sanitizeExecutionKeyInfoValue(value)}")
        }
        reasonSummary?.takeIf { value -> value.isNotBlank() }?.let { value ->
            add("route_reason=${sanitizeExecutionKeyInfoValue(value)}")
        }
    }
}

private fun List<RouteDecisionRecord>.toRouteHistoryKeyInfoSegments(): List<String> {
    if (size <= 1) {
        return emptyList()
    }
    return listOf(
        "route_attempts=$size",
        "route_history=${sanitizeExecutionKeyInfoValue(historySummaryLine())}"
    )
}

data class ExecutionTraceStep(
    val stepId: String = UUID.randomUUID().toString(),
    val stepType: String,
    val groundingMode: String,
    val expectedOutcome: String,
    val fallbackPolicy: String,
    val riskLevel: String,
    val verificationSignals: List<String> = emptyList(),
    val continuationMode: String = "STOP",
    val note: String? = null,
    val actionType: VisionActionType? = null,
    val locatorHint: String? = null,
    val targetX: Float? = null,
    val targetY: Float? = null,
    val inputText: String? = null,
    val swipeFromX: Float? = null,
    val swipeFromY: Float? = null,
    val swipeToX: Float? = null,
    val swipeToY: Float? = null,
    val actionDurationMs: Long? = null,
    val pageSignature: String? = null
)

data class ExecutionTrace(
    val traceId: String = UUID.randomUUID().toString(),
    val candidateId: String?,
    val selectedToolId: String?,
    val processId: String?,
    val steps: List<ExecutionTraceStep>,
    val startedAt: Long = System.currentTimeMillis()
)

enum class ExecutionRouteEntryType {
    PROCESS_REFERENCE,
    SHORTCUT,
    EXPLORATORY,
    SYSTEM_INTENT
}

data class ExecutionResult(
    val candidateId: String?,
    val selectedToolId: String?,
    val selectedProcessId: String?,
    val lifecycleStatus: ExecutionLifecycleStatus,
    val summary: String,
    val latestAutomationResponsePayload: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val routeInfo: String? = null,
    val routeEntryType: ExecutionRouteEntryType? = null
)

data class ExecutionRuntimeState(
    val candidateId: String?,
    val taskId: String,
    val taskUpdatedAt: Long,
    val capabilityId: String? = null,
    val processId: String? = null,
    val routeDecisionSource: String? = null,
    val routeReasonSummary: String? = null,
    val verificationChecks: List<ExecutionCheck> = emptyList(),
    val executionResult: ExecutionResult,
    val executionTrace: ExecutionTrace,
    val routeDecisionRecord: RouteDecisionRecord? = null,
    val preferenceRecallDebugSnapshot: PreferenceRecallDebugSnapshot? = null,
    val preferenceMappingTrace: PreferenceSlotMappingTrace? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val routeDecisionHistory: List<RouteDecisionRecord> = emptyList()
)

data class ExecutionSession(
    val executionRuntime: ExecutionRuntimeState? = null,
    val boundaryPacket: TaskExecutionBoundaryPacket? = null,
    @Transient
    val processReuseContext: CandidateProcessReferenceContext? = null,
    val processRuntime: ProcessRuntimeState? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ConversationSlice(
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val snapshots: MutableList<TurnSnapshot> = mutableListOf()
)

data class IntentSlice(
    val currentState: LocalConversationState = LocalConversationState(),
    val semanticRuntimePreferences: SemanticRuntimePreferences? = SemanticRuntimePreferences()
)

data class ExecutionSlice(
    val preparedExecutionStart: PreparedExecutionStart? = null,
    val executionSession: ExecutionSession? = null,
    val executionEvents: MutableList<ExecutionEvent> = mutableListOf(),
    val executionTraces: MutableList<ExecutionTrace> = mutableListOf(),
    val latestCompletedProcessReviewContext: ProcessReviewContext? = null,
    val pendingProcessRecoveryContext: ProcessRecoveryContext? = null
)

data class AssetSlice(
    val processExtractionRawMaterials: MutableList<CanonicalTraceRawMaterial> = mutableListOf(),
    val readyProcessAssets: MutableList<ReadyProcessAsset> = mutableListOf(),
    val processAssetEntries: MutableList<ProcessAssetEntry> = mutableListOf(),
    val pageEvidenceAssets: MutableList<PageEvidenceAsset> = mutableListOf(),
    val processShortcutAtlas: MutableList<ProcessShortcutCandidate> = mutableListOf(),
    val processAssetEvents: MutableList<ProcessAssetEvent> = mutableListOf(),
    val processExtractionConsumedIds: MutableList<String> = mutableListOf(),
    val processLearningMaterials: MutableList<ProcessLearningMaterial> = mutableListOf(),
    val lastProcessCurationSummary: ProcessCurationSummary? = null
)

data class MemorySlice(
    val memoryState: MemoryState? = MemoryState()
)

data class CanonicalTraceRawMaterial(
    val id: String = UUID.randomUUID().toString(),
    val traceId: String,
    val candidateId: String?,
    val selectedToolId: String?,
    val processId: String,
    val objective: String,
    val lifecycleStatus: ExecutionLifecycleStatus,
    val steps: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val processAction: String? = null,
    val slotEvidenceSnapshot: TaskSlotEvidenceSnapshot? = null
)

data class ProcessAssetStepBinding(
    val stepType: String,
    val actionType: VisionActionType = VisionActionType.TAP,
    val locatorHint: String? = null,
    val targetX: Float? = null,
    val targetY: Float? = null,
    val inputText: String? = null,
    val swipeFromX: Float? = null,
    val swipeFromY: Float? = null,
    val swipeToX: Float? = null,
    val swipeToY: Float? = null,
    val actionDurationMs: Long? = null,
    val verificationSignals: List<String> = emptyList(),
    val pageSignature: String? = null,
    val note: String? = null
)

data class ProcessStageReference(
    val stageId: String = UUID.randomUUID().toString(),
    val stageName: String = "",
    val stageGoal: String = "",
    val entrySignals: List<String> = emptyList(),
    val exitSignals: List<String> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val pageSemanticHints: List<String> = emptyList(),
    val transitionNotes: List<String> = emptyList()
)

data class ProcessPageSemanticAnchor(
    val anchorId: String = UUID.randomUUID().toString(),
    val stageName: String? = null,
    val semanticRole: String = "",
    val pageSignature: String? = null,
    val locatorHints: List<String> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

data class ProcessExemplarActionSummary(
    val exemplarId: String = UUID.randomUUID().toString(),
    val stageName: String? = null,
    val stepType: String = "",
    val actionType: VisionActionType = VisionActionType.TAP,
    val outcomeSignal: String? = null,
    val locatorHint: String? = null,
    val pageSignature: String? = null,
    val note: String? = null
)

data class ProcessFailurePattern(
    val patternId: String = UUID.randomUUID().toString(),
    val stageName: String? = null,
    val failureMode: String = "",
    val evidenceSignals: List<String> = emptyList(),
    val recoveryHints: List<String> = emptyList(),
    val note: String? = null
)

data class ProcessLearningMaterial(
    val materialId: String = UUID.randomUUID().toString(),
    val traceId: String,
    val processId: String,
    val appScope: String,
    val domain: String,
    val objective: String,
    val stageTransitions: List<String> = emptyList(),
    val pageSemanticAnchors: List<ProcessPageSemanticAnchor> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val exemplarActionSummaries: List<ProcessExemplarActionSummary> = emptyList(),
    val failurePatterns: List<ProcessFailurePattern> = emptyList(),
    val finalBusinessOutcome: String? = null,
    val lineageTraceIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val processAction: String? = null,
    val pathIndex: Int = 1,
    val slotEvidenceSnapshot: TaskSlotEvidenceSnapshot? = null,
    val slotHints: List<ProcessSlotHint> = emptyList()
)

data class ReadyProcessAsset(
    val processId: String,
    val domain: String,
    val appScope: String,
    val semanticDescription: String,
    val stages: List<String>,
    val acceptanceCriteria: List<String>,
    val version: Int,
    val lineageSourceTraceId: String,
    val lastDerivedAt: Long = System.currentTimeMillis(),
    val processAction: String? = null,
    val pathIndex: Int = 1,
    val stageReferences: List<ProcessStageReference> = emptyList(),
    val pageSemanticAnchors: List<ProcessPageSemanticAnchor> = emptyList(),
    val verificationSignals: List<String> = emptyList(),
    val exemplarActionSummaries: List<ProcessExemplarActionSummary> = emptyList(),
    val successPatterns: List<String> = emptyList(),
    val failurePatterns: List<ProcessFailurePattern> = emptyList(),
    val generalizationNotes: List<String> = emptyList(),
    val referenceWeight: Double = 0.0,
    val slotHints: List<ProcessSlotHint> = emptyList()
)

data class PageEvidenceAsset(
    val evidenceId: String,
    val appScope: String,
    val processId: String,
    val pageSignature: String,
    val verificationSignals: List<String> = emptyList(),
    val locatorHints: List<String> = emptyList(),
    val observationCount: Int = 0,
    val version: Int = 1,
    val lineageSourceTraceIds: List<String> = emptyList(),
    val lastObservedAt: Long = System.currentTimeMillis()
)

data class ProcessShortcutCandidate(
    val shortcutId: String,
    val appScope: String,
    val processId: String,
    val screenSignature: String,
    val elementRole: String,
    val tapX: Float = 0.5f,
    val tapY: Float = 0.5f,
    val verificationHint: String = "shortcut_verified",
    val stabilityScore: Double,
    val version: Int,
    val lineageSourceTraceId: String,
    val lastDerivedAt: Long = System.currentTimeMillis(),
    val processAction: String? = null,
    val pathIndex: Int = 1,
    val slotHints: List<ProcessSlotHint> = emptyList()
)

enum class ProcessCandidateNamingStage {
    TEMPORARY,
    CANONICAL
}

enum class ProcessCandidateCurationState {
    PENDING,
    READY,
    FAILED,
    SUPERSEDED
}

enum class ProcessFeedbackType {
    THUMBS_UP,
    THUMBS_DOWN
}

data class ProcessCandidateRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val processId: String,
    val appScope: String,
    val domain: String,
    val recordName: String,
    val namingStage: ProcessCandidateNamingStage = ProcessCandidateNamingStage.TEMPORARY,
    val curationState: ProcessCandidateCurationState = ProcessCandidateCurationState.PENDING,
    val sourceTraceId: String,
    val sourceToolId: String? = null,
    val sourceType: String = "execution_success",
    val revision: Int = 1,
    val successCount: Int = 1,
    val reviewComment: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val processAction: String? = null,
    val pathIndex: Int = 1
)

data class ProcessFeedbackRecord(
    val feedbackId: String = UUID.randomUUID().toString(),
    val processId: String,
    val appScope: String,
    val feedbackType: ProcessFeedbackType,
    val comment: String,
    val recordName: String? = null,
    val sourceTraceId: String? = null,
    val recordedAt: Long = System.currentTimeMillis()
)

data class PendingProcessFeedbackDraft(
    val feedbackType: ProcessFeedbackType,
    val reviewContext: ProcessReviewContext,
    val createdAt: Long = System.currentTimeMillis()
)

data class LocalConversationState(
    val stage: ConversationStage = ConversationStage.ACCUMULATING,
    val workflowLane: WorkflowLane? = WorkflowLane.PASSIVE,
    val stageOwner: StageOwner? = StageOwner.USER,
    val lastPassiveUserTransitionIntent: PassiveUserTransitionIntent? = null,
    val lastPassiveUserProgressSignal: PassiveUserProgressSignal? = null,
    val currentPhase: CurrentPhase? = null,
    val userRequestSemantic: UserRequestSemantic? = null,
    val stageTransitionRecommendation: StageTransitionRecommendation? = null,
    val lastProactiveOpportunitySignal: ProactiveOpportunitySignal? = null,
    val pendingProactiveDeliveryPlan: ProactiveDeliveryPlan? = null,
    val lastDeliveredProactivePlanFingerprint: String? = null,
    val lastDeliveredProactivePlanAt: Long? = null,
    val proactiveDeliveryCooldownUntil: Long? = null,
    val activeCandidateId: String? = null,
    val currentDialogueCandidates: List<IntentCandidate> = emptyList(),
    val dormantHistoricalCandidates: List<IntentCandidate> = emptyList(),
    val awaitingApproval: Boolean = false,
    val currentSemanticIntentState: SemanticIntentState? = null,
    val currentTaskDraft: TaskDraft? = null,
    val currentTaskRecord: TaskRecord? = null,
    val executionStartedAt: Long? = null,
    val pendingExecutionRecovery: ProcessRecoveryContext? = null,
    @Deprecated("Migration-only compatibility slot for legacy store payloads.")
    @SerializedName("latestCompletedExecutionReview")
    val legacyExecutionReviewForMigration: ProcessReviewContext? = null,
    val pendingProcessFeedbackDraft: PendingProcessFeedbackDraft? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

data class PrototypeStoreData(
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val snapshots: MutableList<TurnSnapshot> = mutableListOf(),
    var currentConversationSlice: ConversationSlice? = null,
    var currentIntentSlice: IntentSlice? = null,
    val executionEvents: MutableList<ExecutionEvent> = mutableListOf(),
    var currentExecutionRuntime: ExecutionRuntimeState? = null,
    var currentExecutionSlice: ExecutionSlice? = null,
    var currentProcessRuntime: ProcessRuntimeState? = null,
    val executionTraces: MutableList<ExecutionTrace> = mutableListOf(),
    var currentAssetSlice: AssetSlice? = null,
    val processExtractionRawMaterials: MutableList<CanonicalTraceRawMaterial> = mutableListOf(),
    val readyProcessAssets: MutableList<ReadyProcessAsset> = mutableListOf(),
    val processAssetEntries: MutableList<ProcessAssetEntry> = mutableListOf(),
    val pageEvidenceAssets: MutableList<PageEvidenceAsset> = mutableListOf(),
    val processShortcutAtlas: MutableList<ProcessShortcutCandidate> = mutableListOf(),
    val processAssetEvents: MutableList<ProcessAssetEvent> = mutableListOf(),
    val processExtractionConsumedIds: MutableList<String> = mutableListOf(),
    var lastProcessCurationSummary: ProcessCurationSummary? = null,
    var latestCompletedProcessReviewContext: ProcessReviewContext? = null,
    var pendingProcessRecoveryContext: ProcessRecoveryContext? = null,
    var semanticRuntimePreferences: SemanticRuntimePreferences? = SemanticRuntimePreferences(),
    var currentState: LocalConversationState = LocalConversationState(),
    var currentMemorySlice: MemorySlice? = null,
    var memoryState: MemoryState? = MemoryState(),
    var earningsHubState: EarningsHubState? = null,
    val processLearningMaterials: MutableList<ProcessLearningMaterial> = mutableListOf()
)

data class SemanticTurnResponse(
    val assistantReply: String,
    val stage: ConversationStage,
    val currentPhase: CurrentPhase? = null,
    val userRequestSemantic: UserRequestSemantic? = null,
    val stageTransitionRecommendation: StageTransitionRecommendation? = null,
    val workflowLane: WorkflowLane = WorkflowLane.PASSIVE,
    val stageOwner: StageOwner = StageOwner.USER,
    val passiveUserTransitionIntent: PassiveUserTransitionIntent? = null,
    val userProgressSignal: UserProgressSignal,
    val proactiveOpportunitySignal: ProactiveOpportunitySignal? = null,
    val activeCandidateId: String?,
    val candidates: List<IntentCandidate>,
    val semanticIntentState: SemanticIntentState? = null,
    val taskDraft: TaskDraft? = null,
    val semanticSummary: String,
    val searchSummaryContent: String? = null,
    val tokenUsage: TokenUsage? = null,
    val reasoningContent: String? = null,
    val requestPayload: String? = null,
    val responsePayload: String? = null
) {
    val passiveUserProgressSignal: PassiveUserProgressSignal
        get() = userProgressSignal
}

private val semanticTurnResponseBoundaryPacketRegistry = WeakHashMap<SemanticTurnResponse, TaskExecutionBoundaryPacket>()

internal fun SemanticTurnResponse.attachExecutionBoundaryPacket(
    executionBoundaryPacket: TaskExecutionBoundaryPacket?
): SemanticTurnResponse {
    synchronized(semanticTurnResponseBoundaryPacketRegistry) {
        if (executionBoundaryPacket == null) {
            semanticTurnResponseBoundaryPacketRegistry.remove(this)
        } else {
            semanticTurnResponseBoundaryPacketRegistry[this] = executionBoundaryPacket
        }
    }
    return this
}

internal fun SemanticTurnResponse.resolveAttachedExecutionBoundaryPacket(): TaskExecutionBoundaryPacket? {
    synchronized(semanticTurnResponseBoundaryPacketRegistry) {
        return semanticTurnResponseBoundaryPacketRegistry[this]
    }
}

fun defaultStageOwnerFor(workflowLane: WorkflowLane): StageOwner = when (workflowLane) {
    WorkflowLane.PASSIVE -> StageOwner.USER
    WorkflowLane.PROACTIVE -> StageOwner.PROACTIVE_ENGINE
}

fun LocalConversationState.effectiveWorkflowLane(): WorkflowLane = workflowLane ?: WorkflowLane.PASSIVE

fun LocalConversationState.effectiveStageOwner(): StageOwner = stageOwner ?: defaultStageOwnerFor(effectiveWorkflowLane())

private fun LocalConversationState.resolveSemanticDialogueCandidates(): List<IntentCandidate> {
    return currentSemanticIntentState?.candidateIntents
        ?.map { candidate -> candidate.toSemanticShadowIntentCandidate() }
        .orEmpty()
}

private fun LocalConversationState.resolvePersistedOrSemanticDialogueCandidates(): List<IntentCandidate> {
    val semanticCandidates = resolveSemanticDialogueCandidates()
    return if (semanticCandidates.isNotEmpty()) {
        semanticCandidates
    } else {
        currentDialogueCandidates
    }
}

private fun LocalConversationState.resolvePersistedOrSemanticActiveCandidateId(): String? {
    val semanticCandidates = currentSemanticIntentState?.candidateIntents.orEmpty()
    if (semanticCandidates.isNotEmpty()) {
        return currentSemanticIntentState?.activeIntentId?.takeIf { activeIntentId ->
            semanticCandidates.any { candidate -> candidate.intentId == activeIntentId }
        }
    }
    return activeCandidateId
}

private fun SemanticIntentCandidate.toSemanticShadowIntentCandidate(): IntentCandidate {
    return IntentCandidate(
        id = intentId,
        anchorObject = anchorObject,
        focusedObject = focusedObject,
        action = canonicalAction?.name?.lowercase(Locale.US) ?: rawActionLabel,
        readiness = toSemanticShadowCandidateReadiness(),
        confidence = confidence,
        evidence = reasonSummary.orEmpty(),
        rationale = reasonSummary.orEmpty(),
        detailSlots = detailSlots,
        missingRequiredSlots = emptyList(),
        canStartExecution = canStartExecution
    )
}

private fun SemanticIntentCandidate.toSemanticShadowCandidateReadiness(): CandidateReadiness {
    return when {
        canStartExecution || readiness == SemanticIntentReadiness.READY_FOR_EXECUTION -> CandidateReadiness.READY_TO_START
        readiness == SemanticIntentReadiness.READY_FOR_CONFIRMATION ||
            readiness == SemanticIntentReadiness.READY_FOR_OFFER -> CandidateReadiness.READY_TO_PREPARE
        readiness == SemanticIntentReadiness.CONVERGING -> CandidateReadiness.ACCUMULATING
        else -> CandidateReadiness.EMERGING
    }
}

private fun Map<DetailSlotKey, String>.toTaskSchemaDetailSlots(): List<DetailSlot> {
    return entries.mapNotNull { (detailSlotKey, rawValue) ->
        val normalizedValue = rawValue.trim()
        if (normalizedValue.isBlank()) {
            return@mapNotNull null
        }
        DetailSlot(
            key = detailSlotKey,
            value = normalizedValue,
            source = "TASK_SCHEMA"
        )
    }
}

private fun TaskDraft.toTaskSchemaIntentCandidate(): IntentCandidate? {
    val anchorObject = targetKey?.trim().orEmpty().ifBlank {
        targetLabel?.trim().orEmpty()
    }
    val focusedObject = targetLabel?.trim().orEmpty().ifBlank {
        targetKey?.trim().orEmpty()
    }
    val actionIntent = actionCode?.wireName?.trim().orEmpty()
    if (anchorObject.isBlank() && focusedObject.isBlank() && actionIntent.isBlank()) {
        return null
    }
    val candidateId = listOf(
        "task_draft",
        actionCode?.wireName,
        processId,
        capabilityId,
        targetKey,
        targetLabel
    ).mapNotNull { value ->
        value?.trim()?.takeIf { text -> text.isNotBlank() }
    }.joinToString(":").ifBlank { "task_draft" }
    return IntentCandidate(
        id = candidateId,
        anchorObject = anchorObject,
        focusedObject = focusedObject,
        action = actionIntent.ifBlank { processId?.trim().orEmpty() },
        readiness = CandidateReadiness.READY_TO_PREPARE,
        confidence = 0.75,
        evidence = reasonSummary.orEmpty(),
        rationale = reasonSummary.orEmpty(),
        detailSlots = detailSlots.toTaskSchemaDetailSlots(),
        missingRequiredSlots = emptyList(),
        canStartExecution = false
    )
}

private fun TaskRecord.toTaskSchemaIntentCandidate(): IntentCandidate {
    return IntentCandidate(
        id = taskId,
        anchorObject = targetKey,
        focusedObject = displayTarget(),
        action = actionCode.wireName,
        readiness = when (phase) {
            TaskPhase.ACCUMULATING -> CandidateReadiness.ACCUMULATING
            TaskPhase.PREPARING -> CandidateReadiness.READY_TO_PREPARE
            TaskPhase.EXECUTING -> CandidateReadiness.READY_TO_START
            TaskPhase.LEARNING -> CandidateReadiness.EMERGING
        },
        confidence = 0.92,
        evidence = reasonSummary.orEmpty(),
        rationale = reasonSummary.orEmpty(),
        detailSlots = detailSlots.toTaskSchemaDetailSlots(),
        missingRequiredSlots = emptyList(),
        canStartExecution = phase == TaskPhase.EXECUTING
    )
}

fun PrototypeStoreData.currentDialogueCandidates(): List<IntentCandidate> =
    resolveCurrentState().resolvePersistedOrSemanticDialogueCandidates()

fun PrototypeStoreData.resolveCurrentActiveCandidateId(): String? =
    resolveCurrentState().resolvePersistedOrSemanticActiveCandidateId()

fun PrototypeStoreData.resolveTaskFirstCandidate(): IntentCandidate? {
    val currentState = resolveCurrentState()
    return currentState.currentTaskRecord?.toTaskSchemaIntentCandidate()
        ?: currentState.currentTaskDraft?.toTaskSchemaIntentCandidate()
        ?: activeCandidate()
}

fun PrototypeStoreData.activeCandidateContext(): List<IntentCandidate> = resolveTaskFirstCandidate()?.let(::listOf) ?: emptyList()

fun collectExplicitReMentionedCandidateIds(
    userMessage: String,
    candidates: List<IntentCandidate>
): Set<String> {
    val normalizedUserMessage = normalizeForMatching(userMessage)
    if (normalizedUserMessage.isBlank() || candidates.isEmpty()) {
        return emptySet()
    }

    val explicitObjectMatches = candidates.filter { candidate ->
        listOf(candidate.anchoredLabel, candidate.focusedObject)
            .map(::normalizeForMatching)
            .filter { it.length >= 2 }
            .distinct()
            .any { normalizedUserMessage.contains(it) }
    }.map { it.id }

    val uniqueAnchorMatches = candidates
        .groupBy { candidate -> normalizeForMatching(candidate.anchorObject) }
        .filterKeys { normalizedAnchor ->
            normalizedAnchor.length >= 2 && normalizedUserMessage.contains(normalizedAnchor)
        }
        .filterValues { matchingCandidates -> matchingCandidates.size == 1 }
        .values
        .flatten()
        .map { it.id }

    return (explicitObjectMatches + uniqueAnchorMatches).toSet()
}

fun migrateLegacySnapshotState(store: PrototypeStoreData): PrototypeStoreData {
    val currentState = store.currentState
    if (
        currentState.currentSemanticIntentState != null ||
        currentState.currentDialogueCandidates.isNotEmpty() ||
        currentState.dormantHistoricalCandidates.isNotEmpty()
    ) {
        return store
    }

    val lastSnapshot = store.snapshots.lastOrNull() ?: return store
    val legacyCandidates = mergeCandidateLists(
        emptyList(),
        store.snapshots.flatMap { snapshot -> snapshot.candidates }
    )
    if (legacyCandidates.isEmpty()) {
        return store
    }

    val legacyActiveCandidateId = currentState.activeCandidateId ?: lastSnapshot.activeCandidateId
    val hadLegacyLiveState = legacyActiveCandidateId != null ||
        resolveTaskFirstExecutionBoundaryPacket(currentState) != null ||
        currentState.executionStartedAt != null ||
        currentState.stage.normalized() != ConversationStage.ACCUMULATING

    return store.copy(
        messages = store.messages.toMutableList(),
        snapshots = store.snapshots.toMutableList(),
        executionEvents = store.executionEvents.toMutableList(),
        currentState = if (hadLegacyLiveState) {
            currentState.copy(
                activeCandidateId = legacyActiveCandidateId,
                currentDialogueCandidates = legacyCandidates
            )
        } else {
            currentState.copy(
                dormantHistoricalCandidates = mergeCandidateLists(
                    currentState.dormantHistoricalCandidates,
                    legacyCandidates
                )
            )
        }
    )
}

private fun normalizeForMatching(value: String): String {
    return value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
}

private fun mergeCandidateLists(
    existingCandidates: List<IntentCandidate>,
    newCandidates: List<IntentCandidate>
): List<IntentCandidate> {
    val merged = linkedMapOf<String, IntentCandidate>()
    existingCandidates.forEach { merged[it.id] = it }
    newCandidates.forEach { merged[it.id] = it }
    return merged.values.toList()
}

fun PrototypeStoreData.activeCandidate(): IntentCandidate? {
    val currentCandidates = currentDialogueCandidates()
    if (currentCandidates.isEmpty()) {
        return null
    }

    resolveCurrentActiveCandidateId()?.let { activeId ->
        currentCandidates.firstOrNull { it.id == activeId }?.let { return it }
    }

    return currentCandidates.firstOrNull()
}

internal fun normalizeModelNullableString(value: String?): String? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    if (normalized.equals("null", ignoreCase = true)) {
        return null
    }
    return normalized
}

private fun normalizeModelString(value: String?): String {
    return normalizeModelNullableString(value).orEmpty()
}

private fun DetailSlot.normalizeModelFields(): DetailSlot? {
    val normalizedValue = normalizeModelNullableString(value) ?: return null
    val normalizedSource = normalizeModelNullableString(source) ?: "USER_CONTEXT"
    return copy(value = normalizedValue, source = normalizedSource)
}

private fun IntentCandidate.normalizeModelFields(): IntentCandidate {
    val normalizedId = normalizeModelNullableString(id) ?: "candidate_${UUID.randomUUID()}"
    return copy(
        id = normalizedId,
        anchorObject = normalizeModelString(anchorObject),
        focusedObject = normalizeModelString(focusedObject),
        action = normalizeModelString(action),
        evidence = normalizeModelString(evidence),
        rationale = normalizeModelString(rationale),
        detailSlots = detailSlots.mapNotNull { slot -> slot.normalizeModelFields() }
    )
}

private fun SemanticIntentCandidate.normalizeModelFields(): SemanticIntentCandidate {
    val normalizedIntentId = normalizeModelNullableString(intentId) ?: "semantic_intent_${UUID.randomUUID()}"
    return copy(
        intentId = normalizedIntentId,
        anchorObject = normalizeModelString(anchorObject),
        focusedObject = normalizeModelString(focusedObject),
        rawActionLabel = normalizeModelString(rawActionLabel),
        detailSlots = detailSlots.mapNotNull { slot -> slot.normalizeModelFields() },
        constraints = constraints.mapNotNull(::normalizeModelNullableString),
        confirmationPolicy = normalizeModelNullableString(confirmationPolicy),
        executionConstraints = executionConstraints.mapNotNull(::normalizeModelNullableString),
        executionPreferenceSignals = executionPreferenceSignals.mapNotNull(::normalizeModelNullableString),
        capabilityId = normalizeModelNullableString(capabilityId),
        processId = normalizeModelNullableString(processId),
        continuationHint = normalizeModelNullableString(continuationHint),
        reasonSummary = normalizeModelNullableString(reasonSummary)
    )
}

private fun SemanticIntentState.normalizeModelFields(): SemanticIntentState {
    val normalizedCandidates = candidateIntents.map { candidate -> candidate.normalizeModelFields() }
    val normalizedActiveIntentId = normalizeModelNullableString(activeIntentId)
        ?.takeIf { activeId -> normalizedCandidates.any { candidate -> candidate.intentId == activeId } }
        ?: normalizedCandidates.firstOrNull()?.intentId
    return copy(
        activeIntentId = normalizedActiveIntentId,
        candidateIntents = normalizedCandidates
    )
}

private fun TaskExecutionBoundaryPacket.normalizeModelFields(): TaskExecutionBoundaryPacket {
    val normalizedTaskId = normalizeModelNullableString(taskId) ?: "task_boundary_${UUID.randomUUID()}"
    val normalizedDetailSlots = detailSlots.mapNotNull { (key, rawValue) ->
        val normalizedValue = normalizeModelNullableString(rawValue) ?: return@mapNotNull null
        key to normalizedValue
    }.toMap()
    return copy(
        taskId = normalizedTaskId,
        targetKey = normalizeModelString(targetKey),
        targetLabel = normalizeModelNullableString(targetLabel),
        detailSlots = normalizedDetailSlots,
        capabilityId = normalizeModelNullableString(capabilityId),
        processId = normalizeModelNullableString(processId),
        checkpointId = normalizeModelNullableString(checkpointId),
        reasonSummary = normalizeModelNullableString(reasonSummary),
        missingInformation = missingInformation.mapNotNull(::normalizeModelNullableString),
        requiredDetailSlots = requiredDetailSlots.distinct()
    )
}

internal fun normalizeModelArtifacts(store: PrototypeStoreData): PrototypeStoreData {
    store.hydrateLegacyRootsFromSlices()
    store.snapshots.replaceAll { snapshot ->
        snapshot.copy(
            activeCandidateId = normalizeModelNullableString(snapshot.activeCandidateId),
            candidates = snapshot.candidates.map { candidate -> candidate.normalizeModelFields() },
            semanticIntentState = snapshot.semanticIntentState?.normalizeModelFields(),
            taskDraft = snapshot.taskDraft?.normalizeModelFields(),
            taskRecord = snapshot.taskRecord?.normalizeModelFields(),
            semanticRequestPayload = normalizeModelNullableString(snapshot.semanticRequestPayload),
            semanticResponsePayload = normalizeModelNullableString(snapshot.semanticResponsePayload)
        )
    }

    val resolvedState = store.resolveCurrentState()
    val normalizedCurrentDialogueCandidates = resolvedState.currentDialogueCandidates.map { candidate ->
        candidate.normalizeModelFields()
    }
    val normalizedDormantCandidates = resolvedState.dormantHistoricalCandidates.map { candidate ->
        candidate.normalizeModelFields()
    }
    val normalizedSemanticIntentState = resolvedState.currentSemanticIntentState?.normalizeModelFields()
    val persistLegacyCandidateMirrors = normalizedSemanticIntentState?.candidateIntents?.isNotEmpty() != true
    val normalizedActiveCandidateId = normalizeModelNullableString(resolvedState.activeCandidateId)
        ?.takeIf { activeId -> normalizedCurrentDialogueCandidates.any { candidate -> candidate.id == activeId } }

    store.currentState = resolvedState.copy(
        activeCandidateId = if (persistLegacyCandidateMirrors) normalizedActiveCandidateId else null,
        currentDialogueCandidates = if (persistLegacyCandidateMirrors) normalizedCurrentDialogueCandidates else emptyList(),
        dormantHistoricalCandidates = normalizedDormantCandidates,
        currentSemanticIntentState = normalizedSemanticIntentState,
        currentTaskDraft = resolvedState.currentTaskDraft?.normalizeModelFields(),
        currentTaskRecord = resolvedState.currentTaskRecord?.normalizeModelFields()
    )

    val resolvedMemoryState = store.resolveMemoryState()
    store.memoryState = resolvedMemoryState?.copy(
        activeGroundingStore = resolvedMemoryState.activeGroundingStore.map { record ->
            record.copy(
                candidateId = normalizeModelNullableString(record.candidateId) ?: "candidate_${UUID.randomUUID()}",
                anchorObject = normalizeModelString(record.anchorObject),
                focusedObject = normalizeModelString(record.focusedObject),
                action = normalizeModelString(record.action)
            )
        },
        continuationStore = resolvedMemoryState.continuationStore.map { record ->
            record.copy(
                candidateId = normalizeModelNullableString(record.candidateId) ?: "candidate_${UUID.randomUUID()}",
                anchorObject = normalizeModelString(record.anchorObject),
                focusedObject = normalizeModelString(record.focusedObject),
                action = normalizeModelString(record.action)
            )
        },
        dialoguePreferenceBacklog = resolvedMemoryState.dialoguePreferenceBacklog.map { record ->
            record.copy(
                candidateId = normalizeModelNullableString(record.candidateId),
                anchorObject = normalizeModelNullableString(record.anchorObject),
                focusedObject = normalizeModelNullableString(record.focusedObject),
                action = normalizeModelNullableString(record.action),
                userMessage = normalizeModelNullableString(record.userMessage),
                assistantReply = normalizeModelNullableString(record.assistantReply),
                detailSlots = record.detailSlots.mapNotNull { slot -> slot.normalizeModelFields() },
                semanticSummary = normalizeModelNullableString(record.semanticSummary)
            )
        }
    )

    return store.syncIntentSliceFromLegacy().syncMemorySliceFromLegacy()
}