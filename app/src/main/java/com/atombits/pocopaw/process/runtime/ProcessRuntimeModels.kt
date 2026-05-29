package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.RouteDecisionRecord
import java.util.UUID

enum class ProcessRuntimeStatus {
    IDLE,
    READY,
    RUNNING,
    WAITING_GUIDANCE,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ProcessActionType {
    APP_LAUNCH,
    SYSTEM_LAUNCH,
    MCP_CALL,
    TAP,
    SWIPE,
    LONG_PRESS,
    INPUT_TEXT,
    KEYEVENT,
    WAIT,
    DONE
}

data class ProcessAction(
    val type: ProcessActionType = ProcessActionType.DONE,
    val x: Float? = null,
    val y: Float? = null,
    val fromX: Float? = null,
    val fromY: Float? = null,
    val toX: Float? = null,
    val toY: Float? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val keyCode: Int? = null,
    val packageName: String? = null,
    val uri: String? = null,
    val expectedOutcome: String? = null,
    val reason: String? = null
)

data class ProcessRuntimeState(
    val runtimeId: String = UUID.randomUUID().toString(),
    val taskContextId: String? = null,
    val status: ProcessRuntimeStatus = ProcessRuntimeStatus.IDLE,
    val currentStep: Int = 0,
    val maxSteps: Int = 100,
    val lastActionFingerprint: String? = null,
    val sameActionRepeatCount: Int = 0,
    val matchedReadyAssetId: String? = null,
    val matchedReadyAssetName: String? = null,
    val processGuidanceLayerSummary: String? = null,
    val candidateReferenceIds: List<String> = emptyList(),
    val blockedContext: String? = null,
    val recoveryAction: String? = null,
    val retryBudget: Int = 0,
    val finalUserSummary: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ProcessReviewContext(
    val reviewId: String = UUID.randomUUID().toString(),
    val processAssetEntryId: String? = null,
    val processAssetName: String? = null,
    val finalUserSummary: String? = null,
    val verificationSummary: String? = null,
    val reviewedAt: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val routeDecisionHistory: List<RouteDecisionRecord> = emptyList()
)

data class ProcessRecoveryContext(
    val recoveryId: String = UUID.randomUUID().toString(),
    val processAssetEntryId: String? = null,
    val processAssetName: String? = null,
    val objective: String,
    val blockedContext: String,
    val recoveryAction: String,
    val selectedToolId: String? = null,
    val selectedProcessId: String? = null,
    val sourceTraceId: String? = null,
    val retryBudget: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val awaitingUserGuidance: Boolean = true,
    val guidanceReceivedAt: Long? = null,
    val taskId: String? = null,
    val routeDecisionHistory: List<RouteDecisionRecord> = emptyList()
)