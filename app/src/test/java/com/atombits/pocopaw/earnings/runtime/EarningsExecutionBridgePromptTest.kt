package com.atombits.pocopaw.earnings.runtime

import com.atombits.pocopaw.earnings.EarningsDispatchRequest
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.TaskCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsExecutionBridgePromptTest {

    @Test
    fun buildEarningsTaskRecord_limitsDouyinThirtyDayWatchVideosTaskToTenVideosPerRun() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-1",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:daily_streak:watch_videos_30_days",
                displayName = "continuous watch video streak",
                category = TaskCategory.STREAK_MULTI_DAY,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        val reasonSummary = taskRecord.reasonSummary.orEmpty()
        assertTrue(reasonSummary.contains("watch at most 10 videos"))
        assertTrue(reasonSummary.contains("return to Pocopaw"))
        assertTrue(reasonSummary.contains("If the target reward or check-in condition is confirmed earlier, stop immediately"))
        assertTrue(reasonSummary.contains("Do not continue swiping or watching after the 10th video"))
        assertTrue(reasonSummary.contains("contains this exact target task name"))
        assertTrue(taskRecord.structuredDetailSlots.domain["title"] == "continuous watch video streak")
        assertTrue(taskRecord.structuredDetailSlots.domain["per_run_video_limit"] == "10")
        assertTrue(taskRecord.structuredDetailSlots.domain["run_stop_rule"].orEmpty().contains("at most 10 videos"))
        assertTrue(taskRecord.structuredDetailSlots.common.isNotEmpty())
        assertTrue(taskRecord.targetLabel.orEmpty().contains("watch up to 10 videos this run"))
    }

    @Test
    fun buildEarningsTaskRecord_limitsChineseContinuousSpecifiedVideoTasksToTenVideosPerRun() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-4",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:streak:specified_video_30_day_reward",
                displayName = "连续刷指定视频赚金币活动",
                category = TaskCategory.STREAK_MULTI_DAY,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        val reasonSummary = taskRecord.reasonSummary.orEmpty()
        assertTrue(reasonSummary.contains("watch at most 10 videos"))
        assertTrue(reasonSummary.contains("target continuous/specified-video activity"))
        assertTrue(taskRecord.targetLabel.orEmpty().contains("watch up to 10 videos this run"))
    }

    @Test
    fun buildEarningsTaskRecord_limitsChineseThirtyDayFiftyYuanVideoTasksToTenVideosPerRun() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-5",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:local:model_generated_key",
                displayName = "连续看视频30天赚50元",
                category = TaskCategory.STREAK_MULTI_DAY,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        assertTrue(taskRecord.reasonSummary.orEmpty().contains("watch at most 10 videos"))
        assertTrue(taskRecord.targetLabel.orEmpty().contains("watch up to 10 videos this run"))
    }

    @Test
    fun buildEarningsTaskRecord_requiresExactVisibleTaskTitleForEveryEarningsDispatch() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-3",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:daily_once:claim_daily_coins",
                displayName = "daily coin claim",
                category = TaskCategory.DAILY_ONCE,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        val reasonSummary = taskRecord.reasonSummary.orEmpty()
        assertTrue(reasonSummary.contains("verify that the visible task title"))
        assertTrue(reasonSummary.contains("daily coin claim"))
        assertTrue(reasonSummary.contains("Do not choose a similar, adjacent, same-reward, or same-streak task"))
        assertTrue(taskRecord.structuredDetailSlots.domain["title"] == "daily coin claim")
        assertTrue(taskRecord.structuredDetailSlots.domain["channel"] == EntertainmentAppId.DOUYIN_LITE.displayName)
    }

    @Test
    fun buildEarningsTaskRecord_addsFillerCompletionBoundaryForRepeatableTasks() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-7",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:filler_repeatable:watch_ads_interval",
                displayName = "看广告赚金币",
                category = TaskCategory.FILLER_REPEATABLE_DECAY,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        val reasonSummary = taskRecord.reasonSummary.orEmpty()
        assertTrue(reasonSummary.contains("filler repeatable earnings tasks"))
        assertTrue(reasonSummary.contains("Return flow_state=completed with action=null"))
        assertTrue(reasonSummary.contains("Countdown and cooldown text are completion evidence"))
        assertTrue(reasonSummary.contains("top-right '领取成功' button"))
        assertTrue(reasonSummary.contains("safe reward-greedy policy"))
        assertTrue(reasonSummary.contains("return-step-reserve"))
        assertTrue(reasonSummary.contains("Keep the final 2 to 3 execution steps reserved"))
        assertTrue(reasonSummary.contains("Never continue into paid"))
        assertFalse(reasonSummary.contains("continuing is allowed at most once per run"))
        assertTrue(taskRecord.structuredDetailSlots.domain["task_category"] == TaskCategory.FILLER_REPEATABLE_DECAY.name)
        assertTrue(taskRecord.structuredDetailSlots.domain["derived_reward_policy"].orEmpty().contains("safe reward-greedy policy"))
        assertTrue(taskRecord.structuredDetailSlots.domain["forbidden_continuation_policy"].orEmpty().contains("Never continue into paid"))
        assertTrue(taskRecord.structuredDetailSlots.domain["return_confirmation_step_reserve"] == "2-3")
        assertTrue(taskRecord.structuredDetailSlots.domain["return_confirmation_step_reserve_rule"].orEmpty().contains("exit, return, and confirmation"))
        assertTrue(taskRecord.structuredDetailSlots.domain["run_completion_rule"].orEmpty().contains("target reward is claimed"))
        assertTrue(taskRecord.structuredDetailSlots.domain["reward_success_exit_rule"].orEmpty().contains("领取成功"))
        assertTrue(taskRecord.structuredDetailSlots.domain["ad_video_chain_rule"].orEmpty().contains("another rewarded ad/video"))
        assertTrue(taskRecord.structuredDetailSlots.domain["ad_video_chain_rule"].orEmpty().contains("return-step-reserve"))
        assertTrue(taskRecord.structuredDetailSlots.domain["cooldown_handling"].orEmpty().contains("do not tap or wait through it"))
        assertTrue(taskRecord.structuredDetailSlots.common.isNotEmpty())
    }

    @Test
    fun buildEarningsTaskRecord_keepsOtherTasksWithoutVideoCountLimit() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-2",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:daily_once:scheduled_coin_claim",
                displayName = "claim coins",
                category = TaskCategory.DAILY_ONCE,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        val reasonSummary = taskRecord.reasonSummary.orEmpty()
        assertFalse(reasonSummary.contains("watch at most 10 videos"))
        assertFalse(taskRecord.targetLabel.orEmpty().contains("watch up to 10 videos this run"))
        assertTrue(reasonSummary.contains("stop after at most 15 execution steps"))
        assertTrue(reasonSummary.contains("Keep the final 2 to 3 execution steps reserved"))
    }

    @Test
    fun buildEarningsTaskRecord_keepsOrdinaryShortVideoTasksWithoutContinuousVideoCountLimit() {
        val taskRecord = buildEarningsTaskRecord(
            dispatch = EarningsDispatchRequest(
                executionId = "execution-6",
                appId = EntertainmentAppId.DOUYIN_LITE,
                taskKey = "douyin_lite:daily_once:watch_video_3_minutes",
                displayName = "看视频3分钟",
                category = TaskCategory.DAILY_ONCE,
                maxSteps = 15
            ),
            now = 1_780_384_892_832L
        )

        assertFalse(taskRecord.reasonSummary.orEmpty().contains("watch at most 10 videos"))
        assertFalse(taskRecord.targetLabel.orEmpty().contains("watch up to 10 videos this run"))
    }
}
