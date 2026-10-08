package pl.szept.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pojedyncze połączenie WebSocket z przekaźnikiem Nostr.
 * Samo się odnawia (wykładniczy backoff), obsługuje NIP-42 AUTH i potwierdzenia OK.
 */
class Relay(
    val url: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onRelayEvent(relay: Relay, subId: String, event: JSONObject)
        fun onRelayEose(relay: Relay, subId: String)
        fun onRelayClosed(relay: Relay, subId: String, message: String) {}
        fun onRelayConnected(relay: Relay)
        fun onRelayStateChanged(relay: Relay)
        /** Podpisuje zdarzenie AUTH (kind 22242) dla wyzwania. */
        fun signAuth(relay: Relay, challenge: String): Event?
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var authenticated = false
        private set

    private var ws: WebSocket? = null
    private var generation = 0
    private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private val subs = ConcurrentHashMap<String, String>()          // subId -> pełna wiadomość REQ
    private val authBlockedSubs = ConcurrentHashMap.newKeySet<String>()
    private val pendingOk = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    @Volatile private var authEventId: String? = null
    private var authWaiter = CompletableDeferred<Boolean>()

    val isConnected get() = state == State.CONNECTED

    @Synchronized
    fun connect() {
        wanted = true
        if (state == State.DISCONNECTED) open()
    }

    @Synchronized
    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        generation++
        ws?.close(1000, "bye")
        ws = null
        setState(State.DISCONNECTED)
        failPending("disconnected")
    }

    /** Wymusza szybkie ponowne połączenie (np. po zmianie sieci). */
    @Synchronized
    fun reconnectNow(force: Boolean = false) {
        if (!wanted) return
        attempt = 0
        if (force && state != State.DISCONNECTED) {
            generation++
            ws?.cancel()
            ws = null
            setState(State.DISCONNECTED)
            failPending("reconnect")
        }
        if (state == State.DISCONNECTED) { reconnectJob?.cancel(); open() }
    }

    private fun open() {
        val gen = ++generation
        setState(State.CONNECTING)
        authenticated = false
        authEventId = null
        if (authWaiter.isCompleted) authWaiter = CompletableDeferred()
        val req = try { Request.Builder().url(url).header("User-Agent", "Szept/1.0").build() } catch (e: IllegalArgumentException) {
            // zły adres (np. „wss://” bez nazwy) — przekaźnik po prostu nie działa, aplikacja się nie wywraca
            setState(State.DISCONNECTED); return
        }
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.header("Date")?.let { d ->
                    try {
                        val server = java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                        Clock.addSample(server, (response.sentRequestAtMillis + response.receivedResponseAtMillis) / 2)
                    } catch (_: Exception) {}
                }
                synchronized(this@Relay) {
                    if (gen != generation) return
                    attempt = 0
                    lastError = null
                    setState(State.CONNECTED)
                    subs.values.forEach { webSocket.send(it) }
                }
                listener.onRelayConnected(this@Relay)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                dropped(gen, "zamknięte: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen, "zamknięte: $code")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                dropped(gen, (t.message ?: t.javaClass.simpleName) + (response?.let { " (HTTP ${it.code})" } ?: ""))
        })
    }

    private fun dropped(gen: Int, reason: String) {
        synchronized(this) {
            if (gen != generation) return
            generation++
            ws = null
            lastError = reason
            setState(State.DISCONNECTED)
            failPending(reason)
            if (!wanted) return
            val delayMs = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
            attempt++
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(this@Relay) { if (wanted && state == State.DISCONNECTED) open() }
            }
        }
    }

    private fun failPending(reason: String) {
        val list = pendingOk.values.toList()
        pendingOk.clear()
        list.forEach { it.complete(false to "net: $reason") }
    }

    private fun setState(s: State) {
        if (state != s) { state = s; listener.onRelayStateChanged(this) }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val arr = try { JSONArray(text) } catch (e: Exception) { return }
        when (arr.optString(0)) {
            "EVENT" -> {
                val ev = arr.optJSONObject(2) ?: return
                listener.onRelayEvent(this, arr.optString(1), ev)
            }
            "EOSE" -> listener.onRelayEose(this, arr.optString(1))
            "OK" -> {
                val id = arr.optString(1)
                val ok = arr.optBoolean(2)
                val msg = arr.optString(3)
                if (id == authEventId) {
                    authenticated = ok
                    authWaiter.complete(ok)
                    if (ok) {
                        val again = authBlockedSubs.toList()
                        authBlockedSubs.clear()
                        again.forEach { sid -> subs[sid]?.let { webSocket.send(it) } }
                    }
                }
                pendingOk.remove(id)?.complete(ok to msg)
            }
            "CLOSED" -> {
                val sid = arr.optString(1)
                val msg = arr.optString(2)
                if (msg.startsWith("auth-required")) {
                    if (authenticated) subs[sid]?.let { webSocket.send(it) } else authBlockedSubs.add(sid)
                } else listener.onRelayClosed(this, sid, msg)
            }
            "AUTH" -> {
                val challenge = arr.optString(1)
                val ev = listener.signAuth(this, challenge) ?: return
                authEventId = ev.id
                webSocket.send("[\"AUTH\"," + ev.toJson() + "]")
            }
        }
    }

    fun subscribe(subId: String, filters: List<JSONObject>) {
        val msg = StringBuilder("[\"REQ\",").append(JSONObject.quote(subId))
        filters.forEach { msg.append(',').append(it.toString()) }
        msg.append(']')
        val s = msg.toString()
        subs[subId] = s
        synchronized(this) { if (state == State.CONNECTED) ws?.send(s) }
    }

    fun unsubscribe(subId: String) {
        if (subs.remove(subId) != null) {
            synchronized(this) { if (state == State.CONNECTED) ws?.send("[\"CLOSE\"," + JSONObject.quote(subId) + "]") }
        }
        authBlockedSubs.remove(subId)
    }

    fun hasSub(subId: String) = subs.containsKey(subId)

    /** Publikuje zdarzenie i czeka na odpowiedź OK. Zwraca (sukces, komunikat). */
    suspend fun publish(eventJson: String, eventId: String, timeoutMs: Long = 20_000): Pair<Boolean, String> {
        repeat(2) { round ->
            val d = CompletableDeferred<Pair<Boolean, String>>()
            pendingOk[eventId] = d
            val sent = synchronized(this) { state == State.CONNECTED && ws?.send("[\"EVENT\",$eventJson]") == true }
            if (!sent) { pendingOk.remove(eventId); return false to "net: brak połączenia" }
            val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: run { pendingOk.remove(eventId); false to "net: timeout" }
            if (!r.first && r.second.startsWith("auth-required") && round == 0) {
                // przekaźnik chce uwierzytelnienia — poczekaj na AUTH i spróbuj jeszcze raz
                val ok = withTimeoutOrNull(8_000) { authWaiter.await() } ?: false
                if (!ok) return r
            } else return r
        }
        return false to "auth-required"
    }

    companion object {
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Czy adres przekaźnika jest poprawny (ws:// lub wss:// z nazwą hosta i portem w zakresie). */
fun isValidRelayUrl(u: String): Boolean {
    val t = u.trim()
    if (!t.startsWith("ws://") && !t.startsWith("wss://")) return false
    val http = "http" + t.removePrefix("ws")
    return try { java.net.URI(http).let { it.host != null && it.port <= 65535 } && okhttp3.HttpUrl.Companion.run { http.toHttpUrlOrNull() } != null } catch (e: Exception) { false }
}
