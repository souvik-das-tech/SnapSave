package com.bbcl.snapsave.download

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Engine(
    val state: State,
    val version: String? = null,
    val message: String? = null,
    /** Newest yt-dlp release on GitHub, once we've checked. */
    val latest: String? = null,
    /** The user asked to update; it runs as soon as no yt-dlp process is using the engine. */
    val updatePending: Boolean = false,
) {
    enum class State { STARTING, READY, UPDATING, FAILED }

    val updateAvailable get() = latest != null && latest != version
}

data class Settings(val parallel: Int, val lastPick: String?)

/**
 * The download queue. Each download runs yt-dlp (bundled in the APK) on its own
 * thread; up to [Settings.parallel] run at once and the rest wait as QUEUED.
 *
 * Pause kills the yt-dlp process but keeps its work folder, so the next run
 * continues the `.part` files instead of starting over.
 */
@SuppressLint("StaticFieldLeak") // only ever holds the application context
object Downloads {
    private const val FOLDER = "SnapSave"
    private const val INFO_TAG = "SNAPSAVE_INFO "
    private const val INFO_FILE = "info.json"
    private const val RELEASES = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    /** A probed link is reused for this long (YouTube's format URLs live ~6 hours). */
    private const val PROBE_TTL = 15 * 60 * 1000L

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _engine = MutableStateFlow(Engine(Engine.State.STARTING))
    val engine: StateFlow<Engine> = _engine.asStateFlow()

    private val _settings = MutableStateFlow(Settings(3, null))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Download id -> worker thread. */
    private val workers = ConcurrentHashMap<String, Thread>()
    /** Download id -> current yt-dlp process id. */
    private val processes = ConcurrentHashMap<String, String>()
    /** Download id -> what the user asked a running worker to become (PAUSED or CANCELLED). */
    private val stopRequests = ConcurrentHashMap<String, Status>()
    /** Link -> its formats, so sharing the same link again opens instantly. */
    private val probes = ConcurrentHashMap<String, Probe>()
    /** yt-dlp processes reading formats right now; the engine is never swapped under them. */
    private var probing = 0
    @Volatile private var updateWanted = false
    @Volatile private var lastUpdateCheck = 0L

    fun init(context: Context) {
        app = context.applicationContext
        _settings.value = Settings(
            parallel = prefs.getInt("parallel", 3),
            lastPick = prefs.getString("lastPick", null),
        )
        _items.value = load()
        Thread {
            try {
                YoutubeDL.init(app)
                FFmpeg.getInstance().init(app)
                _engine.update { Engine(Engine.State.READY, YoutubeDL.version(app), latest = it.latest) }
                schedule()
            } catch (e: Exception) {
                _engine.update { Engine(Engine.State.FAILED, message = e.message, latest = it.latest) }
            }
        }.start()
        checkForUpdate()
    }

    // ---- settings -------------------------------------------------------------------------

    fun setParallel(n: Int) {
        prefs.edit().putInt("parallel", n).apply()
        _settings.update { it.copy(parallel = n) }
        schedule()
    }

    /** Asks GitHub for the newest yt-dlp release (at most every 30 minutes) without downloading it. */
    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 30 * 60 * 1000L) return
        lastUpdateCheck = now
        Thread {
            val tag = runCatching {
                val c = URL(RELEASES).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("tag_name")
                } finally {
                    c.disconnect()
                }
            }.getOrElse {
                lastUpdateCheck = 0 // offline: try again on the next app open
                return@Thread
            }
            _engine.update { it.copy(latest = tag) }
        }.start()
    }

    /**
     * Replacing yt-dlp under a running process breaks it half way, so the update waits
     * until running downloads finish; queued ones hold back and start on the new engine.
     */
    fun updateEngine() {
        if (_engine.value.state != Engine.State.READY || updateWanted) return
        updateWanted = true
        _engine.update { it.copy(updatePending = true, message = null) }
        runPendingUpdate()
    }

    @Synchronized
    private fun runPendingUpdate() {
        if (!updateWanted || workers.isNotEmpty() || probing > 0 || _engine.value.state != Engine.State.READY) return
        updateWanted = false
        _engine.update { it.copy(state = Engine.State.UPDATING, updatePending = false) }
        Thread {
            val msg = try {
                when (YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)) {
                    YoutubeDL.UpdateStatus.DONE -> "Updated to the latest version"
                    else -> "Already up to date"
                }
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            val version = YoutubeDL.version(app)
            probes.clear()
            _engine.update { Engine(Engine.State.READY, version, msg, latest = it.latest) }
            schedule()
        }.start()
    }

    // ---- reading formats -------------------------------------------------------------------

    /**
     * Resolves the link with yt-dlp and lists the qualities it really has. Cancelling the
     * calling coroutine kills the yt-dlp process.
     */
    suspend fun probe(url: String): Probe {
        probes[url]?.takeIf { System.currentTimeMillis() - it.createdAt < PROBE_TTL }?.let { return it }
        while (!enterProbe()) {
            val e = engine.first { it.state == Engine.State.READY || it.state == Engine.State.FAILED }
            if (e.state == Engine.State.FAILED) error("The download engine failed to start: ${e.message}")
        }
        val pid = "probe:${System.nanoTime()}"
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { YoutubeDL.destroyProcessById(pid) }
            Thread({
                try {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--dump-single-json")
                        addOption("--no-playlist")
                        addOption("--playlist-items", "1")
                        addOption("--no-warnings")
                        addOption("--socket-timeout", "20")
                    }
                    val probe = Probe.parse(url, YoutubeDL.execute(request, pid).out)
                    probes[url] = probe
                    cont.resume(probe)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                } finally {
                    leaveProbe()
                }
            }, "probe").start()
        }
    }

    @Synchronized
    private fun enterProbe(): Boolean {
        if (_engine.value.state != Engine.State.READY) return false
        probing++
        return true
    }

    @Synchronized
    private fun leaveProbe() {
        probing--
        runPendingUpdate()
    }

    // ---- queue actions ----------------------------------------------------------------------

    sealed interface AddResult {
        data object Added : AddResult
        data object Duplicate : AddResult
    }

    /** Queues the option the user picked from [probe]. */
    fun add(probe: Probe, option: Option): AddResult {
        val pick = option.pick
        if (_items.value.any { it.url == probe.url && it.pick == pick && (it.status.isActive || it.status == Status.PAUSED) }) {
            return AddResult.Duplicate
        }
        prefs.edit().putString("lastPick", pick.key).apply()
        _settings.update { it.copy(lastPick = pick.key) }
        val now = System.currentTimeMillis()
        val id = "d$now${(100..999).random()}"
        // The worker downloads from this instead of extracting the page again.
        runCatching { File(workDir(id).apply { mkdirs() }, INFO_FILE).writeText(probe.infoJson) }
        val item = DownloadItem(
            id = id, url = probe.url, pick = pick, createdAt = now,
            title = probe.title, thumbnail = probe.thumbnail, uploader = probe.uploader, site = probe.site,
            durationSec = probe.durationSec, size = option.bytes.takeIf { it > 0 }?.let { "~" + humanBytes(it) },
        )
        _items.update { listOf(item) + it }
        save()
        schedule()
        return AddResult.Added
    }

    fun pause(id: String) = stop(id, Status.PAUSED)

    fun cancel(id: String) = stop(id, Status.CANCELLED)

    fun resume(id: String) {
        set(id, persist = true) {
            if (it.status == Status.PAUSED || it.status == Status.FAILED || it.status == Status.CANCELLED) {
                it.copy(status = Status.QUEUED, error = null, speed = null, etaSec = -1)
            } else it
        }
        schedule()
    }

    /** Removes the entry; with [deleteFile] a finished download's file goes too. */
    fun remove(id: String, deleteFile: Boolean) {
        val item = get(id) ?: return
        if (item.status.isActive) cancel(id)
        if (deleteFile && item.fileUri != null) {
            runCatching { app.contentResolver.delete(Uri.parse(item.fileUri), null, null) }
        }
        if (!item.status.isRunning) workDir(id).deleteRecursively()
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    fun clearFinished() {
        _items.update { list -> list.filterNot { it.status == Status.COMPLETED } }
        save()
    }

    private fun stop(id: String, target: Status) {
        val item = get(id) ?: return
        if (workers.containsKey(id)) {
            stopRequests[id] = target
            processes[id]?.let { YoutubeDL.destroyProcessById(it) }
        } else if (item.status == Status.QUEUED || item.status == Status.PAUSED || item.status == Status.FAILED) {
            if (target == Status.CANCELLED) workDir(id).deleteRecursively()
            set(id, persist = true) { it.copy(status = target, speed = null, etaSec = -1) }
        }
    }

    // ---- scheduler & worker ------------------------------------------------------------------

    @Synchronized
    private fun schedule() {
        if (_engine.value.state != Engine.State.READY || updateWanted) return
        val free = _settings.value.parallel - workers.size
        _items.value
            .filter { it.status == Status.QUEUED && !workers.containsKey(it.id) }
            .sortedBy { it.createdAt }
            .take(free.coerceAtLeast(0))
            .forEach { item ->
                val t = Thread({ work(item.id) }, "dl-${item.id}")
                workers[item.id] = t
                t.start()
            }
        if (_items.value.any { it.status.isActive }) DownloadService.start(app)
    }

    private fun work(id: String) {
        val dir = workDir(id).apply { mkdirs() }
        try {
            val item = get(id) ?: return
            set(id, persist = true) { it.copy(status = Status.FETCHING, error = null) }
            // Reuse the formats read for the share sheet. If their links have expired,
            // yt-dlp notices and re-extracts from the page URL stored inside.
            val info = File(dir, INFO_FILE)
            val request = (
                if (info.isFile) YoutubeDLRequest(emptyList()).apply { addOption("--load-info-json", info.absolutePath) }
                else YoutubeDLRequest(item.url)
            ).apply {
                addOption("--no-playlist")
                addOption("--playlist-items", "1")
                addOption("-o", "${dir.absolutePath}/%(title).100B [%(id)s].%(ext)s")
                addOption("--no-mtime")
                // It prints the metadata as a tagged JSON line right before downloading.
                addOption("--print", "before_dl:$INFO_TAG%(.{title,thumbnail,uploader,channel,extractor_key,duration,format_id,filesize_approx})j")
                addOption("--no-simulate")
                addOption("--no-quiet")
                // Against stalls: fetch HLS/DASH pieces 4 at a time, re-extract when a server
                // throttles below 100 KB/s, and drop a dead socket after 20 s and retry.
                addOption("--concurrent-fragments", "4")
                addOption("--throttled-rate", "100K")
                addOption("--socket-timeout", "20")
                addOption("--retries", "15")
                addOption("--fragment-retries", "15")
                addCommands(item.pick.args)
            }
            var parts = 1
            var part = 0
            var lastUi = 0L
            execute(id, request) { pct, eta, line ->
                when {
                    line.startsWith(INFO_TAG) -> {
                        parts = applyInfo(id, line.removePrefix(INFO_TAG))
                        set(id, persist = true) { it.copy(status = Status.DOWNLOADING) }
                    }
                    line.startsWith("[download] Destination:") -> part = (part + 1).coerceAtMost(parts)
                    line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[Fixup") ->
                        set(id) { it.copy(status = Status.PROCESSING, progress = 1f, speed = null, etaSec = -1) }
                    line.startsWith("[download]") && pct >= 0 && PERCENT.containsMatchIn(line) -> {
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 300 && pct < 100f) return@execute
                        lastUi = now
                        val done = (part - 1).coerceAtLeast(0)
                        val overall = ((done + pct / 100f) / parts).coerceIn(0f, 1f)
                        set(id) {
                            it.copy(
                                status = Status.DOWNLOADING,
                                progress = overall,
                                etaSec = eta,
                                speed = SPEED.find(line)?.groupValues?.get(1) ?: it.speed,
                                size = it.size ?: SIZE.find(line)?.groupValues?.get(1),
                            )
                        }
                    }
                }
            }

            set(id) { it.copy(status = Status.PROCESSING, progress = 1f, speed = null, etaSec = -1) }
            val output = dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension !in setOf("json", "part", "ytdl", "jpg", "webp") }
                .maxByOrNull { it.length() }
                ?: error("yt-dlp finished but produced no file")
            val bytes = output.length()
            val uri = publish(output)
            dir.deleteRecursively()
            set(id, persist = true) {
                it.copy(
                    status = Status.COMPLETED, progress = 1f, speed = null, etaSec = -1,
                    fileName = output.name, fileUri = uri.toString(), mime = mimeOf(output), fileBytes = bytes,
                )
            }
            get(id)?.let { DownloadService.notifyFinished(app, it) }
        } catch (e: Throwable) {
            Log.w("SnapSave", "download $id stopped", e)
            val requested = stopRequests[id]
            when {
                requested == Status.CANCELLED -> {
                    dir.deleteRecursively()
                    set(id, persist = true) { it.copy(status = Status.CANCELLED, speed = null, etaSec = -1, progress = 0f) }
                }
                requested == Status.PAUSED || e is YoutubeDL.CanceledException ->
                    set(id, persist = true) { it.copy(status = Status.PAUSED, speed = null, etaSec = -1) }
                else -> {
                    // Retry extracts the page afresh instead of trusting the saved formats.
                    File(dir, INFO_FILE).delete()
                    set(id, persist = true) { it.copy(status = Status.FAILED, error = friendlyError(e), speed = null, etaSec = -1) }
                    get(id)?.let { DownloadService.notifyFinished(app, it) }
                }
            }
        } finally {
            stopRequests.remove(id)
            processes.remove(id)
            workers.remove(id)
            runPendingUpdate()
            schedule()
        }
    }

    /** Runs one yt-dlp process that [stop] can kill. */
    private fun execute(id: String, request: YoutubeDLRequest, callback: ((Float, Long, String) -> Unit)?): YoutubeDLResponse {
        if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
        val pid = "$id:${System.nanoTime()}"
        processes[id] = pid
        return YoutubeDL.execute(request, pid) { pct, eta, line ->
            // A stop that raced the process start missed it; catch it on the next line.
            if (stopRequests.containsKey(id)) YoutubeDL.destroyProcessById(pid)
            else callback?.invoke(pct, eta, line)
        }.also {
            processes.remove(id)
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
        }
    }

    /** Copies title/thumbnail/size into the item and returns how many streams will be downloaded. */
    private fun applyInfo(id: String, json: String): Int {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return 1
        val bytes = o.optLong("filesize_approx", 0)
        set(id, persist = true) {
            it.copy(
                title = o.optString("title").ifBlank { null } ?: it.title,
                thumbnail = o.optString("thumbnail").ifBlank { null } ?: it.thumbnail,
                uploader = (o.optString("uploader").ifBlank { null } ?: o.optString("channel").ifBlank { null }) ?: it.uploader,
                site = o.optString("extractor_key").ifBlank { null } ?: it.site,
                durationSec = o.optDouble("duration", -1.0).takeIf { d -> d > 0 }?.toInt() ?: it.durationSec,
                size = if (bytes > 0) humanBytes(bytes) else it.size,
            )
        }
        // "137+140" means separate video and audio streams, each with its own 0-100% run.
        return o.optString("format_id").count { it == '+' } + 1
    }

    // ---- saving to Downloads -----------------------------------------------------------------

    private fun publish(file: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create ${file.name} in Downloads")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        // Android 8/9: let the media scanner index it so we get a shareable content:// uri.
        var scanned: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), arrayOf(mimeOf(target))) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(target)
    }

    fun openIntent(item: DownloadItem): Intent? {
        val uri = item.fileUri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mime ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(item: DownloadItem): Intent? {
        val uri = item.fileUri?.let(Uri::parse) ?: return null
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime ?: "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---- state helpers -----------------------------------------------------------------------

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private fun set(id: String, persist: Boolean = false, change: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
        if (persist) save()
    }

    private fun workDir(id: String) = File(app.noBackupFilesDir, "work/$id")

    private val storeFile get() = File(app.filesDir, "downloads.json")

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(it.toJson()) }
        val tmp = File(storeFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(storeFile)
    }

    private fun load(): List<DownloadItem> = runCatching {
        val arr = JSONArray(storeFile.readText())
        (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun extractUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    fun friendlyError(e: Throwable): String {
        val line = e.message?.lines()?.lastOrNull { it.contains("ERROR") }
            ?: e.message?.lines()?.lastOrNull { it.isNotBlank() }
            ?: e.javaClass.simpleName
        val clean = line.substringAfter("ERROR:").trim()
        fun has(vararg s: String) = s.any { clean.contains(it, ignoreCase = true) }
        return when {
            has("Unsupported URL") -> "This link is not supported"
            has("not a bot") -> "YouTube blocked this request. Update the engine in Settings and retry"
            has("confirm your age", "age-restricted", "inappropriate") -> "This video is age-restricted and needs a login"
            has("Private video", "is private") -> "This video is private"
            has("login", "log in", "logged-in", "cookies", "rate-limit reached") -> "This site wants a login for this video"
            has("Requested format is not available") -> "That quality isn't available any more. Pick another one"
            has("Video unavailable", "has been removed", "no longer available", "not available in your country") ->
                "This video is unavailable or was removed"
            has("HTTP Error 403", "Forbidden") -> "The site refused the download. Update the engine in Settings and retry"
            has("No video formats found", "no video in this") -> "No video found at this link"
            has("Unable to download", "timed out", "resolve host", "Connection reset", "Network is unreachable") ->
                "Network problem. Check your internet and retry"
            else -> clean.take(160)
        }
    }

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    private val URL = Regex("""https?://[^\s<>"]+""")
    private val PERCENT = Regex("""\d+\.\d%""")
    private val SPEED =Regex("""at\s+([\d.]+\s*[KMG]i?B/s)""")
    private val SIZE = Regex("""of\s+~?\s*([\d.]+\s*[KMG]i?B)""")
}
