package com.bbcl.snapsave.download

import org.json.JSONObject

enum class Status {
    QUEUED, FETCHING, DOWNLOADING, PROCESSING, PAUSED, COMPLETED, FAILED, CANCELLED;

    /** Holds (or is waiting for) a worker thread. */
    val isActive get() = this == QUEUED || this == FETCHING || this == DOWNLOADING || this == PROCESSING
    val isRunning get() = this == FETCHING || this == DOWNLOADING || this == PROCESSING
}

data class DownloadItem(
    val id: String,
    val url: String,
    val pick: Pick,
    val createdAt: Long,
    val status: Status = Status.QUEUED,
    val title: String? = null,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val site: String? = null,
    val durationSec: Int? = null,
    val progress: Float = 0f,
    val speed: String? = null,
    val size: String? = null,
    val etaSec: Long = -1,
    val error: String? = null,
    val fileName: String? = null,
    val fileUri: String? = null,
    val mime: String? = null,
    val fileBytes: Long = 0,
) {
    val displayTitle get() = title ?: url

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("pick", pick.toJson()); put("createdAt", createdAt)
        put("status", status.name); put("progress", progress.toDouble()); put("fileBytes", fileBytes)
        title?.let { put("title", it) }
        thumbnail?.let { put("thumbnail", it) }
        uploader?.let { put("uploader", it) }
        site?.let { put("site", it) }
        durationSec?.let { put("duration", it) }
        size?.let { put("size", it) }
        error?.let { put("error", it) }
        fileName?.let { put("fileName", it) }
        fileUri?.let { put("fileUri", it) }
        mime?.let { put("mime", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            fun str(k: String) = if (o.has(k)) o.getString(k) else null
            val saved = Status.valueOf(o.getString("status"))
            return DownloadItem(
                id = o.getString("id"),
                url = o.getString("url"),
                pick = o.optJSONObject("pick")?.let(Pick::fromJson) ?: Pick.legacy(str("quality")),
                createdAt = o.getLong("createdAt"),
                // A worker never survives a process restart; anything mid-flight is resumable.
                status = if (saved.isRunning) Status.PAUSED else saved,
                title = str("title"),
                thumbnail = str("thumbnail"),
                uploader = str("uploader"),
                site = str("site"),
                durationSec = if (o.has("duration")) o.getInt("duration") else null,
                progress = o.optDouble("progress", 0.0).toFloat(),
                size = str("size"),
                error = str("error"),
                fileName = str("fileName"),
                fileUri = str("fileUri"),
                mime = str("mime"),
                fileBytes = o.optLong("fileBytes", 0),
            )
        }
    }
}
