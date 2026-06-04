package com.atombits.pocopaw

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

internal enum class AppLanguage(val languageTag: String) {
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-Hans");

    companion object {
        private val DEFAULT_LANGUAGE = ENGLISH

        fun fromLanguageTag(languageTag: String?): AppLanguage {
            return when {
                languageTag.isNullOrBlank() -> DEFAULT_LANGUAGE
                languageTag.startsWith("zh", ignoreCase = true) -> SIMPLIFIED_CHINESE
                else -> ENGLISH
            }
        }
    }
}

internal object AppLocaleManager {

    private const val PREFERENCES_NAME = "pocopaw_language_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"
    @Volatile
    private var cachedLanguage: AppLanguage? = null

    fun wrap(base: Context): Context {
        return createLocalizedContext(base, readLanguage(base))
    }

    fun applyStoredLanguage(context: Context) {
        applyLanguage(context, readLanguage(context), persist = false)
    }

    fun currentLanguage(context: Context): AppLanguage {
        return readLanguage(context)
    }

    fun toggle(context: Context): AppLanguage {
        val nextLanguage = when (readLanguage(context)) {
            AppLanguage.ENGLISH -> AppLanguage.SIMPLIFIED_CHINESE
            AppLanguage.SIMPLIFIED_CHINESE -> AppLanguage.ENGLISH
        }
        applyLanguage(context, nextLanguage, persist = true)
        UiStrings.initialize(context)
        return nextLanguage
    }

    fun isEnglishLocale(): Boolean {
        return (cachedLanguage ?: languageFromDefaultLocale()) == AppLanguage.ENGLISH
    }

    private fun applyLanguage(context: Context, language: AppLanguage, persist: Boolean) {
        cachedLanguage = language
        if (persist) {
            preferences(context)
                .edit()
                .putString(KEY_LANGUAGE_TAG, language.languageTag)
                .apply()
        }
        val locale = Locale.forLanguageTag(language.languageTag)
        Locale.setDefault(locale)
        updateResources(context, locale)
        val appContext = context.applicationContext
        if (appContext != null && appContext !== context) {
            updateResources(appContext, locale)
        }
    }

    private fun readLanguage(context: Context): AppLanguage {
        val storedLanguageTag = preferences(context).getString(KEY_LANGUAGE_TAG, AppLanguage.SIMPLIFIED_CHINESE.languageTag)
        val language = AppLanguage.fromLanguageTag(storedLanguageTag)
        cachedLanguage = language
        return language
    }

    private fun languageFromDefaultLocale(): AppLanguage {
        val languageTag = Locale.getDefault().toLanguageTag()
        return AppLanguage.fromLanguageTag(languageTag)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun createLocalizedContext(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    private fun updateResources(context: Context, locale: Locale) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}