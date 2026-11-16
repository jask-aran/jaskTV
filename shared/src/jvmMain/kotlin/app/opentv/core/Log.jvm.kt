package app.opentv.core

actual object Log {
    private fun out(level: String, tag: String, message: String, error: Throwable? = null): Int {
        System.err.println("$level/$tag: $message")
        error?.printStackTrace()
        return 0
    }
    actual fun d(tag: String, message: String): Int = out("D", tag, message)
    actual fun i(tag: String, message: String): Int = out("I", tag, message)
    actual fun w(tag: String, message: String, error: Throwable?): Int = out("W", tag, message, error)
    actual fun e(tag: String, message: String, error: Throwable?): Int = out("E", tag, message, error)
}
