package com.atombits.pocopaw

import android.content.Context
import android.content.SharedPreferences
import java.io.File

private const val DEMO_RELEASE_PREFS = "demo_release_control"
private const val KEY_DEMO_USED_TOKENS = "demo_used_tokens"
private const val KEY_DEMO_ONBOARDING_COMPLETED = "demo_onboarding_completed"
private const val KEY_DEMO_ONBOARDING_TOOL_DISCOVERY_COMPLETED = "demo_onboarding_tool_discovery_completed"
private const val KEY_DEMO_ONBOARDING_ACCESSIBILITY_OPENED = "demo_onboarding_accessibility_opened"
private const val KEY_DEMO_ONBOARDING_ACCESSIBILITY_COMPLETED = "demo_onboarding_accessibility_completed"
private const val KEY_DEMO_ONBOARDING_SCREEN_CAPTURE_COMPLETED = "demo_onboarding_screen_capture_completed"
private const val ONBOARDING_NO_BACKUP_SENTINEL = "demo_onboarding_seed_v1"

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
                ensureOnboardingSeedInitialized(context.applicationContext)
            }
        }
    }

    private fun ensureOnboardingSeedInitialized(context: Context) {
        val sentinel = File(context.noBackupFilesDir, ONBOARDING_NO_BACKUP_SENTINEL)
        if (sentinel.exists()) {
            return
        }
        prefsOrThrow().edit()
            .putBoolean(KEY_DEMO_ONBOARDING_COMPLETED, false)
            .putBoolean(KEY_DEMO_ONBOARDING_TOOL_DISCOVERY_COMPLETED, false)
            .putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_OPENED, false)
            .putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_COMPLETED, false)
            .putBoolean(KEY_DEMO_ONBOARDING_SCREEN_CAPTURE_COMPLETED, false)
            .apply()
        sentinel.parentFile?.mkdirs()
        sentinel.writeText("initialized")
    }

    @Synchronized
    fun ensureBackendAccessAllowed() {
        // Unlocked: backend access is always allowed.
    }

    @Synchronized
    fun consumeFromTokenUsage(tokenUsage: TokenUsage?, fallbackTokens: Int = 0) {
        // Unlocked: quota accounting is disabled.
    }

    fun tokenLimit(): Int = DEMO_TOKEN_LIMIT

    @Synchronized
    fun readUsedTokens(): Int {
        return 0
    }

    @Synchronized
    fun readRemainingTokens(): Int {
        return DEMO_TOKEN_LIMIT
    }

    @Synchronized
    fun isOnboardingCompleted(): Boolean {
        return prefsOrThrow().getBoolean(KEY_DEMO_ONBOARDING_COMPLETED, false)
    }

    @Synchronized
    fun markOnboardingCompleted() {
        prefsOrThrow().edit()
            .putBoolean(KEY_DEMO_ONBOARDING_COMPLETED, true)
            .putBoolean(KEY_DEMO_ONBOARDING_TOOL_DISCOVERY_COMPLETED, true)
            .putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_OPENED, true)
            .putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_COMPLETED, true)
            .putBoolean(KEY_DEMO_ONBOARDING_SCREEN_CAPTURE_COMPLETED, true)
            .apply()
    }

    @Synchronized
    fun isToolDiscoveryOnboardingCompleted(): Boolean {
        return prefsOrThrow().getBoolean(KEY_DEMO_ONBOARDING_TOOL_DISCOVERY_COMPLETED, false)
    }

    @Synchronized
    fun markToolDiscoveryOnboardingCompleted() {
        prefsOrThrow().edit().putBoolean(KEY_DEMO_ONBOARDING_TOOL_DISCOVERY_COMPLETED, true).apply()
    }

    @Synchronized
    fun hasOpenedAccessibilityOnboardingSettings(): Boolean {
        return prefsOrThrow().getBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_OPENED, false)
    }

    @Synchronized
    fun markAccessibilityOnboardingSettingsOpened() {
        prefsOrThrow().edit().putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_OPENED, true).apply()
    }

    @Synchronized
    fun isAccessibilityOnboardingCompleted(): Boolean {
        return prefsOrThrow().getBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_COMPLETED, false)
    }

    @Synchronized
    fun markAccessibilityOnboardingCompleted() {
        prefsOrThrow().edit().putBoolean(KEY_DEMO_ONBOARDING_ACCESSIBILITY_COMPLETED, true).apply()
    }

    @Synchronized
    fun isScreenCaptureOnboardingCompleted(): Boolean {
        return prefsOrThrow().getBoolean(KEY_DEMO_ONBOARDING_SCREEN_CAPTURE_COMPLETED, false)
    }

    @Synchronized
    fun markScreenCaptureOnboardingCompleted() {
        prefsOrThrow().edit().putBoolean(KEY_DEMO_ONBOARDING_SCREEN_CAPTURE_COMPLETED, true).apply()
    }

    private fun prefsOrThrow(): SharedPreferences {
        return prefs ?: error("DemoReleaseControl must be initialized before use")
    }
}
