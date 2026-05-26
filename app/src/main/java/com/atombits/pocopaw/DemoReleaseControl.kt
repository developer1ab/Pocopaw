package com.atombits.pocopaw

import android.content.Context
import android.content.SharedPreferences

private const val DEMO_RELEASE_PREFS = "demo_release_control"
private const val KEY_DEMO_USED_TOKENS = "demo_used_tokens"
private const val KEY_DEMO_ONBOARDING_COMPLETED = "demo_onboarding_completed"

private const val DEMO_TOKEN_LIMIT = 1_000_000

internal class DemoQuotaExceededException : IllegalStateException("DEMO_QUOTA_EXCEEDED")

internal object DemoReleaseControl {
    @Volatile
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) {
            return
        }
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(
                    DEMO_RELEASE_PREFS,
                    Context.MODE_PRIVATE
                )
            }
        }
    }

    @Synchronized
    fun ensureBackendAccessAllowed() {
        if (prefs == null) {
            return
        }
        if (readUsedTokens() >= DEMO_TOKEN_LIMIT) {
            throw DemoQuotaExceededException()
        }
    }

    @Synchronized
    fun consumeFromTokenUsage(tokenUsage: TokenUsage?, fallbackTokens: Int = 0) {
        if (prefs == null) {
            return
        }
        val consumedTokens = tokenUsage?.totalTokens?.coerceAtLeast(0) ?: fallbackTokens.coerceAtLeast(0)
        if (consumedTokens <= 0) {
            return
        }
        val current = readUsedTokens()
        val updated = (current + consumedTokens).coerceAtMost(DEMO_TOKEN_LIMIT)
        writeUsedTokens(updated)
    }

    fun tokenLimit(): Int = DEMO_TOKEN_LIMIT

    @Synchronized
    fun readUsedTokens(): Int {
        val activePrefs = prefs ?: return 0
        return activePrefs.getInt(KEY_DEMO_USED_TOKENS, 0).coerceAtLeast(0)
    }

    @Synchronized
    fun readRemainingTokens(): Int {
        return (DEMO_TOKEN_LIMIT - readUsedTokens()).coerceAtLeast(0)
    }

    @Synchronized
    fun isOnboardingCompleted(): Boolean {
        val activePrefs = prefs ?: return false
        return activePrefs.getBoolean(KEY_DEMO_ONBOARDING_COMPLETED, false)
    }

    @Synchronized
    fun markOnboardingCompleted() {
        prefs?.edit()?.putBoolean(KEY_DEMO_ONBOARDING_COMPLETED, true)?.apply()
    }

    private fun writeUsedTokens(value: Int) {
        prefs?.edit()?.putInt(KEY_DEMO_USED_TOKENS, value)?.apply()
    }
}
