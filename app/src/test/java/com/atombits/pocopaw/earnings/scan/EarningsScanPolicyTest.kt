package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.EarningsManualTaskBlacklist
import com.atombits.pocopaw.earnings.RawScanItem
import com.atombits.pocopaw.earnings.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsScanPolicyTest {

    @Test
    fun evaluate_acceptsConcreteAdTaskWhenPageContextContainsGenericNoise() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "看广告赚金币",
                rewardText = "最高20000金币",
                scheduleText = "每20分钟完成一次广告任务，单日最高可赚20000金币",
                actionText = "去观看",
                screenContext = "任务中心 - 做任务赚金币",
                evidenceText = "Accessibility text explicitly states 看广告赚金币 and 去观看.",
                modelTaskKey = "douyin_lite:filler:watch_ad_for_coins",
                modelCategory = TaskCategory.FILLER_REPEATABLE_DECAY,
                cooldownMinutes = 20,
                confidence = 0.95
            )
        )

        assertTrue(decision.accepted)
    }

    @Test
    fun evaluate_rejectsGenericEntryTitleEvenWhenItMentionsCoins() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "做任务赚金币",
                modelTaskKey = "douyin_lite:generic:task_center",
                modelCategory = TaskCategory.FILLER_REPEATABLE_DECAY,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals("GENERIC_ENTRY_OR_BALANCE_NOISE", decision.reason)
    }

    @Test
    fun evaluate_rejectsGenericContextWhenConcreteTaskSignalMissing() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "精选任务",
                screenContext = "任务中心",
                modelTaskKey = "douyin_lite:generic:selected_tasks",
                modelCategory = TaskCategory.FILLER_REPEATABLE_DECAY,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals("GENERIC_ENTRY_OR_BALANCE_NOISE", decision.reason)
    }

    @Test
    fun evaluate_rejectsManuallyBlacklistedDailyCoinTask() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "天天领金币",
                rewardText = "100金币",
                actionText = "去领取",
                modelTaskKey = "douyin_lite:daily_once:daily_coin_claim",
                modelCategory = TaskCategory.DAILY_ONCE,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals(EarningsManualTaskBlacklist.rejectionCode, decision.reason)
        assertEquals(listOf("manualBlacklist=天天领金币"), decision.notes)
    }

    @Test
    fun evaluate_rejectsModelGameTaskContext() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "Play Pig Game to Earn Gold Coins",
                rewardText = "264979",
                actionText = "Go earn",
                screenContext = "Game task",
                evidenceText = "Playing specific mini-games to earn coins.",
                modelTaskKey = "douyin_lite:filler_repeatable_decay:play_games",
                modelCategory = TaskCategory.FILLER_REPEATABLE_DECAY,
                confidence = 0.8
            )
        )

        assertFalse(decision.accepted)
        assertEquals("GAME_TASK", decision.reason)
    }

    @Test
    fun evaluate_rejectsNamedPlayForCoinsTitle() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "玩《猪了个猪》赚金币",
                rewardText = "264979",
                actionText = "去赚钱",
                modelTaskKey = "douyin_lite:filler_repeatable_decay:pig_reward",
                modelCategory = TaskCategory.FILLER_REPEATABLE_DECAY,
                confidence = 0.8
            )
        )

        assertFalse(decision.accepted)
        assertEquals("GAME_TASK", decision.reason)
    }

    @Test
    fun evaluate_rejectsAlreadyCompletedAction() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "打卡领喷水拖把",
                actionText = "已打卡",
                evidenceText = "今日已打卡",
                modelTaskKey = "douyin_lite:daily_streak:check_in_gift",
                modelCategory = TaskCategory.STREAK_MULTI_DAY,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals("NOT_CURRENTLY_EXECUTABLE", decision.reason)
    }

    @Test
    fun evaluate_rejectsDownloadTask() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "看视频3分钟",
                actionText = "去下载",
                rewardText = "1金币",
                modelTaskKey = "douyin_lite:daily:watch_video_3min",
                modelCategory = TaskCategory.DAILY_WINDOWED_REPEAT,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals("DOWNLOAD_OR_INSTALL_TASK", decision.reason)
    }

    @Test
    fun evaluate_rejectsContactTask() {
        val decision = DefaultEarningsScanPolicy.evaluate(
            RawScanItem(
                appId = EntertainmentAppId.DOUYIN_LITE,
                visibleTitle = "发现通讯录好友",
                actionText = "去查看",
                rewardText = "1000",
                evidenceText = "看看通讯录里有谁在玩抖音",
                modelTaskKey = "douyin_lite:social:find_contacts",
                modelCategory = TaskCategory.ONE_TIME,
                confidence = 0.9
            )
        )

        assertFalse(decision.accepted)
        assertEquals("SOCIAL_OR_PRIVACY_TASK", decision.reason)
    }
}