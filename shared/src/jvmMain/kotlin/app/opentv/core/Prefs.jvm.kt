package app.opentv.core

import java.io.File
import java.util.Properties

/** Where desktop keeps its data: `JASKTV_HOME`, else `~/.jasktv`. */
val desktopHome: File by lazy {
    (System.getenv("JASKTV_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".jasktv")).apply { mkdirs() }
}

actual fun openPrefs(name: String): Prefs = FilePrefs(File(desktopHome, "$name.properties"))

/** A properties file; string sets are stored as newline-joined values under `key` with a `set:` prefix. */
class FilePrefs(private val file: File) : Prefs {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    private val lock = Any()

    private fun raw(key: String): String? = synchronized(lock) { props.getProperty(key) }
    override fun getBoolean(key: String, default: Boolean) = raw(key)?.toBooleanStrictOrNull() ?: default
    override fun getInt(key: String, default: Int) = raw(key)?.toIntOrNull() ?: default
    override fun getLong(key: String, default: Long) = raw(key)?.toLongOrNull() ?: default
    override fun getString(key: String, default: String?) = raw(key) ?: default
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        raw(key)?.removePrefix("set:")?.split('\n')?.filter(String::isNotEmpty)?.toSet() ?: default
    override fun contains(key: String) = raw(key) != null
    override fun edit(): Prefs.Editor = Editor()

    private inner class Editor : Prefs.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { changes[key] = value?.joinToString("\n", "set:") }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(lock) {
            changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
        }
    }
}
