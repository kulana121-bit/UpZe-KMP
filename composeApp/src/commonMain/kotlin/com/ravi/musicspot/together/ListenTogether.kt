package com.ravi.musicspot.together

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Pills
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlin.math.abs
import kotlin.random.Random

object ListenTogether {
    enum class Role { HOST, LISTENER }
    data class Room(
        val code: String,
        val role: Role,
        val connected: Boolean = false,
        val listeners: Int = 0,
        val hostName: String = "",
        val nowPlaying: Track? = null,
        val note: String? = null,
    )

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room
    val isListener: Boolean get() = _room.value?.role == Role.LISTENER

    private val me = "p" + Random.nextLong().toULong().toString(36)
    private val myName = "UpZe User"
    private var roomScope: CoroutineScope? = null
    private fun newRoomScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { roomScope = it }
    private var seq = 0L
    private var lastApplied: RoomMsg? = null
    private val members = mutableMapOf<String, String>()

    private fun server() = Graph.settings.value.togetherServer.trimEnd('/').ifBlank { "https://ntfy.sh" }

    fun host(): String {
        leaveNow(silent = true)
        val code = RoomProtocol.newCode()
        _room.value = Room(code, Role.HOST, hostName = myName)
        members.clear(); seq = 0; lastSent = null; lastSentAt = 0L
        val sc = newRoomScope()
        sc.launch { listen(code) }
        sc.launch { watchHost(code) }
        return code
    }

    fun join(input: String): Boolean {
        val code = RoomProtocol.normalize(input) ?: return false
        leaveNow(silent = true)
        _room.value = Room(code, Role.LISTENER, note = "Waiting for the host…")
        lastApplied = null
        val sc = newRoomScope()
        sc.launch { listen(code) }
        sc.launch {
            publish(code, RoomMsg("hello", me, myName))
            delay(12_000)
            if (_room.value?.code == code && lastApplied == null) update(code) { it.copy(note = "No host answered yet. Check the code, or ask the host to open the room.") }
        }
        sc.launch { keepInSync(code) }
        return true
    }

    fun leave(silent: Boolean = false) {
        leaveNow(silent)
    }

    private fun leaveNow(silent: Boolean) {
        val r = _room.value
        if (r != null) {
            val code = r.code
            IoScope.launch { publish(code, RoomMsg(if (r.role == Role.HOST) "end" else "bye", me, myName)) }
        }
        closeRoom()
        if (r != null && !silent) Pills.show(if (r.role == Role.HOST) "Room closed" else "Left the room")
    }

    private fun closeRoom() {
        roomScope?.cancel(); roomScope = null
        _room.value = null
        members.clear(); lastApplied = null; lastSent = null
    }

    fun onServiceDestroyed() = leave(silent = true)

    fun suggest(t: Track) {
        val r = _room.value ?: return
        if (r.role != Role.LISTENER) return
        roomScope?.launch { publish(r.code, RoomMsg("add", me, myName, track = t.forRoom())) }
        Pills.show("Suggested to the host")
    }

    private suspend fun publish(code: String, m: RoomMsg) {
        val topic = RoomProtocol.topic(code)
        val body = Http.json.encodeToString(RoomMsg.serializer(), m)
        runCatching {
            Http.client.post("${server()}/$topic") {
                setBody(body)
            }
        }
    }

    private suspend fun listen(code: String) {
        val topic = RoomProtocol.topic(code)
        val url = "${server()}/$topic/json"
        while (currentCoroutineContext().isActive && _room.value?.code == code) {
            try {
                val response = Http.client.get(url)
                val channel = response.bodyAsChannel()
                update(code) { it.copy(connected = true) }
                while (currentCoroutineContext().isActive && _room.value?.code == code && !channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val o = runCatching { Http.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    if (o["event"]?.jsonPrimitive?.content != "message") continue
                    val text = o["message"]?.jsonPrimitive?.content ?: continue
                    val m = runCatching { Http.json.decodeFromString(RoomMsg.serializer(), text) }.getOrNull() ?: continue
                    if (m.from != me) onMessage(code, m)
                }
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
            } catch (_: Exception) { }
            if (_room.value?.code != code) return
            update(code) { it.copy(connected = false) }
            delay(3000)
        }
    }

    private fun onMessage(code: String, m: RoomMsg) {
        val r = _room.value ?: return
        if (r.code != code) return
        when (r.role) {
            Role.HOST -> when (m.t) {
                "hello" -> { members[m.from] = m.name; update(code) { it.copy(listeners = members.size) }; Pills.show("${m.name.ifBlank { "Someone" }} joined"); sendState(code, force = true) }
                "bye" -> { members.remove(m.from); update(code) { it.copy(listeners = members.size) } }
                "add" -> m.track?.let { t -> Graph.player.addToQueue(listOf(t)); Pills.show("${m.name.ifBlank { "A listener" }} added ${t.title}") }
            }
            Role.LISTENER -> when (m.t) {
                "state" -> if (m.seq >= (lastApplied?.seq ?: -1) || m.seq < 3) apply(m)
                "end" -> { closeRoom(); Pills.show("The host closed the room") }
            }
        }
    }

    private var lastSent: RoomMsg? = null
    private var lastSentAt = 0L

    private suspend fun watchHost(code: String) {
        coroutineScope {
            val changes = launch {
                Graph.player.state.map { it.current?.id to it.playing }.distinctUntilChanged().collect { sendState(code) }
            }
            try {
                while (_room.value?.code == code) {
                    delay(1_000)
                    val sent = lastSent ?: continue
                    val st = Graph.player.state.value
                    if (st.current?.id != sent.track?.id || st.current?.source?.isLive == true) continue
                    val drift = abs(Graph.player.position.value - RoomProtocol.expectedPosition(sent, currentTimeMillis()))
                    if (drift > 3_000) sendState(code)
                }
            } finally { changes.cancel() }
        }
    }

    private fun sendState(code: String, force: Boolean = false) {
        if (_room.value?.code != code) return
        val st = Graph.player.state.value
        val m = RoomMsg("state", me, myName, track = st.current?.forRoom(), positionMs = Graph.player.position.value, playing = st.playing,
            atMs = currentTimeMillis(), listeners = members.size, seq = ++seq)
        val now = currentTimeMillis()
        if (!force && lastSent?.let { it.track?.id == m.track?.id && it.playing == m.playing } == true && now - lastSentAt < 1_500) return
        lastSent = m; lastSentAt = now
        update(code) { it.copy(nowPlaying = st.current) }
        (roomScope ?: return).launch { publish(code, m) }
    }

    private fun apply(m: RoomMsg) {
        lastApplied = m
        val t = m.track
        update { it.copy(hostName = m.name, listeners = m.listeners, nowPlaying = t, note = null) }
        if (t == null) { Graph.player.setPlaying(false); return }
        if (t.source == SourceKind.LOCAL || t.source == SourceKind.SUBSONIC || t.source == SourceKind.JELLYFIN) {
            update { it.copy(note = "The host is playing a song from their own phone or server, which can't stream to you.") }
            return
        }
        val st = Graph.player.state.value
        val target = if (t.source.isLive) 0L else RoomProtocol.expectedPosition(m, currentTimeMillis())
        if (st.current?.id != t.id) Graph.player.playAt(listOf(t), 0, target, m.playing)
        else {
            if (!t.source.isLive && abs(Graph.player.position.value - target) > RoomProtocol.DRIFT_MS) Graph.player.seek(target)
            if (st.playing != m.playing) Graph.player.setPlaying(m.playing)
        }
    }

    private suspend fun keepInSync(code: String) {
        while (_room.value?.code == code) {
            delay(5_000)
            val m = lastApplied ?: continue
            val t = m.track ?: continue
            val st = Graph.player.state.value
            if (st.current?.id != t.id || t.source.isLive || !m.playing || st.buffering) continue
            val target = RoomProtocol.expectedPosition(m, currentTimeMillis())
            if (t.durationMs > 0 && target > t.durationMs) continue
            if (abs(Graph.player.position.value - target) > RoomProtocol.DRIFT_MS) Graph.player.seek(target)
        }
    }

    private fun update(code: String? = null, f: (Room) -> Room) {
        _room.update { r -> if (r == null || (code != null && r.code != code)) r else f(r) }
    }
    private fun Track.forRoom() = copy(album = album?.take(80), title = title.take(160), artist = artist.take(120))
}
