package com.atombits.pocopaw.earnings.planning

import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.EarningsTimeWindow
import com.atombits.pocopaw.earnings.ExecutionLedgerState
import com.atombits.pocopaw.earnings.FourAppScanState
import com.atombits.pocopaw.earnings.RewardLedgerState
import com.atombits.pocopaw.earnings.TaskCategory
import com.atombits.pocopaw.earnings.TaskOpportunity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsPlanCompilerBlacklistTest {

    @Test
    fun compile_filtersManuallyBlacklistedPersistedOpportunityBeforeModelAdvice() = runBlocking {
        var modelAdviceRequested = false
        val compiler = DefaultEarningsPlanCompiler(
            planAdviceProvider = object : EarningsPlanAdviceProvider {
                override suspend fun requestAdvice(
                    scanState: FourAppScanState,
                    executionLedgerState: ExecutionLedgerState,
                    rewardLedgerState: RewardLedgerState,
                    now: Long
                ): EarningsPlanAdviceResult {
                    modelAdviceRequested = true
                    return EarningsPlanAdviceResult(advice = EarningsPlanAdvice(summary = "unexpected"))
                }
            }
        )

        val result = compiler.compile(
            scanState = FourAppScanState(
                acceptedOpportunities = listOf(
                    TaskOpportunity(
                        appId = EntertainmentAppId.DOUYIN_LITE,
                        taskKey = "douyin_lite:daily_once:daily_coin_claim",
                        displayName = "天天领金币",
                        rewardText = "100金币",
                        actionText = "去领取",
                        category = TaskCategory.DAILY_ONCE,
                        rawTextSnapshot = "天天领金币 去领取 100金币"
                    )
                )
            ),
            executionLedgerState = ExecutionLedgerState(),
            rewardLedgerState = RewardLedgerState(),
            now = 1_780_384_892_832L
        )

        assertFalse(modelAdviceRequested)
        assertTrue(result.importantScheduleQueue.isEmpty())
        assertTrue(result.fillerCandidatePool.isEmpty())
        assertTrue(result.plannerDiagnostics.any { diagnostic ->
            diagnostic.code == "PLANNING_OPPORTUNITY_REJECTED" &&
                diagnostic.message.contains("manual task blacklist: 天天领金币")
        })
    }

        @Test
        fun compile_warnsButSchedulesWhenModelAdviceOmitsLocallyPlannableOpportunity() = runBlocking {
            val compiler = DefaultEarningsPlanCompiler(
                planAdviceProvider = object : EarningsPlanAdviceProvider {
                    override suspend fun requestAdvice(
                        scanState: FourAppScanState,
                        executionLedgerState: ExecutionLedgerState,
                        rewardLedgerState: RewardLedgerState,
                        now: Long
                    ): EarningsPlanAdviceResult {
                        return EarningsPlanAdviceResult(advice = EarningsPlanAdvice(summary = "partial model advice"))
                    }
                }
            )

            val result = compiler.compile(
                scanState = FourAppScanState(
                    acceptedOpportunities = listOf(
                        TaskOpportunity(
                            appId = EntertainmentAppId.DOUYIN_LITE,
                            taskKey = "douyin_lite:daily_windowed_repeat:midnight_claim",
                            displayName = "领金币",
                            rewardText = "最高30",
                            actionText = "去领取",
                            category = TaskCategory.DAILY_WINDOWED_REPEAT,
                            timeWindowHints = listOf(
                                EarningsTimeWindow(label = "Before Midnight", startMinuteOfDay = 0, endMinuteOfDay = 1440)
                            ),
                            rawTextSnapshot = "领金币 最高30 已预约，24点前可领取 去领取"
                        ),
                        TaskOpportunity(
                            appId = EntertainmentAppId.DOUYIN_LITE,
                            taskKey = "douyin_lite:filler_repeatable_decay:ad_watch_interval",
                            displayName = "看广告赚金币",
                            rewardText = "单日最高可赚20000金币",
                            actionText = "开启惊喜宝箱",
                            category = TaskCategory.FILLER_REPEATABLE_DECAY,
                            cooldownHintMinutes = 20,
                            rawTextSnapshot = "看广告赚金币 每20分钟完成一次广告任务 开启惊喜宝箱"
                        )
                    )
                ),
                executionLedgerState = ExecutionLedgerState(),
                rewardLedgerState = RewardLedgerState(),
                now = 1_780_397_886_359L
            )

            assertEquals(1, result.importantScheduleQueue.size)
            assertEquals("douyin_lite:daily_windowed_repeat:midnight_claim", result.importantScheduleQueue.first().taskKey)
            assertEquals(1, result.fillerCandidatePool.size)
            assertTrue(result.plannerDiagnostics.any { diagnostic ->
                diagnostic.code == "MODEL_PLANNER_MISSING_IMPORTANT_ADVICE" && diagnostic.severity == "WARN"
            })
            assertTrue(result.plannerDiagnostics.any { diagnostic ->
                diagnostic.code == "MODEL_PLANNER_MISSING_FILLER_ADVICE" && diagnostic.severity == "WARN"
            })
            assertFalse(result.plannerDiagnostics.any { diagnostic -> diagnostic.code == "MODEL_PLANNER_INCOMPLETE_ADVICE" })
        }
}