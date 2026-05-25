package com.atombits.pocopaw

import android.content.Context
import android.content.SharedPreferences

class LanguageSettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pocopaw_language_settings", Context.MODE_PRIVATE)

    fun isEnglish(): Boolean {
        return prefs.getBoolean(KEY_ENGLISH, false)
    }

    fun setEnglish(english: Boolean) {
        prefs.edit().putBoolean(KEY_ENGLISH, english).apply()
    }

    companion object {
        private const val KEY_ENGLISH = "language_english"
    }
}