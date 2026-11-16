package app.opentv.core

import android.content.Context
import android.content.SharedPreferences

/** Set once at startup (by [AppSettings.get]); Android stores need a Context. */
@Volatile internal var prefsContext: Context? = null

fun initPrefs(context: Context) { prefsContext = context.applicationContext }

actual fun openPrefs(name: String): Prefs =
    SharedPrefs(checkNotNull(prefsContext) { "initPrefs(context) not called" }.getSharedPreferences(name, Context.MODE_PRIVATE))

class SharedPrefs(private val p: SharedPreferences) : Prefs {
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? = p.getStringSet(key, default)
    override fun contains(key: String) = p.contains(key)
    override fun edit(): Prefs.Editor = Editor(p.edit())

    private class Editor(private val e: SharedPreferences.Editor) : Prefs.Editor {
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putStringSet(key: String, value: Set<String>?) = apply { e.putStringSet(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun apply() = e.apply()
        override fun commit() = e.commit()
    }
}
