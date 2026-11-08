package com.ravi.musicspot

import kotlin.concurrent.Volatile

import com.ravi.musicspot.account.Account
import com.ravi.musicspot.account.AccountStore
import com.ravi.musicspot.account.KeyValue
import com.ravi.musicspot.account.SyncManager
import com.ravi.musicspot.core.File
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.PlatformIO
import com.ravi.musicspot.core.createPlatformSecretBox
import com.ravi.musicspot.data.CachedSongs
import com.ravi.musicspot.data.DownloadsRepo
import com.ravi.musicspot.data.LibraryRepo
import com.ravi.musicspot.data.PlayStatsRepo
import com.ravi.musicspot.data.QueueStore
import com.ravi.musicspot.data.SettingsRepo
import com.ravi.musicspot.lyrics.*
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.AudioPlayer
import com.ravi.musicspot.playback.createPlatformAudioPlayer
import com.ravi.musicspot.source.*
import com.ravi.musicspot.spotify.SpotifyClient
import com.ravi.musicspot.yt.Innertube
import com.ravi.musicspot.yt.YtMusic
import com.ravi.musicspot.yt.YtStreamResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object Graph {
    lateinit var settings: SettingsRepo; private set
    val incognito: Boolean get() = ::settings.isInitialized && settings.value.incognito
    lateinit var library: LibraryRepo; private set
    lateinit var playStats: PlayStatsRepo; private set
    lateinit var queueStore: QueueStore; private set
    lateinit var downloads: DownloadsRepo; private set
    lateinit var cached: CachedSongs; private set
    lateinit var accounts: AccountStore; private set
    lateinit var tube: Innertube; private set
    lateinit var yt: YtMusic; private set
    lateinit var jiosaavn: JioSaavnSource; private set
    lateinit var monochrome: MonochromeSource; private set
    lateinit var qobuz: QobuzProxySource; private set
    lateinit var netease: NetEaseSource; private set
    lateinit var local: LocalSource; private set
    lateinit var archive: ArchiveSource; private set
    lateinit var subsonic: SubsonicSource; private set
    lateinit var jellyfin: JellyfinSource; private set
    lateinit var sources: SourceManager; private set
    lateinit var spotify: SpotifyClient; private set
    lateinit var sync: SyncManager; private set
    lateinit var lyricsCache: LyricsCache; private set
    lateinit var offlineLyrics: OfflineLyricsStore; private set
    lateinit var player: AudioPlayer; private set

    private val readyDeferred = CompletableDeferred<Unit>()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    suspend fun awaitReady() = readyDeferred.await()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    var metered = false
    var lowEnd = false
    var liteFx = false

    @Volatile private var initStarted = false

    fun init(baseDir: String? = null) {
        if (initStarted) return
        initStarted = true
        val dir = File(baseDir ?: PlatformIO.appDir())
        dir.mkdirs()
        val cacheDir = File(PlatformIO.cacheDir())
        cacheDir.mkdirs()

        settings = SettingsRepo(dir)
        library = LibraryRepo(dir)
        playStats = PlayStatsRepo(dir)
        queueStore = QueueStore(dir)
        downloads = DownloadsRepo(dir)
        cached = CachedSongs(dir)

        val accountsFile = File(dir, "accounts_kv.json")
        val accountsLock = com.ravi.musicspot.core.PlatformLock()
        val mapSerializer = MapSerializer(String.serializer(), String.serializer())
        val kvData: MutableMap<String, String> = accountsLock.withLock {
            runCatching {
                if (accountsFile.exists()) {
                    val raw = accountsFile.readText()
                    if (raw.isNotBlank()) Json.decodeFromString(mapSerializer, raw).toMutableMap() else mutableMapOf()
                } else mutableMapOf()
            }.getOrElse { mutableMapOf() }
        }
        val kv = object : KeyValue {
            override fun get(key: String): String? = accountsLock.withLock { kvData[key] }
            override fun put(key: String, value: String?) {
                accountsLock.withLock {
                    if (value == null) kvData.remove(key) else kvData[key] = value
                    runCatching {
                        accountsFile.writeText(Json.encodeToString(mapSerializer, kvData))
                    }
                }
            }
        }
        accounts = AccountStore(kv, createPlatformSecretBox())
        tube = Innertube()
        val en = { k: SourceKind -> { k in settings.value.enabledSources } }
        yt = YtMusic(tube, YtStreamResolver(tube), en(SourceKind.YOUTUBE))
        jiosaavn = JioSaavnSource(en(SourceKind.JIOSAAVN))
        monochrome = MonochromeSource(en(SourceKind.MONOCHROME))
        qobuz = QobuzProxySource(en(SourceKind.QOBUZ))
        netease = NetEaseSource(en(SourceKind.NETEASE))
        local = LocalSource(en(SourceKind.LOCAL))
        archive = ArchiveSource(en(SourceKind.ARCHIVE))
        subsonic = SubsonicSource({ accounts.get(Account.Service.SUBSONIC) }, en(SourceKind.SUBSONIC))
        jellyfin = JellyfinSource({ accounts.get(Account.Service.JELLYFIN) }, en(SourceKind.JELLYFIN))

        sources = SourceManager(
            mapOf(
                SourceKind.QOBUZ to qobuz,
                SourceKind.MONOCHROME to monochrome,
                SourceKind.NETEASE to netease,
                SourceKind.YOUTUBE to yt,
                SourceKind.JIOSAAVN to jiosaavn,
                SourceKind.LOCAL to local,
                SourceKind.AUDIUS to AudiusSource(en(SourceKind.AUDIUS)),
                SourceKind.JAMENDO to JamendoSource({ settings.value.jamendoClientId }, en(SourceKind.JAMENDO)),
                SourceKind.ARCHIVE to archive,
                SourceKind.SUBSONIC to subsonic,
                SourceKind.JELLYFIN to jellyfin,
            ),
            priority = { settings.value.sourcePriority },
            enabled = { settings.value.enabledSources },
            premium = { tube.signedIn },
            downloaded = { downloads.resolvedFor(it) },
            streamProbe = { r -> com.ravi.musicspot.source.StreamProbe.status(r) },
        )

        spotify = SpotifyClient(dir)
        lyricsCache = LyricsCache(File(cacheDir, "lyrics"))
        offlineLyrics = OfflineLyricsStore(File(dir, "downloads/lyrics"))
        sync = SyncManager()
        player = createPlatformAudioPlayer()

        IoScope.launch {
            try {
                settings.load()
                library.load()
                playStats.load()
                downloads.load()
                accounts.load()
                tube.cookie = accounts.get(Account.Service.YOUTUBE)?.secret
                settings.value.region?.let { tube.region = it }
            } catch (e: Throwable) {
                // Ignore load error
            } finally {
                readyDeferred.complete(Unit)
                _ready.value = true
            }
        }
    }

    private val providers: Map<String, LyricsProvider> by lazy {
        listOf(
            LyricsPlusProvider, MusixmatchProvider, AmllProvider, KuGouProvider, NetEaseProvider,
            QQMusicProvider, JioSaavnLyricsProvider, LrcLib,
            YtLyricsProvider { vid -> yt.lyrics(vid)?.let { it.text to it.source } },
            LyricsOvhProvider
        ).associateBy { it.id }
    }
    fun lyricsProviders(): List<LyricsProvider> = providers.values.toList()

    suspend fun lyricsFor(t: Track, force: Boolean = false): Lyrics? {
        if (!force) offlineLyrics.get(t.id)?.let { saved -> return saved.lyrics }
        val key = "${t.title}|${t.artist}|${t.durationMs / 2000}"
        if (!force) {
            val (hit, c) = lyricsCache.get(key)
            if (hit) return c
        }
        val s = settings.value
        val chain = s.lyricsOrder.filter { it in s.lyricsEnabled }.mapNotNull { providers[it] }
        if (t.source.isLive) return null
        val found = LyricsChain.find(chain, LyricsQuery(t.title, t.artist, t.album, t.durationMs, if (t.source == SourceKind.YOUTUBE) t.nativeId else null))
        lyricsCache.put(key, found)
        if (force && downloads.isDownloaded(t.id)) runCatching { saveOfflineLyrics(t, found) }
        return found
    }

    suspend fun saveOfflineLyrics(t: Track, lyrics: Lyrics? = null) {
        val found = lyrics ?: lyricsFor(t, force = false) ?: return
        val roman = (found as? Lyrics.Synced)?.let { s ->
            withContext(Dispatchers.Default) { if (Romanizer.available) Romanizer.lines(s.lines.map { it.text }) else emptyList() }
        } ?: emptyList()
        offlineLyrics.put(t.id, found, roman)
    }

    private val romanLock = com.ravi.musicspot.core.PlatformLock()
    private val romanCache = LinkedHashMap<String, List<String?>>()

    /**
     * Romanized lines for [l] (null where a line needs none), computed on Dispatchers.Default (transliteration
     * is CPU work). A downloaded song's saved romanization is reused; results are kept in a small cache per song.
     */
    suspend fun romanizedLines(t: Track, l: Lyrics.Synced): List<String?> {
        val k = t.id + "#" + l.lines.size + "#" + l.source
        romanLock.withLock { romanCache[k] }?.let { return it }
        val saved = offlineLyrics.get(t.id)
            ?.takeIf { (it.lyrics as? Lyrics.Synced)?.lines?.size == l.lines.size && it.roman.size == l.lines.size }?.roman
        val out = saved ?: withContext(Dispatchers.Default) {
            if (Romanizer.available) Romanizer.lines(l.lines.map { it.text }) else l.lines.map { null }
        }
        romanLock.withLock {
            romanCache[k] = out
            while (romanCache.size > 12) romanCache.remove(romanCache.keys.first())
        }
        return out
    }
}
