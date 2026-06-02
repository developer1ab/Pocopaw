package com.atombits.pocopaw

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploratoryAutomationRunnerFillerCooldownTest {

    @Test
    fun execute_completesEntertainmentFillerWhenPlannerPayloadShowsTargetCountdownBeforeAction() = runBlocking {
        var executedActionCount = 0
        val writebacks = mutableListOf<ExecutionWritebackRecord>()
        val runner = ExploratoryAutomationRunner(
            toolBundleProvider = { _, _ -> null },
            automationClient = object : AutomationAgentClient {
                override suspend fun query(request: AutomationQueryRequest): AutomationAgentResponse {
                    return AutomationAgentResponse(
                        thought = "The target task 看广告赚金币 is visible with countdown timer 03:25, but the button looks tappable.",
                        action = BridgeAction(type = "tap", x = 0.85f, y = 0.73f),
                        message = "Trying the visible ad task button.",
                        flowState = AutomationFlowState.IN_PROGRESS,
                        businessState = AutomationBusinessState.UNKNOWN,
                        executionStatus = "ok",
                        rawPayload = "{\"thought\":\"The target task 看广告赚金币 is visible with countdown timer 03:25.\",\"action\":{\"type\":\"tap\"}}"
                    )
                }
            },
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot {
                    return ScreenCaptureSnapshot(
                        imageDataUrl = "data:image/jpeg;base64,AAA",
                        captureWidth = 1080,
                        captureHeight = 2400
                    )
                }
            },
            actionExecutor = object : BridgeActionExecutor {
                override suspend fun execute(action: BridgeAction): Boolean {
                    executedActionCount += 1
                    return true
                }
            },
            maxSteps = 2,
            settleMs = 0L,
            pause = { }
        )

        runner.execute(
            runtimeState = fillerRuntimeState(),
            boundaryPacket = fillerBoundaryPacket(),
            shortcutCandidate = null,
            executionMode = AutomationExecutionMode.VISION,
            onWriteback = { writeback -> writebacks += writeback }
        )

        assertEquals(0, executedActionCount)
        assertEquals(ExecutionLifecycleStatus.COMPLETED, writebacks.last().lifecycleStatus)
        assertTrue(writebacks.last().summary.contains("countdown/cooldown evidence"))
    }

    private fun fillerBoundaryPacket(): TaskExecutionBoundaryPacket {
        return TaskExecutionBoundaryPacket(
            taskId = "earnings:test",
            taskUpdatedAt = 1L,
            phase = TaskPhase.EXECUTING,
            actionCode = ActionCode.NAVIGATE,
            targetType = TargetType.APP,
            targetKey = "douyin_lite:filler_repeatable_decay:ad_task_20min",
            targetLabel = "Douyin Lite: 看广告赚金币",
            structuredDetailSlots = TaskDetailSlots(
                domain = mapOf(
                    "title" to "看广告赚金币",
                    "task_category" to "FILLER_REPEATABLE_DECAY",
                    "cooldown_handling" to "Treat visible countdown as completion evidence."
                )
            ),
            capabilityStack = CapabilityStack.APP,
            capabilityDomain = CapabilityDomain.ENTERTAINMENT,
            capabilityId = "app.com.ss.android.ugc.aweme.lite.open"
        )
    }

    private fun fillerRuntimeState(): ExecutionRuntimeState {
        return ExecutionRuntimeState(
            candidateId = null,
            taskId = "earnings:test",
            taskUpdatedAt = 1L,
            capabilityId = "app.com.ss.android.ugc.aweme.lite.open",
            executionResult = ExecutionResult(
                candidateId = null,
                selectedToolId = "app.com.ss.android.ugc.aweme.lite.open",
                selectedProcessId = "navigate",
                lifecycleStatus = ExecutionLifecycleStatus.RUNNING,
                summary = "Douyin Lite: 看广告赚金币",
                routeInfo = "route_guidance=task=Douyin Lite: 看广告赚金币"
            ),
            executionTrace = ExecutionTrace(
                traceId = "trace-test",
                candidateId = null,
                selectedToolId = "app.com.ss.android.ugc.aweme.lite.open",
                processId = "navigate",
                steps = emptyList(),
                startedAt = 1L
            ),
            startedAt = 1L
        )
    }
}