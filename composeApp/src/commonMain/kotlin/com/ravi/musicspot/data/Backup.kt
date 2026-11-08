package com.ravi.musicspot.data

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.logic.epochDayToCivil
import kotlinx.serialization.Serializable

/**
 * 1.0.2: one JSON file with everything the user built up in UpZe: settings (theme, glass, sources, EQ, lyrics…),
 * liked songs, playlists, saved albums/artists, listening history, play counts, "Your artists" and the last queue.
 * Not included on purpose: account sign-ins (tokens stay on the phone), downloaded audio files and caches.
 */
@Serializable
data class BackupFile(
    val app: String,
    val format: Int,
    val appVersion: String = "",
    val createdMs: Long = 0,
    val settings: AppSettings? = null,
    val library: LibraryData? = null,
    val playStats: PlayStatsData? = null,
    val seedArtists: List<SeedArtist>? = null,
    val queue: QueueSnapshot? = null,
) {
    companion object {
        const val APP = "UpZe"
        const val FORMAT = 1
        const val VERSION = "2.0.16"
    }
}

object Backup {
    data class Summary(val liked: Int, val playlists: Int, val history: Int)

    fun fileName(nowMs: Long = currentTimeMillis()): String {
        val day = ListenStats.epochDay(nowMs)
        val civil = epochDayToCivil(day)
        val y = civil.year.toString().padStart(4, '0')
        val m = civil.month.toString().padStart(2, '0')
        val d = civil.dayOfMonth.toString().padStart(2, '0')
        val minuteOfDay = ((nowMs % 86_400_000L) / 60_000L).toInt()
        val hour = (minuteOfDay / 60).toString().padStart(2, '0')
        val min = (minuteOfDay % 60).toString().padStart(2, '0')
        return "UpZe-backup-$y-$m-$d-$hour$min.json"
    }

    fun snapshot(nowMs: Long = currentTimeMillis()): BackupFile = BackupFile(
        app = BackupFile.APP,
        format = BackupFile.FORMAT,
        appVersion = BackupFile.VERSION,
        createdMs = nowMs,
        settings = Graph.settings.value,
        library = Graph.library.data.value,
        playStats = Graph.playStats.data.value,
        seedArtists = SeedArtists.state.value,
        queue = Graph.queueStore.read(),
    )

    fun encode(b: BackupFile): String = Http.json.encodeToString(BackupFile.serializer(), b)

    /** Throws [IllegalArgumentException] with a readable message when the file is not an UpZe backup. */
    fun decode(text: String): BackupFile {
        val b = runCatching { Http.json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("This file isn't an UpZe backup.") }
        require(b.app == BackupFile.APP) { "This file isn't an UpZe backup." }
        require(b.format <= BackupFile.FORMAT) { "This backup is from a newer UpZe. Update the app, then restore." }
        return b
    }

    fun exportJson(): String = encode(snapshot())

    /** Replaces the current library, settings, play counts and artists with the backup's (sign-ins are kept). */
    fun restoreJson(text: String): Summary {
        val b = decode(text)
        b.settings?.let { s -> Graph.settings.update { cur -> s.copy(setupDone = true, onboarded = true, guest = cur.guest) } }
        b.library?.let { Graph.library.replace(it) }
        b.playStats?.let { Graph.playStats.replace(it) }
        b.seedArtists?.let { SeedArtists.replace(it) }
        return summary(b)
    }

    fun summary(b: BackupFile) = Summary(
        b.library?.liked?.size ?: 0,
        b.library?.playlists?.size ?: 0,
        b.library?.history?.size ?: 0
    )
}
