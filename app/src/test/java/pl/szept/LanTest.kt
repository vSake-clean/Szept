package pl.szept

import android.app.Application
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.Links
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import pl.szept.nostr.Clock
import pl.szept.nostr.Event
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tryb bez internetu: przekaźnik w telefonie (prawdziwe gniazda sieciowe na 127.0.0.1),
 * wymiana wiadomości i zdjęć między telefonami bez żadnego przekaźnika w internecie, kurier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LanTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private suspend fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(200)
        }
        throw AssertionError("Timeout: $what")
    }

    /** Prosty klient WebSocket do sprawdzania protokołu. */
    private class Client(url: String) : WebSocketListener() {
        val inbox = Channel<JSONArray>(Channel.UNLIMITED)
        val ws: WebSocket = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
            .newWebSocket(Request.Builder().url(url).build(), this)
        override fun onMessage(webSocket: WebSocket, text: String) { inbox.trySend(JSONArray(text)) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.close(t) }
        fun send(vararg items: Any) { ws.send(JSONArray(items.toList()).toString()) }
        suspend fun next(): JSONArray = withTimeout(10_000) { inbox.receive() }
        suspend fun until(type: String): JSONArray { while (true) { val m = next(); if (m.optString(0) == type) return m } }
    }

    private fun wrap(to: String, ttl: Long = 3600, priv: ByteArray = Crypto.newPrivateKey()): Event =
        Event.create(priv, 1059, listOf(listOf("p", to), listOf("expiration", (Clock.nowSec() + ttl).toString())), "zaszyfrowane-" + Crypto.randomBytes(4).hex())

    @Test
    fun relayProtocol() = runBlocking {
        val relay = LocalRelay(ctx, "lan_proto_${System.nanoTime()}.db")
        val port = relay.start(0)
        val url = "ws://127.0.0.1:$port"
        val bob = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        val alice = Client(url)

        // przyjmuje kopertę, duplikat też jest „OK”
        val e1 = wrap(bob)
        alice.send("EVENT", JSONObject(e1.toJson()))
        val ok = alice.until("OK"); assertEquals(e1.id, ok.getString(1)); assertTrue(ok.getBoolean(2))
        alice.send("EVENT", JSONObject(e1.toJson()))
        val dup = alice.until("OK"); assertTrue(dup.getBoolean(2)); assertTrue(dup.getString(3).startsWith("duplicate"))

        // odrzuca obce rodzaje zdarzeń, wygasłe i ze złym podpisem
        val note = Event.create(Crypto.newPrivateKey(), 1, emptyList(), "publiczny wpis")
        alice.send("EVENT", JSONObject(note.toJson()))
        assertFalse(alice.until("OK").getBoolean(2))
        val old = Event.create(Crypto.newPrivateKey(), 1059, listOf(listOf("p", bob), listOf("expiration", (Clock.nowSec() - 5).toString())), "x")
        alice.send("EVENT", JSONObject(old.toJson()))
        assertFalse(alice.until("OK").getBoolean(2))
        val forged = JSONObject(wrap(bob).toJson()).put("content", "podmieniona treść")
        alice.send("EVENT", forged)
        val bad = alice.until("OK"); assertFalse(bad.getBoolean(2)); assertTrue(bad.getString(3).startsWith("invalid"))

        // zapytanie: zapisane koperty + EOSE, potem nowe na żywo
        val bobClient = Client(url)
        bobClient.send("REQ", "in", JSONObject().put("kinds", JSONArray().put(1059)).put("#p", JSONArray().put(bob)))
        val got = bobClient.until("EVENT"); assertEquals(e1.id, got.getJSONObject(2).getString("id"))
        bobClient.until("EOSE")
        val e2 = wrap(bob)
        alice.send("EVENT", JSONObject(e2.toJson())); alice.until("OK")
        assertEquals(e2.id, bobClient.until("EVENT").getJSONObject(2).getString("id"))
        // koperta do kogoś innego nie trafia do Boba
        alice.send("EVENT", JSONObject(wrap(Crypto.publicKey(Crypto.newPrivateKey()).hex()).toJson())); alice.until("OK")

        // NIP-09: tylko autor może usunąć
        val ephPriv = Crypto.newPrivateKey()
        val e3 = wrap(bob, priv = ephPriv)
        alice.send("EVENT", JSONObject(e3.toJson())); alice.until("OK")
        val fake = Event.create(Crypto.newPrivateKey(), 5, listOf(listOf("e", e3.id)), "")
        alice.send("EVENT", JSONObject(fake.toJson())); alice.until("OK")
        assertEquals(1, relay.query(JSONObject().put("ids", JSONArray().put(e3.id))).size)
        val del = Event.create(ephPriv, 5, listOf(listOf("e", e3.id), listOf("k", "1059")), "")
        alice.send("EVENT", JSONObject(del.toJson())); alice.until("OK")
        assertEquals(0, relay.query(JSONObject().put("ids", JSONArray().put(e3.id))).size)

        // limit i kolejność (najnowsze najpierw), filtr #x dla paczek mediów
        val chunk = Event.create(Crypto.newPrivateKey(), 4334, listOf(listOf("x", "media1"), listOf("c", "0")), "paczka")
        alice.send("EVENT", JSONObject(chunk.toJson())); alice.until("OK")
        assertEquals(listOf(chunk.id), relay.query(JSONObject().put("kinds", JSONArray().put(4334)).put("#x", JSONArray().put("media1"))).map { JSONObject(it).getString("id") })
        assertEquals(1, relay.query(JSONObject().put("#p", JSONArray().put(bob)).put("limit", 1)).size)

        // od innych telefonów (nie od siebie) — czekają na wysłanie do internetu
        assertTrue(relay.pendingForward().isNotEmpty())
        val self = Client("ws://127.0.0.1:$port" + relay.selfPath)
        val mine = wrap(bob)
        self.send("EVENT", JSONObject(mine.toJson())); self.until("OK")
        assertTrue(relay.pendingForward().none { JSONObject(it).getString("id") == mine.id })

        // NIP-11 przez zwykłe HTTP
        val info = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port").header("Accept", "application/nostr+json").build()).execute()
        assertTrue(info.body!!.string().contains("supported_nips"))
        listOf(alice, bobClient, self).forEach { it.ws.close(1000, null) }
        relay.close()
        println("PROTOKÓŁ PRZEKAŹNIKA OK")
    }

    private fun offlineCore(name: String): Core = Core(ctx, name + System.nanoTime()).apply {
        createIdentity(name)
        setRelays(emptyList())   // żadnego przekaźnika w internecie
        appVisible = true
        start()
    }

    @Test
    fun offlineExchange() = runBlocking {
        val a = offlineCore("Ala")
        val b = offlineCore("Bartek")
        assertTrue(a.connectedRelays().isEmpty())
        a.setLanEnabled(true); b.setLanEnabled(true)
        waitFor("A ma przekaźnik w telefonie") { a.lan.running.value && a.connectedRelays().isNotEmpty() }
        val aPort = a.lan.relay.port
        // B dołącza do telefonu A (jak po znalezieniu w sieci Wi-Fi)
        b.lan.addManual("127.0.0.1:$aPort")
        waitFor("B połączony z A") { b.connectedRelays().any { it.url == "ws://127.0.0.1:$aPort" } }

        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")
        val m = a.sendText(dmChatId(b.myPub), "Cześć bez internetu!")
        waitFor("B dostał wiadomość przez telefon A") { b.db.message(m.id)?.body == "Cześć bez internetu!" }
        waitFor("A widzi wysłaną") { (a.db.message(m.id)?.status ?: 0) >= St.SENT }

        // zdjęcie: paczki idą do przekaźnika w telefonie, B je pobiera
        val img = File(ctx.cacheDir, "lan.jpg").apply { writeBytes(Crypto.randomBytes(150_000)) }
        val bytes = img.readBytes()
        val pm = a.send(dmChatId(b.myPub), Kind.IMAGE, "zdjęcie offline", Core.Attachment(img, "image/jpeg", "lan.jpg", null, 4, 3, 0))
        waitFor("B pobrał zdjęcie", 40_000) { b.db.message(pm.id)?.path?.let { File(it).exists() } == true }
        assertTrue(File(b.db.message(pm.id)!!.path!!).readBytes().contentEquals(bytes))

        // odpowiedź B -> A (B wysyła do przekaźnika A)
        val r = b.sendText(dmChatId(a.myPub), "Dostałem 👍")
        waitFor("A dostał odpowiedź") { a.db.message(r.id) != null }

        // store-and-forward: C jeszcze go nie ma; A pisze, C pojawia się później
        val c = offlineCore("Celina")
        a.addContact(c.myPub, "Celina")
        val toC = a.sendText(dmChatId(c.myPub), "Zostawiam Ci wiadomość")
        delay(1500)
        c.setLanEnabled(true)
        c.lan.addManual("127.0.0.1:$aPort")
        waitFor("C odebrał zostawioną wiadomość") { c.db.message(toC.id) != null }
        assertTrue(a.lan.relay.clientCount >= 2)
        listOf(a, b, c).forEach { it.stop() }
        println("WYMIANA BEZ INTERNETU OK")
    }

    @Test
    fun courier() = runBlocking {
        // „internet” w teście to osobny przekaźnik na 127.0.0.1
        val inet = LocalRelay(ctx, "inet_${System.nanoTime()}.db")
        val inetUrl = "ws://127.0.0.1:" + inet.start(0)
        fun core(name: String, online: Boolean) = Core(ctx, name + System.nanoTime()).apply {
            createIdentity(name); setRelays(if (online) listOf(inetUrl) else emptyList()); appVisible = true; start()
        }
        val kurier = core("Kurier", online = true)
        val bez = core("BezZasiegu", online = false)
        val daleko = core("Daleko", online = true)
        kurier.setLanEnabled(true); bez.setLanEnabled(true)
        waitFor("kurier ma przekaźnik") { kurier.lan.running.value && kurier.lan.relay.port > 0 }
        bez.lan.addManual("127.0.0.1:${kurier.lan.relay.port}")
        waitFor("telefon bez zasięgu połączony z kurierem") { bez.connectedRelays().isNotEmpty() }

        // w górę: wiadomość z telefonu bez zasięgu trafia przez kuriera do internetu
        bez.addContact(daleko.myPub, "Daleko")
        val up = bez.sendText(dmChatId(daleko.myPub), "Przekaż dalej, proszę")
        waitFor("odbiorca w internecie dostał wiadomość przez kuriera", 40_000) { daleko.db.message(up.id) != null }

        // w dół: kurier zabiera z internetu koperty dla znajomych i oddaje je w sieci lokalnej
        kurier.addContact(bez.myPub, "BezZasiegu")
        kurier.setCourier(true)
        daleko.addContact(bez.myPub, "BezZasiegu")
        val down = daleko.sendText(dmChatId(bez.myPub), "Odpowiedź przez kuriera")
        waitFor("telefon bez zasięgu dostał odpowiedź", 40_000) { bez.db.message(down.id) != null }
        listOf(kurier, bez, daleko).forEach { it.stop() }
        inet.close()
        println("KURIER OK")
    }

    @Test
    fun lanLink() {
        val h = pl.szept.lan.LanManager.Hotspot("DIRECT-ab Szept", "tajne haslo&1", "192.168.49.1", 4869)
        val p = Links.parse(pl.szept.lan.LanManager.joinLink(h)) as Links.Parsed.Lan
        assertEquals("DIRECT-ab Szept", p.ssid); assertEquals("tajne haslo&1", p.pass); assertEquals("192.168.49.1", p.host); assertEquals(4869, p.port)
        assertEquals(null, Links.parse("szept://lan?p=x"))
    }
}
