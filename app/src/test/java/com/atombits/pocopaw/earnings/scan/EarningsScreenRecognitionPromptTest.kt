package com.atombits.pocopaw.earnings.scan

import com.atombits.pocopaw.earnings.EntertainmentAppId
import org.junit.Assert.assertTrue
import org.junit.Test

class EarningsScreenRecognitionPromptTest {

    @Test
    fun buildSystemPrompt_classifiesCooldownAdTasksAsFiller() {
        val recognizer = DefaultVisionEarningsScreenRecognizer(apiKey = "test")
        val method = DefaultVisionEarningsScreenRecognizer::class.java.getDeclaredMethod(
            "buildSystemPrompt",
            EntertainmentAppId::class.java
        )
        method.isAccessible = true

        val prompt = method.invoke(recognizer, EntertainmentAppId.DOUYIN_LITE) as String

        assertTrue(prompt.contains("every 20 minutes must be FILLER_REPEATABLE_DECAY"))
        assertTrue(prompt.contains("do not use it for cooldown/interval repeat tasks"))
        assertTrue(prompt.contains("visibleTitle, evidenceText, rewardText, scheduleText, and actionText must describe the concrete task item only"))
    }
}