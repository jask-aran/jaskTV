/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import app.opentv.data.db.DownloadDao
import app.opentv.data.model.Download
import app.opentv.data.repo.DownloadEngine.Live
import app.opentv.data.repo.DownloadEngine.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Films and episodes saved for offline viewing (a plane, a train, no signal).
 *
 * jaskTV downloads them itself (DownloadService, a foreground service with a progress notification): one at a time,
 * resuming a partial file after any break, retrying on a short visible schedule, standing aside while you watch from
 * the same provider (see [DownloadEngine]). Files land in the shared Download/jaskTV folder as ordinary files, laid out
 * so every install finds them by name (see [expectedFile]). The database row is the list; [Download.systemId] says where each one is
 * ([PENDING], [ACTIVE], [DONE], [FAILED], [NO_SPACE]). Rows from before (a DownloadManager id, 0 or more) that had
 * finished keep working; unfinished ones are moved to this downloader on start.
 */
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "ts", "m4v", "webm", "mov")

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val http: okhttp3.OkHttpClient,
) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val notes = DownloadEngine.Notes(context.filesDir)

    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    /** A download plus what's known about it right now. */
    data class Item(
        val download: Download,
        val state: State,
        val bytesSoFar: Long,
        val totalBytes: Long,
        /** Local file to play, once [state] is DONE. */
        val fileUri: String?,
        /** Why it failed or is paused (a DownloadManager ERROR_/PAUSED_ reason), 0 when n/a. */
        val reason: Int = 0,
        /** The running transfer's live state (speed, retry countdown, standing aside), when it's ours. */
        val live: Live? = null,
        /** "1080p · HEVC": what the file is, once its first bytes are in. */
        val tech: String? = null,
        /** Where it comes from: the playlist's name, or "Torrent". */
        val origin: String? = null,
        /** QUEUED: its place in line (1 = next). */
        val place: Int = 0,
        /** DONE: when it finished. FAILED: why. */
        val finishedAt: Long = 0,
        val error: String? = null,
    ) {
        val outOfSpace: Boolean get() = state == State.FAILED && reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
        val progress: Float get() = if (totalBytes > 0) (bytesSoFar.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** Everything downloaded or downloading, refreshed every second while someone is watching. */
    fun observe(): Flow<List<Item>> =
        combine(dao.observeAll(), DownloadEngine.live, ticker()) { downloads, _, tick ->
            // A download from before this downloader that Doze paused sends no "finished": look again now and then.
            if (tick % 30 == 0L && downloads.any { it.systemId >= 0 }) runCatching { pump() }
            withStatus(downloads)
        }.flowOn(Dispatchers.IO)

    /** One entry's live state, or null when it isn't downloaded — drives the Download buttons. */
    fun observe(mediaKey: String): Flow<Item?> = flow {
        // Saved by another install? Then it's downloaded here too.
        if (dao.byKey(mediaKey) == null) runCatching { adoptFor(mediaKey) }
        emitAll(combine(dao.observeAll(), DownloadEngine.live, ticker()) { downloads, _, _ ->
            downloads.firstOrNull { it.mediaKey == mediaKey }?.let { withStatus(listOf(it), downloads).firstOrNull() }
        })
    }.flowOn(Dispatchers.IO)

    sealed interface Start {
        data object Started : Start
        /** Another download is running; this one starts when it finishes. */
        data object Queued : Start
        /** Won't fit: [neededBytes] is 0 when the provider didn't say how big it is. */
        data class NoSpace(val neededBytes: Long, val freeBytes: Long) : Start
    }

    /** Adds a download to the queue and makes sure the downloader is running. */
    suspend fun enqueue(download: Download, userAgent: String = USER_AGENT): Start = withContext(Dispatchers.IO) {
        val free = freeBytes()
        if (free < MIN_FREE_BYTES) return@withContext Start.NoSpace(0, free)
        dao.byKey(download.mediaKey)?.let { remove(it) }
        val busy = dao.allNow().any { it.systemId == ACTIVE || it.systemId == PENDING }
        dao.insert(download.copy(systemId = PENDING, createdMillis = System.currentTimeMillis()))
        app.opentv.data.work.DownloadService.start(context)
        if (busy) Start.Queued else Start.Started
    }

    private val pumpLock = kotlinx.coroutines.sync.Mutex()
    private val tidyLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Moves unfinished DownloadManager transfers (from before this downloader) over, and starts the downloader when
     * anything is waiting. Called on app start. Returns null (kept for its callers).
     */
    suspend fun pump(@Suppress("UNUSED_PARAMETER") userAgent: String = USER_AGENT): Start? = withContext(Dispatchers.IO) {
        pumpLock.withLock {
            val all = dao.allNow()
            val legacy = all.filter { it.systemId >= 0 }
            var legacyRunning = false
            if (legacy.isNotEmpty()) {
                val status = legacyStatus(legacy)
                // One DownloadManager is mid-transfer: leave it to finish (its partial file would be lost), and start
                // ours when its completion broadcast calls this again. Waiting ones move here (DownloadManager kept
                // being stopped by Doze with the screen off); they hadn't started, so nothing is lost.
                // A paused one (Doze stopped it; "waiting to retry") moves too and starts over: waiting on Android is what
                // stalled it.
                legacyRunning = status.values.any { it.state == State.RUNNING }
                legacy.filter { d -> val st = status[d.systemId]?.state; st == State.QUEUED || st == State.PAUSED }
                    .forEach { d -> runCatching { manager.remove(d.systemId) }; dao.setSystemId(d.id, PENDING) }
            }
            // Off the lock: a film takes a while to copy, and the list keeps showing the old file meanwhile.
            if (!tidied) { tidied = true; kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { tidyLock.withLock { runCatching { tidy() }.onFailure { android.util.Log.w("jaskDl", "tidy: $it") } } } }
            if (!legacyRunning && dao.allNow().any { it.systemId == ACTIVE || it.systemId == PENDING }) app.opentv.data.work.DownloadService.start(context)
            null
        }
    }

    suspend fun isDone(id: Long): Boolean = dao.allNow().firstOrNull { it.id == id }?.systemId == DONE

    /** How many are waiting behind the running one. */
    suspend fun queueSize(): Int = dao.allNow().count { it.systemId == PENDING }

    /** The next download to work on: one already started first, then the oldest waiting. */
    suspend fun next(): Download? = dao.allNow().let { all -> all.firstOrNull { it.systemId == ACTIVE } ?: all.firstOrNull { it.systemId == PENDING } }

    /**
     * Where downloads go: the phone's shared Download/jaskTV folder, as ordinary files with readable names ("Show -
     * S01E09 - Title.mkv"). They stay when the app is uninstalled, show in the Files app and play in any player, and the
     * debug, perf and release installs all save to the same folder. Download/ (not Movies/) because Android only lets
     * video types into Movies/, and a download is a .part file until it finishes. Falls back to the app's own folder
     * where the shared one can't be written (Android 10 and older without a storage permission).
     */
    private val sharedDir: File? by lazy {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return@lazy null
        runCatching {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jaskTV")
            d.mkdirs()
            // mkdirs succeeding isn't proof: write a file.
            val probe = File(d, ".jasktv-probe-${android.os.Process.myPid()}")
            probe.writeText("ok"); probe.delete()
            d
        }.onFailure { android.util.Log.w("jaskDl", "shared Download/jaskTV not writable, using app storage: $it") }.getOrNull()
    }

    /** The app's own folder: where downloads went before the shared folder (and still do where it can't be written). */
    private fun oldDir(): File = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "downloads").also { it.mkdirs() }

    private fun dir(): File = sharedDir ?: oldDir()

    private fun extensionOf(download: Download): String {
        // From the address's path when it names a video file; a torrent copy (core's or TorrServer's address, no file
        // name in it) is saved as .mkv, the usual container for those (the player reads either regardless).
        val last = android.net.Uri.parse(download.sourceUrl).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        return last.takeIf { it in VIDEO_EXTENSIONS } ?: if ("link=" in download.sourceUrl) "mkv" else "mp4"
    }

    /** The name it had in the app's own folder ("movie_17.mkv"). */
    private fun oldFileFor(download: Download) = File(oldDir(), "${download.mediaKey.replace(':', '_')}.${extensionOf(download)}")

    // ---- The folder layout every jaskTV install shares --------------------------------------------------------------
    //
    //   Download/jaskTV/Movies/Other Mommy.mkv
    //   Download/jaskTV/Shows/Silo/Season 3/Silo - S03E07 - Radio.mkv
    //
    // Where a film or episode belongs is worked out from its name alone, so the debug, perf and release installs (and a
    // reinstall) never need to tell each other anything: each looks where the file should be. A show's folder and a
    // film's file are matched loosely (case, punctuation, a trailing "(2023)" or "(US)" ignored); an episode by its
    // SxxEyy inside its show's folder. Seeing files another install saved needs All files access (see [canSeeAll]).

    /** A title as a file name: the provider's tags and a trailing year or country dropped, unsafe characters gone. */
    private fun clean(raw: String): String {
        val cleaned = runCatching { app.opentv.data.parser.VodTitleCleaner.clean(raw) }.getOrDefault(raw)
        val stripped = cleaned.replace(Regex("""(\s*[(\[]\s*(?:(?:19|20)\d{2}|[A-Za-z]{2,3}|4K|8K|UHD|FHD|HD|SD|MULTI)\s*[)\]])+\s*$""", RegexOption.IGNORE_CASE), "").trim()
        return stripped.ifBlank { cleaned }.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().trim('.').take(80)
    }

    /** What two names must share to be the same title: letters and digits only, any case. */
    private fun keyOf(name: String) = clean(name).lowercase().filter(Char::isLetterOrDigit)

    private fun code(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)

    private fun isEpisode(d: Download) = d.seriesTitle != null && d.season != null && d.episode != null

    /** Where [d] belongs in the shared layout. */
    private fun expectedFile(d: Download, root: File = dir()): File {
        val ext = extensionOf(d)
        if (!isEpisode(d)) return File(File(root, "Movies"), "${clean(d.title).ifBlank { "Film" }}.$ext")
        val show = clean(d.seriesTitle!!).ifBlank { "Show" }
        val c = code(d.season!!, d.episode!!)
        // The episode's own name: what follows its SxxEyy in the provider's title, else the title minus the show's name.
        val raw = d.title
        val after = Regex("""[Ss]\d{1,2}\s*[Ee]\d{1,3}""").findAll(raw).lastOrNull()?.let { raw.substring(it.range.last + 1) } ?: raw
        val name = clean(after.trim(' ', '-', '–', ':', '.')).let { n -> if (keyOf(n).startsWith(keyOf(show))) clean(n.drop(show.length).trim(' ', '-', '–', ':')) else n }
            .takeIf { it.isNotBlank() && !it.equals("Episode ${d.episode}", true) && keyOf(it) != keyOf(show) }
        val file = listOfNotNull(show, c, name).joinToString(" - ") + ".$ext"
        return File(File(File(File(root, "Shows"), show), "Season ${d.season}"), file)
    }

    /** A finished file already in the shared folder for this film or episode, whichever install saved it. */
    private fun findExisting(title: String, show: String?, season: Int?, episode: Int?): File? {
        val root = sharedDir ?: return null
        fun video(f: File) = f.isFile && f.extension.lowercase() in VIDEO_EXTENSIONS
        return runCatching {
            if (show != null && season != null && episode != null) {
                val showDir = File(root, "Shows").listFiles()?.firstOrNull { it.isDirectory && keyOf(it.name) == keyOf(show) } ?: return null
                val c = code(season, episode).lowercase()
                showDir.walkTopDown().maxDepth(2).firstOrNull { video(it) && c in it.name.lowercase() }
            } else {
                File(root, "Movies").listFiles()?.firstOrNull { video(it) && keyOf(it.nameWithoutExtension) == keyOf(title) }
            }
        }.getOrNull()
    }

    private fun findExisting(d: Download) = findExisting(d.title, d.seriesTitle?.takeIf { isEpisode(d) }, d.season, d.episode)

    /**
     * The file a download is (or will be) saved as: the path recorded in the notes, else one already in the folder,
     * else where the layout puts it. One finished in the app's own folder before all this keeps its old name until
     * [tidy] moves it.
     */
    private fun fileFor(download: Download): File {
        notes.get(download.id)?.optString("file")?.takeIf(String::isNotBlank)?.let { File(it) }?.let { if (it.exists() || download.systemId != DONE) return it }
        if (download.systemId == DONE) {
            val old = oldFileFor(download)
            if (old.exists()) return old
            findExisting(download)?.let { notes.put(download.id, "file", it.path); return it }
        }
        val old = oldFileFor(download)
        if (File(old.parentFile, old.name + ".part").exists()) return old
        return findExisting(download) ?: expectedFile(download)
    }

    /** Tells Android's media index about a finished file, so the Files app and video players list it straight away. */
    private fun index(file: File) {
        if (sharedDir != null && file.path.startsWith(sharedDir!!.path)) runCatching { android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) }
    }

    /** Picks and records [download]'s file before its first byte. */
    private fun claimFile(download: Download): File {
        notes.get(download.id)?.optString("file")?.takeIf(String::isNotBlank)?.let { return File(it) }
        val f = fileFor(download)
        f.parentFile?.mkdirs()
        // A partial file nobody owns any more (another copy, from another source, that was replaced or deleted): its bytes
        // aren't this download's, so start clean rather than resume from them.
        File(f.parentFile, f.name + ".part").takeIf { it.exists() && it.parentFile == f.parentFile }?.delete()
        notes.put(download.id, "file", f.path)
        return f
    }

    /** Whether this install can see files other installs saved (All files access; Android 11+). */
    fun canSeeAll(): Boolean = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    @Volatile private var tidied = false

    /** Looks at the shared folder again (after All files access is turned on, say). */
    fun rescan() { tidied = false; kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { pump() } }

    /**
     * Brings the shared folder and this install's list into line, on start:
     *  - moves finished files from the app's own folder, or from an older flat layout, to where the layout puts them
     *    (a rename where Android allows, else copy, check the size, then delete the old copy; a failure leaves it be);
     *  - forgets finished ones whose file was deleted elsewhere;
     *  - lists every film and episode in the folder that this install hasn't (saved by another install, or before a
     *    reinstall), matched to the catalogue where it can be, so it shows in Downloads and plays from the file.
     */
    private suspend fun tidy() {
        val shared = sharedDir ?: return
        // Deleted outside the app (Files, another install): forget it. Only when this install can see every file, so
        // "can't see it" really means "gone".
        if (canSeeAll()) dao.allNow().filter { it.systemId == DONE && !fileFor(it).exists() }.forEach { d ->
            dao.delete(d.id); notes.forget(d.id); android.util.Log.i("jaskDl", "${d.title}: file deleted elsewhere; removed from the list")
        }
        val all = dao.allNow()
        val legacyDone = legacyStatus(all.filter { it.systemId >= 0 }).values.filter { it.state == State.DONE }
        val finished = all.filter { it.systemId == DONE }.map { it to fileFor(it) } +
            legacyDone.mapNotNull { item -> item.fileUri?.let { u -> runCatching { File(android.net.Uri.parse(u).path!!) }.getOrNull() }?.let { item.download to it } }
        for ((d, from) in finished) {
            if (!from.exists()) continue
            // Already in the layout (this file, or another copy where it belongs): leave it be.
            if (findExisting(d) != null) continue
            // Its own extension, whatever the address says: the content doesn't change with the name.
            val to = expectedFile(d, shared).let { File(it.parentFile, it.nameWithoutExtension + "." + from.extension) }
            if (to.path == from.path) continue
            if (to.exists()) continue // something else is already there: leave both alone
            to.parentFile?.mkdirs()
            val when_ = from.lastModified()
            val tmp = File(to.parentFile, to.name + ".part")
            val ok = runCatching { from.renameTo(to) }.getOrDefault(false) || runCatching {
                from.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                tmp.length() == from.length() && tmp.renameTo(to)
            }.getOrDefault(false)
            if (!ok) { tmp.delete(); android.util.Log.w("jaskDl", "couldn't move ${from.name}; left where it was"); continue }
            notes.put(d.id, "file", to.path)
            if (notes.get(d.id)?.has("finishedAt") != true) notes.put(d.id, "finishedAt", when_)
            // Ours from now on. DownloadManager's own record is left alone: removing it deletes by its old path.
            if (d.systemId >= 0) dao.setSystemId(d.id, DONE)
            if (from.exists()) from.delete()
            from.parentFile?.takeIf { it.path.startsWith(shared.path) && it != shared }?.let { p -> if (p.list().isNullOrEmpty()) p.delete() }
            index(to)
            android.util.Log.i("jaskDl", "moved ${from.name} to ${to.path}")
        }
        adoptFolder(shared)
    }

    /** A file name drops punctuation the catalogue keeps ("Dexter: Resurrection"): search by one word, compare keys. */
    private fun longestWord(name: String) = name.split(' ').maxByOrNull { it.length }.orEmpty()

    /** Adds a row for each film or episode file in the shared folder that this install doesn't list yet. */
    private suspend fun adoptFolder(shared: File) {
        val known = dao.allNow().mapNotNull { d -> runCatching { fileFor(d).path }.getOrNull() }.toSet()
        val graph = app.opentv.core.ServiceLocator.get(context)
        val files = runCatching { shared.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }.toList() }.getOrDefault(emptyList())
        for (f in files) {
            if (f.path in known) continue
            val rel = f.relativeTo(shared).path.split('/')
            val ep = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""").find(f.name)
            val row: Download = if (rel.firstOrNull() == "Shows" && rel.size >= 3 && ep != null) {
                val showName = rel[1]
                val season = ep.groupValues[1].toInt(); val number = ep.groupValues[2].toInt()
                val series = runCatching { graph.database.series().search(longestWord(showName)).first() }.getOrDefault(emptyList())
                    .firstOrNull { keyOf(it.name) == keyOf(showName) }
                val episode = series?.let { s -> runCatching { graph.database.episodes().forSeries(s.sourceId, s.seriesId) }.getOrNull()?.firstOrNull { it.season == season && it.episodeNumber == number } }
                val title = f.nameWithoutExtension.substringAfter(code(season, number), "").trim(' ', '-').ifBlank { "Episode $number" }
                Download(mediaKey = episode?.let { "ep:${it.id}" } ?: "file:${f.path}", kind = "EPISODE", title = title,
                    posterUrl = episode?.stillUrl ?: series?.posterUrl, tag = null, seriesTitle = showName, season = season, episode = number,
                    sourceUrl = episode?.streamUrl ?: "", systemId = DONE, createdMillis = f.lastModified())
            } else {
                val name = f.nameWithoutExtension
                val movie = runCatching { graph.database.movies().search(longestWord(name)).first() }.getOrDefault(emptyList()).firstOrNull { keyOf(it.name) == keyOf(name) }
                Download(mediaKey = movie?.let { "movie:${it.id}" } ?: "file:${f.path}", kind = "MOVIE", title = name, posterUrl = movie?.posterUrl,
                    tag = null, sourceUrl = movie?.streamUrl ?: "", systemId = DONE, createdMillis = f.lastModified())
            }
            if (dao.byKey(row.mediaKey) != null) continue
            dao.insert(row)
            dao.byKey(row.mediaKey)?.let { notes.put(it.id, "file", f.path); notes.put(it.id, "finishedAt", f.lastModified()) }
            android.util.Log.i("jaskDl", "listed ${f.name} (saved by another install)")
        }
    }

    /**
     * For something this install has no row for: a finished file in the shared folder for it, from another install.
     * Adopts it (adds the row) so Downloads lists it too.
     */
    private suspend fun adoptFor(mediaKey: String): Download? {
        if (sharedDir == null) return null
        val graph = app.opentv.core.ServiceLocator.get(context)
        val id = mediaKey.substringAfter(':').toLongOrNull() ?: return null
        val row = when {
            mediaKey.startsWith("ep:") -> {
                val ep = graph.database.episodes().byId(id) ?: return null
                val series = graph.database.series().byProviderId(ep.sourceId, ep.seriesId) ?: return null
                Download(mediaKey = mediaKey, kind = "EPISODE", title = ep.title, posterUrl = ep.stillUrl ?: series.posterUrl, tag = null,
                    seriesTitle = series.name, season = ep.season, episode = ep.episodeNumber, sourceUrl = ep.streamUrl, systemId = DONE, createdMillis = 0)
            }
            mediaKey.startsWith("movie:") -> {
                val m = graph.database.movies().byId(id) ?: return null
                Download(mediaKey = mediaKey, kind = "MOVIE", title = m.name, posterUrl = m.posterUrl, tag = null, sourceUrl = m.streamUrl, systemId = DONE, createdMillis = 0)
            }
            else -> return null
        }
        val file = findExisting(row) ?: return null
        dao.insert(row.copy(createdMillis = file.lastModified()))
        return dao.byKey(mediaKey)?.also { notes.put(it.id, "file", file.path); notes.put(it.id, "finishedAt", file.lastModified()) }
    }

    private class HttpFail(val code: Int) : java.io.IOException("HTTP $code")

    /** The client for transfers: no overall deadline (a film takes minutes), but a stalled read gives up after 30 s. */
    private val transferHttp by lazy {
        http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }

    /**
     * Downloads [download] to its file, resuming whatever part is already there. Returns when it's finished, failed
     * for good, didn't fit, or was deleted. Retries after any break: 5, 10, 20, 40, then every 60 seconds, giving up
     * after 15 tries in a row with nothing received. [onUpdate] gets the live state (for the notification).
     */
    suspend fun transfer(download: Download, onUpdate: (Live) -> Unit) = withContext(Dispatchers.IO) {
        val id = download.id
        dao.setSystemId(id, ACTIVE)
        notes.put(id, "error", null)
        DownloadEngine.cancelled.remove(id) // a stale mark from a deleted row that had this id
        val final = claimFile(download)
        // Already there (another install saved it, or an earlier try finished): nothing to fetch.
        if (final.exists()) {
            dao.setSystemId(id, DONE); notes.put(id, "finishedAt", final.lastModified()); notes.put(id, "total", final.length())
            return@withContext
        }
        val part = File(final.parentFile, final.name + ".part")
        val host = DownloadEngine.hostOf(download.sourceUrl)
        var failures = 0
        fun publish(l: Live) { DownloadEngine.set(id, l); onUpdate(l) }
        try {
            while (true) {
                if (id in DownloadEngine.cancelled || dao.allNow().none { it.id == id }) { DownloadEngine.cancelled += id; return@withContext }
                // Standing aside while the player uses the same server (one-connection accounts).
                if (host != null && DownloadEngine.playingHost == host) {
                    DownloadEngine.busyHost = null
                    publish(Live(Phase.HELD, part.length(), notes.get(id)?.optLong("total") ?: 0, why = "Paused while you watch"))
                    delay(1_000); continue
                }
                if (!online()) { DownloadEngine.busyHost = null; publish(Live(Phase.NO_NETWORK, part.length(), notes.get(id)?.optLong("total") ?: 0, why = "Waiting for a connection")); delay(3_000); continue }
                val before = part.length()
                val outcome = runCatching { attempt(download, part, final, host, ::publish) }
                DownloadEngine.busyHost = null
                if (outcome.isSuccess) {
                    when (outcome.getOrThrow()) {
                        Outcome.DONE -> {
                            // Deleted while it ran (the cancel mark can miss a transfer between states): don't keep it.
                            if (id in DownloadEngine.cancelled || dao.allNow().none { it.id == id }) { final.delete(); return@withContext }
                            dao.setSystemId(id, DONE)
                            notes.put(id, "finishedAt", System.currentTimeMillis())
                            notes.put(id, "total", final.length())
                            index(final)
                            return@withContext
                        }
                        Outcome.NO_SPACE -> { dao.setSystemId(id, NO_SPACE); return@withContext }
                        Outcome.CANCELLED -> return@withContext
                        Outcome.YIELDED -> continue
                    }
                }
                val error = outcome.exceptionOrNull()
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (part.length() > before) failures = 0
                failures++
                val why = describe(error)
                // Gone for good (404/410): stop after a few tries. Anything else (refused because the account's one
                // connection is in use elsewhere, busy, offline) keeps waiting: the queue mustn't fail while the TV watches.
                val gone = error is HttpFail && (error.code == 404 || error.code == 410)
                if (gone && failures >= 3) { dao.setSystemId(id, FAILED); notes.put(id, "error", why); return@withContext }
                val refused = error is HttpFail && error.code in setOf(401, 403, 429, 458, 509)
                val wait = when (failures) { 1 -> 5_000L; 2 -> 10_000L; 3 -> 20_000L; 4 -> 40_000L; else -> if (refused) 120_000L else 60_000L }
                val at = System.currentTimeMillis() + wait
                android.util.Log.i("jaskDl", "${download.title}: $why; retry ${failures} in ${wait / 1000}s")
                while (System.currentTimeMillis() < at) {
                    if (id in DownloadEngine.cancelled) return@withContext
                    publish(Live(Phase.RETRY_WAIT, part.length(), notes.get(id)?.optLong("total") ?: 0, retryAt = at, why = why))
                    delay(1_000)
                }
            }
        } finally {
            DownloadEngine.busyHost = null
            DownloadEngine.set(id, null)
            if (id in DownloadEngine.cancelled) { part.delete(); DownloadEngine.cancelled.remove(id) }
        }
    }

    private enum class Outcome { DONE, NO_SPACE, CANCELLED, YIELDED }

    private fun attempt(download: Download, part: File, final: File, host: String?, publish: (Live) -> Unit): Outcome {
        val id = download.id
        var have = part.length()
        publish(Live(Phase.CONNECTING, have, notes.get(id)?.optLong("total") ?: 0))
        val request = okhttp3.Request.Builder().url(download.sourceUrl).header("User-Agent", USER_AGENT)
            .apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val call = transferHttp.newCall(request)
        DownloadEngine.busyHost = host
        call.execute().use { r ->
            if (r.code == 416) {
                // Asked to start past the end: the part file is already whole (or wrong); finish or start over.
                val total = notes.get(id)?.optLong("total") ?: 0
                if (total > 0 && have >= total) { part.renameTo(final); return Outcome.DONE }
                part.delete(); throw HttpFail(416)
            }
            if (!r.isSuccessful) throw HttpFail(r.code)
            val resumed = r.code == 206 && have > 0
            if (!resumed) have = 0
            val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 }?.let { it + have } ?: 0L
            if (total > 0) notes.put(id, "total", total)
            if (total > 0 && total - have + MARGIN_BYTES > freeBytes()) return Outcome.NO_SPACE
            val body = r.body ?: throw java.io.IOException("empty body")
            java.io.FileOutputStream(part, resumed).buffered(1 shl 20).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(256 * 1024)
                var bytes = have
                var lastAt = System.currentTimeMillis(); var lastBytes = bytes; var speed = 0.0
                var techDone = notes.get(id)?.has("tech") == true
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    bytes += n
                    val now = System.currentTimeMillis()
                    if (now - lastAt >= 1_000) {
                        val measured = (bytes - lastBytes) * 1000.0 / (now - lastAt)
                        speed = if (speed == 0.0) measured else speed * 0.7 + measured * 0.3
                        lastAt = now; lastBytes = bytes
                        publish(Live(Phase.RUNNING, bytes, total, speed))
                        if (id in DownloadEngine.cancelled) return Outcome.CANCELLED
                        // Someone started watching from this server: stop here, carry on from this byte after.
                        if (host != null && DownloadEngine.playingHost == host) { out.flush(); return Outcome.YIELDED }
                    }
                    if (!techDone && bytes >= 262_144) {
                        out.flush()
                        MediaHead.ofFile(part)?.line()?.takeIf(String::isNotBlank)?.let { notes.put(id, "tech", it) }
                        techDone = true
                    }
                }
                out.flush()
                if (total > 0 && bytes < total) throw java.io.IOException("connection closed at ${bytes * 100 / total}%")
            }
        }
        if (final.exists()) final.delete()
        if (!part.renameTo(final)) throw java.io.IOException("couldn't save the file")
        return Outcome.DONE
    }

    private fun describe(e: Throwable?): String = when (e) {
        is HttpFail -> when (e.code) {
            401, 403 -> "The provider refused (is something else using your account's connection?)"
            404 -> "The provider no longer has this file"
            429, 503, 509, 520 -> "The provider is busy"
            else -> "The provider answered ${e.code}"
        }
        is java.net.SocketTimeoutException -> "The provider stopped sending"
        is java.net.UnknownHostException, is java.net.ConnectException -> "Can't reach the provider"
        null -> "Something went wrong"
        else -> e.message?.takeIf { it.length < 60 } ?: "The connection dropped"
    }

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** The finished local file for [mediaKey], or null when it isn't fully downloaded. */
    suspend fun localFile(mediaKey: String): String? = withContext(Dispatchers.IO) {
        val download = dao.byKey(mediaKey) ?: runCatching { adoptFor(mediaKey) }.getOrNull() ?: return@withContext null
        withStatus(listOf(download)).firstOrNull()?.takeIf { it.state == State.DONE }?.fileUri
    }

    /** Cancels the transfer if it's running, deletes the file, and forgets the entry. */
    suspend fun remove(download: Download) = withContext(Dispatchers.IO) {
        if (download.systemId >= 0) runCatching { manager.remove(download.systemId) }
        // Mark it whenever the downloader may be on it; the transfer clears the mark when it stops (or the next starts).
        val running = download.id in DownloadEngine.live.value
        if (running || download.systemId == ACTIVE) DownloadEngine.cancelled += download.id
        val file = fileFor(download)
        if (!running) File(file.parentFile, file.name + ".part").delete()
        if (download.systemId == DONE) file.delete()
        dao.delete(download.id)
        notes.forget(download.id)
    }

    /** Puts a failed or unfitting download back in the queue. */
    suspend fun retry(download: Download) = withContext(Dispatchers.IO) {
        dao.setSystemId(download.id, PENDING); notes.put(download.id, "error", null)
        app.opentv.data.work.DownloadService.start(context)
    }

    /** Space left where downloads are saved. */
    fun freeBytes(): Long = runCatching { StatFs(dir().path).availableBytes }.getOrDefault(0L)

    private fun originOf(url: String): String? {
        if ("link=" in url) return "Torrent"
        val host = DownloadEngine.hostOf(url) ?: return null
        return originNames.getOrPut(host) {
            runCatching { kotlinx.coroutines.runBlocking { app.opentv.core.ServiceLocator.get(context).sourceRepository.enabled() } }.getOrNull()
                ?.firstOrNull { DownloadEngine.hostOf(it.url) == host }?.name ?: ""
        }.takeIf(String::isNotBlank)
    }
    private val originNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun withStatus(downloads: List<Download>, all: List<Download> = downloads): List<Item> {
        if (downloads.isEmpty()) return emptyList()
        val legacy = legacyStatus(downloads.filter { it.systemId >= 0 })
        val live = DownloadEngine.live.value
        val queue = all.filter { it.systemId == PENDING }.sortedBy { it.createdMillis }.map { it.id }
        return downloads.map { d ->
            val note = notes.get(d.id)
            // "tech" saved blank means looked and found nothing (not MKV): don't read the file again.
            val looked = note?.has("tech") == true
            val tech = note?.optString("tech")?.takeIf(String::isNotBlank)
            val origin = originOf(d.sourceUrl)
            val total = note?.optLong("total") ?: 0L
            when (d.systemId) {
                PENDING -> Item(d, State.QUEUED, partSize(d), total, null, tech = tech, origin = origin, place = queue.indexOf(d.id) + 1)
                NO_SPACE -> Item(d, State.FAILED, 0, total, null, reason = DownloadManager.ERROR_INSUFFICIENT_SPACE, origin = origin)
                ACTIVE -> live[d.id]?.let { l ->
                    val state = if (l.phase == Phase.RUNNING || l.phase == Phase.CONNECTING) State.RUNNING else State.PAUSED
                    Item(d, state, l.bytes, if (l.total > 0) l.total else total, null, live = l, tech = tech, origin = origin)
                } ?: Item(d, State.QUEUED, partSize(d), total, null, tech = tech, origin = origin, place = 1)
                DONE -> {
                    val f = fileFor(d)
                    if (f.exists()) Item(d, State.DONE, f.length(), f.length(), android.net.Uri.fromFile(f).toString(),
                        tech = if (looked) tech else techFromFile(d.id, f), origin = origin, finishedAt = note?.optLong("finishedAt") ?: f.lastModified())
                    else Item(d, State.FAILED, 0, 0, null, origin = origin, error = "The file is gone")
                }
                FAILED -> Item(d, State.FAILED, partSize(d), total, null, tech = tech, origin = origin, error = note?.optString("error")?.takeIf(String::isNotBlank))
                else -> (legacy[d.systemId] ?: Item(d, State.FAILED, 0, 0, null)).let { item ->
                    // Finished under DownloadManager: what the file is, read once from its first bytes.
                    val file = item.fileUri?.let { runCatching { File(android.net.Uri.parse(it).path!!) }.getOrNull() }
                    item.copy(tech = if (looked) tech else file?.takeIf { item.state == State.DONE }?.let { techFromFile(d.id, it) }, origin = origin,
                        finishedAt = file?.lastModified() ?: 0)
                }
            }
        }
    }

    private fun techFromFile(id: Long, file: File): String? =
        (MediaHead.ofFile(file)?.line() ?: "").also { notes.put(id, "tech", it) }.takeIf(String::isNotBlank)

    private fun partSize(d: Download): Long { val f = fileFor(d); return File(f.parentFile, f.name + ".part").length() }

    /** DownloadManager's view of rows from before this downloader, by their DownloadManager id. */
    private fun legacyStatus(rows: List<Download>): Map<Long, Item> {
        if (rows.isEmpty()) return emptyMap()
        val byId = HashMap<Long, Item>()
        runCatching {
            val query = DownloadManager.Query().setFilterById(*rows.map { it.systemId }.toLongArray())
            manager.query(query)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFarCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val download = rows.firstOrNull { it.systemId == id } ?: continue
                    val state = when (c.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                        DownloadManager.STATUS_FAILED -> State.FAILED
                        DownloadManager.STATUS_PAUSED -> State.PAUSED
                        DownloadManager.STATUS_RUNNING -> State.RUNNING
                        else -> State.QUEUED
                    }
                    byId[id] = Item(download, state, c.getLong(soFarCol), c.getLong(totalCol), c.getString(uriCol), reason = c.getInt(reasonCol))
                }
            }
        }
        return byId
    }

    private fun ticker(): Flow<Long> = flow {
        var tick = 0L
        while (true) { emit(tick++); delay(1_000) }
    }

    companion object {
        /** [Download.systemId] of an item waiting its turn. */
        const val PENDING = -1L
        /** [Download.systemId] of an item that turned out not to fit when its turn came. */
        const val NO_SPACE = -2L
        /** Being downloaded (or partly downloaded) by jaskTV's downloader. */
        const val ACTIVE = -3L
        /** Finished by jaskTV's downloader; the file is at its usual name. */
        const val DONE = -4L
        /** Gave up after many tries (the reason is in the notes). */
        const val FAILED = -5L
        /** Matches what the VOD player sends, so providers treat a download like playback. */
        const val USER_AGENT = "OpenTV/0.1 (Android)"
        private const val MIN_FREE_BYTES = 1_500L * 1024 * 1024
        /** Head-room left over after a download, so it doesn't fill the phone to the last byte. */
        private const val MARGIN_BYTES = 500L * 1024 * 1024
    }
}
