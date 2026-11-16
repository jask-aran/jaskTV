/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Direct binding to libmpv's C API through JNA.
 *
 * libmpv is used instead of VLC because its render API can hand finished frames to an arbitrary
 * GPU/CPU surface, which is what lets Compose draw controls over the video. This file declares only
 * the calls the engine needs; `client.h` in the mpv source is the reference.
 *
 * The library is loaded lazily so the window can explain what is missing instead of crashing when
 * mpv is not installed.
 */
internal interface MpvLibrary : Library {

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)

    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_error_string(error: Int): String

    fun mpv_render_context_create(
        result: PointerByReference,
        ctx: Pointer,
        params: Pointer,
    ): Int

    fun mpv_render_context_set_update_callback(
        ctx: Pointer,
        callback: MpvRenderUpdateCallback?,
        callbackData: Pointer?,
    )

    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    companion object {
        /**
         * Resolution order: an explicit `-Dopentv.libmpv=` path, then the usual library names,
         * then the common install locations. Shipping builds will carry the library beside the
         * executable; this covers development machines where mpv is installed by the OS.
         */
        val instance: MpvLibrary by lazy { load() }

        private fun load(): MpvLibrary {
            val explicit = System.getProperty("opentv.libmpv")
                ?: System.getenv("OPENTV_LIBMPV")

            val names = buildList {
                if (!explicit.isNullOrBlank()) add(explicit)
                // JNA applies the platform's prefix/suffix to these.
                add("mpv")
                add("libmpv")
                add("mpv-2")
                add("libmpv-2")
            }

            // Checked relative to the working directory (Gradle's `:desktop:run` uses the module
            // directory) and beside the launcher for a packaged app. The Windows DLL is ~100MB
            // and OS-specific, so it lives in an ignored folder instead of the repository.
            val relative = listOf(
                "libs/mpv/windows-x64/libmpv-2.dll",
                "desktop/libs/mpv/windows-x64/libmpv-2.dll",
                // Packaged layouts: appResourcesRootDir copies the whole libs/mpv tree, so each
                // OS/arch gets its own subdirectory and the rest are simply absent.
                "windows-x64/libmpv-2.dll",
                "macos-arm64/libmpv.dylib",
                "macos-x64/libmpv.dylib",
                "linux-x64/libmpv.so",
                "linux-arm64/libmpv.so",
                "libmpv-2.dll",
                "libmpv.so",
                "libmpv.dylib",
            )
            val roots = buildList {
                add(File(System.getProperty("user.dir")))
                // `jpackage` sets this to the launcher executable in a packaged application.
                System.getProperty("jpackage.app-path")?.let { add(File(it).parentFile) }
                // Compose Desktop sets this to the packaged `resources` directory.
                System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
            }

            val knownPaths = (
                roots.flatMap { root -> relative.map { File(root, it) } } +
                    listOf(
                        File("/home/linuxbrew/.linuxbrew/lib/libmpv.so"),
                        File("/usr/local/lib/libmpv.so"),
                        File("/usr/lib/x86_64-linux-gnu/libmpv.so.2"),
                        File("/usr/lib64/libmpv.so.2"),
                        File("/opt/homebrew/lib/libmpv.dylib"),
                        File("/usr/local/lib/libmpv.dylib"),
                    )
                ).filter { it.exists() }.map { it.absolutePath }

            val failures = mutableListOf<String>()
            for (name in names + knownPaths) {
                try {
                    return Native.load(name, MpvLibrary::class.java)
                } catch (t: Throwable) {
                    failures += "$name (${t.message})"
                }
            }

            throw IllegalStateException(
                buildString {
                    appendLine("libmpv could not be loaded.")
                    appendLine("Install mpv, or point -Dopentv.libmpv=/path/to/libmpv at the library.")
                    append("Tried: ")
                    append(failures.joinToString(", "))
                },
            )
        }
    }
}

/** `void (*mpv_render_update_fn)(void *cb_ctx)` */
internal interface MpvRenderUpdateCallback : Callback {
    fun invoke(callbackData: Pointer?)
}

/**
 * `mpv_render_param { int type; void *data; }`
 *
 * An array of these is terminated by a zero type, which is why the engine always allocates
 * `count + 1` entries and leaves the last one zeroed.
 */
@Structure.FieldOrder("type", "data")
internal class MpvRenderParam : Structure {
    @JvmField
    var type: Int = 0

    @JvmField
    var data: Pointer? = null

    constructor() : super()

    constructor(type: Int, data: Pointer?) : super() {
        this.type = type
        this.data = data
    }
}

/**
 * `mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }`
 */
@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent : Structure {
    @JvmField
    var eventId: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var replyUserdata: Long = 0

    @JvmField
    var data: Pointer? = null

    constructor() : super()

    constructor(pointer: Pointer) : super(pointer)
}

internal object Mpv {
    // mpv_format
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    // mpv_event_id
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_TRACKS_CHANGED = 9

    // mpv_end_file_reason
    const val END_FILE_EOF = 0
    const val END_FILE_STOP = 2
    const val END_FILE_QUIT = 3
    const val END_FILE_ERROR = 4

    // mpv_render_param_type
    const val RENDER_PARAM_API_TYPE = 1
    const val RENDER_PARAM_SW_SIZE = 17
    const val RENDER_PARAM_SW_FORMAT = 18
    const val RENDER_PARAM_SW_STRIDE = 19
    const val RENDER_PARAM_SW_POINTER = 20

    /** `mpv_render_context_update` bit: a new frame is ready. */
    const val RENDER_UPDATE_FRAME = 1L
}
