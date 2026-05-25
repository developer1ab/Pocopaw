package com.atombits.pocopaw

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

internal object UiStrings {

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = AppLocaleManager.wrap(context.applicationContext)
    }

    fun resolve(@StringRes resId: Int, defaultValue: String, vararg formatArgs: Any?): String {
        val context = appContext
        if (context != null) {
            return if (formatArgs.isEmpty()) {
                context.getString(resId)
            } else {
                context.getString(resId, *formatArgs)
            }
        }
        if (formatArgs.isEmpty()) {
            return defaultValue
        }
        return String.format(Locale.getDefault(), defaultValue, *formatArgs)
    }
}