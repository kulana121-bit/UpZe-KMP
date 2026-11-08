package com.ravi.musicspot.logic

import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.core.timeZoneOffsetMs
import com.ravi.musicspot.core.currentTimeMillis

/** 1.0.2 Settings › Listen stats: pure ranking over the per-day play log (unit-tested in ListenStatsTest). */
object ListenStats {
    enum class Period(val label: String, val days: Int?) {
        WEEK("1 week", 7), THREE_WEEKS("3 weeks", 21), MONTH("1 month", 30), YEAR("1 year", 365), ALL("All time", null)
    }

    data class SongRow(val track: Track, val plays: Int)
    data class ArtistRow(val name: String, val plays: Int, val art: String?, val songs: Int)
    data class AlbumRow(val name: String, val artist: String, val plays: Int, val art: String?)
    data class Report(
        val period: Period,
        val plays: Int,
        val minutes: Long,
        val songs: List<SongRow>,
        val artists: List<ArtistRow>,
        val albums: List<AlbumRow>,
        /** Plays per bucket, oldest first: days for week/3 weeks/month, months for a year, years for all time. */
        val chart: List<Int>,
        val chartLabels: List<String>,
        val activeDays: Int,
    )

    fun epochDay(ms: Long, tzOffsetMs: Long = timeZoneOffsetMs()): Long = (ms + tzOffsetMs) / 86_400_000L

    /** First artist of "A, B & C feat. D" (stats are per main artist). */
    fun mainArtist(a: String): String = a.split(", ", " & ", " feat. ", " ft. ", " x ", " X ").first().trim().ifBlank { "Unknown artist" }

    fun report(d: PlayStatsData, p: Period, nowMs: Long = currentTimeMillis(), tzOffsetMs: Long = timeZoneOffsetMs(), limit: Int = 50): Report {
        val today = epochDay(nowMs, tzOffsetMs)
        val counts = HashMap<String, Int>()
        val track = HashMap<String, Track>()
        d.tracks.forEach { (k, v) -> track[k] = v }
        d.stats.forEach { (k, v) -> track[k] = v.track }
        val active: Int
        val chart: List<Int>
        val labels: List<String>
        if (p.days == null) {
            // All time: the lifetime play counter, plus day-log plays of songs it no longer holds.
            d.stats.forEach { (k, v) -> counts[k] = v.plays }
            d.days.values.forEach { m -> m.forEach { (k, n) -> if (k !in d.stats) counts[k] = (counts[k] ?: 0) + n } }
            active = d.days.count { it.value.isNotEmpty() }
            val byYear = d.days.entries.groupBy { yearOf(it.key) }.mapValues { e -> e.value.sumOf { it.value.values.sum() } }
            val years = if (byYear.isEmpty()) listOf(yearOf(today)) else (byYear.keys.min()..byYear.keys.max()).toList()
            chart = years.map { byYear[it] ?: 0 }; labels = years.map { "$it" }
        } else {
            val from = today - p.days + 1
            val inRange = d.days.filterKeys { it in from..today }
            inRange.values.forEach { m -> m.forEach { (k, n) -> counts[k] = (counts[k] ?: 0) + n } }
            active = inRange.count { it.value.isNotEmpty() }
            if (p == Period.YEAR) {
                val months = (11 downTo 0).map { monthsBack(today, it) }
                val per = inRange.entries.groupBy { monthKey(it.key) }.mapValues { e -> e.value.sumOf { it.value.values.sum() } }
                chart = months.map { per[it] ?: 0 }; labels = months.map { MONTHS[(it % 12).toInt()] }
            } else {
                val ds = (from..today).toList()
                chart = ds.map { inRange[it]?.values?.sum() ?: 0 }
                labels = ds.map { dayLabel(it) }
            }
        }
        val songs = counts.entries.mapNotNull { (k, n) -> track[k]?.let { SongRow(it, n) } }.filter { it.plays > 0 }
            .sortedWith(compareByDescending<SongRow> { it.plays }.thenBy { it.track.title.lowercase() })
        val artists = songs.groupBy { mainArtist(it.track.artist) }.map { (a, rows) ->
            ArtistRow(a, rows.sumOf { it.plays }, rows.maxByOrNull { it.plays }?.track?.artUrl, rows.size)
        }.sortedWith(compareByDescending<ArtistRow> { it.plays }.thenBy { it.name.lowercase() })
        val albums = songs.filter { !it.track.album.isNullOrBlank() && it.track.album != "Live radio" }
            .groupBy { it.track.album!!.trim().lowercase() to mainArtist(it.track.artist).lowercase() }
            .map { (_, rows) -> val top = rows.maxBy { it.plays }; AlbumRow(top.track.album!!.trim(), mainArtist(top.track.artist), rows.sumOf { it.plays }, top.track.artUrl) }
            .sortedWith(compareByDescending<AlbumRow> { it.plays }.thenBy { it.name.lowercase() })
        val minutes = songs.sumOf { it.plays.toLong() * (it.track.durationMs.takeIf { d -> d > 0 } ?: 200_000L) } / 60_000
        return Report(p, songs.sumOf { it.plays }, minutes, songs.take(limit), artists.take(limit), albums.take(limit), chart, labels, active)
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    
data class CivilDate(val year: Int, val month: Int, val dayOfMonth: Int)

fun epochDayToCivil(epochDay: Long): CivilDate {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = (yoe + era * 400).toInt()
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)
    return CivilDate(y + (if (m <= 2) 1 else 0), m, d)
}

    private fun civil(day: Long): CivilDate = epochDayToCivil(day)
    private fun yearOf(day: Long) = civil(day).year
    private fun monthKey(day: Long): Long = civil(day).let { it.year * 12L + (it.month - 1) }
    private fun monthsBack(today: Long, n: Int): Long = monthKey(today) - n
    private fun dayLabel(day: Long) = civil(day).let { "${it.dayOfMonth} ${MONTHS[it.month - 1]}" }
}
