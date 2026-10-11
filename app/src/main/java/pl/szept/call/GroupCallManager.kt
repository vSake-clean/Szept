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
import java.util.concurrent.ConcurrentHashMap

/**
 * Rozmowy grupowe: każdy uczestnik łączy się bezpośrednio z każdym (siatka WebRTC).
 * Bez serwera nie da się „zmiksować” rozmowy, więc liczba osób jest ograniczona (każdy wysyła swój dźwięk/obraz do wszystkich).
 *
 * Sygnały (koperty „gcall”): ring (zaproszenie do grupy), join (dołączam), here (jestem w rozmowie),
 * offer/answer (połączenie pary), leave (wychodzę), cancel (dzwoniący zrezygnował, zanim ktoś odebrał).
 * W każdej parze ofertę wysyła ten, kto ma „mniejszy” identyfikator (klucz:urządzenie) — bez kolizji.
 */
class GroupCallManager(private val host: Host, private val scope: CoroutineScope) {

    interface Host {
        val myPub: String
        val deviceId: String
        /** Koperta do wybranych osób z grupy. */
        fun sendSignal(to: List<String>, payload: JSONObject)
        fun sendSelfSignal(payload: JSONObject)
        /** Członkowie aktywnej grupy (bez mnie); null = nie jestem w grupie. */
        fun groupMembers(gid: String): List<String>?
        fun groupName(gid: String): String
        /** Wpis w historii: w grupie albo (pokój „r:”) w rozmowie prywatnej z [peer]. */
        fun callLog(gid: String, peer: String?, id: String, text: String)
        /** Czy ta osoba może do mnie dzwonić (rozmowy prywatne, „pokoje”). */
        fun canCall(pk: String): Boolean
        fun nameOf(pk: String): String
        fun iceServers(): List<IceServer>
        fun now(): Long
        fun onRing(ring: Ring?)
        /** Czy trwa rozmowa 1:1. */
        fun busyElsewhere(): Boolean
    }

    enum class Phase { RINGING, ACTIVE, ENDED }
    enum class PeerPhase { CONNECTING, CONNECTED }

    data class Peer(val pk: String, val dev: String, val phase: PeerPhase) { val id get() = "$pk:$dev" }

    data class GroupCall(
        val id: String, val gid: String, val video: Boolean, val phase: Phase, val startedAt: Long,
        val from: String = "", val peers: Map<String, Peer> = emptyMap(), val muted: Boolean = false, val camOn: Boolean = video,
        val endReason: String = "", val joinedAt: Long = 0,
        /** Do kogo właśnie dzwonię (jeszcze nie dołączyli). */
        val invited: Set<String> = emptySet(),
        /** Wszyscy w „pokoju” (rozmowa prywatna lub dodane osoby) — puste dla grup. */
        val room: Set<String> = emptySet(),
        /** Ostatni problem z połączeniem (pokazywany na ekranie rozmowy). */
        val notice: String = "",
    ) {
        val active get() = phase != Phase.ENDED
        /** Rozmowa poza grupą (zaczęta z rozmowy prywatnej). */
        val isRoom get() = gid.startsWith("r:")
        val connected get() = peers.values.count { it.phase == PeerPhase.CONNECTED }
    }

    /** Dzwonienie do mnie: kto, w jakiej grupie. */
    data class Ring(val cid: String, val gid: String, val groupName: String, val from: String, val video: Boolean) {
        val isRoom get() = gid.startsWith("r:")
    }

    /** Trwająca w grupie rozmowa, do której można dołączyć (z ostatnich sygnałów). */
    data class Known(val cid: String, val video: Boolean, val members: Set<String>, val seen: Long)

    companion object {
        const val RING_MS = 45_000L
        /** Ile czekamy na zestawienie połączenia z uczestnikiem, zanim uznamy, że sieć je blokuje. */
        const val CONNECT_MS = 30_000L
        const val NO_ROUTE = "Nie udało się połączyć — sieć blokuje bezpośrednie połączenie. Spróbujcie przez Wi-Fi albo poproś administratora o serwer TURN."
        const val MAX_AUDIO = 6
        const val MAX_VIDEO = 4
        private const val TAG = "Szept"
        fun max(video: Boolean) = if (video) MAX_VIDEO else MAX_AUDIO
    }

    val state = MutableStateFlow<GroupCall?>(null)
    /** Rozmowy w grupach, o których wiem (do przycisku „Dołącz”). */
    val known = MutableStateFlow<Map<String, Known>>(emptyMap())
    @Volatile var factory: RtcSessionFactory? = null
    var ringMs = RING_MS
    var endedLingerMs = 2_500L
    private var session: RtcSession? = null
    private val links = ConcurrentHashMap<String, RtcPeer>()
    private var ringTimer: Job? = null
    private val lock = Mutex()
    private val seen = LinkedHashSet<String>()
    /** Skład „pokojów” (rozmów poza grupą), które znam: gid → wszyscy uczestnicy (z dzwoniącym). */
    private val rooms = ConcurrentHashMap<String, Set<String>>()
    private var inviteTimer: Job? = null

    val available get() = factory != null
    private val me get() = "${host.myPub}:${host.deviceId}"
    private fun cur(cid: String? = null) = state.value?.takeIf { it.active && (cid == null || it.id == cid) }

    /** Dla ekranu rozmowy (obraz). */
    fun currentSession(): RtcSession? = session
    fun peerLink(id: String): RtcPeer? = links[id]

    private fun base(op: String, c: GroupCall) = JSONObject().put("t", "gcall").put("op", op).put("cid", c.id).put("g", c.gid)
        .put("ts", host.now()).put("dv", host.deviceId)

    /** Pozostali uczestnicy grupy lub pokoju (bez mnie). */
    private fun membersOf(gid: String): List<String>? =
        if (gid.startsWith("r:")) rooms[gid]?.filter { it != host.myPub } else host.groupMembers(gid)

    private fun roomJson(gid: String) = org.json.JSONArray((rooms[gid] ?: emptySet()).toList())

    /** Z kim (do historii) — dla pokoju: dzwoniący albo pierwsza zaproszona osoba. */
    private fun logPeer(c: GroupCall): String? =
        if (!c.isRoom) null else c.from.takeIf { it.isNotEmpty() && it != host.myPub } ?: rooms[c.gid]?.firstOrNull { it != host.myPub }

    private fun kindText(c: GroupCall) =
        if (c.isRoom) (if (c.video) "📹 Rozmowa wideo" else "📞 Połączenie głosowe")
        else (if (c.video) "📹 Rozmowa grupowa wideo" else "📞 Rozmowa grupowa")

    private fun toAll(op: String, c: GroupCall, extra: JSONObject.() -> Unit = {}) {
        val members = membersOf(c.gid) ?: return
        host.sendSignal(members, base(op, c).apply(extra))
    }

    private fun toPeer(op: String, c: GroupCall, pk: String, dev: String, extra: JSONObject.() -> Unit = {}) {
        host.sendSignal(listOf(pk), base(op, c).put("to_dv", dev).apply(extra))
    }

    private fun ended(c: GroupCall) {
        state.value = c
        // komunikat o problemie z siecią zostaje dłużej, żeby dało się go przeczytać
        val linger = if (c.endReason == NO_ROUTE) maxOf(endedLingerMs, 8_000L) else endedLingerMs
        scope.launch { delay(linger); if (state.value === c) state.value = null }
    }

    private fun remember(gid: String, cid: String, video: Boolean, add: String? = null, remove: String? = null) {
        known.value = known.value.toMutableMap().also { m ->
            val k = m[gid]?.takeIf { it.cid == cid } ?: Known(cid, video, emptySet(), 0)
            var members = k.members
            if (add != null) members = members + add
            if (remove != null) members = members - remove
            if (members.isEmpty() && remove != null) m.remove(gid) else m[gid] = k.copy(members = members, seen = System.currentTimeMillis())
        }
    }

    /** Czy w grupie trwa rozmowa, do której mogę dołączyć. */
    fun joinable(gid: String): Known? = known.value[gid]?.takeIf {
        it.members.isNotEmpty() && System.currentTimeMillis() - it.seen < 3 * 3600_000L && cur()?.id != it.cid
    }

    // ---------------- moje działania ----------------

    /** Rozpoczyna rozmowę w grupie (wszyscy członkowie dostają dzwonek). */
    suspend fun start(gid: String, video: Boolean): GroupCall = lock.withLock {
        val f = factory ?: throw IllegalStateException("Rozmowy nie działają na tym urządzeniu")
        if (cur() != null || host.busyElsewhere()) throw IllegalStateException("Trwa już inne połączenie")
        val members = host.groupMembers(gid) ?: throw IllegalStateException("Nie jesteś w tej grupie")
        if (members.isEmpty()) throw IllegalStateException("W grupie nie ma nikogo poza Tobą")
        val id = pl.szept.crypto.Crypto.randomBytes(12).joinToString("") { "%02x".format(it) }
        session = try { f.create(video, host.iceServers()) } catch (t: Throwable) { throw IllegalStateException("Nie udało się uruchomić rozmowy") }
        val c = GroupCall(id, gid, video, Phase.ACTIVE, System.currentTimeMillis(), from = host.myPub, joinedAt = System.currentTimeMillis())
        state.value = c
        remember(gid, id, video, add = me)
        toAll("ring", c) { put("vid", video) }
        c
    }

    /**
     * Dzwoni do wybranych osób spoza grupy (rozmowa z czatu prywatnego). Później można dodać kolejne osoby — [invite].
     */
    suspend fun startRoom(peers: List<String>, video: Boolean): GroupCall = lock.withLock {
        val f = factory ?: throw IllegalStateException("Rozmowy nie działają na tym urządzeniu")
        if (cur() != null || host.busyElsewhere()) throw IllegalStateException("Trwa już inne połączenie")
        val to = peers.distinct().filter { it != host.myPub && host.canCall(it) }
        if (to.isEmpty()) throw IllegalStateException("Nie można zadzwonić do tej osoby")
        val id = pl.szept.crypto.Crypto.randomBytes(12).joinToString("") { "%02x".format(it) }
        val gid = "r:$id"
        session = try { f.create(video, host.iceServers()) } catch (t: Throwable) { throw IllegalStateException("Nie udało się uruchomić rozmowy") }
        rooms[gid] = (to + host.myPub).toSet()
        val c = GroupCall(id, gid, video, Phase.ACTIVE, System.currentTimeMillis(), from = host.myPub, joinedAt = System.currentTimeMillis(),
            invited = to.toSet(), room = rooms[gid]!!)
        state.value = c
        remember(gid, id, video, add = me)
        host.sendSignal(to, base("ring", c).put("vid", video).put("m", roomJson(gid)).put("n", nonce()))
        armInviteTimeout(id)
        c
    }

    /**
     * Dodaje osoby do trwającej rozmowy — dzwoni do nich (także ponownie, jeśli wcześniej nie odebrały).
     * W grupie: tylko członkowie grupy; w rozmowie prywatnej: dowolne kontakty (rozmowa staje się „pokojem”).
     */
    suspend fun invite(pks: List<String>) = lock.withLock {
        val c = cur()?.takeIf { it.phase == Phase.ACTIVE } ?: return@withLock
        val inCall = c.peers.values.map { it.pk }.toSet()
        var to = pks.distinct().filter { it != host.myPub && it !in inCall }
        if (c.isRoom) {
            to = to.filter { host.canCall(it) }
            if (to.isEmpty()) return@withLock
            val before = rooms[c.gid] ?: emptySet()
            rooms[c.gid] = before + to
            // dotychczasowi uczestnicy muszą wiedzieć, że nowe osoby mogą się z nimi łączyć
            val old = before.filter { it != host.myPub && it !in to }
            if (old.isNotEmpty()) host.sendSignal(old, base("add", c).put("m", roomJson(c.gid)))
        } else {
            val members = host.groupMembers(c.gid) ?: return@withLock
            to = to.filter { it in members }
            if (to.isEmpty()) return@withLock
        }
        val now = c.copy(invited = c.invited + to, room = rooms[c.gid] ?: emptySet())
        state.value = now
        host.sendSignal(to, base("ring", now).put("vid", c.video).put("n", nonce()).apply { if (c.isRoom) put("m", roomJson(c.gid)) })
        armInviteTimeout(c.id)
    }

    private fun nonce() = pl.szept.crypto.Crypto.randomBytes(4).joinToString("") { "%02x".format(it) }

    /** Po czasie dzwonienia: przestajemy czekać na zaproszonych; pusty pokój kończymy po cichu. */
    private fun armInviteTimeout(cid: String) {
        inviteTimer?.cancel()
        inviteTimer = scope.launch {
            delay(ringMs)
            lock.withLock {
                val c = cur(cid)?.takeIf { it.phase == Phase.ACTIVE } ?: return@withLock
                state.value = c.copy(invited = emptySet())
                endIfAlone(c.copy(invited = emptySet()), "nieodebrane")
            }
        }
    }

    /** Pokój, w którym nikogo już nie ma i do nikogo nie dzwonię — kończymy (bez okna „nikt nie odebrał”). */
    private fun endIfAlone(c: GroupCall, why: String, failed: Boolean = false) {
        if (!c.isRoom || c.peers.isNotEmpty() || c.invited.isNotEmpty()) return
        val hadSomeone = c.joinedAt > 0 && everConnected.contains(c.id)
        if (!hadSomeone && c.from == host.myPub && !failed) toAll("cancel", c)
        if (failed && !hadSomeone) toAll("leave", c) { put("nr", true) }
        close(c, if (hadSomeone) "Rozmowa zakończona" else why, quiet = !hadSomeone && !failed)
    }
    var connectMs = CONNECT_MS
    private val everConnected = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Dołącza do rozmowy, która dzwoni albo trwa w grupie. */
    suspend fun join(gid: String? = null) = lock.withLock {
        val f = factory ?: return@withLock
        val ringing = cur()?.takeIf { it.phase == Phase.RINGING }
        val c0 = ringing ?: run {
            val g = gid ?: return@withLock
            if (cur() != null || host.busyElsewhere()) return@withLock
            val k = joinable(g) ?: return@withLock
            GroupCall(k.cid, g, k.video, Phase.RINGING, System.currentTimeMillis())
        }
        if (membersOf(c0.gid) == null) return@withLock
        val k = known.value[c0.gid]
        if (k != null && k.cid == c0.id && k.members.count { !it.startsWith(host.myPub + ":") } >= max(c0.video)) {
            ended(c0.copy(phase = Phase.ENDED, endReason = "Rozmowa jest pełna (maks. ${max(c0.video)} osób)"))
            host.onRing(null)
            return@withLock
        }
        host.onRing(null)
        ringTimer?.cancel()
        session = try { f.create(c0.video, host.iceServers()) } catch (t: Throwable) {
            ended(c0.copy(phase = Phase.ENDED, endReason = "Nie udało się uruchomić rozmowy")); return@withLock
        }
        val c = c0.copy(phase = Phase.ACTIVE, joinedAt = System.currentTimeMillis())
        state.value = c
        remember(c.gid, c.id, c.video, add = me)
        host.sendSelfSignal(base("taken", c))
        toAll("join", c) { put("vid", c.video); if (c.isRoom) put("m", roomJson(c.gid)) }
    }

    /** Nie odbieram (tylko u mnie — inni dalej mogą rozmawiać). */
    suspend fun decline() = lock.withLock {
        val c = cur()?.takeIf { it.phase == Phase.RINGING } ?: return@withLock
        ringTimer?.cancel()
        host.onRing(null)
        host.sendSelfSignal(base("taken", c))
        // w rozmowie prywatnej dzwoniący od razu wie, że nie odbiorę
        if (c.isRoom) toAll("decline", c)
        if (c.isRoom) logPeer(c)?.let { host.callLog(c.gid, it, "gcall:${c.id}", kindText(c) + " · odrzucone") }
        state.value = null
    }

    /** Wychodzę z rozmowy. */
    suspend fun leave() = lock.withLock {
        val c = cur() ?: return@withLock
        if (c.phase == Phase.RINGING) { ringTimer?.cancel(); host.onRing(null); state.value = null; return@withLock }
        // nikt jeszcze nie dołączył — odwołujemy dzwonienie u wszystkich
        val nobody = c.peers.isEmpty() && c.from == host.myPub && !everConnected.contains(c.id)
        toAll(if (nobody) "cancel" else "leave", c)
        close(c, "Rozmowa zakończona", quiet = true)
    }

    private fun close(c: GroupCall, reason: String, quiet: Boolean = false) {
        inviteTimer?.cancel()
        links.values.forEach { it.close() }
        links.clear()
        session?.close(); session = null
        remember(c.gid, c.id, c.video, remove = me)
        val secs = if (c.joinedAt > 0) (System.currentTimeMillis() - c.joinedAt) / 1000 else 0
        val kind = kindText(c)
        val talked = everConnected.contains(c.id)
        host.callLog(c.gid, logPeer(c), "gcall:${c.id}", when {
            reason == NO_ROUTE && !talked -> "$kind · nie udało się połączyć (sieć blokuje połączenie)"
            talked || (!c.isRoom && c.joinedAt > 0) -> "$kind · ${secs / 60}:${"%02d".format(secs % 60)}"
            c.isRoom && c.from == host.myPub -> "$kind · brak odpowiedzi"
            else -> "$kind · nieodebrana"
        })
        // bez okna „Nikt nie odebrał” / „Wyszedłeś” — ekran rozmowy po prostu znika
        if (quiet) state.value = null else ended(c.copy(phase = Phase.ENDED, endReason = reason, peers = emptyMap()))
    }

    fun setMuted(m: Boolean) { val c = cur() ?: return; session?.setMuted(m); state.value = c.copy(muted = m) }
    fun setCamera(on: Boolean) { val c = cur() ?: return; session?.setVideoEnabled(on); state.value = c.copy(camOn = on) }
    fun switchCamera() { session?.switchCamera() }

    // ---------------- połączenia par ----------------

    private fun peerListener(cid: String, pid: String) = object : RtcEngine.Listener {
        override fun onConnected() { scope.launch { lock.withLock { setPeer(cid, pid, PeerPhase.CONNECTED) } } }
        override fun onDisconnected() { scope.launch { lock.withLock { dropPeer(cid, pid) } } }
        override fun onFailed(reason: String) { scope.launch { lock.withLock { dropPeer(cid, pid, failed = true) } } }
    }

    private fun setPeer(cid: String, pid: String, phase: PeerPhase) {
        val c = cur(cid) ?: return
        val p = c.peers[pid] ?: return
        if (phase == PeerPhase.CONNECTED) everConnected += cid
        state.value = c.copy(peers = c.peers + (pid to p.copy(phase = phase)), invited = c.invited - p.pk)
    }

    private fun dropPeer(cid: String, pid: String, failed: Boolean = false) {
        val c = cur(cid) ?: return
        links.remove(pid)?.close()
        val now = c.copy(peers = c.peers - pid, notice = if (failed) NO_ROUTE else c.notice)
        state.value = now
        endIfAlone(now, if (failed) NO_ROUTE else "Rozmowa zakończona", failed)
    }

    /** Nowe połączenie z uczestnikiem (null = już jest albo rozmowa pełna). */
    private fun newLink(cid: String, pk: String, dev: String): RtcPeer? {
        val c = cur(cid) ?: return null
        val pid = "$pk:$dev"
        if (links.containsKey(pid)) return null
        if (c.peers.size >= max(c.video) - 1) return null
        val s = session ?: return null
        val l = s.newPeer(peerListener(cid, pid))
        links[pid] = l
        // nie wisimy w nieskończoność na „Łączenie…”
        scope.launch {
            kotlinx.coroutines.delay(connectMs)
            lock.withLock {
                val cc = cur(cid) ?: return@withLock
                if (links[pid] === l && cc.peers[pid]?.phase == PeerPhase.CONNECTING) {
                    pl.szept.core.CrashLog.note("Rozmowa: brak połączenia z uczestnikiem po ${connectMs / 1000} s")
                    dropPeer(cid, pid, failed = true)
                }
            }
        }
        state.value = c.copy(peers = c.peers + (pid to Peer(pk, dev, PeerPhase.CONNECTING)), invited = c.invited - pk)
        return l
    }

    /** Oferta poza blokadą — zbieranie adresów ICE trwa kilka sekund. */
    private fun offerTo(cid: String, pk: String, dev: String) {
        val l = newLink(cid, pk, dev) ?: return
        val pid = "$pk:$dev"
        scope.launch {
            val sdp = try { l.createOffer() } catch (e: Exception) { lock.withLock { dropPeer(cid, pid) }; return@launch }
            lock.withLock {
                val c = cur(cid) ?: return@withLock
                if (links[pid] !== l) return@withLock
                toPeer("offer", c, pk, dev) { put("sdp", sdp) }
            }
        }
    }

    private fun answerTo(cid: String, pk: String, dev: String, offer: String) {
        val pid = "$pk:$dev"
        val c = cur(cid) ?: return
        val l = newLink(cid, pk, dev) ?: run { if (!links.containsKey(pid)) toPeer("full", c, pk, dev); return }
        scope.launch {
            val sdp = try { l.createAnswer(offer) } catch (e: Exception) { lock.withLock { dropPeer(cid, pid) }; return@launch }
            lock.withLock {
                val cc = cur(cid) ?: return@withLock
                if (links[pid] !== l) return@withLock
                toPeer("answer", cc, pk, dev) { put("sdp", sdp) }
            }
        }
    }

    // ---------------- sygnały ----------------

    fun onSignal(from: String, p: JSONObject) {
        scope.launch { lock.withLock { try { handle(from, p) } catch (e: Exception) { Log.w(TAG, "gcall", e) } } }
    }

    private fun handle(from: String, p: JSONObject) {
        val cid = p.optString("cid").take(64)
        val gid = p.optString("g")
        val op = p.optString("op")
        val dev = p.optString("dv").take(32)
        if (cid.isEmpty() || gid.isEmpty() || dev.isEmpty()) return
        val toDv = p.optString("to_dv")
        if (toDv.isNotEmpty() && toDv != host.deviceId) return
        if (gid.startsWith("r:")) {
            if (gid != "r:$cid" || !host.canCall(from)) return
            val m = p.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { pl.szept.crypto.Crypto.isValidPub(it) }.take(12).toSet() }
            val known = rooms[gid]
            when {
                // pierwsze zaproszenie: skład pokoju podaje dzwoniący (muszę w nim być)
                known == null -> if (op == "ring" && m != null && host.myPub in m) rooms[gid] = m + from else return
                from !in known -> return
                // ktoś z pokoju dodał nowe osoby
                m != null && (op == "add" || op == "ring" || op == "join") -> rooms[gid] = known + m
            }
            while (rooms.size > 50) rooms.remove(rooms.keys.first())
        } else {
            val members = host.groupMembers(gid) ?: return
            if (from !in members) return
        }
        if (!seen.add("$op:$cid:$from:$dev:${p.optString("n")}")) return
        if (seen.size > 1000) seen.remove(seen.first())
        val pid = "$from:$dev"
        val video = p.optBoolean("vid")
        val c = cur()
        when (op) {
            "ring" -> {
                remember(gid, cid, video, add = pid)
                val call = GroupCall(cid, gid, video, Phase.RINGING, System.currentTimeMillis(), from = from, room = rooms[gid] ?: emptySet())
                if (host.now() - p.optLong("ts") > 60_000) { host.callLog(gid, logPeer(call), "gcall:$cid", kindText(call) + " · nieodebrana"); return }
                if (c?.id == cid) return
                if (c != null || host.busyElsewhere()) {
                    if (call.isRoom) host.sendSignal(listOf(from), base("busy", call).put("to_dv", dev))
                    return
                }
                state.value = call
                host.onRing(Ring(cid, gid, if (call.isRoom) host.nameOf(from) else host.groupName(gid), from, video))
                ringTimer?.cancel()
                ringTimer = scope.launch {
                    delay(ringMs)
                    lock.withLock {
                        cur(cid)?.takeIf { it.phase == Phase.RINGING }?.let {
                            host.onRing(null)
                            host.callLog(gid, logPeer(it), "gcall:$cid", kindText(it) + " · nieodebrana")
                            state.value = null
                        }
                    }
                }
            }
            "join" -> {
                remember(gid, cid, video, add = pid)
                val call = c?.takeIf { it.id == cid && it.phase == Phase.ACTIVE } ?: return
                if (pid < me) toPeer("here", call, from, dev) else offerTo(cid, from, dev)
            }
            "here" -> {
                remember(gid, cid, video, add = pid)
                c?.takeIf { it.id == cid && it.phase == Phase.ACTIVE } ?: return
                if (me < pid) offerTo(cid, from, dev)
            }
            "offer" -> {
                c?.takeIf { it.id == cid && it.phase == Phase.ACTIVE } ?: return
                answerTo(cid, from, dev, p.optString("sdp"))
            }
            "answer" -> {
                if (c?.id != cid) return
                val l = links[pid] ?: return
                scope.launch { try { l.applyAnswer(p.optString("sdp")) } catch (e: Exception) { lock.withLock { dropPeer(cid, pid) } } }
            }
            "leave" -> {
                remember(gid, cid, video, remove = pid)
                // „nr” — druga strona nie mogła się z nami połączyć (zablokowana sieć)
                if (c?.id == cid) dropPeer(cid, pid, failed = p.optBoolean("nr") && c.peers[pid]?.phase != PeerPhase.CONNECTED)
            }
            "cancel" -> {
                known.value = known.value - gid
                val call = c?.takeIf { it.id == cid } ?: return
                if (call.phase == Phase.RINGING) {
                    ringTimer?.cancel(); host.onRing(null)
                    host.callLog(gid, logPeer(call), "gcall:$cid", kindText(call) + " · nieodebrana")
                    state.value = null
                }
            }
            "decline", "busy" -> {
                // zaproszona osoba nie odbierze — przestajemy na nią czekać
                val call = c?.takeIf { it.id == cid && it.phase == Phase.ACTIVE } ?: return
                val now = call.copy(invited = call.invited - from)
                state.value = now
                if (now.isRoom && now.peers.isEmpty() && now.invited.isEmpty() && !everConnected.contains(cid)) {
                    close(now, if (op == "busy") "Zajęte" else "Odrzucono", quiet = false)
                }
            }
            "add" -> {
                val call = c?.takeIf { it.id == cid } ?: return
                state.value = call.copy(room = rooms[gid] ?: call.room)
            }
            "full" -> {
                val call = c?.takeIf { it.id == cid } ?: return
                dropPeer(cid, pid)
                if (call.peers.size <= 1 && call.from != host.myPub) close(call, "Rozmowa jest pełna")
            }
        }
    }

    /** Sygnał od mojego innego urządzenia: dołączyłem/odrzuciłem tam. */
    fun onSelfSignal(p: JSONObject) {
        scope.launch {
            lock.withLock {
                if (p.optString("op") != "taken") return@withLock
                val c = cur(p.optString("cid"))?.takeIf { it.phase == Phase.RINGING } ?: return@withLock
                ringTimer?.cancel(); host.onRing(null)
                ended(c.copy(phase = Phase.ENDED, endReason = "Odebrano na innym urządzeniu"))
            }
        }
    }

    fun clearEnded() { if (state.value?.phase == Phase.ENDED) state.value = null }
}
