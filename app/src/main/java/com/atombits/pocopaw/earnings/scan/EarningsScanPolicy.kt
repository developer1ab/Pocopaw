package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.earnings.EarningsManualTaskBlacklist
import com.atombits.pocopaw.earnings.EntertainmentAppId
import com.atombits.pocopaw.earnings.RawScanItem
import com.atombits.pocopaw.earnings.ScanPolicyDecision
import java.util.Locale

interface EarningsScanPolicy {
    fun evaluate(rawItem: RawScanItem): ScanPolicyDecision
}

object DefaultEarningsScanPolicy : EarningsScanPolicy {
    private const val minimumConfidence = 0.55

    private val genericNoiseTerms = listOf(
        "任务中心",
        "做任务赚金币",
        "福利中心",
        "金币余额",
        "现金余额",
        "总收益",
        "活动中心"
    )
    private val paidTerms = listOf("充值", "消费", "购买", "会员", "缴费", "付款", "支付", "下单", "订单")
    private val financeTerms = listOf("信用卡", "办卡", "借贷", "贷款", "分期", "额度", "授信", "提现", "现金", "红包")
    private val completedActionTerms = listOf("已完成", "已打卡", "明日再来", "明天再来", "明日可领", "明天领")
    private val downloadActionTerms = listOf("去下载", "下载", "安装")
    private val couponOrIdentityTerms = listOf("券", "补贴", "车主", "地铁", "团购")
    private val socialOrPrivacyTerms = listOf("通讯录", "联系人", "好友", "朋友", "邀请", "送金币")
    private val gameTerms = listOf(
        "游戏",
        "晚安小岛",
        "小游戏",
        "玩一玩",
        "猪了个猪",
        "game task",
        "mini-game",
        "mini game",
        "play games",
        "play_games"
    )
    private val creatorTerms = listOf("创作", "投稿", "上传", "发作品", "开播", "直播")
    private val redirectTerms = listOf("打开", "启动", "跳转", "前往", "下载", "安装")
    private val specificSignalTerms = listOf("金币", "奖励", "领取", "签到", "观看", "广告", "宝箱", "倒计时", "分钟", "打卡")

    override fun evaluate(rawItem: RawScanItem): ScanPolicyDecision {
        val title = rawItem.visibleTitle.trim()
        val taskText = rawItem.taskEvidenceText()
        val text = rawItem.combinedText()
        val notes = mutableListOf<String>()

        if (rawItem.confidence < minimumConfidence) {
            return ScanPolicyDecision(false, "LOW_CONFIDENCE", listOf("scanConfidence=${rawItem.confidence}"))
        }
        if (title.isBlank()) {
            return ScanPolicyDecision(false, "MISSING_TITLE")
        }
        if (rawItem.modelCategory == null) {
            return ScanPolicyDecision(false, "MISSING_CATEGORY")
        }
        EarningsManualTaskBlacklist.matchingPhrase(taskText)?.let { matchedPhrase ->
            return ScanPolicyDecision(
                accepted = false,
                reason = EarningsManualTaskBlacklist.rejectionCode,
                notes = listOf("manualBlacklist=$matchedPhrase")
            )
        }
        if (rawItem.actionText.orEmpty().containsAny(completedActionTerms)) {
            return ScanPolicyDecision(false, "NOT_CURRENTLY_EXECUTABLE")
        }
        if (rawItem.actionText.orEmpty().containsAny(downloadActionTerms) || taskText.containsAny(downloadActionTerms)) {
            return ScanPolicyDecision(false, "DOWNLOAD_OR_INSTALL_TASK")
        }
        val hasSpecificSignal = hasSpecificTaskSignal(rawItem, taskText)
        if (isGenericNoise(title, text, hasSpecificSignal)) {
            return ScanPolicyDecision(false, "GENERIC_ENTRY_OR_BALANCE_NOISE")
        }
        if (taskText.containsAny(paidTerms)) {
            return ScanPolicyDecision(false, "PAID_OR_PURCHASE_TASK")
        }
        if (taskText.containsAny(financeTerms)) {
            return ScanPolicyDecision(false, "FINANCIAL_TASK")
        }
        if (taskText.containsAny(couponOrIdentityTerms)) {
            return ScanPolicyDecision(false, "COUPON_OR_IDENTITY_TASK")
        }
        if (taskText.containsAny(socialOrPrivacyTerms)) {
            return ScanPolicyDecision(false, "SOCIAL_OR_PRIVACY_TASK")
        }
        if (isGameTask(rawItem, taskText)) {
            return ScanPolicyDecision(false, "GAME_TASK")
        }
        if (taskText.containsAny(creatorTerms)) {
            return ScanPolicyDecision(false, "CREATOR_OR_LIVE_TASK")
        }
        rejectCrossApp(rawItem.appId, taskText)?.let { reason ->
            return ScanPolicyDecision(false, reason)
        }
        if (!hasSpecificSignal) {
            return ScanPolicyDecision(false, "MISSING_SPECIFIC_TASK_SIGNAL")
        }

        if (rawItem.modelTaskKey.isNullOrBlank()) {
            notes += "MODEL_TASK_KEY_MISSING_LOCAL_STABLE_KEY_REQUIRED"
        }
        return ScanPolicyDecision(true, notes = notes)
    }

    private fun hasSpecificTaskSignal(rawItem: RawScanItem, text: String): Boolean {
        return rawItem.rewardText?.isNotBlank() == true ||
            rawItem.actionText?.isNotBlank() == true ||
            rawItem.scheduleText?.isNotBlank() == true ||
            rawItem.cooldownMinutes != null ||
            rawItem.windows.isNotEmpty() ||
            text.containsAny(specificSignalTerms)
    }

    private fun isGenericNoise(title: String, text: String, hasSpecificSignal: Boolean): Boolean {
        if (genericNoiseTerms.any { term -> title.equals(term, ignoreCase = true) }) {
            return true
        }
        return !hasSpecificSignal && text.containsAny(genericNoiseTerms)
    }

    private fun isGameTask(rawItem: RawScanItem, text: String): Boolean {
        if (text.containsAny(gameTerms) || namedGameRewardPattern.containsMatchIn(text)) {
            return true
        }
        val taskKey = rawItem.modelTaskKey
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        return gameTaskKeyPattern.containsMatchIn(taskKey)
    }

    private fun rejectCrossApp(appId: EntertainmentAppId, text: String): String? {
        val redirectsAway = text.containsAny(redirectTerms) && text.containsAny(listOf("小说", "听书", "短剧", "游戏", "番茄", "红果", "头条", "抖音"))
        if (redirectsAway) {
            return "CROSS_APP_REDIRECT"
        }
        return when (appId) {
            EntertainmentAppId.DOUYIN_LITE,
            EntertainmentAppId.TOUTIAO_LITE -> if (text.containsAny(listOf("小说", "听书", "短剧"))) "CROSS_APP_CONTENT_DOMAIN" else null
            EntertainmentAppId.FANQIE -> if (text.containsAny(listOf("短剧", "刷视频", "直播"))) "CROSS_APP_CONTENT_DOMAIN" else null
            EntertainmentAppId.HONGGUO -> if (text.containsAny(listOf("小说", "听书", "刷视频", "直播"))) "CROSS_APP_CONTENT_DOMAIN" else null
        }
    }
}

private val gameTaskKeyPattern = Regex("(^|[:_\\-])(game|games)([:_\\-]|$)|play[_\\-]?games?")
private val namedGameRewardPattern = Regex("玩\\s*[《（(][^》）)]+[》）)]\\s*(赚|得|领).{0,8}(金币|金幣|奖励)")

internal fun RawScanItem.combinedText(): String {
    return listOfNotNull(
        visibleTitle,
        rewardText,
        scheduleText,
        actionText,
        screenContext,
        evidenceText,
        semanticMatchReason
    ).joinToString(" ").trim()
}

private fun RawScanItem.taskEvidenceText(): String {
    return listOfNotNull(
        visibleTitle,
        rewardText,
        scheduleText,
        actionText,
        evidenceText,
        modelTaskKey,
        scheduleLabel,
        semanticMatchReason
    ).joinToString(" ").trim()
}

internal fun String.containsAny(terms: List<String>): Boolean {
    return terms.any { term -> contains(term, ignoreCase = true) }
}
