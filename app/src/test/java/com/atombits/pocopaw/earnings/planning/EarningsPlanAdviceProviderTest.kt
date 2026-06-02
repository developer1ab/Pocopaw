package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsPlanAdviceProviderTest {

    @Test
    fun requestAdvice_includesCopyableRequiredResponseSkeleton() = runBlocking {
        var capturedUserPrompt = ""
        val provider = ModelEarningsPlanAdviceProvider(
            isConfiguredOverride = { true },
            requestPromptPacketOverride = { packet ->
                capturedUserPrompt = packet.promptMessages[1].content
                """
                    {
                      "summary":"ok",
                      "importantAdvice":[{"appId":"douyin_lite","taskKey":"douyin_lite:daily_once:scheduled_coin_claim","plannedWindowId":null,"windowLabel":null,"recommendedRunAt":null,"priorityRank":1,"reason":"important","riskNotes":[]}],
                      "fillerAdvice":{"policyMode":"STATIC_ROUND_ROBIN","candidateAppOrder":["douyin_lite"],"candidateTaskOrder":[{"appId":"douyin_lite","taskKey":"douyin_lite:engagement:treasure_chest","priorityRank":1,"reason":"filler"}],"cooldownNotes":[],"riskNotes":[]},
                      "rejectedAdvice":[],
                      "responseNotes":[]
                    }
                """.trimIndent()
            }
        )

        val result = provider.requestAdvice(
            scanState = FourAppScanState(
                acceptedOpportunities = listOf(
                    TaskOpportunity(
                        appId = EntertainmentAppId.DOUYIN_LITE,
                        taskKey = "douyin_lite:daily_once:scheduled_coin_claim",
                        displayName = "领金币",
                        category = TaskCategory.DAILY_ONCE
                    ),
                    TaskOpportunity(
                        appId = EntertainmentAppId.DOUYIN_LITE,
                        taskKey = "douyin_lite:engagement:treasure_chest",
                        displayName = "开宝箱得金币",
                        category = TaskCategory.FILLER_REPEATABLE_DECAY
                    )
                )
            ),
            executionLedgerState = ExecutionLedgerState(),
            rewardLedgerState = RewardLedgerState(),
            now = 1_780_383_616_336L
        )

        assertNotNull(result.advice)
        assertEquals(1, result.advice?.importantAdvice?.size)
        assertEquals(1, result.advice?.fillerAdvice?.candidateTaskOrder?.size)
        assertTrue(capturedUserPrompt.contains("copyableRequiredResponseSkeleton"))
        assertTrue(capturedUserPrompt.contains("Start from copyableRequiredResponseSkeleton"))
        assertTrue(capturedUserPrompt.contains("\"importantAdvice\""))
        assertTrue(capturedUserPrompt.contains("\"candidateTaskOrder\""))
        assertTrue(capturedUserPrompt.contains("\"plannedWindowId\":null"))
        assertTrue(capturedUserPrompt.contains("\"taskKey\":\"douyin_lite:daily_once:scheduled_coin_claim\""))
        assertTrue(capturedUserPrompt.contains("\"taskKey\":\"douyin_lite:engagement:treasure_chest\""))
    }
}
