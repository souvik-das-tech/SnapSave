package com.bbcl.snapsave.download

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** What to download: a yt-dlp format selector built from the formats the link really has. */
data class Pick(
    val label: String,
    val selector: String,
    val audioOnly: Boolean = false,
    val toMp3: Boolean = false,
) {
    /** "video:1080p" / "audio:MP3": remembered so the sheet can mark the last choice. */
    val key get() = "${if (audioOnly) "audio" else "video"}:$label"

    val args: List<String>
        get() = buildList {
            add("-f"); add(selector)
            when {
                toMp3 -> addAll(listOf("-x", "--audio-format", "mp3", "--audio-quality", "0"))
                // Original stream, just unwrapped from its container (webm -> .opus, m4a stays).
                audioOnly -> add("-x")
                else -> addAll(listOf("--merge-output-format", "mp4"))
            }
            // Same resolution: prefer H.264 + AAC, which every phone can play.
            add("-S"); add("res,fps,vcodec:h264,acodec:aac")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label); put("selector", selector); put("audioOnly", audioOnly); put("toMp3", toMp3)
    }

    companion object {
        val MP3 = Pick("MP3", "ba/b", audioOnly = true, toMp3 = true)

        fun fromJson(o: JSONObject) = Pick(
            label = o.getString("label"),
            selector = o.getString("selector"),
            audioOnly = o.optBoolean("audioOnly"),
            toMp3 = o.optBoolean("toMp3"),
        )

        /** Downloads saved by v1.2 and earlier stored a fixed quality name instead of a selector. */
        fun legacy(name: String?): Pick = when (name) {
            "MP3" -> MP3
            "P1080" -> upTo(1080)
            "P720" -> upTo(720)
            "P480" -> upTo(480)
            else -> Pick("Best", "bv*+ba/b")
        }

        private fun upTo(h: Int) = Pick("${h}p", "bv*[height<=$h]+ba/b[height<=$h]/b")
    }
}

/** One row in the share sheet. */
data class Option(val pick: Pick, val detail: String, val bytes: Long)

/** A link yt-dlp has already resolved, so the sheet only offers what really exists. */
class Probe(
    val url: String,
    val title: String?,
    val thumbnail: String?,
    val uploader: String?,
    val site: String?,
    val durationSec: Int?,
    val video: List<Option>,
    val audio: List<Option>,
    /** yt-dlp's info JSON; the download loads it instead of extracting the page a second time. */
    val infoJson: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Parses `yt-dlp -J` output. */
        fun parse(url: String, json: String): Probe {
            var root = JSONObject(json)
            var raw = json
            // A playlist-shaped answer (multi-video post, playlist link): take the first video.
            root.optJSONArray("entries")?.optJSONObject(0)?.let { root = it; raw = it.toString() }

            val duration = root.optDouble("duration", 0.0).takeIf { it > 0 }
            val formats = root.optJSONArray("formats").objects().ifEmpty { listOf(root) }
            fun bytes(f: JSONObject): Long {
                val exact = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0).takeIf { it > 0 }
                if (exact != null) return exact
                val kbps = f.optDouble("tbr", 0.0).takeIf { it > 0 } ?: return 0
                return ((duration ?: return 0) * kbps * 125).toLong()
            }

            val videos = formats.filter { it.optString("vcodec") != "none" && it.optInt("height") > 0 }
            val audios = formats.filter { it.optString("vcodec") == "none" && it.optString("acodec").let { a -> a.isNotEmpty() && a != "none" } }
            val bestAudio = audios.maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)) }

            val video = videos
                .groupBy { it.optInt("height") }
                .toSortedMap(compareByDescending { it })
                .map { (height, group) ->
                    // The stream yt-dlp will most likely take with our -S order. It ranks plain
                    // https above HLS, whose bitrate (and so our size guess) is far higher.
                    val f = group.maxWith(
                        compareBy<JSONObject>(
                            { it.optDouble("fps", 0.0) },
                            { !it.optString("protocol").startsWith("m3u8") },
                            { isH264(it) },
                            { it.optDouble("tbr", 0.0) },
                        )
                    )
                    val width = f.optInt("width")
                    val short = if (width > 0) minOf(width, height) else height
                    val fps = f.optDouble("fps", 0.0).roundToInt()
                    val withAudio = f.optString("acodec") != "none"
                    val size = bytes(f) + if (withAudio) 0 else bestAudio?.let(::bytes) ?: 0
                    val tag = when {
                        short >= 2160 -> "4K"
                        short >= 1440 -> "2K"
                        short >= 1080 -> "Full HD"
                        short >= 720 -> "HD"
                        else -> "SD"
                    }
                    Option(
                        Pick(
                            label = "${short}p",
                            // Exact height first; the fallbacks only matter if the site changed in between.
                            selector = "bv*[height=$height]+ba/b[height=$height]/bv*[height<=$height]+ba/b[height<=$height]/b",
                        ),
                        detail = listOfNotNull(tag, if (fps > 30) "$fps fps" else null, "MP4").joinToString(" · "),
                        bytes = size,
                    )
                }
                .distinctBy { it.pick.label }
                .ifEmpty {
                    // The site doesn't say the resolution: offer the one thing we can promise.
                    if (formats.any { it.optString("vcodec") != "none" }) {
                        listOf(Option(Pick("Best", "bv*+ba/b"), "Best quality this site offers", formats.maxOf(::bytes)))
                    } else emptyList()
                }

            val originals = audios
                .sortedWith(
                    compareBy<JSONObject>(
                        { it.optString("format_id").contains("drc") }, // YouTube's volume-normalised duplicates
                        { -it.optInt("language_preference", 0) },
                        { -it.optDouble("abr", it.optDouble("tbr", 0.0)) },
                    )
                )
                .map { f ->
                    val kbps = f.optDouble("abr", f.optDouble("tbr", 0.0)).roundToInt()
                    val codec = codecName(f)
                    Option(
                        Pick(
                            label = if (kbps > 0) "$codec $kbps kbps" else codec,
                            selector = "${f.getString("format_id")}/ba/b",
                            audioOnly = true,
                        ),
                        detail = "Original quality · no conversion",
                        bytes = bytes(f),
                    )
                }
                .distinctBy { it.pick.label }
                .take(5)
            val mp3Bytes = duration?.let { (it * 245 * 125).toLong() } ?: 0 // ~V0 average bitrate
            val audio = if (video.isEmpty() && audios.isEmpty()) emptyList() else buildList {
                add(Option(Pick.MP3, "Plays everywhere · best MP3 quality", mp3Bytes))
                addAll(originals)
                // Only combined streams: the audio has to come out of the video file.
                if (originals.isEmpty()) {
                    add(Option(Pick("Original audio", "ba/b", audioOnly = true), "Audio track from the video, no conversion", 0))
                }
            }

            return Probe(
                url = url,
                title = root.optString("title").ifBlank { null },
                thumbnail = root.optString("thumbnail").ifBlank { null },
                uploader = root.optString("uploader").ifBlank { null } ?: root.optString("channel").ifBlank { null },
                site = root.optString("extractor_key").ifBlank { null },
                durationSec = duration?.toInt(),
                video = video,
                audio = audio,
                infoJson = raw,
            )
        }

        private fun isH264(f: JSONObject) = f.optString("vcodec").let { it.startsWith("avc") || it.startsWith("h264") }

        private fun codecName(f: JSONObject): String {
            val a = f.optString("acodec").lowercase()
            return when {
                a.startsWith("mp4a") || a.startsWith("aac") -> "M4A"
                a.startsWith("opus") -> "OPUS"
                a.startsWith("mp3") -> "MP3"
                a.startsWith("vorbis") -> "OGG"
                a.startsWith("flac") -> "FLAC"
                else -> f.optString("ext").uppercase().ifBlank { "Audio" }
            }
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
