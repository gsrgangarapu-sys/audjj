package com.autodj

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtTime(ms: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
fun fmtClock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
fun fmtDur(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}
fun Context.startPlayback(kind: String, endAt: Long = 0L) {
    ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java)
        .setAction(PlaybackService.ACTION_START).putExtra("kind", kind).putExtra("end_at", endAt))
}
fun Context.serviceAction(a: String) { startService(Intent(this, PlaybackService::class.java).setAction(a)) }

/** Returns "video", "playlist" or null if the link is not a valid YouTube video/playlist link. */
fun youtubeKind(link: String): String? {
    val u = try { java.net.URI(link.trim()) } catch (e: Exception) { return null }
    val scheme = u.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    val host = (u.host ?: return null).lowercase().removePrefix("www.")
    val q = (u.rawQuery ?: "").split("&").mapNotNull { val p = it.split("=", limit = 2); if (p.size == 2) p[0] to p[1] else null }.toMap()
    return when (host) {
        "youtube.com", "m.youtube.com", "music.youtube.com" -> when {
            u.path == "/playlist" && !q["list"].isNullOrBlank() -> "playlist"
            u.path == "/watch" && !q["v"].isNullOrBlank() -> "video"
            else -> null
        }
        "youtu.be" -> if ((u.path ?: "").length > 1) "video" else null
        else -> null
    }
}

object Audio {
    fun name(c: Context, uri: Uri): String =
        c.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) it.getString(i) else null } else null
        } ?: uri.lastPathSegment ?: "Unknown"
    fun duration(c: Context, uri: Uri): Long? {
        val m = MediaMetadataRetriever()
        return try { m.setDataSource(c, uri); m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
        catch (e: Exception) { null } finally { try { m.release() } catch (_: Exception) {} }
    }
    fun readable(c: Context, uri: Uri): Boolean =
        try { c.contentResolver.openInputStream(uri)?.use { true } ?: false } catch (e: Exception) { false }
}

object Previewer {
    private var mp: MediaPlayer? = null
    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null }
    fun play(c: Context, uri: Uri, onStarted: () -> Unit, onError: (String) -> Unit) {
        stop()
        try {
            val p = MediaPlayer(); mp = p
            p.setDataSource(c, uri)
            p.setOnPreparedListener { it.start(); onStarted(); Handler(Looper.getMainLooper()).postDelayed({ if (mp === it) stop() }, 10_000) }
            p.setOnErrorListener { _, w, e -> onError("error $w/$e"); stop(); true }
            p.prepareAsync()
        } catch (e: Exception) { onError(e.message ?: "cannot open file"); stop() }
    }
}
