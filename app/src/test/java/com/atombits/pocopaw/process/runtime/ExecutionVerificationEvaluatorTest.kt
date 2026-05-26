package com.atombits.pocopaw.process.runtime

import com.atombits.pocopaw.ExecutionCheck
import com.atombits.pocopaw.ExecutionCheckType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionVerificationEvaluatorTest {

    @Test
    fun evaluateExecutionCompletionAgainstChecks_skipsOptionalSlotPreservedMismatch() {
        val outcome = evaluateExecutionCompletionAgainstChecks(
            checks = listOf(
                ExecutionCheck(
                    type = ExecutionCheckType.SLOT_PRESERVED,
                    key = "TARGET_OBJECT",
                    expectedValue = "bluetooth",
                    required = false
                )
            ),
            terminalSummary = "执行完成",
            automationPayload = """
                {
                  "message": "蓝牙已关闭",
                  "semantic_context": {
                    "expected_outcome": "蓝牙已关闭",
                    "verification_signals": ["开关显示关闭"]
                  }
                }
            """.trimIndent()
        )

        assertTrue(outcome.passed)
        assertTrue(outcome.failedChecks.isEmpty())
        assertEquals("执行完成", outcome.summary)
    }

    @Test
    fun evaluateExecutionCompletionAgainstChecks_failsRequiredSlotPreservedMismatch() {
        val outcome = evaluateExecutionCompletionAgainstChecks(
            checks = listOf(
                ExecutionCheck(
                    type = ExecutionCheckType.SLOT_PRESERVED,
                    key = "TARGET_OBJECT",
                    expectedValue = "bluetooth",
                    required = true
                )
            ),
            terminalSummary = "执行完成",
            automationPayload = """
                {
                  "message": "蓝牙已关闭",
                  "semantic_context": {
                    "expected_outcome": "蓝牙已关闭",
                    "verification_signals": ["开关显示关闭"]
                  }
                }
            """.trimIndent()
        )

        assertFalse(outcome.passed)
        assertEquals(1, outcome.failedChecks.size)
        assertEquals(ExecutionCheckType.SLOT_PRESERVED, outcome.failedChecks.first().type)
        assertTrue(outcome.summary.contains("Execution verification failed"))
    }
}
