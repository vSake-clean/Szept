package pl.szept.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.crypto.B64
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.crypto.isHex64
import pl.szept.crypto.isPubKey
import pl.szept.crypto.unhex
import pl.szept.data.Chat
import pl.szept.data.Contact
import pl.szept.data.Db
import pl.szept.data.DbCrypto
import pl.szept.data.Extra
import pl.szept.data.Group
import pl.szept.data.Invite
import pl.szept.data.JoinReq
import pl.szept.data.Loc
import pl.szept.data.Poll
import pl.szept.data.Scheduled
import pl.szept.data.Story
import pl.szept.data.GroupState
import pl.szept.data.Kind
import pl.szept.data.MediaMeta
import pl.szept.data.Member
import pl.szept.data.Message
import pl.szept.data.Reply
import pl.szept.data.St
import pl.szept.data.XMode
import pl.szept.data.dmChatId
import pl.szept.data.groupChatId
import pl.szept.nostr.Clock
import pl.szept.nostr.Event
import pl.szept.nostr.Relay
import pl.szept.lan.LanManager
import pl.szept.call.CallManager
import pl.szept.call.GroupCallManager
import pl.szept.call.IceServer
import pl.szept.lan.LocalRelay
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rdzeń komunikatora. Działa bez własnego serwera:
 *  - każda wiadomość jest szyfrowana end-to-end osobno dla każdego odbiorcy i pakowana w „kopertę”
 *    (zdarzenie Nostr kind 1059 podpisane jednorazowym kluczem — przekaźnik nie zna nadawcy ani treści),
 *  - koperty trafiają na kilka publicznych przekaźników Nostr, które przechowują je do czasu,
 *    aż odbiorca się połączy (telefon wyłączony => odbierze po włączeniu),
 *  - media są dzielone na zaszyfrowane paczki ~44 KB i wysyłane raz (klucz idzie w kopercie).
 */
class Core(val ctx: Context, val profile: String = "main") : Relay.Listener, LanManager.Host {

    companion object {
        /** Status: telefon odzywa się co 5 min; dłuższa cisza = aplikacja wyłączona (nie działa w tle). */
        const val PRESENCE_EVERY_MS = 5 * 60_000L
        const val PRESENCE_STALE_MS = 13 * 60_000L
        const val TAG = "Szept"
        const val WRAP_KIND = 1059
        const val CHUNK_KIND = 4334
        const val INNER_KIND = 14
        const val EXPIRE_SEC = 21L * 86400
        const val CHUNK_RAW = 44_000
        const val MEDIA_RELAYS = 5
        const val MAX_MEDIA = 16L * 1024 * 1024
        /** Tryby pracy w tle. */
        const val BG_FULL = 0       // wszystkie przekaźniki na żywo (najszybciej, najwięcej baterii)
        const val BG_BALANCED = 1   // 2 przekaźniki na żywo + pełna synchronizacja co ~10 min
        const val BG_SAVER = 2      // bez stałego połączenia, synchronizacja co ~10–15 min
        const val LIVE_RELAYS = 2
        /** Prywatność powiadomień. */
        const val NOTIF_FULL = 0      // nadawca i treść
        const val NOTIF_SENDER = 1    // tylko nadawca
        const val NOTIF_NONE = 2      // tylko „Nowa wiadomość”
        const val STORY_MS = 24L * 3600_000
        /** Wskaźnik „pisze…” wysyłamy tylko w mniejszych rozmowach (oszczędność przekaźników i baterii). */
        const val TYPING_MAX_MEMBERS = 15
        /** Rotacja kluczy: nowy klucz urządzenia co 7 dni, stare kasowane 35 dni po zastąpieniu (koperty żyją 21 dni). */
        const val PREKEY_ROTATE_MS = 7L * 86400_000
        const val PREKEY_KEEP_MS = 35L * 86400_000
        /** Klucz urządzenia ogłoszony dawniej niż 60 dni temu uznajemy za nieaktualny (urządzenie porzucone). */
        const val PREKEY_FRESH_MS = 60L * 86400_000
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://nostr.oxtr.dev",
            "wss://nostr-pub.wellorder.net",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
            "wss://nostr.data.haus",
        )
        private val PERMANENT_ERRORS = listOf("blocked", "invalid", "pow", "restricted", "error", "mute")
        val EDITABLE = setOf(Kind.TEXT, Kind.IMAGE, Kind.VIDEO, Kind.FILE)
        val FORWARDABLE = setOf(Kind.TEXT, Kind.IMAGE, Kind.VIDEO, Kind.VOICE, Kind.FILE)
    }

    val dbName = if (profile == "main") "szept.db" else "szept_$profile.db"
    /** Baza w telefonie — zaszyfrowana (SQLCipher + klucz w Android Keystore), gdy telefon to umożliwia. */
    val db = Db(ctx, dbName, encrypt = true)
    val prefs = ctx.getSharedPreferences("szept_$profile", Context.MODE_PRIVATE)
    val mediaDir = File(ctx.filesDir, if (profile == "main") "media" else "media_$profile").apply { mkdirs() }
    private val tmpDir = File(ctx.cacheDir, "dl_$profile").apply { mkdirs() }

    // CrashLog.handler: błąd w zadaniu w tle trafia do dziennika zamiast wyłączać aplikację
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.handler)
    private val http = Relay.httpClient()
    private val relays = ConcurrentHashMap<String, Relay>()
    val relayStates = MutableStateFlow<Map<String, Relay.State>>(emptyMap())

    /** Czat aktualnie otwarty na ekranie (bez powiadomień, automatyczne „przeczytano”). */
    @Volatile var activeChat: String? = null
    @Volatile var appVisible: Boolean = false
        set(v) {
            val changed = field != v
            field = v
            if (!v) hiddenRevealed.value = false   // ukryte czaty znikają po wyjściu z aplikacji
            if (changed) onVisibilityChanged(v)
        }
    /** Czy w tle utrzymujemy tylko część połączeń (oszczędzanie baterii). */
    val trimmed = MutableStateFlow(false)
    private var trimJob: Job? = null
    /** Po jakim czasie od zamknięcia aplikacji ograniczyć połączenia (ms). */
    var trimDelayMs = 60_000L
    var notifier: Notifier? = null

    private val incoming = Channel<JSONObject>(Channel.UNLIMITED)
    private val stateLock = Mutex()
    private val expiryKick = Channel<Unit>(Channel.CONFLATED)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val relaySem = ConcurrentHashMap<String, Semaphore>()
    private val uploading = ConcurrentHashMap.newKeySet<String>()
    private val downloads = ConcurrentHashMap<String, DownloadJob>()
    private val inboxCount = ConcurrentHashMap<String, Int>()
    private val inboxOldest = ConcurrentHashMap<String, Long>()
    private var started = false
    private var jobs = mutableListOf<Job>()

    // ================= sieć lokalna (bez internetu) =================

    /** Przekaźniki w sieci lokalnej (własny w telefonie i znalezione w Wi-Fi) — poza listą z Ustawień. */
    private val localUrls = ConcurrentHashMap.newKeySet<String>()
    private var lanCreated = false
    /** Przekaźnik w telefonie, wyszukiwanie telefonów w pobliżu, hotspot. */
    val lan: LanManager by lazy { lanCreated = true; LanManager(ctx, this, if (profile == "main") "lan_relay.db" else "lan_relay_$profile.db") }
    val lanEnabled: Boolean get() = prefs.getBoolean("lan", false)
    /** Przekazywanie do internetu kopert, które inne telefony zostawiły w moim przekaźniku. */
    val lanForward: Boolean get() = prefs.getBoolean("lan_fwd", true)
    /** Kurier: pobieranie z internetu kopert dla znajomych, żeby oddać je w sieci lokalnej. */
    val courier: Boolean get() = prefs.getBoolean("courier", false)
    private val courierBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var courierAgain = false
    private var courierSubAt = 0L

    /** Stały identyfikator tej instalacji (do rozróżniania urządzeń jednego konta). */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: Crypto.randomBytes(8).hex().also { prefs.edit().putString("device_id", it).apply() }
    override val deviceTag: String get() = deviceId

    fun isLocal(url: String) = url in localUrls
    override fun localRelayUrls(): Set<String> = localUrls.toSet()
    override fun baseClient(): OkHttpClient = http

    override fun addLocalRelay(url: String, client: OkHttpClient) {
        if (url in configuredRelays()) return
        localUrls.add(url)
        relays.getOrPut(url) { Relay(url, client, scope, this) }.connect()
        publishStates()
    }

    override fun removeLocalRelay(url: String) {
        localUrls.remove(url)
        if (url !in configuredRelays()) relays.remove(url)?.disconnect()
        publishStates()
    }

    /** Stan przekaźników w sieci lokalnej (adres, stan). */
    fun localRelayInfo(): List<Pair<String, Relay.State>> = localUrls.map { it to (relays[it]?.state ?: Relay.State.DISCONNECTED) }.sortedBy { it.first }

    fun setLanEnabled(on: Boolean) {
        prefs.edit().putBoolean("lan", on).apply()
        if (on) { if (started) lan.start() } else if (lanCreated) lan.stop()
    }

    fun setLanForward(on: Boolean) = prefs.edit().putBoolean("lan_fwd", on).apply()

    fun setCourier(on: Boolean) {
        prefs.edit().putBoolean("courier", on).apply()
        courierSubAt = 0
        refreshCourierSubs()
    }

    private fun internetRelays(): List<Relay> = connectedRelays().filter { !isLocal(it.url) }

    /** Subskrypcja kopert dla znajomych na kilku przekaźnikach internetowych (tryb kuriera). */
    private fun refreshCourierSubs() {
        val active = courier && lanCreated && lan.running.value
        val internet = relays.values.filter { !isLocal(it.url) }
        if (!active) { internet.forEach { if (it.hasSub("cr")) it.unsubscribe("cr") }; return }
        val now = System.currentTimeMillis()
        if (now - courierSubAt < 30 * 60_000 && internet.any { it.hasSub("cr") }) return
        val pks = db.contacts(false).filter { !it.blocked && it.pk != myPub }.map { it.pk }.take(150)
        if (pks.isEmpty()) return
        courierSubAt = now
        val f = JSONObject().put("kinds", JSONArray().put(WRAP_KIND)).put("#p", JSONArray(pks))
            .put("since", Clock.nowSec() - 3 * 86400).put("limit", 500)
        val chosen = internet.filter { it.isConnected }.take(3).toSet()
        internet.forEach { if (it in chosen) it.subscribe("cr", listOf(f)) else if (it.hasSub("cr")) it.unsubscribe("cr") }
    }

    override fun onLanStored(origin: Int) { if (origin == LocalRelay.ORIGIN_LAN) courierForward() }

    /** Kurier „w górę”: koperty od telefonów z sieci lokalnej wysyłamy do internetu, gdy go mamy. */
    private fun courierForward() {
        if (!lanCreated || !lan.running.value || !lanForward) return
        val internet = internetRelays()
        if (internet.isEmpty()) return
        if (!courierBusy.compareAndSet(false, true)) { courierAgain = true; return }
        scope.launch {
            try {
                courierAgain = false
                for (json in lan.relay.pendingForward(200)) {
                    val id = JSONObject(json).optString("id")
                    var done = false
                    for (r in internet.take(3)) {
                        val (ok, msg) = r.publish(json, id, 15_000)
                        if (ok || !(msg.startsWith("net:") || msg.startsWith("rate-limited"))) done = true
                    }
                    if (done) lan.relay.markForwarded(id)
                }
            } catch (e: Exception) { Log.w(TAG, "kurier", e) } finally {
                courierBusy.set(false)
                if (courierAgain) courierForward()
            }
        }
    }

    // ================= tożsamość =================

    val hasIdentity get() = prefs.getString("priv", null) != null
    private var privCache: ByteArray? = null
    val myPriv: ByteArray get() = privCache ?: prefs.getString("priv", null)!!.unhex().also { privCache = it }
    val myPub: String by lazy { Crypto.publicKey(myPriv).hex() }
    val myName: String get() = prefs.getString("name", null) ?: "Ja"
    val myStatus: String get() = prefs.getString("status", "") ?: ""
    val myBio: String get() = prefs.getString("bio", "") ?: ""
    val readReceipts: Boolean get() = prefs.getBoolean("receipts", true)
    val myShortId: String get() = pl.szept.data.shortId(myPub)
    val avatarFile: File get() = File(ctx.filesDir, "me_avatar_$profile.jpg")
    val bannerFile: File get() = File(ctx.filesDir, "me_banner_$profile.jpg")
    private fun b64OrNull(f: File) = if (f.exists()) B64.enc(f.readBytes()) else null

    fun createIdentity(name: String) {
        prefs.edit().putString("priv", Crypto.newPrivateKey().hex()).putString("name", name.trim())
            .putLong("created", Clock.nowSec()).apply()
    }

    fun restoreIdentity(keyHex: String, name: String): Boolean {
        val k = keyHex.trim().lowercase()
        if (!k.isHex64() || !Crypto.isValidPrivateKey(k.unhex())) return false
        prefs.edit().putString("priv", k).putString("name", name.trim())
            .putLong("created", Clock.nowSec() - EXPIRE_SEC)
            // to drugie urządzenie tego konta — przedstawi się pozostałym po uruchomieniu
            .putBoolean("multi", true).putBoolean("announce", true).apply()
        return true
    }

    fun setName(name: String) = updateProfile { putString("name", name.trim().take(60)) }

    /** Zmiana statusu i opisu (jak na Discordzie). */
    fun setStatusBio(status: String, bio: String) = updateProfile {
        putString("status", status.trim().take(120)); putString("bio", bio.trim().take(500))
    }

    /** Ustawia/usuwa zdjęcie profilowe albo baner (plik już przeskalowany). */
    fun setAvatar(file: File?) = setProfileImage(avatarFile, file)
    @Volatile private var avatarCache: Pair<Long, String?>? = null
    /** Moje zdjęcie (base64) — do ekranu rozmowy. */
    fun myAvatarB64(): String? {
        val m = if (avatarFile.exists()) avatarFile.lastModified() else 0L
        avatarCache?.let { if (it.first == m) return it.second }
        return (if (m == 0L) null else B64.enc(avatarFile.readBytes())).also { avatarCache = m to it }
    }
    fun setBanner(file: File?) = setProfileImage(bannerFile, file)

    private fun setProfileImage(dest: File, src: File?) {
        if (src == null) dest.delete() else { src.copyTo(dest, overwrite = true); if (src.absolutePath.startsWith(ctx.cacheDir.absolutePath)) src.delete() }
        updateProfile { }
    }

    private fun updateProfile(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply { edit(); putLong("prof_ts", Clock.now()) }.apply()
        broadcastProfile()
    }

    /** Dokłada moje dane profilowe do koperty (do wysłania innym). */
    private fun fillProfile(o: JSONObject, withImages: Boolean = true): JSONObject {
        o.put("st", myStatus).put("bio", myBio).put("pts", prefs.getLong("prof_ts", 0))
        prefs.getString("badge", null)?.let { o.put("vb", it) }
        if (withImages) {
            b64OrNull(avatarFile)?.let { o.put("av", it) }
            b64OrNull(bannerFile)?.let { o.put("bn", it) }
        }
        return o
    }

    private fun broadcastProfile() {
        scope.launch {
            val targets = (db.contacts(false).filter { it.isContact && !it.blocked }.map { it.pk } +
                db.groups().flatMap { it.members.map { m -> m.pk } }).distinct().filter { it != myPub }
            if (targets.isNotEmpty()) dispatch(fillProfile(JSONObject().put("t", "prof")), targets, null, 7 * 86400)
        }
    }

    fun setReadReceipts(on: Boolean) = prefs.edit().putBoolean("receipts", on).apply()

    fun myCode() = Links.contact(myPub, myName)

    // ================= start / przekaźniki =================

    fun configuredRelays(): List<String> =
        prefs.getString("relays", null)?.split('\n')?.map { it.trim() }?.filter { pl.szept.nostr.isValidRelayUrl(it) } ?: DEFAULT_RELAYS

    fun setRelays(list: List<String>) {
        prefs.edit().putString("relays", list.map { it.trim() }.filter { pl.szept.nostr.isValidRelayUrl(it) }.joinToString("\n")).apply()
        applyRelays()
    }

    @Synchronized
    fun start() {
        if (started || !hasIdentity) return
        started = true
        jobs += scope.launch { processIncoming() }
        jobs += scope.launch { expiryLoop() }
        jobs += scope.launch { scheduleLoop() }
        jobs += scope.launch {
            try { db.prune(); cleanupOrphanFiles() } catch (e: Exception) { CrashLog.record("Porządki", e) }
            while (true) {
                delay(60_000)
                try { maintenance() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    CrashLog.record("Obsługa w tle", e)
                }
            }
        }
        applyRelays()
        registerNetworkCallback()
        if (lanEnabled) lan.start()
        scope.launch {
            if (prefs.getBoolean("announce", false)) { prefs.edit().remove("announce").apply(); announceDevice(ask = true) }
            maintenance()
        }
        if (!appVisible) scheduleTrim(trimDelayMs)
    }

    fun stop() {
        if (lanCreated) lan.close()
        relays.values.forEach { it.disconnect() }
        relays.clear()
        scope.cancel()
        netCallback?.let { cb -> try { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
        netCallback = null
    }

    private fun applyRelays() {
        val want = configuredRelays().toSet()
        relays.keys.filter { it !in want && it !in localUrls }.forEach { relays.remove(it)?.disconnect() }
        want.forEach { url ->
            relays.getOrPut(url) { Relay(url, http, scope, this) }.connect()
        }
        publishStates()
    }

    fun connectedRelays(): List<Relay> = relays.values.filter { it.isConnected }

    /** Wywoływane przez usługę/alarm — sprawdza połączenia i nadrabia zaległości. */
    fun keepalive() {
        if (!started) start()
        if (trimmed.value) {
            // pełna synchronizacja: chwilowo łączymy się ze wszystkimi przekaźnikami
            untrim()
            if (!appVisible) scheduleTrim(35_000)
        } else relays.values.forEach { if (!it.isConnected) it.reconnectNow() }
        scheduleKick.trySend(Unit)
        scope.launch { maintenance() }
    }

    val bgMode: Int get() = prefs.getInt("bgmode", BG_BALANCED)

    fun setBgMode(mode: Int) {
        prefs.edit().putInt("bgmode", mode).apply()
        if (!started || appVisible) return
        if (mode == BG_FULL) { trimJob?.cancel(); untrim() } else scheduleTrim(5_000)
    }

    private fun onVisibilityChanged(visible: Boolean) {
        if (!started) return
        presenceJob?.cancel()
        presenceJob = scope.launch { delay(2500); sendPresence() }
        if (visible) {
            trimJob?.cancel()
            untrim()
            scope.launch { maintenance() }
        } else scheduleTrim(trimDelayMs)
    }

    private fun scheduleTrim(delayMs: Long) {
        trimJob?.cancel()
        trimJob = scope.launch {
            delay(delayMs)
            // nie przerywaj wysyłania/pobierania — poczekaj (maks. 10 min)
            val until = System.currentTimeMillis() + 10 * 60_000
            while (isBusy() && System.currentTimeMillis() < until) delay(5_000)
            if (!appVisible && bgMode == BG_SAVER) {
                // w trybie oszczędzania aplikacja nie działa w tle — znajomi zobaczą „nieaktywny”
                sendPresence(force = true, closing = true)
                delay(1500)
            }
            if (!appVisible) trim()
        }
    }

    private fun isBusy(): Boolean =
        uploading.isNotEmpty() || downloads.isNotEmpty() || inflight.isNotEmpty() || db.hasPending() ||
            db.outbox().any { r -> r.ok.none { !it.startsWith("!") } }

    @Synchronized
    private fun trim() {
        val mode = bgMode
        if (mode == BG_FULL || appVisible) return
        val keep = if (mode == BG_SAVER) emptySet() else {
            val conf = configuredRelays()
            (conf.filter { relays[it]?.isConnected == true } + conf).distinct().take(LIVE_RELAYS).toSet()
        }
        relays.values.forEach { r -> if (r.url in keep || isLocal(r.url)) r.connect() else r.disconnect() }
        trimmed.value = true
        Log.i(TAG, "tryb tła: na żywo ${keep.size} przekaźników")
    }

    @Synchronized
    private fun untrim() {
        if (!trimmed.value) return
        trimmed.value = false
        relays.values.forEach { it.connect() }
    }

    private fun maintenance() {
        flushOutbox()
        kickUploads()
        kickDownloads()
        try { courierForward(); refreshCourierSubs() } catch (e: Exception) { Log.w(TAG, "kurier", e) }
        try { rotatePrekey() } catch (e: Exception) { Log.w(TAG, "rotacja kluczy", e) }
        try { sendPresence() } catch (e: Exception) { Log.w(TAG, "status", e) }
        try {
            purgeStories()
            val now = System.currentTimeMillis()
            db.lives().filter { it.until <= now }.forEach { endLive(it.msg) }
            refreshLive()
        } catch (e: Exception) { Log.w(TAG, "porządki", e) }
    }

    private var lastNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private fun registerNetworkCallback() {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = lastNetwork != null && lastNetwork != network
                    lastNetwork = network
                    relays.values.forEach { it.reconnectNow(force = changed) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { Log.w(TAG, "network callback", e) }
    }

    private fun publishStates() {
        relayStates.value = relays.mapValues { it.value.state }
    }

    override fun onRelayStateChanged(relay: Relay) = publishStates()

    override fun onRelayConnected(relay: Relay) {
        subscribeInbox(relay)
        relay.subscribe("pol", listOf(JSONObject().put("kinds", JSONArray().put(Admin.POLICY_KIND)).put("authors", JSONArray().put(Admin.PUB))
            .put("#d", JSONArray().put(Admin.POLICY_D)).put("limit", 2)))
        if (!isLocal(relay.url) && courier) { courierSubAt = 0; scope.launch { refreshCourierSubs() } }
        scope.launch { maintenance() }
    }

    override fun signAuth(relay: Relay, challenge: String): Event? =
        if (!hasIdentity) null else Event.create(myPriv, 22242, listOf(listOf("relay", relay.url), listOf("challenge", challenge)), "")

    private fun sinceFor(url: String): Long {
        val base = prefs.getLong("created", Clock.nowSec()) - 3600
        val saved = prefs.getLong("since:$url", 0L)
        return if (saved == 0L) base else max(base, saved - 3 * 3600)
    }

    /** Ile kopert pobierać jednym zapytaniem (przekaźniki zwykle pozwalają na 500). */
    var inboxLimit = 500
    private val inboxUntil = ConcurrentHashMap<String, Long>()

    private fun inboxFilter(since: Long, until: Long? = null) = JSONObject().apply {
        put("kinds", JSONArray().put(WRAP_KIND))
        put("#p", JSONArray().put(myPub))
        put("since", since)
        if (until != null) put("until", until)
        put("limit", inboxLimit)
    }

    private fun subscribeInbox(relay: Relay) {
        inboxCount[relay.url] = 0
        inboxOldest[relay.url] = Long.MAX_VALUE
        inboxUntil.remove(relay.url)
        relay.subscribe("in", listOf(inboxFilter(sinceFor(relay.url))))
    }

    override fun onRelayEvent(relay: Relay, subId: String, event: JSONObject) {
        when {
            subId.startsWith("in") -> {
                inboxCount.merge(relay.url, 1, Int::plus)
                val ca = event.optLong("created_at")
                inboxOldest.merge(relay.url, ca) { a, b -> min(a, b) }
                incoming.trySend(event)
            }
            subId.startsWith("m-") -> downloads.values.firstOrNull { it.subId == subId }?.onChunk(event)
            subId == "pol" -> scope.launch { handlePolicy(event) }
            subId == "cr" -> if (lanCreated && lan.running.value) scope.launch { lan.relay.accept(event, LocalRelay.ORIGIN_COURIER) }
        }
    }

    override fun onRelayClosed(relay: Relay, subId: String, message: String) {
        if (subId.startsWith("m-")) downloads.values.firstOrNull { it.subId == subId }?.onEose(relay.url)
    }

    override fun onRelayEose(relay: Relay, subId: String) {
        if (subId.startsWith("in")) {
            val count = inboxCount[relay.url] ?: 0
            val oldest = inboxOldest[relay.url] ?: Long.MAX_VALUE
            if (count >= inboxLimit * 9 / 10 && oldest != Long.MAX_VALUE) {
                // dużo zaległych kopert — pobierz starsze strony
                val prev = inboxUntil[relay.url]
                val until = if (prev != null && oldest >= prev) prev - 1 else oldest
                inboxUntil[relay.url] = until
                inboxCount[relay.url] = 0
                inboxOldest[relay.url] = Long.MAX_VALUE
                relay.subscribe("in-old", listOf(inboxFilter(sinceFor(relay.url), until)))
            } else {
                if (subId == "in-old") relay.unsubscribe("in-old")
                inboxUntil.remove(relay.url)
                prefs.edit().putLong("since:${relay.url}", Clock.nowSec() - 60).apply()
            }
        } else if (subId.startsWith("m-")) {
            downloads.values.firstOrNull { it.subId == subId }?.onEose(relay.url)
        }
    }

    // ================= wysyłanie kopert =================

    private fun newId() = Crypto.randomBytes(16).hex()

    /**
     * Podpisuje ładunek moim kluczem, szyfruje osobno dla każdego odbiorcy i wrzuca do skrzynki nadawczej.
     * [sync] — kopia także dla moich innych urządzeń (gdy używam Szepta na kilku).
     * Jeśli odbiorca ogłosił klucze urządzeń (rotacja kluczy), koperta idzie osobno do każdego urządzenia.
     */
    private fun dispatch(
        payload: JSONObject, recipients: List<String>, msgId: String?, ttlSec: Long = EXPIRE_SEC, expirationAbs: Long? = null,
        sync: Boolean = true, asAdmin: Boolean = false,
    ) {
        val signer = if (asAdmin) adminKey ?: return else myPriv
        val targets = recipients.filter { it != myPub && it.isPubKey() && it != Admin.PUB }.distinct()
        val toSelf = sync && multiDevice && !asAdmin
        if (targets.isEmpty() && !toSelf) return
        payload.put("n", if (asAdmin) Admin.NAME else myName)
        payload.put("v", 1)
        payload.put("dv", if (asAdmin) "admin" else deviceId)
        val inner = Event.create(signer, INNER_KIND, emptyList(), payload.toString())
        val innerBytes = inner.toJson().toByteArray(Charsets.UTF_8)
        val expiration = expirationAbs ?: (Clock.nowSec() + ttlSec)
        val base = msgId ?: inner.id
        for (r in targets + if (toSelf) listOf(myPub) else emptyList()) {
            val now = System.currentTimeMillis()
            val devs = db.prekeys(r).filter { now - it.ts < PREKEY_FRESH_MS && it.pub.isPubKey() && (r != myPub || it.dev != deviceId) }
            if (devs.isEmpty()) addWrap(r, r, "w:$base:$r", "$r", innerBytes, expiration, msgId, signer)
            else devs.forEach { d -> addWrap(r, d.pub, "w:$base:$r:${d.dev}", "$r:${d.dev}", innerBytes, expiration, msgId, signer) }
        }
        flushOutbox()
    }

    /** Jedna koperta: adres [pTag] (kto ją pobierze), szyfrowana do klucza [encPub]. */
    private fun addWrap(pTag: String, encPub: String, ephLabel: String, wrapRef: String, innerBytes: ByteArray, expiration: Long, msgId: String?, signer: ByteArray = myPriv) {
        val eph = Crypto.deriveKey(signer, ephLabel)
        val key = Crypto.conversationKey(eph, encPub.unhex())
        val content = B64.enc(Crypto.encrypt(key, innerBytes))
        val wrap = Event.create(eph, WRAP_KIND, listOf(listOf("p", pTag), listOf("expiration", expiration.toString())), content)
        db.addOutbox(wrap.id, wrap.toJson(), msgId)
        if (msgId != null) db.addWrap(msgId, wrapRef, wrap.id)
    }

    /** Tylko dla moich innych urządzeń. */
    private fun dispatchSelf(payload: JSONObject, ttlSec: Long = EXPIRE_SEC) {
        if (multiDevice) dispatch(payload, emptyList(), null, ttlSec, sync = true)
    }

    private fun sem(url: String) = relaySem.getOrPut(url) { Semaphore(6) }

    fun flushOutbox() {
        scope.launch {
            val rows = try { db.outbox() } catch (e: Exception) { Log.w(TAG, "outbox", e); return@launch }
            if (rows.isEmpty()) return@launch
            val want = configuredRelays()
            for (row in rows) {
                // bez przekaźników w internecie (tylko sieć lokalna) koperta czeka, aż wygaśnie
                val expired = System.currentTimeMillis() - row.created > (EXPIRE_SEC + 86400) * 1000
                if (expired || (want.isNotEmpty() && want.all { it in row.ok || "!$it" in row.ok })) { db.removeOutbox(row.id); continue }
                for (relay in connectedRelays()) {
                    if (relay.url in row.ok || "!${relay.url}" in row.ok) continue
                    val key = relay.url + "|" + row.id
                    if (!inflight.add(key)) continue
                    scope.launch {
                        try {
                            sem(relay.url).withPermit {
                                val json = db.outboxJson(row.id) ?: run { db.removeOutbox(row.id); return@withPermit }
                                val (ok, msg) = relay.publish(json, row.id)
                                when {
                                    ok || msg.startsWith("duplicate") -> {
                                        db.outboxAddRelay(row.id, relay.url)
                                        onWrapAccepted(row.msg)
                                    }
                                    PERMANENT_ERRORS.any { msg.startsWith(it) } -> {
                                        Log.w(TAG, "${relay.url} odrzucił: $msg")
                                        db.outboxAddRelay(row.id, "!" + relay.url)
                                        checkWrapFailure(row.msg)
                                    }
                                    else -> Log.d(TAG, "${relay.url} chwilowy błąd: $msg")
                                }
                            }
                        } finally { inflight.remove(key) }
                    }
                }
            }
        }
    }

    private fun onWrapAccepted(msgId: String?) {
        if (msgId == null) return
        val (total, ok) = db.msgWrapsOk(msgId)
        if (total > 0 && ok >= total) {
            val m = db.message(msgId) ?: return
            if (m.mine && m.status < St.SENT) db.updateStatus(msgId, St.SENT)
        }
    }

    private fun checkWrapFailure(msgId: String?) {
        if (msgId == null) return
        val want = configuredRelays()
        val rows = db.outbox().filter { it.msg == msgId }
        if (want.isNotEmpty() && rows.any { r -> want.all { "!$it" in r.ok } }) db.updateStatus(msgId, St.FAILED, onlyIfLower = false)
    }

    // ================= status obecności =================

    /** Status rozmówcy: „a” aktywny w aplikacji, „i” aplikacja w tle, „b” zaraz wracam; [at] — kiedy przyszedł. */
    data class Presence(val state: String, val at: Long)

    /** Wyświetlany stan obecności. */
    enum class Seen { ACTIVE, IDLE, AWAY, INACTIVE }

    val presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    /** Czy pokazywać innym mój status (gdy wyłączone, nikt nie widzi mojego, a ja widzę cudze). */
    val shareStatus: Boolean get() = prefs.getBoolean("presence", true)
    /** Ręczny status „Zaraz wracam”. */
    val awayManual: Boolean get() = prefs.getBoolean("brb", false)
    private var presenceJob: Job? = null
    @Volatile private var lastPresenceState = ""
    @Volatile private var lastPresenceAt = 0L

    fun setShareStatus(on: Boolean) { prefs.edit().putBoolean("presence", on).apply(); sendPresence(force = true) }
    fun setAway(on: Boolean) { prefs.edit().putBoolean("brb", on).apply(); sendPresence(force = true) }

    fun myPresence(): Seen = when { awayManual -> Seen.AWAY; appVisible -> Seen.ACTIVE; else -> Seen.IDLE }

    /** Wysyła mój status znajomym (przy zmianie i co 5 minut, krótkotrwała koperta). */
    fun sendPresence(force: Boolean = false, closing: Boolean = false) {
        if (!started || !hasIdentity) return
        val st = if (!shareStatus) "off" else if (closing || (!appVisible && bgMode == BG_SAVER && !awayManual)) "x" else when (myPresence()) { Seen.AWAY -> "b"; Seen.ACTIVE -> "a"; else -> "i" }
        val now = System.currentTimeMillis()
        if (!force && st == lastPresenceState && now - lastPresenceAt < PRESENCE_EVERY_MS - 10_000) return
        if (st == "off" && lastPresenceState == "off") return
        val targets = db.contacts(true).filter { !it.blocked && it.pk != myPub && !Admin.isAdmin(it.pk) }.map { it.pk }.take(150)
        if (targets.isEmpty() || connectedRelays().isEmpty()) return
        lastPresenceState = st; lastPresenceAt = now
        publishEphemeral(JSONObject().put("t", "pres").put("s", st).put("ts", Clock.now()), targets, relayCount = 4, ttlSec = 20 * 60)
    }

    private fun handlePresence(from: String, p: JSONObject) {
        if (db.contact(from)?.blocked != false) return
        val st = p.optString("s")
        val ts = p.optLong("ts", Clock.now())
        val age = (Clock.now() - ts).coerceAtLeast(0)
        if (age > PRESENCE_STALE_MS) return
        presence.update { cur ->
            val old = cur[from]
            if (st == "off") cur - from
            else if (st !in setOf("a", "i", "b", "x")) cur
            else if (old != null && old.at > System.currentTimeMillis() - age) cur
            else if (st == "x" && old?.state == "x") cur // „nieaktywny od…” — zachowujemy pierwszy moment
            else cur + (from to Presence(st, System.currentTimeMillis() - age))
        }
    }

    /** Stan rozmówcy do pokazania (null = nie udostępnia albo jeszcze nie wiadomo). */
    fun seenOf(pk: String?, now: Long = System.currentTimeMillis()): Pair<Seen, Long>? {
        val p = presence.value[pk ?: return null] ?: return null
        if (now - p.at > PRESENCE_STALE_MS) return Seen.INACTIVE to p.at
        return when (p.state) { "a" -> Seen.ACTIVE; "b" -> Seen.AWAY; "x" -> Seen.INACTIVE; else -> Seen.IDLE } to p.at
    }

    // ================= konto oficjalne (administrator) =================

    @Volatile private var adminKey: ByteArray? = prefs.getString("admin_k", null)?.let { DbCrypto.open(it) }
        ?.takeIf { Crypto.publicKey(it).hex() == Admin.PUB }
    /** Czy panel administratora jest odblokowany na tym telefonie. */
    val adminUnlocked = MutableStateFlow(adminKey != null)
    /** Wysyłki administratora w toku (id → opis). */
    val adminJobs = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Numer zainstalowanej wersji (w testach można zmienić). */
    var appVersionCode: Int = pl.szept.BuildConfig.VERSION_CODE

    fun adminLogin(password: CharArray, remember: Boolean = true): Boolean {
        val k = Admin.unlock(password) ?: return false
        adminKey = k
        adminViaOtp = false
        if (remember) DbCrypto.seal(k)?.let { prefs.edit().putString("admin_k", it).apply() }
        // hasło zapisane zaszyfrowane kluczem telefonu (Android Keystore) — do podejrzenia w panelu
        DbCrypto.seal(String(password).toByteArray())?.let { prefs.edit().putString("admin_pw", it).apply() }
        adminUnlocked.value = true
        return true
    }

    fun adminLogout() {
        adminKey = null
        adminViaOtp = false
        prefs.edit().remove("admin_k").remove("admin_pw").apply()
        adminUnlocked.value = false
    }

    // ---- moderacja ----

    /** Sankcja administratora: mute (wyciszenie), ban (blokada pisania), del (usunięcie konta). [until] 0 = na zawsze. */
    data class Sanction(val pk: String, val mode: String, val until: Long, val reason: String, val ts: Long) {
        fun active(now: Long) = until == 0L || until > now
    }

    /** Lista sankcji z podpisanej polityki administratora (każdy telefon jej pilnuje). */
    val sanctions: List<Sanction> get() = policy.value?.optJSONArray("ban")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pk = o.optString("pk"); val m = o.optString("m")
            if (!pk.isHex64() || m !in setOf("mute", "ban", "del") || Admin.isAdmin(pk)) null
            else Sanction(pk, m, o.optLong("u"), o.optString("r").take(200), o.optLong("ts"))
        }
    } ?: emptyList()

    fun sanctionOf(pk: String?, now: Long = System.currentTimeMillis()): Sanction? =
        if (pk == null || Admin.isAdmin(pk)) null else sanctions.firstOrNull { it.pk == pk && it.active(now) }

    /** Opis sankcji dla ukaranej osoby (null = może pisać). */
    fun mySanctionText(now: Long = System.currentTimeMillis()): String? = sanctionOf(myPub, now)?.let { s ->
        val why = if (s.reason.isNotBlank()) " Powód: ${s.reason}" else ""
        when (s.mode) {
            "mute" -> "Administrator wyciszył Twoje konto" + (if (s.until > 0) " do " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("pl")).format(java.util.Date(s.until)) else "") + "." + why
            "ban" -> "Administrator zablokował Twoje konto — nie możesz pisać." + why
            else -> "Twoje konto zostało usunięte przez administratora." + why
        }
    }

    /** Usunięcie mojego konta przez administratora — interfejs je wykonuje i wraca do ekranu powitalnego. */
    val adminDeleted = MutableStateFlow(false)

    private fun checkMySanction() {
        if (!hasIdentity) return
        val s = sanctionOf(myPub) ?: return
        if (s.mode == "del") {
            ctx.getSharedPreferences("szept_notice", android.content.Context.MODE_PRIVATE).edit()
                .putString("deleted", "Twoje poprzednie konto zostało usunięte przez administratora." + if (s.reason.isNotBlank()) " Powód: ${s.reason}" else "").apply()
            adminDeleted.value = true
        }
    }

    /** Wykonuje usunięcie konta zleconie przez administratora. */
    fun wipeByAdmin() { if (sanctionOf(myPub)?.mode == "del") wipeAccount() }

    /** Typy kopert odrzucane od osoby wyciszonej (od zablokowanej/usuniętej — wszystkie). */
    private val MUTED_TYPES = setOf("msg", "react", "edit", "vote", "pin", "story", "typ", "call", "gcall", "loc", "shot", "pres")

    /**
     * Nakłada (albo zdejmuje — mode „none”) sankcję. Ukarana osoba dostaje też wiadomość od konta „Szept”.
     */
    suspend fun adminSanction(pk: String, mode: String, until: Long, reason: String) {
        stateLock.withLock {
            if (adminKey == null) throw SendException("Panel administratora jest zablokowany")
            if (!pk.isPubKey() || Admin.isAdmin(pk)) throw SendException("Nieprawidłowe konto")
            val content = policy.value?.let { JSONObject(it.toString()) } ?: JSONObject().put("min", 0)
            val old = content.optJSONArray("ban") ?: JSONArray()
            val now = System.currentTimeMillis()
            val arr = JSONArray()
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                val u = o.optLong("u")
                // wygasłe wyciszenia sprzątamy przy okazji
                if (o.optString("pk") != pk && (u == 0L || u > now)) arr.put(o)
            }
            if (mode != "none") arr.put(JSONObject().put("pk", pk).put("m", mode).put("u", until).put("r", reason.trim().take(200)).put("ts", now))
            content.put("ban", arr)
            publishPolicy(content)
        }
        val text = when (mode) {
            "mute" -> "Twoje konto zostało wyciszone" + (if (until > 0) " do " + java.text.SimpleDateFormat("d.MM.yyyy HH:mm", java.util.Locale("pl")).format(java.util.Date(until)) else "") + "."
            "ban" -> "Twoje konto zostało zablokowane — nie możesz wysyłać wiadomości."
            "del" -> "Twoje konto zostało usunięte przez administratora."
            else -> "Ograniczenia Twojego konta zostały zdjęte. Możesz znowu pisać."
        } + if (reason.isNotBlank() && mode != "none") " Powód: ${reason.trim()}" else ""
        try { adminSend(listOf(pk), text) } catch (_: SendException) {}
    }

    // ---- kody jednorazowe do panelu ----

    /** Czy panel jest otwarty kodem jednorazowym (wtedy bez zapamiętywania i bez podglądu hasła). */
    @Volatile var adminViaOtp = false
        private set

    data class Otp(val id: String, val exp: Long, val label: String)

    val adminOtps: List<Otp> get() = policy.value?.optJSONArray("otp")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Otp(it.optString("id"), it.optLong("exp"), it.optString("l")) } }
            .filter { it.exp > System.currentTimeMillis() }
    } ?: emptyList()

    private fun otpId(code: String) = Crypto.sha256(("szept-otp|" + code.uppercase().filter { it.isLetterOrDigit() }).toByteArray()).hex().take(24)

    /**
     * Tworzy kod jednorazowy: klucz panelu zaszyfrowany kodem (PBKDF2 + AES-GCM) trafia do podpisanej polityki.
     * Kod działa raz — po użyciu znika z listy. Zwraca kod do przekazania (np. „K7QM-…”).
     */
    suspend fun adminCreateOtp(validMs: Long, label: String): String = stateLock.withLock {
        val k = adminKey ?: throw SendException("Panel administratora jest zablokowany")
        if (adminViaOtp) throw SendException("Kody może tworzyć tylko administrator zalogowany hasłem")
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = Crypto.randomBytes(16).map { alphabet[(it.toInt() and 0xff) % alphabet.length] }.joinToString("")
        val code = raw.chunked(4).joinToString("-")
        val salt = Crypto.randomBytes(16)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(raw.toCharArray(), salt, 200_000, 256)).encoded
        val blob = B64.enc(salt + Crypto.encrypt(key, k))
        val content = policy.value?.let { JSONObject(it.toString()) } ?: JSONObject().put("min", 0)
        val arr = JSONArray()
        content.optJSONArray("otp")?.let { o -> for (i in 0 until o.length()) o.optJSONObject(i)?.takeIf { it.optLong("exp") > System.currentTimeMillis() }?.let { arr.put(it) } }
        arr.put(JSONObject().put("id", otpId(raw)).put("b", blob).put("exp", System.currentTimeMillis() + validMs).put("l", label.trim().take(40)))
        content.put("otp", arr)
        publishPolicy(content)
        code
    }

    suspend fun adminRevokeOtp(id: String) = stateLock.withLock {
        if (adminKey == null) return@withLock
        val content = policy.value?.let { JSONObject(it.toString()) } ?: return@withLock
        val arr = JSONArray()
        content.optJSONArray("otp")?.let { o -> for (i in 0 until o.length()) o.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { arr.put(it) } }
        content.put("otp", arr)
        publishPolicy(content)
    }

    /** Logowanie kodem jednorazowym: otwiera panel na tę sesję (bez zapamiętywania) i od razu unieważnia kod. */
    suspend fun adminLoginOtp(code: String): Boolean = stateLock.withLock {
        val raw = code.uppercase().filter { it.isLetterOrDigit() }
        if (raw.length < 12) return@withLock false
        val id = otpId(raw)
        val entry = policy.value?.optJSONArray("otp")?.let { o -> (0 until o.length()).mapNotNull { o.optJSONObject(it) }.firstOrNull { it.optString("id") == id } }
            ?: return@withLock false
        if (entry.optLong("exp") < System.currentTimeMillis()) return@withLock false
        val k = try {
            val b = B64.dec(entry.optString("b"))
            val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(raw.toCharArray(), b.copyOfRange(0, 16), 200_000, 256)).encoded
            Crypto.decrypt(key, b.copyOfRange(16, b.size))
        } catch (e: Exception) { return@withLock false }
        if (Crypto.publicKey(k).hex() != Admin.PUB) return@withLock false
        adminKey = k
        adminViaOtp = true
        adminUnlocked.value = true
        // kod jednorazowy — usuwamy go z listy
        val content = JSONObject(policy.value.toString())
        val arr = JSONArray()
        content.optJSONArray("otp")?.let { o -> for (i in 0 until o.length()) o.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { arr.put(it) } }
        content.put("otp", arr)
        publishPolicy(content)
        true
    }

    /** Hasło panelu zapamiętane przy logowaniu na tym telefonie (null = niedostępne). */
    fun adminSavedPassword(): String? = if (adminViaOtp) null else prefs.getString("admin_pw", null)?.let { DbCrypto.open(it) }?.let { String(it) }

    /** Czy mogę pisać w tej rozmowie (null = tak, inaczej powód: sankcja administratora albo wyciszenie w grupie). */
    fun canWrite(chatId: String, now: Long = System.currentTimeMillis()): String? {
        mySanctionText(now)?.let { return it }
        if (chatId.startsWith("g:")) {
            val g = db.group(chatId.removePrefix("g:")) ?: return null
            if (!g.isAdmin(myPub) && g.isMuted(myPub, now)) {
                val u = g.muted[myPub] ?: 0L
                return "Administrator grupy wyciszył Cię" + (if (u < Long.MAX_VALUE) " do " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("pl")).format(java.util.Date(u)) else "") + "."
            }
        }
        return null
    }

    /** Czy osoba ma znaczek weryfikacji (konto oficjalne zawsze ma). */
    fun isVerified(pk: String?): Boolean = pk != null && (Admin.isAdmin(pk) || db.contact(pk)?.verified == true)
    val myBadge: Boolean get() = prefs.getString("badge", null)?.let { verifyBadge(it, myPub) } == true

    /** Rozwija czaty (grupy → członkowie) na listę osób. */
    fun recipientsOf(chatIds: Collection<String>, extraPks: Collection<String> = emptyList()): List<String> =
        (chatIds.flatMap { id ->
            if (id.startsWith("g:")) db.group(id.removePrefix("g:"))?.members?.map { it.pk } ?: emptyList() else listOf(id.removePrefix("d:"))
        } + extraPks).filter { it.isPubKey() && it != myPub && it != Admin.PUB }.distinct()

    /**
     * Wiadomość albo aktualizacja z konta oficjalnego do wybranych osób. Nie trafia do historii nadawcy,
     * a odbiorcy nie mogą na nią odpowiedzieć. Plik aplikacji jest wysyłany raz, niezależnie od liczby osób.
     */
    suspend fun adminSend(recipients: List<String>, text: String, apk: File? = null, versionName: String? = null): Int = stateLock.withLock {
        if (adminKey == null) throw SendException("Panel administratora jest zablokowany")
        val to = recipients.filter { it.isPubKey() && it != myPub && it != Admin.PUB }.distinct()
        if (to.isEmpty()) throw SendException("Nie wybrano odbiorców")
        val id = "admin:" + newId()
        val payload = JSONObject().put("t", "msg").put("id", newId()).put("ts", Clock.now())
        if (apk != null) {
            val media = prepareAdminUpload(id, apk, "Szept-${versionName ?: "nowa"}.apk", "application/vnd.android.package-archive")
            payload.put("k", Kind.UPDATE).put("txt", versionName ?: "").put("m", media.toJson())
            if (text.isNotBlank()) payload.put("note", text.trim())
            db.addPending(id, payload.toString(), to)
            adminJobs.update { it + (id to "Aktualizacja ${versionName ?: ""} → ${to.size} os.") }
            kickUploads()
        } else {
            if (text.isBlank()) throw SendException("Pusta wiadomość")
            payload.put("k", Kind.TEXT).put("txt", text.trim().take(20_000))
            dispatch(payload, to, null, EXPIRE_SEC, asAdmin = true, sync = false)
        }
        to.size
    }

    /** Kopia pliku do wysłania + zadanie wgrania paczek (wspólne dla wszystkich odbiorców). */
    private fun prepareAdminUpload(id: String, src: File, name: String, mime: String): MediaMeta {
        val dest = File(ctx.cacheDir, "admin_${System.nanoTime()}.bin")
        src.copyTo(dest, overwrite = true)
        val key = Crypto.randomBytes(32)
        val size = dest.length()
        val chunks = ((size + CHUNK_RAW - 1) / CHUNK_RAW).toInt().coerceAtLeast(1)
        val media = MediaMeta(newId(), B64.enc(key), size, chunks, mime, sha256File(dest), name, null, 0, 0, 0)
        db.addUpload(Db.Upload(media.id, id, dest.absolutePath, B64.enc(key), chunks, Clock.nowSec(), JSONObject(), ""))
        return media
    }

    /** Postęp wysyłek administratora: id → procent wgranych paczek. */
    fun adminProgress(): Map<String, Int> = adminJobs.value.keys.associateWith { id ->
        val u = db.uploadForMsg(id) ?: return@associateWith 100
        val best = internetTargets().maxOfOrNull { u.done.optInt(it, 0) } ?: 0
        (best * 100 / u.chunks.coerceAtLeast(1)).coerceIn(0, 99)
    }

    /** Nadaje albo odbiera znaczek weryfikacji (podpisany kluczem administratora). */
    fun adminBadge(pk: String, on: Boolean) {
        if (adminKey == null || !pk.isPubKey()) return
        dispatch(JSONObject().put("t", "badge").put("pk", pk).put("on", on).put("ts", Clock.now()), listOf(pk), null, EXPIRE_SEC, asAdmin = true, sync = false)
        if (db.contact(pk) != null) db.setVerified(pk, on)
    }

    private fun handleBadge(inner: Event, p: JSONObject) {
        if (p.optString("pk") != myPub) return
        if (p.optBoolean("on")) prefs.edit().putString("badge", inner.toJson()).apply() else prefs.edit().remove("badge").apply()
        broadcastProfile()
        db.touchChat("x", 0, "", false)   // odśwież ekrany
    }

    private fun verifyBadge(json: String, pk: String): Boolean = try {
        val ev = Event.fromJson(json)
        val c = JSONObject(ev.content)
        ev.pubkey == Admin.PUB && ev.kind == INNER_KIND && ev.verify() && c.optString("t") == "badge" && c.optString("pk") == pk && c.optBoolean("on")
    } catch (e: Exception) { false }

    // ---- wymuszanie aktualizacji ----

    /** Polityka wersji od administratora: min (najstarsza dozwolona), v/code/m (najnowsza wersja do pobrania), msg. */
    val policy = MutableStateFlow(prefs.getString("policy", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } })
    val updateRequired: Boolean get() = (policy.value?.optInt("min") ?: 0) > appVersionCode

    /**
     * Ustala najstarszą dozwoloną wersję. Starsze aplikacje pokażą ekran „Musisz zaktualizować” z przyciskiem pobrania
     * ([apk] — plik nowej wersji, wysyłany raz dla wszystkich).
     */
    suspend fun adminPolicy(minCode: Int, msg: String, apk: File?, versionName: String, versionCode: Int) = stateLock.withLock {
        if (adminKey == null) throw SendException("Panel administratora jest zablokowany")
        val content = JSONObject().put("min", minCode).put("msg", msg.trim().take(500)).put("v", versionName).put("code", versionCode)
        policy.value?.optString("gk")?.takeIf { it.isNotEmpty() }?.let { content.put("gk", it) }
        // sankcje i kody jednorazowe zostają
        policy.value?.optJSONArray("ban")?.let { content.put("ban", it) }
        policy.value?.optJSONArray("otp")?.let { content.put("otp", it) }
        if (apk != null) {
            val id = "admin:pol:" + newId()
            val media = prepareAdminUpload(id, apk, "Szept-$versionName.apk", "application/vnd.android.package-archive")
            content.put("m", media.toJson())
            db.addPending(id, content.toString(), emptyList())
            adminJobs.update { it + (id to "Wymuszenie aktualizacji (od $versionName)") }
            kickUploads()
        } else publishPolicy(content)
    }

    /** Klucz KLIPY dla wszystkich (GIF-y) — rozsyłany razem z polityką wersji. */
    suspend fun adminSetGifKey(key: String) = stateLock.withLock {
        if (adminKey == null) throw SendException("Panel administratora jest zablokowany")
        val content = policy.value?.let { JSONObject(it.toString()) } ?: JSONObject().put("min", 0)
        if (key.isBlank()) content.remove("gk") else content.put("gk", key.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128))
        publishPolicy(content)
    }

    /** Klucz KLIPY w użyciu (telefon → administrator → wbudowany). */
    fun gifKey(): String = Gifs.klipyKey(ctx, policy.value?.optString("gk"))

    private fun publishPolicy(content: JSONObject) {
        val k = adminKey ?: return
        content.remove("n"); content.remove("v1")
        // każda nowa wersja musi mieć późniejszy czas niż poprzednia (inaczej zostałaby odrzucona)
        val ts = maxOf(Clock.nowSec(), prefs.getLong("policy_ts", 0) + 1)
        val ev = Event.create(k, Admin.POLICY_KIND, listOf(listOf("d", Admin.POLICY_D)), content.toString(), ts)
        db.addOutbox(ev.id, ev.toJson(), null)
        flushOutbox()
        handlePolicy(JSONObject(ev.toJson()))
    }

    private fun handlePolicy(o: JSONObject) {
        try {
            val ev = Event.fromJson(o)
            if (ev.pubkey != Admin.PUB || ev.kind != Admin.POLICY_KIND || ev.tag("d") != Admin.POLICY_D || !ev.verify()) return
            synchronized(this) {
                if (ev.createdAt <= prefs.getLong("policy_ts", 0)) return
                prefs.edit().putString("policy", ev.content).putLong("policy_ts", ev.createdAt).apply()
            }
            val c = JSONObject(ev.content)
            policy.value = c
            checkMySanction()
            // nowsza wersja do pobrania: pojawia się w czacie „Szept” z przyciskiem instalacji
            val code = c.optInt("code")
            val meta = c.optJSONObject("m")?.let { MediaMeta.fromNetwork(it) }
            if (meta != null && code > appVersionCode) {
                val id = "upd:$code"
                if (db.message(id) == null) {
                    val chatId = dmChatId(Admin.PUB)
                    db.upsertContact(Admin.PUB, Admin.NAME, false)
                    db.ensureChat(chatId, false, Admin.PUB, Admin.NAME)
                    val m = Message(id, chatId, Admin.PUB, Clock.now(), Kind.UPDATE, c.optString("v"), meta, null, St.DOWNLOADING, 0, 0, 0, 0, 0, false)
                    if (db.insertMessage(m)) {
                        db.touchChat(chatId, m.ts, Db.preview(m), true)
                        db.addDownload(id, meta); kickDownloads()
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "polityka wersji", e) }
    }

    /** Wiadomość z aktualizacją (najnowsza), jeśli jest w telefonie. */
    fun latestUpdateMessage(): Message? = db.latestUpdate()

    // ================= rozmowy głosowe i wideo =================

    private val callHost: CallManager.Host = object : CallManager.Host {
        override val myPub: String get() = this@Core.myPub
        override val deviceId: String get() = this@Core.deviceId
        override fun sendSignal(to: String, payload: JSONObject) = publishEphemeral(payload, listOf(to), relayCount = 8, ttlSec = 120)
        override fun sendSelfSignal(payload: JSONObject) {
            if (multiDevice) publishEphemeral(payload, listOf(this@Core.myPub), relayCount = 8, ttlSec = 120, allowSelf = true)
        }
        override fun callLog(peer: String, id: String, text: String, missed: Boolean) {
            val chatId = dmChatId(peer)
            db.ensureChat(chatId, false, peer, db.nameOf(peer))
            systemMessage(chatId, id, text)
            if (missed) notifyMessage(chatId, peer, "📞 Nieodebrane połączenie", false)
        }
        override fun canCall(pk: String): Boolean = pk.isPubKey() && pk != this@Core.myPub && db.contact(pk)?.blocked == false
        override fun iceServers(): List<IceServer> = callIceServers()
        override fun now(): Long = Clock.now()
        override fun onRing(call: CallManager.Call?) { notifier?.onCallRing(call, call?.let { db.nameOf(it.peer) } ?: "") }
        override fun busyElsewhere(): Boolean = groupCalls.state.value?.let { it.active && it.phase != GroupCallManager.Phase.RINGING } == true
    }

    /** Rozmowy 1:1 (WebRTC). Silnik ustawia aplikacja (na komputerze w testach — atrapa). */
    val calls: CallManager = CallManager(callHost, scope)

    private val groupCallHost: GroupCallManager.Host = object : GroupCallManager.Host {
        override val myPub: String get() = this@Core.myPub
        override val deviceId: String get() = this@Core.deviceId
        override fun sendSignal(to: List<String>, payload: JSONObject) {
            publishEphemeral(payload, to, relayCount = 8, ttlSec = 120)
        }
        override fun sendSelfSignal(payload: JSONObject) {
            if (multiDevice) publishEphemeral(payload, listOf(this@Core.myPub), relayCount = 8, ttlSec = 120, allowSelf = true)
        }
        override fun groupMembers(gid: String): List<String>? =
            db.group(gid)?.takeIf { it.state == GroupState.ACTIVE && it.isMember(this@Core.myPub) }?.members?.map { it.pk }?.filter { it != this@Core.myPub }
        override fun groupName(gid: String): String = db.group(gid)?.name ?: "Grupa"
        override fun callLog(gid: String, peer: String?, id: String, text: String) {
            if (gid.startsWith("r:")) {
                val pk = peer ?: return
                val chatId = dmChatId(pk)
                db.ensureChat(chatId, false, pk, db.nameOf(pk))
                systemMessage(chatId, id, text)
                if (text.endsWith("nieodebrana")) notifyMessage(chatId, pk, "📞 Nieodebrane połączenie", false)
            } else systemMessage(groupChatId(gid), id, text)
        }
        override fun canCall(pk: String): Boolean = callHost.canCall(pk)
        override fun nameOf(pk: String): String = db.nameOf(pk)
        override fun iceServers(): List<IceServer> = callIceServers()
        override fun now(): Long = Clock.now()
        override fun onRing(ring: GroupCallManager.Ring?) { notifier?.onGroupRing(ring, ring?.let { db.nameOf(it.from) } ?: "") }
        override fun busyElsewhere(): Boolean = calls.state.value?.active == true
    }

    /**
     * Dzwoni do osoby (rozmowa prywatna). Działa na zakresie Core — nie przerywa się, gdy ekran czatu znika
     * (wcześniej przygotowanie połączenia było anulowane razem z ekranem i rozmówca nie dostawał dzwonka).
     */
    fun startCall(peer: String, video: Boolean, onError: (String) -> Unit) {
        scope.launch {
            try { groupCalls.startRoom(listOf(peer), video) } catch (e: IllegalStateException) { onError(e.message ?: "Nie udało się zadzwonić") }
        }
    }

    /** Rozmowy grupowe (każdy z każdym). */
    val groupCalls: GroupCallManager = GroupCallManager(groupCallHost, scope)

    val turnUrl: String get() = prefs.getString("turn_url", "") ?: ""
    val turnUser: String get() = prefs.getString("turn_user", "") ?: ""
    val turnPass: String get() = prefs.getString("turn_pass", "") ?: ""

    fun setTurn(url: String, user: String, pass: String) =
        prefs.edit().putString("turn_url", url.trim()).putString("turn_user", user.trim()).putString("turn_pass", pass).apply()

    /** STUN (sprawdzenie własnego adresu) + opcjonalny własny serwer TURN (pośredniczy, gdy sieć blokuje połączenia bezpośrednie). */
    fun callIceServers(): List<IceServer> {
        val list = mutableListOf(IceServer("stun:stun.l.google.com:19302"), IceServer("stun:stun.cloudflare.com:3478"))
        val t = turnUrl
        if (t.startsWith("turn:") || t.startsWith("turns:")) list += IceServer(t, turnUser, turnPass)
        return list
    }

    // ================= kilka urządzeń i rotacja kluczy =================

    /** Czy wysyłać kopie wiadomości do moich innych urządzeń (włącza się samo, gdy pojawi się drugie urządzenie). */
    val multiDevice: Boolean get() = prefs.getBoolean("multi", false)

    data class Device(val id: String, val name: String, val seen: Long, val me: Boolean)

    /** Moje urządzenia (to i inne, z którymi synchronizuję). */
    fun devices(): List<Device> {
        val o = try { JSONObject(prefs.getString("devices", "{}")!!) } catch (e: Exception) { JSONObject() }
        val others = o.keys().asSequence().filter { it != deviceId }.map { k ->
            val d = o.optJSONObject(k) ?: JSONObject()
            Device(k, d.optString("n").ifBlank { "Inne urządzenie" }, d.optLong("s"), false)
        }.sortedByDescending { it.seen }.toList()
        return listOf(Device(deviceId, deviceName(), System.currentTimeMillis(), true)) + others
    }

    private fun deviceName(): String = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim()

    @Synchronized
    private fun noteDevice(dev: String, name: String?): Boolean {
        val o = try { JSONObject(prefs.getString("devices", "{}")!!) } catch (e: Exception) { JSONObject() }
        val isNew = !o.has(dev)
        val d = o.optJSONObject(dev) ?: JSONObject()
        if (!name.isNullOrBlank()) d.put("n", name.take(60))
        d.put("s", System.currentTimeMillis())
        o.put(dev, d)
        prefs.edit().putString("devices", o.toString()).putBoolean("multi", true).apply()
        return isNew
    }

    /** Włącza/wyłącza synchronizację z innymi urządzeniami. */
    fun setMultiDevice(on: Boolean) {
        prefs.edit().putBoolean("multi", on).apply()
        if (on) announceDevice(ask = true)
    }

    /** Zapomina urządzenie (np. sprzedany telefon). Wiadomości przestaną być do niego szyfrowane. */
    fun forgetDevice(dev: String) {
        val o = try { JSONObject(prefs.getString("devices", "{}")!!) } catch (e: Exception) { JSONObject() }
        o.remove(dev)
        prefs.edit().putString("devices", o.toString()).apply()
        db.setPrekey(myPub, dev, "", Long.MAX_VALUE - 1)
    }

    /** Ogłoszenie tego urządzenia moim innym urządzeniom ([ask] = poproś o ich dane: kontakty, grupy). */
    private fun announceDevice(ask: Boolean) {
        val p = JSONObject().put("t", "sync").put("c", "dev").put("name", deviceName()).put("ts", Clock.now())
        if (ask) p.put("q", 1)
        currentPrekey()?.let { p.put("k", it.optString("pub")).put("kts", it.optLong("created")) }
        dispatchSelf(p)
    }

    /** Przy pierwszym kontakcie z nowym urządzeniem wysyłamy mu kontakty, grupy i ustawienia. */
    private fun sendSnapshotTo() {
        val contacts = JSONArray()
        db.contacts(false).filter { it.pk != myPub }.take(1000).forEach { c ->
            contacts.put(JSONObject().put("pk", c.pk).put("n", c.name).put("c", c.isContact).put("b", c.blocked))
        }
        dispatchSelf(JSONObject().put("t", "sync").put("c", "contacts").put("list", contacts).put("fs", forwardSecrecy))
        db.groups().filter { it.state == GroupState.ACTIVE || it.state == GroupState.PENDING }.forEach { g ->
            dispatchSelf(groupPayload(g).put("t", "sync").put("c", "group").put("st", g.state))
        }
    }

    // ---- rotacja kluczy (forward secrecy) ----

    /** Rotacja kluczy: koperty szyfrowane do krótkotrwałych kluczy urządzenia, które telefon potem kasuje. */
    val forwardSecrecy: Boolean get() = prefs.getBoolean("fs", false)
    @Volatile private var prekeyCache: List<ByteArray>? = null

    private fun myPrekeys(): JSONArray = try { JSONArray(prefs.getString("prekeys", "[]")) } catch (e: Exception) { JSONArray() }

    private fun currentPrekey(): JSONObject? {
        val a = myPrekeys()
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("sup") == 0L }
    }

    /** Wszystkie jeszcze trzymane klucze prywatne urządzenia (bieżący najpierw). */
    private fun prekeyPrivs(): List<ByteArray> = prekeyCache ?: run {
        val a = myPrekeys()
        (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optLong("sup") }.mapNotNull { o ->
            o.optString("priv").takeIf { it.isHex64() }?.unhex()
        }.also { prekeyCache = it }
    }

    /** Data utworzenia bieżącego klucza urządzenia (0 = brak). */
    fun prekeyCreated(): Long = currentPrekey()?.optLong("created") ?: 0L

    fun setForwardSecrecy(on: Boolean, fromSync: Boolean = false) {
        if (on == forwardSecrecy) return
        prefs.edit().putBoolean("fs", on).apply()
        if (on) rotatePrekey(force = true)
        else {
            // obecny klucz zostaje jeszcze 35 dni (koperty w drodze), znajomi wracają do zwykłego klucza
            supersedeCurrent()
            announcePrekey("")
        }
        if (!fromSync) dispatchSelf(JSONObject().put("t", "sync").put("c", "fs").put("on", on))
    }

    @Synchronized
    private fun supersedeCurrent() {
        val a = myPrekeys()
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optLong("sup") == 0L) o.put("sup", now) }
        prefs.edit().putString("prekeys", a.toString()).apply()
        prekeyCache = null
    }

    /** Nowy klucz urządzenia (co 7 dni albo na żądanie) i kasowanie starych. */
    @Synchronized
    fun rotatePrekey(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val a = myPrekeys()
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val sup = o.optLong("sup")
            if (sup == 0L || now - sup < PREKEY_KEEP_MS) keep.put(o)
        }
        val changedOld = keep.length() != a.length()
        if (!forwardSecrecy) {
            if (changedOld) { prefs.edit().putString("prekeys", keep.toString()).apply(); prekeyCache = null }
            return
        }
        val cur = (0 until keep.length()).map { keep.getJSONObject(it) }.firstOrNull { it.optLong("sup") == 0L }
        if (!force && cur != null && now - cur.optLong("created") < PREKEY_ROTATE_MS) {
            if (changedOld) { prefs.edit().putString("prekeys", keep.toString()).apply(); prekeyCache = null }
            return
        }
        cur?.put("sup", now)
        val priv = Crypto.newPrivateKey()
        val pub = Crypto.publicKey(priv).hex()
        keep.put(JSONObject().put("priv", priv.hex()).put("pub", pub).put("created", now).put("sup", 0))
        prefs.edit().putString("prekeys", keep.toString()).apply()
        prekeyCache = null
        announcePrekey(pub)
    }

    /** Ogłasza klucz urządzenia znajomym i moim innym urządzeniom (pusty = koniec rotacji). */
    private fun announcePrekey(pub: String) {
        val targets = (db.contacts(false).filter { !it.blocked }.map { it.pk } +
            db.groups().filter { it.state == GroupState.ACTIVE }.flatMap { g -> g.members.map { it.pk } }).distinct().filter { it != myPub }
        val p = JSONObject().put("t", "pk").put("k", pub).put("ts", Clock.now())
        dispatch(p, targets, null, EXPIRE_SEC, sync = true)
    }

    private fun handlePrekey(from: String, p: JSONObject) {
        val dev = p.optString("dv").take(32)
        val k = p.optString("k")
        if (dev.isEmpty() || (k.isNotEmpty() && !k.isPubKey())) return
        if (from == myPub && dev == deviceId) return
        db.setPrekey(from, dev, k, p.optLong("ts"))
    }

    /** Odszyfrowanie koperty: klucze urządzenia (rotacja), a na końcu stały klucz konta. */
    private fun decryptWrap(wrap: Event): ByteArray? {
        val data = try { B64.dec(wrap.content) } catch (e: Exception) { return null }
        val eph = try { wrap.pubkey.unhex() } catch (e: Exception) { return null }
        for (k in prekeyPrivs() + listOf(myPriv)) {
            try { return Crypto.decrypt(Crypto.conversationKey(k, eph), data) } catch (_: Exception) {}
        }
        return null
    }

    // ---- kopie z moich innych urządzeń ----

    /** Czat, którego dotyczy moja kopia: grupa albo rozmowa z „to”. */
    private fun selfChat(p: JSONObject, gid: String?): String? {
        if (gid != null) return if (db.group(gid) != null) groupChatId(gid) else null
        val to = p.optString("to")
        return if (to.isPubKey() && to != myPub) dmChatId(to) else null
    }

    private fun handleSelf(type: String, p: JSONObject, gid: String?, dev: String) {
        val isNew = noteDevice(dev, if (type == "sync" && p.optString("c") == "dev") p.optString("name") else null)
        when (type) {
            "msg" -> handleSelfMsg(p, gid)
            "del" -> {
                val ids = p.optJSONArray("ids") ?: return
                for (i in 0 until ids.length()) {
                    val id = ids.optString(i)
                    val m = db.message(id)
                    if (m == null) db.addTombstone(id, myPub) else deleteLocal(m)
                }
            }
            "react" -> {
                val chatId = selfChat(p, gid) ?: return
                val m = db.message(p.optString("id"))?.takeIf { it.chat == chatId } ?: return
                db.setReaction(m.id, myPub, p.optString("e").take(16), p.optLong("ts", Clock.now()))
            }
            "edit" -> {
                val m = db.message(p.optString("id")) ?: return
                if (!m.mine || m.kind !in EDITABLE) return
                if (db.editMessage(m.id, p.optString("txt").take(20_000), p.optLong("ets", Clock.now()))) db.refreshChatPreview(m.chat)
            }
            "vote" -> {
                val chatId = selfChat(p, gid) ?: return
                val m = db.message(p.optString("id"))?.takeIf { it.chat == chatId && it.kind == Kind.POLL } ?: return
                val poll = Poll.parse(m.body) ?: return
                val arr = p.optJSONArray("sel") ?: JSONArray()
                val sel = List(arr.length()) { arr.optInt(it, -1) }.distinct().filter { it in poll.opts.indices }
                db.setVote(m.id, myPub, sel, p.optLong("ts", Clock.now()))
            }
            "pin" -> {
                val chatId = selfChat(p, gid) ?: return
                val m = db.message(p.optString("id"))?.takeIf { it.chat == chatId } ?: return
                db.setPin(chatId, m.id, myPub, p.optBoolean("on"), p.optLong("ts", Clock.now()))
            }
            "gupd" -> handleGroupUpdate(myPub, p)
            "gleave" -> {
                val g = db.group(p.optString("g")) ?: return
                if (g.state == GroupState.LEFT) return
                db.saveGroup(g.copy(state = GroupState.LEFT))
                systemMessage(groupChatId(g.id), "sys:${g.id}:left:${p.optLong("ts", Clock.now())}", "Opuściłeś(-aś) grupę (na innym urządzeniu).")
            }
            "prof" -> {
                val to = p.optString("to")
                if (p.optBoolean("add") && to.isPubKey()) {
                    db.upsertContact(to, null, true)
                    db.ensureChat(dmChatId(to), false, to, db.nameOf(to))
                }
                val pts = p.optLong("pts")
                if (pts > prefs.getLong("prof_ts", 0)) {
                    prefs.edit().putString("name", p.optString("n").ifBlank { myName }.take(60)).putString("status", p.optString("st").take(120))
                        .putString("bio", p.optString("bio").take(500)).putLong("prof_ts", pts).apply()
                    p.optString("av").let { if (it.isEmpty()) avatarFile.delete() else runCatching { avatarFile.writeBytes(B64.dec(it)) } }
                    p.optString("bn").let { if (it.isEmpty()) bannerFile.delete() else runCatching { bannerFile.writeBytes(B64.dec(it)) } }
                }
            }
            "loc" -> handleLoc(myPub, p)
            "story" -> handleSelfStory(p)
            "sdel" -> handleStoryDel(myPub, p)
            "pk" -> handlePrekey(myPub, p)
            "sync" -> handleSync(p, dev, isNew)
            "call" -> calls.onSelfSignal(p)
            "gcall" -> groupCalls.onSelfSignal(p)
        }
    }

    private fun handleSync(p: JSONObject, dev: String, isNew: Boolean) {
        when (p.optString("c")) {
            "dev" -> {
                val k = p.optString("k")
                if (k.isPubKey()) db.setPrekey(myPub, dev, k, p.optLong("kts"))
                // nowe urządzenie: przedstaw się i wyślij mu kontakty oraz grupy
                if (p.optInt("q") == 1 || isNew) {
                    announceDevice(ask = false)
                    if (p.optInt("q") == 1) sendSnapshotTo()
                }
            }
            "read" -> {
                val chatId = p.optString("chat")
                if (db.chat(chatId) == null) return
                db.markChatRead(chatId)
                db.unreadIncoming(chatId).forEach { db.markOpenedFlag(it.id) }
                notifier?.cancel(chatId)
            }
            "contacts" -> {
                val list = p.optJSONArray("list") ?: return
                for (i in 0 until list.length()) {
                    val c = list.optJSONObject(i) ?: continue
                    val pk = c.optString("pk")
                    if (!pk.isPubKey() || pk == myPub) continue
                    db.upsertContact(pk, c.optString("n").ifBlank { null }, c.optBoolean("c"))
                    if (c.optBoolean("b")) db.setBlocked(pk, true)
                    if (c.optBoolean("c")) db.ensureChat(dmChatId(pk), false, pk, db.nameOf(pk))
                }
                if (p.optBoolean("fs") && !forwardSecrecy) setForwardSecrecy(true, fromSync = true)
            }
            "group" -> {
                val gid = p.optString("g")
                val old = db.group(gid)
                if (old != null && old.ver >= p.optInt("ver")) return
                val st = p.optInt("st", GroupState.ACTIVE)
                p.put("t", "gupd")
                if (applyGroupUpdate(myPub, p, trusted = true) && st == GroupState.PENDING) db.group(gid)?.let { db.saveGroup(it.copy(state = GroupState.PENDING)) }
            }
            "fs" -> setForwardSecrecy(p.optBoolean("on"), fromSync = true)
            "mute" -> p.optString("chat").let { if (db.chat(it) != null) db.setMuted(it, p.optBoolean("on")) }
        }
    }

    /** Wiadomość wysłana z mojego innego urządzenia — zapisujemy jako moją. */
    private fun handleSelfMsg(p: JSONObject, gid: String?) {
        val id = p.optString("id")
        if (id.isEmpty() || db.message(id) != null || db.tombstone(id) != null) return
        val chatId = selfChat(p, gid) ?: return
        if (gid != null) db.ensureChat(chatId, true, null, db.group(gid)?.name ?: p.optString("gn"))
        else { val to = chatId.removePrefix("d:"); db.ensureChat(chatId, false, to, db.nameOf(to)) }
        val ts = p.optLong("ts", Clock.now())
        val xmode = p.optInt("x", XMode.OFF)
        val ttl = p.optInt("ttl", 10).coerceIn(1, 7 * 86400)
        val now = System.currentTimeMillis()
        var expires = 0L
        var media = p.optJSONObject("m")?.let { MediaMeta.fromNetwork(it) }
        var body = p.optString("txt").take(20_000)
        when (xmode) {
            XMode.AFTER_SEND -> {
                val left = ts + ttl * 1000L - Clock.now()
                if (left <= 0) { db.addTombstone(id, myPub); return }
                expires = now + left
            }
            XMode.AFTER_OPEN -> {
                // jak na urządzeniu, z którego wysłano: zostaje tylko informacja o wysłaniu
                media = null; body = ""
                expires = now + max(ttl.toLong(), 3600L) * 1000L
            }
        }
        val kind = p.optString("k", Kind.TEXT)
        val m = Message(
            id, chatId, myPub, ts, kind, body, media, null,
            if (media != null) St.DOWNLOADING else St.SENT, xmode, ttl, ts, expires, 0, true,
            p.optJSONObject("re")?.let { Reply.fromJson(it) }, 0, Extra.from(p),
        )
        if (!db.insertMessage(m)) return
        db.touchChat(chatId, ts, Db.preview(m), false)
        // pisząc na innym urządzeniu, przeczytałem tę rozmowę
        db.markChatRead(chatId)
        notifier?.cancel(chatId)
        if (media != null) { db.addDownload(id, media); kickDownloads() }
        if (expires > 0) expiryKick.trySend(Unit)
    }

    private fun handleSelfStory(p: JSONObject) {
        val id = p.optString("id").take(64)
        if (id.isEmpty() || db.story(id) != null || db.tombstone("story:$id") != null) return
        val ts = p.optLong("ts", Clock.now())
        val age = Clock.now() - ts
        if (age > STORY_MS) return
        val kind = if (p.optString("k") == Kind.IMAGE) Kind.IMAGE else Kind.TEXT
        val media = if (kind == Kind.IMAGE) (p.optJSONObject("m")?.let { MediaMeta.fromNetwork(it) } ?: return) else null
        val s = Story(id, myPub, ts, kind, p.optString("txt").take(700), media, null, p.optInt("bg"), true,
            System.currentTimeMillis() + (STORY_MS - max(age, 0)))
        if (!db.insertStory(s)) return
        if (media != null) { db.addDownload("story:$id", media); kickDownloads() }
    }

    // ================= odbiór =================

    private suspend fun processIncoming() {
        for (ev in incoming) {
            try {
                val id = ev.optString("id")
                if (id.isEmpty() || db.isSeen(id)) continue
                var transient = false
                try {
                    stateLock.withLock { processWrap(Event.fromJson(ev)) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // chwilowy błąd bazy/dysku — koperta przyjdzie jeszcze raz i wtedy ją przetworzymy
                    transient = e is android.database.SQLException || e is java.io.IOException
                    Log.w(TAG, "błąd przetwarzania koperty", e)
                }
                if (!transient) db.markSeen(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CrashLog.record("Odbiór koperty", e)
            }
        }
    }

    private fun processWrap(wrap: Event) {
        if (wrap.kind != WRAP_KIND || wrap.tag("p") != myPub) return
        val plain = decryptWrap(wrap) ?: return
        val inner = Event.fromJson(String(plain, Charsets.UTF_8))
        if (inner.kind != INNER_KIND || !inner.verify()) return
        val from = inner.pubkey
        val p = JSONObject(inner.content)
        val name = if (Admin.isAdmin(from)) Admin.NAME else Admin.cleanName(p.optString("n").take(60))
        val gid = p.optString("g").ifEmpty { null }
        val type = p.optString("t")
        if (Admin.isAdmin(from)) {
            if (type == "badge") { handleBadge(inner, p); return }
            // oficjalne konto: tylko wiadomości i aktualizacje, ta sama treść do wielu osób (bez pola „to”)
            if (type != "msg" || gid != null) return
            db.upsertContact(from, Admin.NAME, false)
            handleMsg(from, p, null)
            return
        }
        if (from == myPub) {
            // kopia z mojego innego urządzenia (własne echo pomijamy)
            val dv = p.optString("dv")
            if (dv.isEmpty() || dv == deviceId) return
            handleSelf(type, p, gid, dv)
            return
        }
        if (type == "pk") { handlePrekey(from, p); return }
        if (gid == null) {
            if (p.optString("to") != myPub && type == "msg") return
            if (p.has("to") && p.optString("to") != myPub) return
            if (db.contact(from)?.blocked == true) return
        }
        // moderacja: wyciszony przez administratora nie pisze, zablokowany/usunięty — nic do nas nie dociera
        sanctionOf(from)?.let { s -> if (s.mode != "mute" || type in MUTED_TYPES) return }
        // wyciszony w grupie przez jej administratora
        if (gid != null && type in MUTED_TYPES) db.group(gid)?.let { g -> if (!g.isAdmin(from) && g.isMuted(from, System.currentTimeMillis())) return }
        if (name.isNotBlank() && type != "typ" && type != "pres") db.upsertContact(from, name, false)
        when (type) {
            "msg" -> handleMsg(from, p, gid)
            "del" -> handleDel(from, p)
            "rcpt" -> handleReceipt(from, p)
            "gupd" -> handleGroupUpdate(from, p)
            "gjoin" -> handleJoin(from, p)
            "gleave" -> handleLeave(from, p)
            "gdeny" -> handleDeny(from, p)
            "prof" -> handleProfile(from, p)
            "react" -> handleReact(from, p, gid)
            "edit" -> handleEdit(from, p)
            "vote" -> handleVote(from, p, gid)
            "typ" -> handleTyping(from, p, gid)
            "pin" -> handlePin(from, p, gid)
            "shot" -> handleShot(from, p, gid)
            "loc" -> handleLoc(from, p)
            "story" -> handleStory(from, p)
            "sseen" -> handleStorySeen(from, p)
            "sdel" -> handleStoryDel(from, p)
            "call" -> calls.onSignal(from, p)
            "gcall" -> groupCalls.onSignal(from, p)
            "pres" -> handlePresence(from, p)
        }
    }

    /** Czat, do którego należy zdarzenie od [from]; null, gdy nadawca nie jest uczestnikiem. */
    private fun chatOf(from: String, gid: String?): String? {
        if (gid == null) return dmChatId(from)
        val g = db.group(gid) ?: return null
        if (!g.isMember(from) || from in g.removed) return null
        return groupChatId(gid)
    }

    /** Adresuje ładunek do czatu: grupa („g”) albo osoba („to”). */
    private fun target(p: JSONObject, chatId: String): JSONObject {
        if (chatId.startsWith("g:")) p.put("g", chatId.removePrefix("g:")) else p.put("to", chatId.removePrefix("d:"))
        return p
    }

    private fun handleMsg(from: String, p: JSONObject, gid: String?) {
        val id = p.optString("id")
        if (id.isEmpty() || db.message(id) != null || db.tombstone(id) != null) return
        val chatId: String
        if (gid != null) {
            var g = db.group(gid)
            if (g == null) {
                g = Group(gid, p.optString("gn").ifEmpty { "Grupa" }, 0, emptyList(), listOf(Member(from, db.nameOf(from)), Member(myPub, myName)), "", false, GroupState.ACTIVE)
                db.saveGroup(g)
            }
            if (g.state != GroupState.ACTIVE && g.state != GroupState.PENDING) return
            if (g.adminsOnly && !g.isAdmin(from)) return
            if (from in g.removed) return
            chatId = groupChatId(gid)
            db.ensureChat(chatId, true, null, g.name)
        } else {
            chatId = dmChatId(from)
            db.ensureChat(chatId, false, from, db.nameOf(from))
        }
        val ts = p.optLong("ts", Clock.now())
        val xmode = p.optInt("x", XMode.OFF)
        val ttl = p.optInt("ttl", 10).coerceIn(1, 7 * 86400)
        val now = System.currentTimeMillis()
        var expires = 0L
        if (xmode == XMode.AFTER_SEND) {
            val age = Clock.now() - ts
            if (age > ttl * 1000L + 30_000) { db.addTombstone(id, from); return }
            expires = now + max(ttl * 1000L - max(age, 0), min(ttl * 1000L, 5_000))
        }
        val media = p.optJSONObject("m")?.let { MediaMeta.fromNetwork(it) }
        val kind = p.optString("k", Kind.TEXT)
        // plik aplikacji wolno przysłać tylko z oficjalnego konta (inaczej ktoś mógłby podsunąć fałszywą „aktualizację”)
        if (kind == Kind.UPDATE && !Admin.isAdmin(from)) return
        val ex = Extra.from(p)
        val m = Message(
            id, chatId, from, ts, kind, p.optString("txt").take(20_000), media, null,
            if (media != null) St.DOWNLOADING else St.RECEIVED, xmode, ttl, 0, expires, 0, false,
            p.optJSONObject("re")?.let { Reply.fromJson(it) }, 0, ex,
        )
        if (!db.insertMessage(m)) return
        clearTyping(chatId, from)
        val visible = activeChat == chatId && appVisible
        db.touchChat(chatId, ts, Db.preview(m), !visible)
        if (media != null) { db.addDownload(id, media); kickDownloads() }
        if (expires > 0) expiryKick.trySend(Unit)
        val mentioned = gid != null && (myPub in ex.mentions || ("all" in ex.mentions && db.group(gid)?.isAdmin(from) == true))
        if (!visible) notifyMessage(chatId, from, Db.preview(m), gid != null, mentioned)
        val readNow = visible && xmode != XMode.AFTER_OPEN
        if (readNow) db.markOpenedFlag(id)
        if (readReceipts && !Admin.isAdmin(from)) {
            if (gid == null) queueReceipt(from, if (readNow) "r" else "d", id)
            else if (readNow) queueReceipt(from, "r", id, gid)
        }
    }

    // ================= powiadomienia =================

    /** Ile szczegółów pokazywać w powiadomieniach (NOTIF_*). */
    val notifPrivacy: Int get() = prefs.getInt("notif_privacy", NOTIF_FULL)
    fun setNotifPrivacy(level: Int) = prefs.edit().putInt("notif_privacy", level).apply()

    /** Powiadomienie z uwzględnieniem wyciszenia, ukrytych czatów, prywatności treści i wzmianek @. */
    private fun notifyMessage(chatId: String, from: String, text: String, isGroup: Boolean, mentioned: Boolean = false) {
        val n = notifier ?: return
        val chat = db.chat(chatId) ?: return
        if (chat.muted && !mentioned) return
        if (chat.hidden || notifPrivacy == NOTIF_NONE) { n.onMessage(chatId, "Szept", "Szept", "Nowa wiadomość", false); return }
        val sender = db.nameOf(from)
        val body = when {
            notifPrivacy == NOTIF_SENDER -> if (mentioned) "Wspomina o Tobie" else "Nowa wiadomość"
            mentioned -> "@ $text"
            else -> text
        }
        n.onMessage(chatId, chat.name, sender, body, isGroup)
    }

    private fun canDeleteRemote(m: Message, requester: String): Boolean {
        if (m.sender == requester) return true
        if (m.chat == dmChatId(requester)) return true
        if (m.chat.startsWith("g:")) return db.group(m.chat.removePrefix("g:"))?.isAdmin(requester) == true
        return false
    }

    private fun handleDel(from: String, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            val m = db.message(id)
            if (m == null) { db.addTombstone(id, from); continue }
            if (canDeleteRemote(m, from)) deleteLocal(m)
        }
    }

    private fun handleReceipt(from: String, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        val st = when (p.optString("s")) { "d" -> St.DELIVERED; "r" -> St.READ; "o" -> St.OPENED; else -> return }
        val gid = p.optString("g").ifEmpty { null }
        if (gid != null) { handleGroupReceipt(from, gid, ids, st); return }
        for (i in 0 until ids.length()) {
            val m = db.message(ids.getString(i)) ?: continue
            if (!m.mine || m.chat != dmChatId(from)) continue
            db.updateStatus(m.id, st)
            if (st == St.OPENED && m.xmode == XMode.AFTER_OPEN) {
                db.setExpires(m.id, System.currentTimeMillis() + m.ttl * 1000L)
                expiryKick.trySend(Unit)
            }
        }
    }

    /** Potwierdzenia w grupie: zapisujemy, kto przeczytał; ✓✓ na niebiesko, gdy przeczytali wszyscy. */
    private fun handleGroupReceipt(from: String, gid: String, ids: JSONArray, st: Int) {
        val g = db.group(gid) ?: return
        if (!g.isMember(from)) return
        val now = Clock.now()
        for (i in 0 until ids.length()) {
            val m = db.message(ids.getString(i)) ?: continue
            if (!m.mine || m.chat != groupChatId(gid)) continue
            db.setRead(m.id, from, st, now)
            val others = g.members.map { it.pk }.filter { it != myPub }
            val reads = db.reads(m.id)
            when {
                others.isNotEmpty() && others.all { (reads[it]?.first ?: 0) >= St.READ } -> db.updateStatus(m.id, St.READ)
                others.isNotEmpty() && others.all { (reads[it]?.first ?: 0) >= St.DELIVERED } -> db.updateStatus(m.id, St.DELIVERED)
            }
        }
    }

    private fun handleProfile(from: String, p: JSONObject) {
        val existing = db.contact(from)
        db.saveProfile(
            from, Admin.cleanName(p.optString("n")).ifBlank { null }, p.optString("st"), p.optString("bio"),
            p.optString("av").ifEmpty { null }, p.optString("bn").ifEmpty { null }, p.optLong("pts"),
        )
        // znaczek weryfikacji: ważny tylko z podpisem administratora dla tej osoby
        val vb = p.optString("vb")
        val verified = vb.isNotEmpty() && verifyBadge(vb, from)
        if (existing?.verified != verified) { if (existing == null) db.upsertContact(from, null, false); db.setVerified(from, verified) }
        // ktoś dodał mnie do kontaktów → zaproponuj rozmowę
        if (p.optBoolean("add") && existing?.isContact != true) {
            db.upsertContact(from, p.optString("n").ifBlank { null }, true)
            val chatId = dmChatId(from)
            db.ensureChat(chatId, false, from, db.nameOf(from))
            systemMessage(chatId, "sys:prof:$from", "${db.nameOf(from)} dodał(a) Cię do kontaktów. Możecie pisać.")
            notifyMessage(chatId, from, "Nowy kontakt", false)
        }
    }

    fun setAlias(pk: String, alias: String) = db.setAlias(pk, alias)

    private fun systemMessage(chatId: String, id: String, text: String) {
        val now = Clock.now()
        if (db.insertMessage(Message(id, chatId, myPub, now, Kind.SYSTEM, text, null, null, St.RECEIVED, 0, 0, 1, 0, 0, false))) {
            db.touchChat(chatId, now, text, false)
        }
    }

    // ================= potwierdzenia =================

    private data class RKey(val peer: String, val status: String, val gid: String?)
    private val receiptQueue = HashMap<RKey, MutableSet<String>>()
    private var receiptJob: Job? = null

    /** Potwierdzenia zbieramy przez chwilę i wysyłamy paczką (jedna koperta na osobę i stan). */
    private fun queueReceipt(peer: String, status: String, id: String, gid: String? = null) {
        synchronized(receiptQueue) {
            receiptQueue.getOrPut(RKey(peer, status, gid)) { mutableSetOf() }.add(id)
            if (receiptJob?.isActive != true) receiptJob = scope.launch {
                delay(1500)
                val snapshot = synchronized(receiptQueue) { val c = receiptQueue.toMap(); receiptQueue.clear(); c }
                snapshot.forEach { (k, ids) ->
                    val p = JSONObject().put("t", "rcpt").put("s", k.status).put("ids", JSONArray(ids.toList()))
                    if (k.gid != null) p.put("g", k.gid)
                    dispatch(p, listOf(k.peer), null, 3 * 86400, sync = false)
                }
            }
        }
    }

    /** Wywoływane, gdy użytkownik ogląda czat. */
    fun markChatRead(chatId: String) {
        scope.launch {
            db.markChatRead(chatId)
            notifier?.cancel(chatId)
            val unread = db.unreadIncoming(chatId)
            unread.forEach { db.markOpenedFlag(it.id) }
            if (unread.isNotEmpty() && multiDevice) dispatchSelf(JSONObject().put("t", "sync").put("c", "read").put("chat", chatId), 7 * 86400)
            if (readReceipts) {
                if (chatId.startsWith("d:")) unread.forEach { queueReceipt(chatId.removePrefix("d:"), "r", it.id) }
                else unread.forEach { queueReceipt(it.sender, "r", it.id, chatId.removePrefix("g:")) }
            }
        }
    }

    // ================= znikające wiadomości =================

    private suspend fun expiryLoop() {
        while (true) {
            // błąd w jednym obiegu nie może zatrzymać usuwania wiadomości czasowych
            val next = try { purgeExpired(); db.nextExpiry() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                CrashLog.record("Wiadomości czasowe", e); 0L
            }
            val wait = if (next == 0L) 60_000L else (next - System.currentTimeMillis()).coerceIn(100L, 60_000L)
            withTimeoutOrNull(wait) { expiryKick.receive() }
        }
    }

    private fun purgeExpired() {
        val list = db.expiredMessages(System.currentTimeMillis())
        list.forEach { deleteLocal(it) }
    }

    /** Otwiera wiadomość jednorazową — od teraz liczy się czas. */
    fun openViewOnce(msgId: String) {
        scope.launch {
            val m = db.message(msgId) ?: return@launch
            if (m.mine || m.opened != 0L || m.xmode != XMode.AFTER_OPEN) return@launch
            val now = System.currentTimeMillis()
            db.setOpened(msgId, now, now + m.ttl * 1000L)
            expiryKick.trySend(Unit)
            if (m.chat.startsWith("d:")) queueReceipt(m.sender, "o", msgId)
        }
    }

    fun setChatTimer(chatId: String, xmode: Int, ttl: Int) = scope.launch { db.setChatTimer(chatId, xmode, ttl) }

    // ================= wysyłanie wiadomości =================

    class SendException(msg: String) : Exception(msg)

    private fun recipientsFor(chat: Chat): Pair<List<String>, Group?> {
        if (!chat.isGroup) return listOf(chat.peer!!) to null
        val g = db.group(chat.id.removePrefix("g:")) ?: throw SendException("Nieznana grupa")
        when (g.state) {
            GroupState.PENDING -> throw SendException("Czekasz na akceptację administratora")
            GroupState.REMOVED -> throw SendException("Nie jesteś już członkiem tej grupy")
            GroupState.LEFT -> throw SendException("Opuściłeś(-aś) tę grupę")
            GroupState.CLOSED -> throw SendException("Grupa została rozwiązana")
        }
        if (g.adminsOnly && !g.isAdmin(myPub)) throw SendException("Tylko administratorzy mogą pisać w tej grupie")
        return g.members.map { it.pk }.filter { it != myPub } to g
    }

    suspend fun sendText(chatId: String, text: String, xmode: Int? = null, ttl: Int? = null, replyTo: Message? = null, extra: Extra = Extra.NONE) =
        send(chatId, Kind.TEXT, text.trim(), null, xmode, ttl, replyTo, extra)

    fun replyOf(m: Message) = Reply(m.id, m.sender, Db.preview(m).replace('\n', ' ').take(120), m.kind)

    class Attachment(
        val file: File, val mime: String, val name: String, val thumb: String?, val w: Int, val h: Int, val dur: Long,
        val wave: String? = null,
    )

    /**
     * Wysyła wiadomość (tekst lub media). Rzuca SendException z opisem błędu.
     * [xmodeOverride]/[ttlOverride] — znikanie tylko dla tej wiadomości (inaczej ustawienie czatu).
     */
    suspend fun send(
        chatId: String, kind: String, body: String, att: Attachment?,
        xmodeOverride: Int? = null, ttlOverride: Int? = null, replyTo: Message? = null,
        extra: Extra = Extra.NONE,
    ) = withContext(Dispatchers.IO) { sendLocked(chatId, kind, body, att, xmodeOverride, ttlOverride, replyTo, extra) }

    /** Kopiowanie i liczenie skrótu pliku (do 16 MB) poza wątkiem interfejsu. */
    private suspend fun sendLocked(
        chatId: String, kind: String, body: String, att: Attachment?,
        xmodeOverride: Int?, ttlOverride: Int?, replyTo: Message?, extra: Extra,
    ) = stateLock.withLock {
        val chat0 = db.chat(chatId) ?: throw SendException("Brak czatu")
        canWrite(chatId)?.let { throw SendException(it) }
        if (chat0.peer != null && Admin.isAdmin(chat0.peer)) throw SendException("Na wiadomości od Szept nie można odpowiadać")
        val mode = xmodeOverride ?: chat0.xmode
        var ttl = (ttlOverride ?: chat0.ttl).coerceIn(1, 7 * 86400)
        // wiadomość czasowa z głosem/wideo nie może zniknąć w trakcie odtwarzania
        if (mode == XMode.AFTER_OPEN && att != null && att.dur > 0 && (kind == Kind.VOICE || kind == Kind.VIDEO)) {
            ttl = max(ttl, (att.dur / 1000).toInt() + 5)
        }
        val chat = chat0.copy(xmode = mode, ttl = ttl)
        val (recipients, group) = recipientsFor(chat)
        val reply = replyTo?.let { replyOf(it) }
        if (att != null && att.file.length() > MAX_MEDIA) throw SendException("Plik za duży (max ${MAX_MEDIA / 1024 / 1024} MB)")
        val msgId = newId()
        val now = Clock.now()
        var media: MediaMeta? = null
        var localPath: String? = null
        if (att != null) {
            val mediaId = newId()
            val ext = att.name.substringAfterLast('.', "bin").take(5)
            val dest = File(mediaDir, "$msgId.$ext")
            if (att.file.absolutePath != dest.absolutePath) {
                att.file.copyTo(dest, overwrite = true)
                if (att.file.absolutePath.startsWith(ctx.cacheDir.absolutePath)) att.file.delete()
            }
            localPath = dest.absolutePath
            val size = dest.length()
            val key = Crypto.randomBytes(32)
            val sha = sha256File(dest)
            val chunks = ((size + CHUNK_RAW - 1) / CHUNK_RAW).toInt().coerceAtLeast(1)
            // wiadomość czasowa: bez miniatury — nic nie widać przed dotknięciem
            val thumb = if (chat.xmode == XMode.AFTER_OPEN) null else att.thumb
            media = MediaMeta(mediaId, B64.enc(key), size, chunks, att.mime, sha, att.name, thumb, att.w, att.h, att.dur, att.wave)
            if (recipients.isNotEmpty()) db.addUpload(Db.Upload(mediaId, msgId, dest.absolutePath, B64.enc(key), chunks, now / 1000, JSONObject(), ""))
        }
        val payload = JSONObject().apply {
            put("t", "msg"); put("id", msgId); put("ts", now); put("k", kind); put("txt", body)
            if (media != null) put("m", media.toJson())
            if (chat.xmode != XMode.OFF) { put("x", chat.xmode); put("ttl", chat.ttl) }
            if (group != null) { put("g", group.id); put("gn", group.name) } else put("to", chat.peer)
            if (reply != null) put("re", reply.toJson())
            extra.putInto(this)
        }
        val m = Message(msgId, chatId, myPub, now, kind, body, media, localPath,
            if (media != null && recipients.isNotEmpty()) St.UPLOADING else St.QUEUED, chat.xmode, chat.ttl, now, 0, 0, true, reply, 0, extra)
        db.insertMessage(m)
        db.touchChat(chatId, now, Db.preview(m), false)
        if (recipients.isEmpty()) {
            db.updateStatus(msgId, St.SENT)
            afterRelease(m)
            return@withLock m
        }
        if (media != null) {
            db.addPending(msgId, payload.toString(), recipients)
            kickUploads()
        } else {
            release(msgId, payload, recipients)
        }
        m
    }

    /** Wysyła koperty z wiadomością (dla mediów — po wgraniu paczek). */
    private fun release(msgId: String, payload: JSONObject, recipients: List<String>) {
        if (msgId.startsWith("admin:")) {
            if (msgId.startsWith("admin:pol")) publishPolicy(payload) else dispatch(payload, recipients, null, EXPIRE_SEC, asAdmin = true, sync = false)
            adminJobs.update { it - msgId }
            return
        }
        if (msgId.startsWith("story:")) {
            if (db.story(msgId.removePrefix("story:")) != null) dispatch(payload, recipients, msgId, STORY_MS / 1000 + 3600)
            return
        }
        val m = db.message(msgId) ?: return
        val now = Clock.now()
        var exp: Long? = null
        if (m.xmode == XMode.AFTER_SEND) {
            payload.put("ts", now)
            exp = now / 1000 + m.ttl + 120
        }
        dispatch(payload, recipients, msgId, EXPIRE_SEC, exp)
        afterRelease(m)
    }

    private fun afterRelease(m: Message) {
        val now = System.currentTimeMillis()
        when (m.xmode) {
            XMode.AFTER_SEND -> { db.setExpires(m.id, now + m.ttl * 1000L); expiryKick.trySend(Unit) }
            XMode.AFTER_OPEN -> {
                // kopia nadawcy: treść znika od razu, zostaje tylko informacja o wysłaniu
                if (db.uploadForMsg(m.id) == null || uploadComplete(m.id)) m.path?.let { File(it).delete() }
                db.clearContent(m.id)
                db.setExpires(m.id, now + max(m.ttl.toLong(), 3600L) * 1000L)
                expiryKick.trySend(Unit)
            }
        }
    }

    /** Ponawia nieudaną wysyłkę lub pobieranie. */
    fun retry(msgId: String) {
        scope.launch {
            val m = db.message(msgId) ?: return@launch
            if (!m.mine) {
                val meta = m.media ?: return@launch
                db.addDownload(msgId, meta)
                db.updateStatus(msgId, St.DOWNLOADING, false)
                kickDownloads()
                return@launch
            }
            db.uploadForMsg(msgId)?.let { u ->
                u.done.keys().asSequence().toList().forEach { k -> if (u.done.optInt(k) < 0) u.done.remove(k) }
                db.addUpload(u)
            }
            db.outboxClearRejected(msgId)
            db.updateStatus(msgId, if (db.pending(msgId) != null) St.UPLOADING else St.QUEUED, false)
            onWrapAccepted(msgId)
            maintenance()
        }
    }

    // ================= usuwanie =================

    fun canDeleteForOthers(m: Message): Boolean {
        if (m.kind == Kind.SYSTEM) return false
        if (m.mine || m.chat.startsWith("d:")) return true
        return db.group(m.chat.removePrefix("g:"))?.isAdmin(myPub) == true
    }

    private fun deleteLocal(m: Message) {
        db.deleteMessage(m.id)
        db.addTombstone(m.id, myPub)
        if (m.mine) {
            // nie wysłane jeszcze media — anuluj; wysłane są już na przekaźnikach
            db.removePending(m.id)
            db.uploadForMsg(m.id)?.let { u -> db.removeUpload(u.media); File(u.path).delete() }
        }
        m.path?.let { File(it).delete() }
        db.removeDownloadForMsg(m.id)
        m.media?.let { downloads.remove(it.id)?.cancel() }
        db.refreshChatPreview(m.chat)
        notifier?.onDeleted(m.chat)
        if (m.kind == Kind.LOCATION) refreshLive()
    }

    fun deleteForMe(msgId: String) = scope.launch {
        stateLock.withLock { db.message(msgId)?.let { deleteLocal(it) } }
        dispatchSelf(JSONObject().put("t", "del").put("ids", JSONArray().put(msgId)))
    }

    /** Usuwa u wybranych osób (bez usuwania u mnie). */
    fun deleteFor(msgId: String, pks: List<String>, alsoMe: Boolean) = scope.launch {
        stateLock.withLock {
            val m = db.message(msgId) ?: return@withLock
            if (!canDeleteForOthers(m)) return@withLock
            val payload = JSONObject().put("t", "del").put("ids", JSONArray().put(msgId))
            if (m.chat.startsWith("g:")) payload.put("g", m.chat.removePrefix("g:"))
            dispatch(payload, pks.filter { it != myPub }, null, sync = alsoMe)
            if (!alsoMe) db.setDeletedForOthers(m.id)
            if (alsoMe) {
                if (m.mine) withdrawFromRelays(m.id)
                deleteLocal(m)
            }
        }
    }

    fun deleteForEveryone(msgId: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        deleteFor(msgId, participants(m.chat), true)
    }

    fun participants(chatId: String): List<String> =
        if (chatId.startsWith("d:")) listOf(chatId.removePrefix("d:"))
        else db.group(chatId.removePrefix("g:"))?.members?.map { it.pk }?.filter { it != myPub } ?: emptyList()

    /** Prosi przekaźniki o skasowanie kopert i paczek mediów (NIP-09). */
    private fun withdrawFromRelays(msgId: String) {
        db.removePending(msgId)
        db.removeOutboxForMsg(msgId)
        for ((recipient, eventId) in db.wraps(msgId)) {
            val eph = Crypto.deriveKey(myPriv, "w:$msgId:$recipient")
            val del = Event.create(eph, 5, listOf(listOf("e", eventId), listOf("k", WRAP_KIND.toString())), "")
            db.addOutbox(del.id, del.toJson(), null)
        }
        val up = db.uploadForMsg(msgId)
        if (up != null && up.ids.isNotEmpty()) {
            val signer = Crypto.deriveKey(B64.dec(up.key), "chunk-sign")
            val tags = up.ids.split(',').filter { it.isNotEmpty() }.map { listOf("e", it) } + listOf(listOf("k", CHUNK_KIND.toString()))
            val del = Event.create(signer, 5, tags, "")
            db.addOutbox(del.id, del.toJson(), null)
        }
        db.removeUpload(up?.media ?: "")
        flushOutbox()
    }

    fun deleteChat(chatId: String) = scope.launch {
        stateLock.withLock {
            db.scheduled(chatId).forEach { s -> s.att?.optString("path")?.ifEmpty { null }?.let { File(it).delete() } }
            db.deleteChat(chatId).forEach { m ->
                db.addTombstone(m.id, myPub)
                db.removePending(m.id)
                db.uploadForMsg(m.id)?.let { u -> db.removeUpload(u.media); File(u.path).delete() }
                m.path?.let { File(it).delete() }
                db.removeDownloadForMsg(m.id)
                m.media?.let { downloads.remove(it.id)?.cancel() }
            }
            notifier?.cancel(chatId)
        }
        refreshLive()
    }

    // ================= kontakty =================

    /** Dodaje kontakt z kodu/linku; zwraca id czatu lub null. */
    fun addContact(pk: String, name: String): String? {
        if (pk == myPub || !pk.isPubKey()) return null
        db.upsertContact(pk, name.ifBlank { null }, true)
        val chatId = dmChatId(pk)
        db.ensureChat(chatId, false, pk, db.nameOf(pk))
        dispatch(fillProfile(JSONObject().put("t", "prof").put("add", true).put("to", pk)), listOf(pk), null, EXPIRE_SEC)
        return chatId
    }

    /** Usuwa osobę ze znajomych (opcjonalnie razem z rozmową). Nie dostaje już mojego statusu ani relacji. */
    fun removeFriend(pk: String, alsoChat: Boolean) {
        if (!pk.isPubKey() || pk == myPub) return
        db.removeContact(pk)
        presence.update { it - pk }
        if (alsoChat) deleteChat(dmChatId(pk))
    }

    /** Osoby, które zna telefon: znajomi + członkowie moich grup (bez usuniętych znajomych spoza grup). */
    fun knownPeople(): List<Contact> {
        val inGroups = db.groups().flatMap { g -> g.members.map { it.pk } }.toSet()
        return db.contacts(false).filter { it.pk != myPub && (it.isContact || it.pk in inGroups || Admin.isAdmin(it.pk)) }
    }

    fun openDm(pk: String): String {
        val chatId = dmChatId(pk)
        db.ensureChat(chatId, false, pk, db.nameOf(pk))
        return chatId
    }

    fun setBlocked(pk: String, blocked: Boolean) = db.setBlocked(pk, blocked)

    fun setMuted(chatId: String, muted: Boolean) {
        db.setMuted(chatId, muted)
        if (muted) notifier?.cancel(chatId)
        dispatchSelf(JSONObject().put("t", "sync").put("c", "mute").put("chat", chatId).put("on", muted))
    }

    fun streakDays(chatId: String): Int = db.streakDays(chatId)

    // ================= reakcje, edycja, ankiety, przypięte =================

    /** Reakcja emoji; ponowne wybranie tej samej usuwa ją. */
    fun react(msgId: String, emoji: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (m.kind == Kind.SYSTEM) return@launch
        val now = Clock.now()
        val e = if (db.reaction(msgId, myPub)?.emoji == emoji) "" else emoji.take(16)
        db.setReaction(msgId, myPub, e, now)
        dispatch(target(JSONObject().put("t", "react").put("id", msgId).put("e", e).put("ts", now), m.chat), participants(m.chat), null)
    }

    private fun handleReact(from: String, p: JSONObject, gid: String?) {
        val chatId = chatOf(from, gid) ?: return
        val m = db.message(p.optString("id")) ?: return
        if (m.chat != chatId || m.kind == Kind.SYSTEM) return
        val e = p.optString("e").take(16)
        db.setReaction(m.id, from, e, p.optLong("ts", Clock.now()))
        if (m.mine && e.isNotEmpty() && !(activeChat == chatId && appVisible)) {
            notifyMessage(chatId, from, "$e do: " + Db.preview(m).replace('\n', ' ').take(60), gid != null)
        }
    }

    /** Edycja własnej wiadomości (tekst albo podpis zdjęcia/wideo/pliku). */
    suspend fun editMessage(msgId: String, newText: String) {
        val m = db.message(msgId) ?: throw SendException("Wiadomość nie istnieje")
        if (!canEdit(m)) throw SendException("Tej wiadomości nie można edytować")
        val text = newText.trim().take(20_000)
        if (text.isEmpty() && m.kind == Kind.TEXT) throw SendException("Wiadomość nie może być pusta")
        val now = Clock.now()
        db.editMessage(msgId, text, now)
        db.refreshChatPreview(m.chat)
        dispatch(target(JSONObject().put("t", "edit").put("id", msgId).put("txt", text).put("ets", now), m.chat), participants(m.chat), null)
    }

    fun canEdit(m: Message) = m.mine && m.xmode == XMode.OFF && m.kind in EDITABLE && m.status != St.FAILED

    private fun handleEdit(from: String, p: JSONObject) {
        val m = db.message(p.optString("id")) ?: return
        if (m.sender != from || m.mine || m.xmode != XMode.OFF || m.kind !in EDITABLE) return
        if (db.editMessage(m.id, p.optString("txt").take(20_000), p.optLong("ets", Clock.now()))) db.refreshChatPreview(m.chat)
    }

    /** Ankieta. Głosy są jawne dla uczestników rozmowy. */
    suspend fun sendPoll(chatId: String, poll: Poll): Message {
        val chat = db.chat(chatId) ?: throw SendException("Brak czatu")
        return send(chatId, Kind.POLL, poll.toJson(), null, if (chat.xmode == XMode.AFTER_SEND) null else XMode.OFF)
    }

    /** Głos w ankiecie; pusta lista = wycofanie głosu. */
    fun vote(msgId: String, sel: List<Int>) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        val poll = Poll.parse(m.body) ?: return@launch
        val clean = sel.distinct().filter { it in poll.opts.indices }.let { if (poll.multi) it else it.take(1) }
        val now = Clock.now()
        db.setVote(msgId, myPub, clean, now)
        dispatch(target(JSONObject().put("t", "vote").put("id", msgId).put("sel", JSONArray(clean)).put("ts", now), m.chat), participants(m.chat), null)
    }

    private fun handleVote(from: String, p: JSONObject, gid: String?) {
        val chatId = chatOf(from, gid) ?: return
        val m = db.message(p.optString("id")) ?: return
        if (m.chat != chatId || m.kind != Kind.POLL) return
        val poll = Poll.parse(m.body) ?: return
        val arr = p.optJSONArray("sel") ?: JSONArray()
        val sel = List(arr.length()) { arr.optInt(it, -1) }.distinct().filter { it in poll.opts.indices }.let { if (poll.multi) it else it.take(1) }
        db.setVote(m.id, from, sel, p.optLong("ts", Clock.now()))
    }

    /** Przypinać może każdy w rozmowie prywatnej, a w grupie — administratorzy (ogłoszenia). */
    fun canPin(chatId: String): Boolean =
        chatId.startsWith("d:") || db.group(chatId.removePrefix("g:"))?.let { it.isAdmin(myPub) && it.state == GroupState.ACTIVE } == true

    fun pinMessage(msgId: String, on: Boolean) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (!canPin(m.chat) || m.kind == Kind.SYSTEM) return@launch
        val now = Clock.now()
        db.setPin(m.chat, msgId, myPub, on, now)
        dispatch(target(JSONObject().put("t", "pin").put("id", msgId).put("on", on).put("ts", now), m.chat), participants(m.chat), null)
    }

    private fun handlePin(from: String, p: JSONObject, gid: String?) {
        val chatId = chatOf(from, gid) ?: return
        if (gid != null && db.group(gid)?.isAdmin(from) != true) return
        val m = db.message(p.optString("id")) ?: return
        if (m.chat != chatId) return
        val on = p.optBoolean("on")
        val ts = p.optLong("ts", Clock.now())
        db.setPin(chatId, m.id, from, on, ts)
        if (on) systemMessage(chatId, "sys:pin:${m.id}:$ts", "📌 ${db.nameOf(from)} przypiął(-ęła) wiadomość: ${Db.preview(m).replace('\n', ' ').take(60)}")
    }

    // ================= „pisze…” i zrzuty ekranu =================

    /** chatId → (pk → do kiedy pokazywać „pisze…”). */
    val typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    private val lastTypingSent = ConcurrentHashMap<String, Long>()
    val typingEnabled: Boolean get() = prefs.getBoolean("typing", true)
    fun setTypingEnabled(on: Boolean) = prefs.edit().putBoolean("typing", on).apply()

    /** Wywoływane przy pisaniu w polu tekstowym (wysyła najwyżej raz na 4 s). */
    fun sendTyping(chatId: String) {
        if (!typingEnabled) return
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[chatId] ?: 0L) < 4000) return
        lastTypingSent[chatId] = now
        val to = participants(chatId)
        if (to.isEmpty() || to.size > TYPING_MAX_MEMBERS) return
        if (chatId.startsWith("g:") && db.group(chatId.removePrefix("g:"))?.state != GroupState.ACTIVE) return
        scope.launch { publishEphemeral(target(JSONObject().put("t", "typ").put("ts", Clock.now()), chatId), to) }
    }

    private fun handleTyping(from: String, p: JSONObject, gid: String?) {
        val chatId = chatOf(from, gid) ?: return
        if (abs(Clock.now() - p.optLong("ts")) > 20_000) return
        val until = System.currentTimeMillis() + 6000
        typing.update { cur -> cur + (chatId to ((cur[chatId] ?: emptyMap()) + (from to until))) }
    }

    private fun clearTyping(chatId: String, from: String) {
        typing.update { cur -> val m = cur[chatId] ?: return@update cur; if (from !in m) cur else cur + (chatId to (m - from)) }
    }

    /** Krótkotrwałe zdarzenie (np. „pisze…”): bez skrzynki nadawczej i ponawiania, na kilka przekaźników. */
    private fun publishEphemeral(payload: JSONObject, recipients: List<String>, relayCount: Int = 3, ttlSec: Long = 60, allowSelf: Boolean = false) {
        payload.put("n", myName).put("v", 1).put("dv", deviceId)
        val inner = Event.create(myPriv, INNER_KIND, emptyList(), payload.toString())
        val bytes = inner.toJson().toByteArray(Charsets.UTF_8)
        val exp = Clock.nowSec() + ttlSec
        val targets = connectedRelays().take(relayCount)
        if (targets.isEmpty()) return
        for (r in recipients.distinct()) {
            if ((r == myPub && !allowSelf) || !r.isPubKey()) continue
            val eph = Crypto.newPrivateKey()
            val key = Crypto.conversationKey(eph, r.unhex())
            val wrap = Event.create(eph, WRAP_KIND, listOf(listOf("p", r), listOf("expiration", exp.toString())), B64.enc(Crypto.encrypt(key, bytes)))
            val json = wrap.toJson()
            targets.forEach { relay -> scope.launch { try { relay.publish(json, wrap.id, 10_000) } catch (_: Exception) { } } }
        }
    }

    /** Android 14+: zrobiono zrzut ekranu otwartej rozmowy — informujemy jej uczestników. */
    fun onScreenshot(chatId: String) {
        scope.launch {
            if (db.chat(chatId) == null) return@launch
            val now = Clock.now()
            systemMessage(chatId, "sys:shot:me:$now", "📸 Zrobiłeś(-aś) zrzut ekranu rozmowy.")
            val to = participants(chatId)
            if (to.isNotEmpty()) dispatch(target(JSONObject().put("t", "shot").put("ts", now), chatId), to, null, 3 * 86400, sync = false)
        }
    }

    private fun handleShot(from: String, p: JSONObject, gid: String?) {
        val chatId = chatOf(from, gid) ?: return
        if (db.chat(chatId) == null) return
        val ts = p.optLong("ts", Clock.now())
        systemMessage(chatId, "sys:shot:$from:$ts", "📸 ${db.nameOf(from)} zrobił(a) zrzut ekranu rozmowy.")
        notifyMessage(chatId, from, "📸 zrobił(a) zrzut ekranu rozmowy", gid != null)
    }

    // ================= przekazywanie =================

    /** Przekazuje wiadomość do innych czatów (zdjęcia i pliki są kopiowane). Zwraca liczbę udanych. */
    suspend fun forward(m: Message, chatIds: List<String>): Int = withContext(Dispatchers.IO) { forwardIo(m, chatIds) }

    private suspend fun forwardIo(m: Message, chatIds: List<String>): Int {
        if (!canForward(m)) throw SendException("Tej wiadomości nie można przekazać")
        var n = 0
        for (chatId in chatIds.distinct()) {
            try {
                val att = m.media?.let { meta ->
                    val src = File(m.path ?: throw SendException("Plik nie jest jeszcze pobrany"))
                    val tmp = File(ctx.cacheDir, "fwd_${newId()}." + meta.name.substringAfterLast('.', "bin").take(5))
                    src.copyTo(tmp, overwrite = true)
                    Attachment(tmp, meta.mime, meta.name, meta.thumb, meta.w, meta.h, meta.dur, meta.wave)
                }
                // transkrypcja głosówki jest tylko u mnie — nie przekazujemy jej dalej
                send(chatId, m.kind, if (m.kind == Kind.VOICE) "" else m.body, att, null, null, null, Extra(fwd = true, round = m.ex.round))
                n++
            } catch (e: SendException) { Log.w(TAG, "przekazanie → $chatId: ${e.message}") }
        }
        return n
    }

    fun canForward(m: Message) = m.xmode == XMode.OFF && m.kind in FORWARDABLE && (m.media == null || m.path != null)

    // ================= lokalizacja =================

    /** Czy trwa udostępnianie lokalizacji na żywo (usługa włącza wtedy odbiornik GPS). */
    val liveActive = MutableStateFlow(false)

    private fun refreshLive() { liveActive.value = db.lives().any { it.until > System.currentTimeMillis() } }

    /** Wysyła lokalizację: jednorazową ([liveMs] = 0) albo udostępnianą na żywo przez [liveMs]. */
    suspend fun sendLocation(chatId: String, lat: Double, lon: Double, acc: Float, liveMs: Long = 0): Message {
        val now = Clock.now()
        val chat = db.chat(chatId) ?: throw SendException("Brak czatu")
        val loc = Loc(lat, lon, acc, if (liveMs > 0) now + liveMs else 0, now)
        val xm = if (liveMs > 0 || chat.xmode != XMode.AFTER_SEND) XMode.OFF else null
        val m = send(chatId, Kind.LOCATION, loc.toJson(), null, xm)
        if (liveMs > 0) {
            db.addLive(m.id, chatId, System.currentTimeMillis() + liveMs)
            db.setLiveLast(m.id, System.currentTimeMillis(), lat, lon)
            refreshLive()
        }
        return m
    }

    /**
     * Nowa pozycja z odbiornika — rozsyłana w aktywnych sesjach na żywo:
     * najwyżej co 30 s przy ruchu (≥ 25 m), a bez ruchu co 3 min.
     */
    fun onLocation(lat: Double, lon: Double, acc: Float) {
        scope.launch {
            val now = System.currentTimeMillis()
            for (l in db.lives()) {
                if (now >= l.until) { endLive(l.msg); continue }
                val since = now - l.last
                if (since < 30_000 || (distanceM(l.lat, l.lon, lat, lon) < 25 && since < 180_000)) continue
                val m = db.message(l.msg)
                if (m == null) { db.removeLive(l.msg); continue }
                val old = Loc.parse(m.body) ?: continue
                val upd = old.copy(lat = lat, lon = lon, acc = acc, upd = Clock.now())
                db.setBody(m.id, upd.toJson())
                db.setLiveLast(l.msg, now, lat, lon)
                val p = JSONObject().put("t", "loc").put("id", m.id).put("lat", lat).put("lon", lon).put("acc", acc.toDouble()).put("ts", upd.upd)
                dispatch(target(p, l.chat), participants(l.chat), null, (l.until - now) / 1000 + 600)
            }
            refreshLive()
        }
    }

    /** Kończy udostępnianie na żywo (ręcznie albo po czasie). */
    fun stopLive(msgId: String) = scope.launch { endLive(msgId) }

    private fun endLive(msgId: String) {
        val live = db.lives().firstOrNull { it.msg == msgId }
        db.removeLive(msgId)
        val m = db.message(msgId)
        if (m != null && m.mine) {
            Loc.parse(m.body)?.let { if (!it.ended) db.setBody(m.id, it.copy(ended = true, upd = Clock.now()).toJson()) }
            val chat = live?.chat ?: m.chat
            dispatch(target(JSONObject().put("t", "loc").put("id", msgId).put("end", true).put("ts", Clock.now()), chat), participants(chat), null, 86400)
        }
        refreshLive()
    }

    private fun handleLoc(from: String, p: JSONObject) {
        val m = db.message(p.optString("id")) ?: return
        if (m.sender != from || m.kind != Kind.LOCATION) return
        val old = Loc.parse(m.body) ?: return
        val ts = p.optLong("ts")
        val n = if (p.optBoolean("end")) old.copy(ended = true, upd = max(ts, old.upd))
        else {
            if (ts <= old.upd || old.ended) return
            val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            old.copy(lat = lat, lon = lon, acc = p.optDouble("acc", 0.0).toFloat(), upd = ts)
        }
        db.setBody(m.id, n.toJson())
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    // ================= relacje (24 h) =================

    /** Relacje widzą moje kontakty (bez zablokowanych). */
    private fun storyAudience(): List<String> = db.contacts(true).filter { !it.blocked && it.pk != myPub }.map { it.pk }

    suspend fun postStory(kind: String, text: String, att: Attachment?, bg: Int): Story = stateLock.withLock {
        val id = newId()
        val now = Clock.now()
        var media: MediaMeta? = null
        var path: String? = null
        val audience = storyAudience()
        if (att != null) {
            if (att.file.length() > MAX_MEDIA) throw SendException("Plik za duży (max ${MAX_MEDIA / 1024 / 1024} MB)")
            val dest = File(mediaDir, "story_$id." + att.name.substringAfterLast('.', "jpg").take(5))
            att.file.copyTo(dest, overwrite = true)
            if (att.file.absolutePath.startsWith(ctx.cacheDir.absolutePath)) att.file.delete()
            path = dest.absolutePath
            val key = Crypto.randomBytes(32)
            val size = dest.length()
            val chunks = ((size + CHUNK_RAW - 1) / CHUNK_RAW).toInt().coerceAtLeast(1)
            media = MediaMeta(newId(), B64.enc(key), size, chunks, att.mime, sha256File(dest), att.name, att.thumb, att.w, att.h, att.dur)
            if (audience.isNotEmpty()) db.addUpload(Db.Upload(media.id, "story:$id", dest.absolutePath, B64.enc(key), chunks, now / 1000, JSONObject(), ""))
        }
        val story = Story(id, myPub, now, if (media != null) Kind.IMAGE else Kind.TEXT, text.trim().take(700), media, path, bg, true, System.currentTimeMillis() + STORY_MS)
        db.insertStory(story)
        val payload = JSONObject().put("t", "story").put("id", id).put("ts", now).put("k", story.kind).put("txt", story.body).put("bg", bg)
        if (media != null) payload.put("m", media.toJson())
        if (audience.isNotEmpty()) {
            if (media != null) { db.addPending("story:$id", payload.toString(), audience); kickUploads() }
            else dispatch(payload, audience, "story:$id", STORY_MS / 1000 + 3600)
        }
        story
    }

    fun deleteStory(id: String) = scope.launch {
        val s = db.deleteStory(id) ?: return@launch
        s.path?.let { File(it).delete() }
        db.removeDownloadForMsg("story:$id")
        if (s.pk == myPub) {
            db.removePending("story:$id")
            withdrawFromRelays("story:$id")
            val audience = storyAudience()
            if (audience.isNotEmpty()) dispatch(JSONObject().put("t", "sdel").put("ids", JSONArray().put(id)), audience, null, STORY_MS / 1000 + 3600)
        }
    }

    fun markStorySeen(id: String) = scope.launch {
        val s = db.story(id) ?: return@launch
        if (s.seen || s.pk == myPub) return@launch
        db.setStorySeen(id)
        if (readReceipts) dispatch(JSONObject().put("t", "sseen").put("id", id).put("ts", Clock.now()), listOf(s.pk), null, 86400, sync = false)
    }

    private fun handleStory(from: String, p: JSONObject) {
        if (db.contact(from) == null) return
        val id = p.optString("id").take(64)
        if (id.isEmpty() || db.tombstone("story:$id") != null) return
        val ts = p.optLong("ts", Clock.now())
        val age = Clock.now() - ts
        if (age > STORY_MS) return
        val kind = if (p.optString("k") == Kind.IMAGE) Kind.IMAGE else Kind.TEXT
        val media = if (kind == Kind.IMAGE) (p.optJSONObject("m")?.let { MediaMeta.fromNetwork(it) } ?: return) else null
        val s = Story(id, from, ts, kind, p.optString("txt").take(700), media, null, p.optInt("bg"), false,
            System.currentTimeMillis() + (STORY_MS - max(age, 0)))
        if (!db.insertStory(s)) return
        if (media != null) { db.addDownload("story:$id", media); kickDownloads() }
    }

    private fun handleStorySeen(from: String, p: JSONObject) {
        val s = db.story(p.optString("id")) ?: return
        if (s.pk != myPub) return
        db.addStoryView(s.id, from, p.optLong("ts", Clock.now()))
    }

    private fun handleStoryDel(from: String, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            val s = db.story(id)
            if (s == null) { db.addTombstone("story:$id", from); continue }
            if (s.pk != from) continue
            db.deleteStory(id); s.path?.let { File(it).delete() }
            db.removeDownloadForMsg("story:$id")
        }
    }

    private fun purgeStories() {
        db.expiredStories(System.currentTimeMillis()).forEach { s ->
            db.deleteStory(s.id); s.path?.let { File(it).delete() }; db.removeDownloadForMsg("story:${s.id}")
        }
    }

    // ================= wiadomości zaplanowane =================

    private val scheduleKick = Channel<Unit>(Channel.CONFLATED)
    private val schedDir: File get() = File(ctx.filesDir, "scheduled_$profile").apply { mkdirs() }
    fun schedulePath(): File = schedDir
    fun schedulePathFiles(): List<File> = schedDir.listFiles()?.filter { it.isFile } ?: emptyList()
    /** Budzik systemowy na godzinę wysyłki (ustawiany przez aplikację). */
    var wakeAt: ((Long) -> Unit)? = null

    /** Zapamiętuje wiadomość do wysłania o [at] (ms). Załącznik trafia do pamięci aplikacji. */
    fun schedule(
        chatId: String, at: Long, kind: String, body: String, att: Attachment? = null, xmode: Int? = null, ttl: Int? = null,
        replyTo: Message? = null, extra: Extra = Extra.NONE,
    ): Scheduled {
        val id = newId()
        var attJson = att?.let {
            val dest = File(schedDir, id + "." + it.name.substringAfterLast('.', "bin").take(5))
            it.file.copyTo(dest, overwrite = true)
            if (it.file.absolutePath.startsWith(ctx.cacheDir.absolutePath)) it.file.delete()
            JSONObject().put("path", dest.absolutePath).put("mime", it.mime).put("name", it.name).put("w", it.w).put("h", it.h).put("dur", it.dur)
                .apply { it.thumb?.let { t -> put("th", t) }; it.wave?.let { wv -> put("wf", wv) } }
        }
        // wzmianki/album zapisujemy razem z załącznikiem (albo samodzielnie przy tekście)
        if (!extra.isEmpty()) attJson = (attJson ?: JSONObject()).put("ex", extra.putInto(JSONObject()))
        val s = Scheduled(id, chatId, at, kind, body, attJson, xmode, ttl, replyTo?.id, System.currentTimeMillis())
        db.addScheduled(s)
        scheduleKick.trySend(Unit)
        wakeAt?.invoke(at)
        return s
    }

    fun cancelScheduled(id: String) = scope.launch {
        db.scheduled().firstOrNull { it.id == id }?.att?.optString("path")?.ifEmpty { null }?.let { File(it).delete() }
        db.removeScheduled(id)
    }

    fun sendScheduledNow(id: String) = scope.launch { db.setScheduledAt(id, 0); scheduleKick.trySend(Unit) }

    fun nextScheduled(): Long = db.scheduled().minOfOrNull { it.at } ?: 0L

    private suspend fun runScheduled() {
        val now = System.currentTimeMillis()
        for (s in db.scheduled().filter { it.at <= now }) {
            db.removeScheduled(s.id)
            val a = s.att
            val att = a?.takeIf { it.optString("path").isNotEmpty() }?.let {
                Attachment(File(it.optString("path")), it.optString("mime"), it.optString("name"), it.optString("th").ifEmpty { null },
                    it.optInt("w"), it.optInt("h"), it.optLong("dur"), it.optString("wf").ifEmpty { null })
            }
            var keep = false
            try {
                if (att != null && !att.file.exists()) throw SendException("brak załącznika")
                send(s.chat, s.kind, s.body, att, s.xmode, s.ttl, s.reply?.let { db.message(it) }, Extra.from(a?.optJSONObject("ex")))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is java.io.IOException && now - s.at < 3600_000L) {
                    // chwilowy błąd (np. brak miejsca) — spróbujemy za 5 minut, załącznik zostaje
                    keep = true
                    db.addScheduled(s.copy(at = now + 5 * 60_000L))
                } else systemMessage(s.chat, "sys:sched:${s.id}", "Nie wysłano zaplanowanej wiadomości: ${e.message}")
            } finally {
                if (!keep) att?.file?.delete()
            }
        }
        nextScheduled().takeIf { it > 0 }?.let { wakeAt?.invoke(it) }
    }

    private suspend fun scheduleLoop() {
        while (true) {
            try { runScheduled() } catch (e: Exception) { Log.w(TAG, "zaplanowane", e) }
            val next = nextScheduled()
            val wait = if (next == 0L) 60_000L else (next - System.currentTimeMillis()).coerceIn(500L, 60_000L)
            withTimeoutOrNull(wait) { scheduleKick.receive() }
        }
    }

    // ================= czaty: przypinanie, archiwum, ukryte, szkice =================

    fun setChatPinned(chatId: String, on: Boolean) = db.setChatPinned(chatId, on)
    fun setArchived(chatId: String, on: Boolean) = db.setArchived(chatId, on)
    fun saveDraft(chatId: String, text: String) = db.setDraft(chatId, text)

    val hasHiddenPin: Boolean get() = prefs.getString("hpin_h", null) != null
    /** Ukryte czaty są widoczne do zamknięcia aplikacji. */
    val hiddenRevealed = MutableStateFlow(false)

    /** PIN ukrytych czatów; null = wyłączenie (wszystkie ukryte czaty wracają na listę). */
    fun setHiddenPin(code: String?) {
        val e = prefs.edit()
        if (code.isNullOrBlank()) {
            e.remove("hpin_h").remove("hpin_s")
            db.chats().filter { it.hidden }.forEach { db.setHidden(it.id, false) }
        } else {
            val salt = Crypto.randomBytes(8).hex()
            e.putString("hpin_s", salt).putString("hpin_h", hashCode(salt, code))
        }
        e.apply()
    }

    fun verifyHiddenPin(code: String): Boolean {
        val salt = prefs.getString("hpin_s", null) ?: return false
        return prefs.getString("hpin_h", null) == hashCode(salt, code)
    }

    fun setHidden(chatId: String, on: Boolean) {
        db.setHidden(chatId, on)
        if (on) notifier?.cancel(chatId)
    }

    // ================= rozsyłanie nowej wersji aplikacji =================

    /**
     * Rozsyła zainstalowany plik aplikacji (APK) do wszystkich grup i kontaktów.
     * Do grupy idzie jedno wgranie dla wszystkich członków. Odbiorca i tak potwierdza instalację w Androidzie.
     * Zwraca liczbę czatów, do których poszła aktualizacja.
     */
    // ================= blokada aplikacji i kod awaryjny (wipe) =================

    val lockEnabled: Boolean get() = prefs.getBoolean("lock", false)
    val biometricEnabled: Boolean get() = prefs.getBoolean("biometric", false)
    val autoLockMs: Long get() = prefs.getLong("autolock", 0L)
    val hasWipeCode: Boolean get() = prefs.getString("wipe_h", null) != null

    private fun hashCode(salt: String, code: String) = Crypto.sha256((salt + "|" + code).toByteArray()).hex()

    fun setPin(code: String?, biometric: Boolean = biometricEnabled, autoLock: Long = autoLockMs) {
        val e = prefs.edit()
        if (code.isNullOrBlank()) e.putBoolean("lock", false).remove("pin_h").remove("pin_s").putBoolean("biometric", false)
        else {
            val salt = Crypto.randomBytes(8).hex()
            e.putBoolean("lock", true).putString("pin_s", salt).putString("pin_h", hashCode(salt, code))
                .putBoolean("biometric", biometric).putLong("autolock", autoLock)
        }
        e.apply()
    }

    fun setBiometric(on: Boolean) = prefs.edit().putBoolean("biometric", on && lockEnabled).apply()

    fun verifyPin(code: String): Boolean {
        val salt = prefs.getString("pin_s", null) ?: return false
        return prefs.getString("pin_h", null) == hashCode(salt, code)
    }

    /** cfg: {"scope":"all"|"sel","chats":[...], "block":"none"|"all"|"sel","pks":[...], "account":bool} */
    fun setWipeCode(code: String?, cfg: JSONObject) {
        val e = prefs.edit()
        if (code.isNullOrBlank()) e.remove("wipe_h").remove("wipe_s").remove("wipe_cfg")
        else {
            val salt = Crypto.randomBytes(8).hex()
            e.putString("wipe_s", salt).putString("wipe_h", hashCode(salt, code)).putString("wipe_cfg", cfg.toString())
        }
        e.apply()
    }

    fun wipeConfig(): JSONObject = try { JSONObject(prefs.getString("wipe_cfg", "{}")!!) } catch (e: Exception) { JSONObject() }

    fun isWipeCode(code: String): Boolean {
        val salt = prefs.getString("wipe_s", null) ?: return false
        return prefs.getString("wipe_h", null) == hashCode(salt, code)
    }

    /** Wykonuje kod awaryjny. Zwraca true, jeśli usunięto też konto (trzeba wrócić do ekranu powitalnego). */
    fun applyWipe(): Boolean {
        val cfg = wipeConfig()
        val chatsToWipe = if (cfg.optString("scope", "all") == "sel") {
            val arr = cfg.optJSONArray("chats"); if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }
        } else db.chats().map { it.id }
        chatsToWipe.forEach { id ->
            db.scheduled(id).forEach { s -> s.att?.optString("path")?.ifEmpty { null }?.let { File(it).delete() } }
            db.deleteChat(id).forEach { m -> m.path?.let { File(it).delete() }; db.uploadForMsg(m.id)?.let { u -> File(u.path).delete() } }
        }
        if (cfg.optString("scope", "all") != "sel") {
            db.stories(0).forEach { st -> db.deleteStory(st.id); st.path?.let { File(it).delete() } }
        }
        when (cfg.optString("block", "none")) {
            "all" -> db.contacts(false).forEach { db.setBlocked(it.pk, true) }
            "sel" -> cfg.optJSONArray("pks")?.let { arr -> for (i in 0 until arr.length()) db.setBlocked(arr.optString(i), true) }
        }
        if (cfg.optBoolean("account")) {
            wipeAccount()
            return true
        }
        return false
    }

    /** Usuwa konto z telefonu. Po tym trzeba utworzyć nowy obiekt Core (App.resetCore). */
    private fun wipeAccount() {
        if (lanCreated) { lan.relay.clear(); lan.close() }
        relays.values.forEach { it.disconnect() }
        prefs.edit().clear().commit()
        privCache = null
        db.close()
        ctx.deleteDatabase(dbName)
        DbCrypto.forgetKey(ctx, dbName)
        mediaDir.deleteRecursively(); mediaDir.mkdirs()
        schedDir.deleteRecursively()
        avatarFile.delete(); bannerFile.delete()
    }

    // ================= grupy =================

    private fun stateHash(g: Group) = Crypto.sha256(
        (g.name + "|" + g.admins.sorted() + "|" + g.members.map { it.pk }.sorted() + "|" + g.adminsOnly).toByteArray(),
    ).hex()

    /** Lista usuniętych bez aktualnych członków. */
    private fun cleanRemoved(n: Group, extra: Collection<String> = emptyList()): Group =
        n.copy(removed = (n.removed + extra).distinct().filter { pk -> !n.isMember(pk) && pk != myPub }.takeLast(500))

    private fun groupPayload(g: Group, closed: Boolean = false) = JSONObject().apply {
        put("t", "gupd"); put("g", g.id); put("name", g.name); put("ver", g.ver)
        put("rm", JSONArray(g.removed.takeLast(200)))
        put("admins", JSONArray(g.admins)); put("ao", g.adminsOnly); put("tok", g.token)
        put("members", JSONArray().apply { g.members.forEach { put(JSONObject().put("pk", it.pk).put("n", it.name)) } })
        if (g.icon.isNotEmpty()) put("ic", g.icon)
        if (g.color != 0) put("col", g.color)
        put("ds", g.descr); put("ap", g.approve)
        put("inv", JSONArray().apply { g.invites.forEach { put(it.toJson()) } })
        // moderacja: wyciszeni i zbanowani
        put("mod", g.moderJson())
        if (closed) put("closed", true)
    }

    private fun broadcastGroup(g: Group, extra: Collection<String> = emptyList(), closed: Boolean = false) {
        val to = (g.members.map { it.pk } + extra).distinct().filter { it != myPub }
        dispatch(groupPayload(g, closed), to, null, EXPIRE_SEC)
    }

    suspend fun createGroup(name: String, memberPks: List<String>): String = stateLock.withLock {
        val gid = Crypto.randomBytes(12).hex()
        val members = listOf(Member(myPub, myName)) + memberPks.distinct().filter { it != myPub }.map { Member(it, db.nameOf(it)) }
        val g = Group(gid, name.trim().ifEmpty { "Grupa" }, 1, listOf(myPub), members, Crypto.randomBytes(8).hex(), false, GroupState.ACTIVE)
        db.saveGroup(g)
        val chatId = groupChatId(gid)
        db.ensureChat(chatId, true, null, g.name)
        systemMessage(chatId, "sys:$gid:create", "Utworzono grupę „${g.name}”.")
        broadcastGroup(g)
        chatId
    }

    fun groupInvite(gid: String): String? {
        val g = db.group(gid) ?: return null
        return Links.group(g.id, g.token, g.admins, g.name)
    }

    /** Zmiana stanu grupy przez administratora. */
    suspend fun updateGroup(gid: String, change: (Group) -> Group): Boolean = stateLock.withLock {
        val g = db.group(gid) ?: return@withLock false
        if (!g.isAdmin(myPub) || g.state != GroupState.ACTIVE) return@withLock false
        val n = cleanRemoved(change(g).copy(ver = g.ver + 1))
        db.saveGroup(n)
        db.renameChat(groupChatId(gid), n.name)
        describeGroupChange(g, n)
        broadcastGroup(n, g.members.map { it.pk })
        true
    }

    suspend fun addMembers(gid: String, pks: List<String>) = updateGroup(gid) { g ->
        // ręczne dodanie przez administratora zdejmuje bana
        g.copy(members = g.members + pks.filter { pk -> !g.isMember(pk) }.distinct().map { Member(it, db.nameOf(it)) }, banned = g.banned - pks.toSet())
    }

    /** Wyciszenie w grupie (osoba czyta, ale nie pisze). [until] = Long.MAX_VALUE — do odwołania; 0 — zdjęcie wyciszenia. */
    suspend fun muteMember(gid: String, pk: String, until: Long) = updateGroup(gid) { g ->
        if (g.isAdmin(pk)) g
        else g.copy(muted = (g.muted.filterValues { it > System.currentTimeMillis() } - pk) + (if (until > System.currentTimeMillis()) mapOf(pk to until) else emptyMap()))
    }

    /** Ban: usunięcie z grupy bez możliwości powrotu z linku zaproszenia. */
    suspend fun banMember(gid: String, pk: String) = updateGroup(gid) { g ->
        g.copy(members = g.members.filter { it.pk != pk }, admins = g.admins.filter { it != pk }, removed = g.removed + pk,
            banned = (g.banned + pk).distinct(), muted = g.muted - pk)
    }

    suspend fun unbanMember(gid: String, pk: String) = updateGroup(gid) { g -> g.copy(banned = g.banned - pk) }

    suspend fun removeMember(gid: String, pk: String) = updateGroup(gid) { g ->
        g.copy(members = g.members.filter { it.pk != pk }, admins = g.admins.filter { it != pk }, removed = g.removed + pk)
    }

    suspend fun setAdmin(gid: String, pk: String, admin: Boolean) = updateGroup(gid) { g ->
        g.copy(admins = if (admin) (g.admins + pk).distinct() else g.admins.filter { it != pk }.ifEmpty { g.admins })
    }

    suspend fun renameGroup(gid: String, name: String) = updateGroup(gid) { it.copy(name = name.trim().ifEmpty { it.name }) }
    suspend fun setAdminsOnly(gid: String, on: Boolean) = updateGroup(gid) { it.copy(adminsOnly = on) }
    suspend fun resetInvite(gid: String) = updateGroup(gid) { it.copy(token = Crypto.randomBytes(8).hex()) }

    /** Zmiana wyglądu grupy: ikona (emoji lub obraz base64) i kolor akcentu. */
    suspend fun setGroupLook(gid: String, icon: String?, color: Int?) = updateGroup(gid) {
        it.copy(icon = (icon ?: it.icon).take(60_000), color = color ?: it.color)
    }

    suspend fun closeGroup(gid: String) {
        stateLock.withLock {
            val g = db.group(gid) ?: return
            if (!g.isAdmin(myPub)) return
            val n = g.copy(ver = g.ver + 1, state = GroupState.CLOSED)
            db.saveGroup(n)
            systemMessage(groupChatId(gid), "sys:$gid:closed", "Rozwiązałeś(-aś) grupę.")
            broadcastGroup(n, closed = true)
        }
    }

    suspend fun leaveGroup(gid: String) {
        stateLock.withLock {
            val g = db.group(gid) ?: return
            val others = g.members.filter { it.pk != myPub }
            if (g.state == GroupState.ACTIVE) {
                if (g.isAdmin(myPub) && g.admins.size == 1 && others.isNotEmpty()) {
                    // przekaż administrację, żeby grupa nie została bez admina
                    val n = g.copy(ver = g.ver + 1, admins = listOf(others.first().pk), members = others)
                    broadcastGroup(n, listOf(myPub))
                } else {
                    dispatch(JSONObject().put("t", "gleave").put("g", gid).put("ts", Clock.now()), others.map { it.pk }, null)
                }
            }
            db.saveGroup(g.copy(state = GroupState.LEFT))
            systemMessage(groupChatId(gid), "sys:$gid:left:${System.currentTimeMillis()}", "Opuściłeś(-aś) grupę.")
        }
    }

    suspend fun joinGroup(inv: Links.Parsed.GroupInvite): String = stateLock.withLock {
        val chatId = groupChatId(inv.gid)
        val existing = db.group(inv.gid)
        if (existing != null && existing.state == GroupState.ACTIVE && existing.isMember(myPub)) return@withLock chatId
        if (existing == null || existing.ver == 0) {
            db.saveGroup(Group(inv.gid, inv.name, 0, inv.admins, emptyList(), inv.token, false, GroupState.PENDING))
        } else db.saveGroup(existing.copy(state = GroupState.PENDING))
        db.ensureChat(chatId, true, null, inv.name)
        systemMessage(chatId, "sys:${inv.gid}:join:${System.currentTimeMillis()}",
            "Wysłano prośbę o dołączenie. Zostaniesz dodany(-a), gdy administrator będzie online.")
        inv.admins.forEach { db.upsertContact(it, null, false) }
        dispatch(JSONObject().put("t", "gjoin").put("g", inv.gid).put("tok", inv.token), inv.admins, null, sync = false)
        chatId
    }

    private val deferredUpdates = mutableListOf<Pair<String, JSONObject>>()

    private fun handleGroupUpdate(from: String, p: JSONObject) {
        if (applyGroupUpdate(from, p)) {
            // aktualizacje, które przyszły za wcześnie (np. od nowego admina) — spróbuj ponownie
            val gid = p.optString("g")
            var progress = true
            while (progress) {
                progress = false
                val pending = deferredUpdates.filter { it.second.optString("g") == gid }
                for (d in pending) if (applyGroupUpdate(d.first, d.second)) { deferredUpdates.remove(d); progress = true }
            }
        }
    }

    private fun applyGroupUpdate(from: String, p: JSONObject, trusted: Boolean = false): Boolean {
        val gid = p.optString("g").ifEmpty { return false }
        val ver = p.optInt("ver")
        val adminsArr = p.optJSONArray("admins") ?: return false
        val membersArr = p.optJSONArray("members") ?: return false
        val admins = List(adminsArr.length()) { adminsArr.getString(it) }.filter { it.isPubKey() }
        val members = List(membersArr.length()) { membersArr.getJSONObject(it) }
            .map { Member(it.getString("pk"), it.optString("n").take(60)) }.filter { it.pk.isPubKey() }.distinctBy { it.pk }
        val closed = p.optBoolean("closed")
        val rmArr = p.optJSONArray("rm")
        val rm = if (rmArr == null) emptyList() else List(rmArr.length()) { rmArr.optString(it) }.filter { it.isPubKey() }
        val old = db.group(gid)
        val known = old != null && old.ver > 0
        if (known && !trusted) {
            if (!old!!.isAdmin(from)) {
                if (ver > old.ver && deferredUpdates.size < 200) deferredUpdates.add(from to p)
                return false
            }
            if (ver < old.ver) return false
            val incomingState = Group(gid, p.optString("name"), ver, admins, members, p.optString("tok"), p.optBoolean("ao"), 0)
            if (ver == old.ver && !closed && stateHash(incomingState) <= stateHash(old)) return false
        } else if (!known) {
            if (from !in admins && !trusted) return false
        } else if (ver < old!!.ver) return false
        val meIn = members.any { it.pk == myPub }
        val self = from == myPub
        val state = when {
            closed -> GroupState.CLOSED
            meIn -> GroupState.ACTIVE
            old?.state == GroupState.LEFT || self -> GroupState.LEFT
            else -> GroupState.REMOVED
        }
        if (!known && !meIn) return false
        val n = cleanRemoved(
            Group(
                gid, p.optString("name").take(80).ifEmpty { "Grupa" }, ver, admins, members, p.optString("tok"), p.optBoolean("ao"), state, rm,
                p.optString("ic").take(60_000).ifEmpty { old?.icon ?: "" }, if (p.has("col")) p.optInt("col") else (old?.color ?: 0),
                // starsze wersje nie wysyłają tych pól — wtedy zostawiamy, co było
                if (p.has("ds")) p.optString("ds").take(1000) else old?.descr ?: "",
                if (p.has("ap")) p.optBoolean("ap") else old?.approve ?: false,
                if (p.has("inv")) Invite.listFrom(p.optJSONArray("inv")) else old?.invites ?: emptyList(),
                p.optJSONObject("mod")?.optJSONObject("mu")?.let { o -> o.keys().asSequence().filter { it.isHex64() }.take(500).associateWith { o.optLong(it) } } ?: old?.muted ?: emptyMap(),
                p.optJSONObject("mod")?.optJSONArray("bn")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isHex64() }.take(500) } ?: old?.banned ?: emptyList(),
            ),
            old?.removed ?: emptyList(),
        )
        db.saveGroup(n)
        db.joinReqs(gid).filter { r -> n.isMember(r.pk) }.forEach { db.removeJoinReq(gid, it.pk) }
        members.forEach { if (it.pk != myPub && it.name.isNotBlank()) db.upsertContact(it.pk, it.name, false) }
        val chatId = groupChatId(gid)
        db.ensureChat(chatId, true, null, n.name)
        db.renameChat(chatId, n.name)
        if (self && (old == null || old.ver == 0)) {
            systemMessage(chatId, "sys:$gid:added:$ver", "Grupa „${n.name}” (z Twojego innego urządzenia).")
        } else if (old == null || old.ver == 0 || old.state != GroupState.ACTIVE && state == GroupState.ACTIVE) {
            systemMessage(chatId, "sys:$gid:added:$ver", "${db.nameOf(from)} dodał(a) Cię do grupy „${n.name}”.")
            notifyMessage(chatId, from, "Dodano Cię do grupy", true)
        } else describeGroupChange(old, n)
        if (closed) systemMessage(chatId, "sys:$gid:closed", "Administrator rozwiązał grupę.")
        else if (state == GroupState.REMOVED && old?.state == GroupState.ACTIVE) systemMessage(chatId, "sys:$gid:removed:$ver", "Usunięto Cię z grupy.")
        else if (self && state == GroupState.LEFT && old?.state == GroupState.ACTIVE) systemMessage(chatId, "sys:$gid:left:$ver", "Opuściłeś(-aś) grupę (na innym urządzeniu).")
        return true
    }

    private fun describeGroupChange(old: Group, n: Group) {
        val chatId = groupChatId(n.id)
        val added = n.members.filter { !old.isMember(it.pk) }
        val removed = old.members.filter { !n.isMember(it.pk) }
        val texts = mutableListOf<String>()
        if (old.name != n.name) texts += "Zmieniono nazwę grupy na „${n.name}”."
        added.forEach { texts += "${it.name.ifBlank { db.nameOf(it.pk) }} dołączył(a) do grupy." }
        removed.forEach { if (it.pk != myPub) texts += "${it.name.ifBlank { db.nameOf(it.pk) }} nie jest już w grupie." }
        (n.admins - old.admins.toSet()).forEach { texts += "${if (it == myPub) "Jesteś teraz" else db.nameOf(it) + " jest teraz"} administratorem." }
        if (old.adminsOnly != n.adminsOnly) texts += if (n.adminsOnly) "Teraz tylko administratorzy mogą pisać." else "Wszyscy mogą znowu pisać."
        if (old.descr != n.descr) texts += if (n.descr.isBlank()) "Usunięto opis grupy." else "Zmieniono opis grupy."
        if (old.approve != n.approve) texts += if (n.approve) "Nowe osoby z linku czekają na zatwierdzenie przez administratora." else "Nowe osoby z linku dołączają od razu."
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("pl"))
        fun who(pk: String) = if (pk == myPub) "Ciebie" else db.nameOf(pk)
        n.muted.filter { (pk, u) -> u > now && (old.muted[pk] ?: 0L) != u }.forEach { (pk, u) ->
            texts += "🔇 Administrator wyciszył: ${who(pk)}" + (if (u < Long.MAX_VALUE) " (do ${fmt.format(java.util.Date(u))})" else "") + "."
        }
        old.muted.filter { (pk, u) -> u > now && !n.isMuted(pk, now) && n.isMember(pk) }.keys.forEach { texts += "🔊 ${if (it == myPub) "Możesz" else db.nameOf(it) + " może"} znowu pisać." }
        (n.banned - old.banned.toSet()).forEach { texts += "⛔ ${db.nameOf(it)} został(a) zbanowany(-a) w grupie." }
        texts.forEachIndexed { i, t -> systemMessage(chatId, "sys:${n.id}:${n.ver}:$i:${stateHash(n).take(6)}", t) }
    }

    private fun handleJoin(from: String, p: JSONObject) {
        val gid = p.optString("g")
        val g = db.group(gid) ?: return
        if (!g.isAdmin(myPub) || g.state != GroupState.ACTIVE) return
        val tok = p.optString("tok")
        if (!g.validToken(tok, System.currentTimeMillis())) return
        if (g.isMember(from)) { dispatch(groupPayload(g), listOf(from), null, sync = false); return }
        // zbanowany nie wraca — nawet z ważnym linkiem
        if (from in g.banned) return
        if (from in g.removed) {
            // usunięty przez admina nie wraca sam przez stary link
            systemMessage(groupChatId(gid), "sys:$gid:rejoin:$from", "${db.nameOf(from)} (usunięty z grupy) prosi o ponowne dołączenie. Możesz dodać tę osobę ręcznie.")
            return
        }
        val name = p.optString("n").ifBlank { db.nameOf(from) }
        if (g.approve) {
            // ręczne zatwierdzanie: prośba czeka w informacjach o grupie
            db.addJoinReq(JoinReq(gid, from, name, tok, Clock.now()))
            val chatId = groupChatId(gid)
            systemMessage(chatId, "sys:$gid:req:$from", "$name prosi o dołączenie do grupy. Zatwierdź w informacjach o grupie.")
            notifyMessage(chatId, from, "prosi o dołączenie do grupy", true)
            return
        }
        admit(g, from, name, tok)
    }

    /** Dodaje osobę z linku (i zwiększa licznik użyć zaproszenia z limitem). */
    private fun admit(g: Group, pk: String, name: String, tok: String) {
        val n = g.copy(
            ver = g.ver + 1, members = g.members + Member(pk, name),
            invites = g.invites.map { if (it.token == tok) it.copy(used = it.used + 1) else it },
        )
        db.saveGroup(n)
        db.removeJoinReq(g.id, pk)
        describeGroupChange(g, n)
        broadcastGroup(n)
    }

    /** Administrator zatwierdza prośbę o dołączenie. */
    suspend fun acceptJoin(gid: String, pk: String): Boolean = stateLock.withLock {
        val g = db.group(gid) ?: return@withLock false
        if (!g.isAdmin(myPub) || g.state != GroupState.ACTIVE) return@withLock false
        val r = db.joinReqs(gid).firstOrNull { it.pk == pk } ?: return@withLock false
        if (g.isMember(pk)) { db.removeJoinReq(gid, pk); return@withLock true }
        admit(g, pk, r.name, r.tok)
        true
    }

    /** Administrator odrzuca prośbę — osoba dostaje informację. */
    fun rejectJoin(gid: String, pk: String) = scope.launch {
        db.removeJoinReq(gid, pk)
        dispatch(JSONObject().put("t", "gdeny").put("g", gid), listOf(pk), null, sync = false)
    }

    private fun handleDeny(from: String, p: JSONObject) {
        val gid = p.optString("g")
        val g = db.group(gid) ?: return
        if (g.state != GroupState.PENDING || from !in g.admins) return
        db.saveGroup(g.copy(state = GroupState.REMOVED))
        systemMessage(groupChatId(gid), "sys:$gid:deny:${Clock.now()}", "Administrator odrzucił prośbę o dołączenie.")
        notifyMessage(groupChatId(gid), from, "Odrzucono prośbę o dołączenie", true)
    }

    suspend fun setGroupDescr(gid: String, d: String) = updateGroup(gid) { it.copy(descr = d.trim().take(1000)) }
    suspend fun setApprove(gid: String, on: Boolean) = updateGroup(gid) { it.copy(approve = on) }

    /** Nowy link zaproszenia: ważny przez [validMs] (0 = bez limitu), na [maxUses] osób (0 = bez limitu). */
    suspend fun createInvite(gid: String, validMs: Long, maxUses: Int): String? {
        val tok = Crypto.randomBytes(8).hex()
        val ok = updateGroup(gid) { g ->
            val now = System.currentTimeMillis()
            g.copy(invites = (g.invites.filter { it.valid(now) } + Invite(tok, if (validMs > 0) now + validMs else 0, maxUses, 0, myPub)).takeLast(40))
        }
        return if (ok) inviteLink(gid, tok) else null
    }

    suspend fun revokeInvite(gid: String, token: String) = updateGroup(gid) { g -> g.copy(invites = g.invites.filter { it.token != token }) }

    fun inviteLink(gid: String, token: String): String? {
        val g = db.group(gid) ?: return null
        return Links.group(g.id, token, g.admins, g.name)
    }

    private fun handleLeave(from: String, p: JSONObject) {
        val gid = p.optString("g")
        val g = db.group(gid) ?: return
        if (!g.isMember(from)) return
        val n = g.copy(members = g.members.filter { it.pk != from }, admins = g.admins.filter { it != from })
        if (g.isAdmin(myPub) && g.state == GroupState.ACTIVE) {
            val bumped = n.copy(ver = g.ver + 1)
            db.saveGroup(bumped)
            describeGroupChange(g, bumped)
            broadcastGroup(bumped)
        } else {
            db.saveGroup(n)
            describeGroupChange(g, n)
        }
    }

    // ================= media: wysyłanie paczek =================

    private fun encKey(mediaKey: ByteArray) = Crypto.hkdf(mediaKey, "szept/media".toByteArray(), "enc".toByteArray())
    private fun ivFor(mediaKey: ByteArray, idx: Int) = Crypto.hmac(Crypto.hkdf(mediaKey, "szept/media".toByteArray(), "iv".toByteArray()), "iv:$idx".toByteArray()).copyOf(12)
    private fun chunkSigner(mediaKey: ByteArray) = Crypto.deriveKey(mediaKey, "chunk-sign")

    private fun chunkEvent(u: Db.Upload, idx: Int, data: ByteArray): Event {
        val key = B64.dec(u.key)
        val enc = Crypto.encrypt(encKey(key), data, ivFor(key, idx), "${u.media}:$idx".toByteArray())
        // paczki relacji wygasają po ~25 h, zwykłych mediów po 21 dniach
        val life = if (u.msg.startsWith("story:")) STORY_MS / 1000 + 3600 else EXPIRE_SEC
        val tags = listOf(listOf("x", u.media), listOf("c", idx.toString()), listOf("expiration", (u.created + life).toString()))
        return Event.create(chunkSigner(key), CHUNK_KIND, tags, B64.enc(enc), u.created)
    }

    private fun internetTargets(): List<String> = configuredRelays().take(MEDIA_RELAYS)

    /** Internet + połączone przekaźniki w sieci lokalnej (tam paczki trafiają od razu, nawet bez internetu). */
    private fun mediaTargets(): List<String> = internetTargets() + localUrls.filter { relays[it]?.isConnected == true }

    private fun uploadComplete(msgId: String): Boolean {
        val u = db.uploadForMsg(msgId) ?: return true
        return internetTargets().all { val d = u.done.optInt(it, 0); d >= u.chunks || d < 0 }
    }

    /** Czy wiadomość/relacja, do której należy wysyłany plik, nadal istnieje. */
    private fun uploadOwnerExists(msgId: String): Boolean =
        if (msgId.startsWith("admin:")) true
        else if (msgId.startsWith("story:")) db.story(msgId.removePrefix("story:")) != null else db.message(msgId) != null

    fun kickUploads() {
        val list = db.uploads()
        for (u in list) {
            if (!uploadOwnerExists(u.msg) && db.pending(u.msg) == null) {
                db.removeUpload(u.media)
                if (!u.msg.startsWith("story:")) File(u.path).delete()
                continue
            }
            for (url in mediaTargets()) {
                val d = u.done.optInt(url, 0)
                if (d >= u.chunks || d < 0) continue
                val relay = relays[url] ?: continue
                if (!relay.isConnected) continue
                val key = url + "|" + u.media
                if (!uploading.add(key)) continue
                scope.launch {
                    try { uploadTo(relay, u.media) } catch (e: Exception) { Log.w(TAG, "upload", e) } finally { uploading.remove(key) }
                }
            }
        }
    }

    private suspend fun uploadTo(relay: Relay, mediaId: String) {
        val u = db.upload(mediaId) ?: return
        val file = File(u.path)
        if (!file.exists()) { db.updateStatus(u.msg, St.FAILED, false); return }
        var done = u.done.optInt(relay.url, 0)
        val ids = if (u.ids.isEmpty()) arrayOfNulls<String>(u.chunks) else u.ids.split(',').toTypedArray<String?>()
        RandomAccessFile(file, "r").use { raf ->
            while (done < u.chunks) {
                if (!uploadOwnerExists(u.msg)) return
                val batch = (done until min(done + 4, u.chunks)).toList()
                val results = coroutineScopeAsync(batch) { idx ->
                    val len = min(CHUNK_RAW.toLong(), file.length() - idx.toLong() * CHUNK_RAW).toInt()
                    val buf = ByteArray(len)
                    synchronized(raf) { raf.seek(idx.toLong() * CHUNK_RAW); raf.readFully(buf) }
                    val ev = chunkEvent(u, idx, buf)
                    ids[idx] = ev.id
                    var r = relay.publish(ev.toJson(), ev.id, 30_000)
                    var tries = 0
                    while (!r.first && r.second.startsWith("rate-limited") && tries++ < 4) {
                        delay(2000L * tries); r = relay.publish(ev.toJson(), ev.id, 30_000)
                    }
                    r
                }
                val fail = results.firstOrNull { !it.first && !it.second.startsWith("duplicate") }
                if (fail != null) {
                    if (PERMANENT_ERRORS.any { fail.second.startsWith(it) }) {
                        Log.w(TAG, "${relay.url} odrzucił media: ${fail.second}")
                        db.setUploadDone(mediaId, relay.url, -1)
                        if (internetTargets().all { db.upload(mediaId)?.done?.optInt(it, 0) == -1 }) db.updateStatus(u.msg, St.FAILED, false)
                    }
                    return
                }
                done = batch.last() + 1
                db.setUploadDone(mediaId, relay.url, done)
                if (ids.all { it != null }) db.setUploadIds(mediaId, ids.joinToString(","))
                val best = mediaTargets().maxOf { db.upload(mediaId)?.done?.optInt(it, 0) ?: 0 }
                db.setProgress(u.msg, (best * 100 / u.chunks).coerceIn(0, 100))
            }
        }
        // pierwszy przekaźnik ma komplet — można wysłać koperty
        stateLock.withLock {
            val pend = db.pending(u.msg)
            if (pend != null) {
                db.removePending(u.msg)
                release(u.msg, JSONObject(pend.first), pend.second)
            }
        }
        if (uploadComplete(u.msg) && !u.msg.startsWith("story:")) {
            val m = db.message(u.msg)
            if (m == null || m.path == null) file.delete()
        }
    }

    private suspend fun <T> coroutineScopeAsync(items: List<Int>, block: suspend (Int) -> T): List<T> =
        kotlinx.coroutines.coroutineScope { items.map { async { block(it) } }.awaitAll() }

    // ================= media: pobieranie paczek =================

    /** Nazwa pliku z danych z sieci: bez „/”, „..” i innych znaków spoza bezpiecznego zestawu. */
    private fun safeName(n: String): String = n.replace(Regex("[^A-Za-z0-9_.:-]"), "_").replace("..", "_").take(120).ifEmpty { "_" }

    fun kickDownloads() {
        for (d in db.downloads()) {
            if (downloads.containsKey(d.media)) continue
            if (connectedRelays().isEmpty() || downloads.size >= 4) return
            // uszkodzony wpis (np. zły klucz) — usuwamy, zamiast wywracać aplikację przy każdym starcie
            val job = try { DownloadJob(d) } catch (e: Exception) { CrashLog.record("Zły plik do pobrania", e); db.removeDownload(d.media); continue }
            if (downloads.putIfAbsent(d.media, job) == null) job.start()
        }
    }

    inner class DownloadJob(private val d: Db.Download) {
        val subId = "m-" + d.media.take(16)
        private val dir = File(tmpDir, safeName(d.media)).apply { mkdirs() }
        private val mediaKey = B64.dec(d.meta.key)
        private val signer = Crypto.publicKey(chunkSigner(mediaKey)).hex()
        private val have = ConcurrentHashMap.newKeySet<Int>().apply {
            dir.listFiles()?.forEach { f -> f.name.toIntOrNull()?.let { add(it) } }
        }
        private val eose = ConcurrentHashMap.newKeySet<String>()
        private val signal = Channel<Unit>(Channel.CONFLATED)
        private var job: Job? = null
        private var asked = listOf<Relay>()

        fun start() {
            job = scope.launch {
                try { run() } catch (e: Exception) { Log.w(TAG, "download", e) } finally {
                    asked.forEach { it.unsubscribe(subId) }
                    downloads.remove(d.media)
                }
            }
        }

        fun cancel() { job?.cancel(); dir.deleteRecursively() }

        private suspend fun run() {
            if (have.size < d.meta.chunks) {
                asked = connectedRelays()
                val filter = JSONObject().put("kinds", JSONArray().put(CHUNK_KIND)).put("#x", JSONArray().put(d.media)).put("limit", d.meta.chunks + 20)
                asked.forEach { it.subscribe(subId, listOf(filter)) }
                val deadline = System.currentTimeMillis() + 120_000
                var allEoseAt = 0L
                while (have.size < d.meta.chunks && System.currentTimeMillis() < deadline) {
                    withTimeoutOrNull(1000) { signal.receive() }
                    db.setProgress(d.msg, have.size * 100 / d.meta.chunks)
                    if (eose.size >= asked.size || asked.none { it.isConnected && it.hasSub(subId) && it.url !in eose }) {
                        if (allEoseAt == 0L) allEoseAt = System.currentTimeMillis()
                        if (System.currentTimeMillis() - allEoseAt > 4000) break
                    }
                }
            }
            if (have.size >= d.meta.chunks) finish()
            else {
                db.bumpDownload(d.media)
                val ageDays = (System.currentTimeMillis() - d.created) / 86400_000
                if (d.tries > 30 && ageDays > 3) {
                    db.removeDownload(d.media); db.updateStatus(d.msg, St.DOWNLOAD_FAILED, false); dir.deleteRecursively()
                }
            }
        }

        fun onEose(url: String) { eose.add(url); signal.trySend(Unit) }

        fun onChunk(o: JSONObject) {
            try {
                val ev = Event.fromJson(o)
                if (ev.pubkey != signer || ev.kind != CHUNK_KIND || ev.tag("x") != d.media) return
                val idx = ev.tag("c")?.toIntOrNull() ?: return
                if (idx < 0 || idx >= d.meta.chunks || idx in have) return
                if (!ev.verify()) return
                val plain = Crypto.decrypt(encKey(mediaKey), B64.dec(ev.content), "${d.media}:$idx".toByteArray())
                val f = File(dir, idx.toString())
                File(dir, "$idx.tmp").apply { writeBytes(plain); renameTo(f) }
                have.add(idx)
                signal.trySend(Unit)
            } catch (e: Exception) { Log.w(TAG, "chunk", e) }
        }

        private fun finish() {
            val isStory = d.msg.startsWith("story:")
            val exists = if (isStory) db.story(d.msg.removePrefix("story:")) != null else db.message(d.msg) != null
            if (!exists) { db.removeDownload(d.media); dir.deleteRecursively(); return }
            val ext = d.meta.name.substringAfterLast('.', "bin").take(5)
            val out = File(mediaDir, safeName((if (isStory) "story_" + d.msg.removePrefix("story:") else d.msg) + ".$ext"))
            out.outputStream().use { os -> for (i in 0 until d.meta.chunks) File(dir, i.toString()).inputStream().use { it.copyTo(os) } }
            if (sha256File(out) != d.meta.sha) {
                Log.w(TAG, "zła suma kontrolna mediów")
                out.delete(); dir.deleteRecursively(); have.clear()
                db.bumpDownload(d.media)
                return
            }
            dir.deleteRecursively()
            db.removeDownload(d.media)
            if (isStory) {
                if (db.story(d.msg.removePrefix("story:")) == null) out.delete() else db.setStoryPath(d.msg.removePrefix("story:"), out.absolutePath)
                return
            }
            val owner = db.message(d.msg) ?: run { out.delete(); return }
            db.setPath(d.msg, out.absolutePath, if (owner.mine) St.SENT else St.RECEIVED)
        }
    }

    // ================= różne =================

    private fun sha256File(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().hex()
    }

    private fun cleanupOrphanFiles() {
        val used = db.allMediaPaths() + db.uploads().map { it.path }
        mediaDir.listFiles()?.forEach { if (it.absolutePath !in used && System.currentTimeMillis() - it.lastModified() > 3600_000) it.delete() }
    }

    fun relayInfo(): List<Triple<String, Relay.State, String?>> =
        configuredRelays().map { url -> relays[url].let { Triple(url, it?.state ?: Relay.State.DISCONNECTED, it?.lastError) } }
}
