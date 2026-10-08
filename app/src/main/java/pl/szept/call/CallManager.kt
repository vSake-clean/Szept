package pl.szept.call

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Serwer STUN/TURN do zestawienia połączenia. */
data class IceServer(val url: String, val user: String = "", val pass: String = "")

/** Silnik mediów (WebRTC w aplikacji, atrapa w testach). SDP zawiera już wszystkie kandydaty ICE. */
interface RtcEngine {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onFailed(reason: String)
    }
    suspend fun createOffer(): String
    suspend fun createAnswer(offer: String): String
    suspend fun applyAnswer(answer: String)
    fun setMuted(muted: Boolean)
    fun setVideoEnabled(on: Boolean)
    fun switchCamera()
    fun close()
}

fun interface RtcFactory {
    fun create(video: Boolean, ice: List<IceServer>, listener: RtcEngine.Listener): RtcEngine
}

/**
 * Rozmowy głosowe i wideo 1:1. Sygnalizacja (oferta, odpowiedź, rozłączenie) idzie w zaszyfrowanych kopertach
 * przez te same przekaźniki co wiadomości; dźwięk i obraz płyną bezpośrednio między telefonami (WebRTC, DTLS-SRTP).
 */
class CallManager(private val host: Host, private val scope: CoroutineScope) {

    interface Host {
        val myPub: String
        val deviceId: String
        /** Krótkotrwała koperta do osoby (wszystkie jej urządzenia). */
        fun sendSignal(to: String, payload: JSONObject)
        /** Koperta tylko do moich innych urządzeń. */
        fun sendSelfSignal(payload: JSONObject)
        fun callLog(peer: String, id: String, text: String, missed: Boolean)
        fun canCall(pk: String): Boolean
        fun iceServers(): List<IceServer>
        fun now(): Long
        /** Dzwonek / ekran przychodzącego połączenia (null = zakończ). */
        fun onRing(call: Call?)
        /** Czy trwa rozmowa grupowa. */
        fun busyElsewhere(): Boolean = false
    }

    enum class Phase { DIALING, RINGING, CONNECTING, CONNECTED, ENDED }

    data class Call(
        val id: String, val peer: String, val video: Boolean, val outgoing: Boolean, val phase: Phase,
        val startedAt: Long, val connectedAt: Long = 0, val endReason: String = "",
        val muted: Boolean = false, val camOn: Boolean = video, val peerDev: String = "",
    ) {
        val active get() = phase != Phase.ENDED
    }

    companion object {
        const val RING_MS = 45_000L
        const val CONNECT_MS = 30_000L
        /** Starsza oferta niż minuta = nieodebrane (telefon był wyłączony). */
        const val OFFER_MAX_AGE_MS = 60_000L
        private const val TAG = "Szept"
    }

    val state = MutableStateFlow<Call?>(null)
    @Volatile var factory: RtcFactory? = null
    /** Jak długo dzwonić (testy skracają). */
    var ringMs = RING_MS
    /** Jak długo pokazywać informację o zakończeniu, zanim ekran rozmowy zniknie. */
    var endedLingerMs = 2_500L

    private fun ended(c: Call) {
        state.value = c
        scope.launch { delay(endedLingerMs); if (state.value === c) state.value = null }
    }
    private var engine: RtcEngine? = null
    private var pendingOffer: String? = null
    private var timer: Job? = null
    private val lock = Mutex()
    private val seen = LinkedHashSet<String>()

    val available: Boolean get() = factory != null

    private fun cur(id: String? = null): Call? = state.value?.takeIf { it.active && (id == null || it.id == id) }

    private fun signal(op: String, call: Call, extra: JSONObject.() -> Unit = {}) {
        val p = JSONObject().put("t", "call").put("op", op).put("cid", call.id).put("ts", host.now()).put("dv", host.deviceId)
        if (call.peerDev.isNotEmpty()) p.put("to_dv", call.peerDev)
        p.extra()
        host.sendSignal(call.peer, p)
    }

    private fun listener(id: String) = object : RtcEngine.Listener {
        override fun onConnected() { scope.launch { lock.withLock { connected(id) } } }
        override fun onDisconnected() { scope.launch { lock.withLock { cur(id)?.let { if (it.phase == Phase.CONNECTED) finish(it, "Połączenie przerwane", notifyPeer = true) } } } }
        override fun onFailed(reason: String) {
            scope.launch { lock.withLock { cur(id)?.let { finish(it, "Nie udało się połączyć — sieć blokuje połączenia bezpośrednie. Spróbuj przez Wi-Fi.", notifyPeer = true) } } }
        }
    }

    private fun connected(id: String) {
        val c = cur(id) ?: return
        if (c.phase == Phase.CONNECTED) return
        timer?.cancel()
        state.value = c.copy(phase = Phase.CONNECTED, connectedAt = System.currentTimeMillis())
    }

    // ---------------- dzwonienie ----------------

    /** Dzwoni do [peer]. Rzuca IllegalStateException z opisem, gdy się nie da. */
    suspend fun startCall(peer: String, video: Boolean): Call = lock.withLock {
        val f = factory ?: throw IllegalStateException("Połączenia nie działają na tym urządzeniu")
        if (cur() != null || host.busyElsewhere()) throw IllegalStateException("Trwa już inne połączenie")
        if (!host.canCall(peer)) throw IllegalStateException("Nie można zadzwonić do tej osoby")
        val id = pl.szept.crypto.Crypto.randomBytes(12).joinToString("") { "%02x".format(it) }
        val call = Call(id, peer, video, true, Phase.DIALING, System.currentTimeMillis())
        state.value = call
        val e = try { f.create(video, host.iceServers(), listener(id)) } catch (t: Throwable) {
            ended(call.copy(phase = Phase.ENDED, endReason = "Nie udało się uruchomić rozmowy"))
            throw IllegalStateException("Nie udało się uruchomić rozmowy: ${t.message}")
        }
        engine = e
        val sdp = try { e.createOffer() } catch (t: Throwable) {
            e.close(); engine = null
            ended(call.copy(phase = Phase.ENDED, endReason = "Nie udało się przygotować połączenia"))
            throw IllegalStateException("Nie udało się przygotować połączenia")
        }
        if (cur(id) == null) return@withLock state.value!!   // rozłączono w trakcie przygotowania
        signal("offer", call) { put("sdp", sdp); put("vid", video) }
        timer = scope.launch {
            delay(ringMs)
            lock.withLock { cur(id)?.takeIf { it.phase == Phase.DIALING }?.let { finish(it, "Brak odpowiedzi", notifyPeer = true, reason = "timeout") } }
        }
        call
    }

    /** Odbiera przychodzące połączenie. */
    suspend fun answer() = lock.withLock {
        val c = cur()?.takeIf { it.phase == Phase.RINGING } ?: return@withLock
        val offer = pendingOffer ?: return@withLock
        val f = factory ?: run { finish(c, "Połączenia nie działają na tym urządzeniu", notifyPeer = true); return@withLock }
        host.onRing(null)
        state.value = c.copy(phase = Phase.CONNECTING)
        host.sendSelfSignal(JSONObject().put("t", "call").put("op", "taken").put("cid", c.id).put("ts", host.now()))
        val e = try { f.create(c.video, host.iceServers(), listener(c.id)) } catch (t: Throwable) {
            finish(c, "Nie udało się uruchomić rozmowy", notifyPeer = true); return@withLock
        }
        engine = e
        val sdp = try { e.createAnswer(offer) } catch (t: Throwable) {
            finish(c, "Nie udało się połączyć", notifyPeer = true); return@withLock
        }
        if (cur(c.id) == null) return@withLock
        signal("answer", c) { put("sdp", sdp) }
        armConnectTimeout(c.id)
    }

    /** Odrzuca przychodzące połączenie. */
    suspend fun decline() = lock.withLock {
        val c = cur()?.takeIf { it.phase == Phase.RINGING } ?: return@withLock
        host.sendSelfSignal(JSONObject().put("t", "call").put("op", "taken").put("cid", c.id).put("ts", host.now()))
        finish(c, "Odrzucono", notifyPeer = true, reason = "declined")
    }

    /** Rozłącza (albo anuluje dzwonienie). */
    suspend fun hangup() = lock.withLock {
        val c = cur() ?: return@withLock
        finish(c, if (c.phase == Phase.DIALING) "Anulowano" else "Zakończono", notifyPeer = true)
    }

    fun setMuted(m: Boolean) { val c = cur() ?: return; engine?.setMuted(m); state.value = c.copy(muted = m) }
    fun setCamera(on: Boolean) { val c = cur() ?: return; engine?.setVideoEnabled(on); state.value = c.copy(camOn = on) }
    fun switchCamera() { engine?.switchCamera() }
    /** Dla ekranu rozmowy (podłączenie podglądu obrazu). */
    fun currentEngine(): RtcEngine? = engine

    private fun armConnectTimeout(id: String) {
        timer?.cancel()
        timer = scope.launch {
            delay(CONNECT_MS)
            lock.withLock {
                cur(id)?.takeIf { it.phase == Phase.CONNECTING }?.let {
                    finish(it, "Nie udało się połączyć — sieć blokuje połączenia bezpośrednie. Spróbuj przez Wi-Fi.", notifyPeer = true)
                }
            }
        }
    }

    private fun finish(c: Call, text: String, notifyPeer: Boolean, reason: String = "end") {
        timer?.cancel(); timer = null
        if (notifyPeer) signal("end", c) { put("r", reason) }
        engine?.close(); engine = null
        pendingOffer = null
        if (c.phase == Phase.RINGING) host.onRing(null)
        ended(c.copy(phase = Phase.ENDED, endReason = text))
        log(c, reason)
    }

    private fun log(c: Call, reason: String) {
        val kind = if (c.video) "📹 Rozmowa wideo" else "📞 Połączenie głosowe"
        val connected = c.connectedAt
        val text = when {
            connected > 0 -> {
                val s = (System.currentTimeMillis() - connected) / 1000
                "$kind · ${s / 60}:${"%02d".format(s % 60)}"
            }
            c.outgoing -> "$kind · " + when (reason) { "busy" -> "zajęte"; "declined" -> "odrzucone"; "timeout" -> "brak odpowiedzi"; else -> "nie połączono" }
            reason == "declined" -> "$kind · odrzucone"
            else -> "$kind · nieodebrane"
        }
        host.callLog(c.peer, "call:${c.id}", text, missed = !c.outgoing && connected == 0L && reason != "declined" && reason != "taken")
    }

    // ---------------- sygnały od innych ----------------

    /** Sygnał od innej osoby. */
    fun onSignal(from: String, p: JSONObject) {
        scope.launch {
            lock.withLock {
                try { handle(from, p) } catch (e: Exception) { Log.w(TAG, "call", e) }
            }
        }
    }

    private fun handle(from: String, p: JSONObject) {
        val cid = p.optString("cid").take(64)
        val op = p.optString("op")
        val dv = p.optString("dv")
        if (cid.isEmpty()) return
        // sygnał dla innego mojego urządzenia
        val toDv = p.optString("to_dv")
        if (toDv.isNotEmpty() && toDv != host.deviceId) return
        if (!seen.add("$op:$cid:$dv")) return
        if (seen.size > 500) seen.remove(seen.first())
        val c = cur()
        when (op) {
            "offer" -> {
                if (!host.canCall(from)) return
                val sdp = p.optString("sdp")
                if (sdp.isEmpty()) return
                val video = p.optBoolean("vid")
                val age = host.now() - p.optLong("ts")
                val call = Call(cid, from, video, false, Phase.RINGING, System.currentTimeMillis(), peerDev = dv)
                if (age > OFFER_MAX_AGE_MS) {
                    // telefon był wyłączony — tylko informacja o nieodebranym połączeniu
                    log(call, "missed"); return
                }
                if (c != null || host.busyElsewhere()) {
                    if (c?.id == cid) return
                    // trwa inna rozmowa (albo oboje dzwonią naraz) — zajęte
                    host.sendSignal(from, JSONObject().put("t", "call").put("op", "busy").put("cid", cid).put("ts", host.now())
                        .put("dv", host.deviceId).put("to_dv", dv))
                    log(call, "missed")
                    return
                }
                pendingOffer = sdp
                state.value = call
                host.onRing(call)
                timer?.cancel()
                timer = scope.launch {
                    delay(ringMs)
                    lock.withLock { cur(cid)?.takeIf { it.phase == Phase.RINGING }?.let { finish(it, "Nieodebrane", notifyPeer = false, reason = "missed") } }
                }
            }
            "answer" -> {
                val call = c?.takeIf { it.id == cid && it.outgoing && it.peer == from } ?: return
                if (call.phase != Phase.DIALING) {
                    // odebrało inne urządzenie tej osoby — temu dziękujemy
                    if (dv != call.peerDev) host.sendSignal(from, JSONObject().put("t", "call").put("op", "end").put("cid", cid)
                        .put("r", "taken").put("ts", host.now()).put("dv", host.deviceId).put("to_dv", dv))
                    return
                }
                val sdp = p.optString("sdp")
                state.value = call.copy(phase = Phase.CONNECTING, peerDev = dv)
                timer?.cancel()
                scope.launch {
                    try { engine?.applyAnswer(sdp) } catch (e: Exception) {
                        lock.withLock { cur(cid)?.let { finish(it, "Nie udało się połączyć", notifyPeer = true) } }
                    }
                }
                armConnectTimeout(cid)
            }
            "end", "busy" -> {
                val call = c?.takeIf { it.id == cid && it.peer == from } ?: return
                // po odebraniu liczy się tylko urządzenie, które odebrało
                if (call.peerDev.isNotEmpty() && dv.isNotEmpty() && dv != call.peerDev && call.phase != Phase.DIALING) return
                val r = if (op == "busy") "busy" else p.optString("r")
                val text = when {
                    op == "busy" -> "Zajęte"
                    r == "declined" -> "Odrzucono"
                    r == "taken" -> "Odebrano na innym urządzeniu"
                    call.phase == Phase.RINGING -> "Nieodebrane"
                    else -> "Rozmówca się rozłączył"
                }
                finish(call, text, notifyPeer = false, reason = if (call.phase == Phase.RINGING && r != "declined") "missed" else r)
            }
        }
    }

    /** Sygnał od mojego innego urządzenia (odebrałem/odrzuciłem tam). */
    fun onSelfSignal(p: JSONObject) {
        scope.launch {
            lock.withLock {
                if (p.optString("op") != "taken") return@withLock
                val c = cur(p.optString("cid"))?.takeIf { it.phase == Phase.RINGING } ?: return@withLock
                timer?.cancel(); pendingOffer = null
                host.onRing(null)
                ended(c.copy(phase = Phase.ENDED, endReason = "Odebrano na innym urządzeniu"))
                log(c, "taken")
            }
        }
    }

    /** Ekran rozmowy zamknięty po zakończeniu. */
    fun clearEnded() { if (state.value?.phase == Phase.ENDED) state.value = null }
}
