package app.opentv.core

import android.content.Context

/** Android entry point: binds the store to the app context, then returns the shared settings. */
fun AppSettings.Companion.get(context: Context): AppSettings {
    initPrefs(context)
    return AppSettings.shared()
}

/** Reads the saved language tag straight from prefs, for use in attachBaseContext before the settings exist. */
fun AppSettings.Companion.savedLanguageTag(context: Context): String =
    context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE).getString(AppSettings.KEY_LANGUAGE_TAG, "").orEmpty()
