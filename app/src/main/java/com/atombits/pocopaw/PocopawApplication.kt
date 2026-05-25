package com.atombits.pocopaw

import android.app.Application
import android.content.Context

class PocopawApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.applyStoredLanguage(this)
        UiStrings.initialize(this)
    }
}