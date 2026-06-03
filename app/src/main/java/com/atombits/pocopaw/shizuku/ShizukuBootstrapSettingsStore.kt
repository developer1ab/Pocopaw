package com.atombits.pocopaw.shizuku

import android.content.Context

private const val SHIZUKU_BOOTSTRAP_PREFS_NAME = "shizuku_bootstrap"
private const val KEY_AUTO_BOOTSTRAP_ENABLED = "auto_bootstrap_enabled"
private const val KEY_LAST_BOOTSTRAP_ATTEMPT_AT = "last_bootstrap_attempt_at"
private const val KEY_LAST_BOOTSTRAP_STATUS = "last_bootstrap_status"

class ShizukuBootstrapSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        SHIZUKU_BOOTSTRAP_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isAutoBootstrapEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_BOOTSTRAP_ENABLED, true)
    }

    fun writeAutoBootstrapEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(KEY_AUTO_BOOTSTRAP_ENABLED, enabled).apply()
        return enabled
    }

    fun readLastBootstrapAttemptAt(): Long? {
        val value = prefs.getLong(KEY_LAST_BOOTSTRAP_ATTEMPT_AT, 0L)
        return value.takeIf { it > 0L }
    }

    fun readLastBootstrapStatusCode(): ShizukuBootstrapStatusCode {
        return ShizukuBootstrapStatusCode.fromPersistedValue(
            prefs.getString(KEY_LAST_BOOTSTRAP_STATUS, null)
        )
    }

    fun recordBootstrapStatus(
        statusCode: ShizukuBootstrapStatusCode,
        attemptAt: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putLong(KEY_LAST_BOOTSTRAP_ATTEMPT_AT, attemptAt)
            .putString(KEY_LAST_BOOTSTRAP_STATUS, statusCode.persistedValue)
            .apply()
    }
}