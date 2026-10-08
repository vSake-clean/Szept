package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.call.CallManager.Phase
import pl.szept.call.IceServer
import pl.szept.call.RtcEngine
import pl.szept.call.RtcFactory
import pl.szept.core.Core
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import java.util.Collections

/**
 * Rozmowy: sygnalizacja (oferta, odpowiedź, rozłączenie, zajęte, odrzucenie, nieodebrane, drugie urządzenie)
 * przez prawdziwy przekaźnik na 127.0.0.1. Zamiast WebRTC — atrapa silnika (WebRTC działa tylko na telefonie).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CallTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** Atrapa: „łączy się”, gdy obie strony wymieniły opisy sesji. */
    class FakeEngine(private val l: RtcEngine.Listener, val log: MutableList<String>) : RtcEngine {
        override suspend fun createOffer() = "v=0 oferta".also { log += "offer" }
        override suspend fun createAnswer(offer: String): String { log += "answer:$offer"; l.onConnected(); return "v=0 odpowiedz" }
        override suspend fun applyAnswer(answer: String) { log += "apply:$answer"; l.onConnected() }
        override fun setMuted(muted: Boolean) { log += "mute:$muted" }
        override fun setVideoEnabled(on: Boolean) { log += "cam:$on" }
        override fun switchCamera() {}
        override fun close() { log += "close" }
    }

    private fun fake(log: MutableList<String>, ice: MutableList<List<IceServer>>? = null) =
        RtcFactory { _, servers, l -> ice?.add(servers); FakeEngine(l, log) }

    private suspend fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(100)
        }
        throw AssertionError("Timeout: $what")
    }

    private class Rings : pl.szept.core.Notifier {
        val events = Collections.synchronizedList(mutableListOf<String>())
        override fun onMessage(chatId: String, chatName: String, sender: String, text: String, isGroup: Boolean) { events += "msg:$text" }
        override fun cancel(chatId: String) {}
        override fun onCallRing(call: pl.szept.call.CallManager.Call?, name: String) { events += if (call == null) "stop" else "ring:$name" }
    }

    private fun core(name: String, url: String, restoreFrom: Core? = null) = Core(ctx, name + System.nanoTime()).apply {
        if (restoreFrom != null) restoreIdentity(restoreFrom.myPriv.hex(), name) else createIdentity(name)
        setRelays(listOf(url)); appVisible = true; start()
        calls.factory = fake(Collections.synchronizedList(mutableListOf()))
        calls.endedLingerMs = 600_000
    }

    private fun logs(c: Core, peer: String) = c.db.messages(dmChatId(peer), 50).filter { it.kind == Kind.SYSTEM && it.id.startsWith("call:") }.map { it.body }

    @Test
    fun calls() = runBlocking {
        val inet = LocalRelay(ctx, "call_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b = core("Bartek", url); val c = core("Celina", url)
        waitFor("połączeni") { listOf(a, b, c).all { it.connectedRelays().isNotEmpty() } }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala"); c.addContact(a.myPub, "Ala"); a.addContact(c.myPub, "Celina")
        waitFor("kontakty") { b.db.contact(a.myPub) != null && a.db.contact(c.myPub) != null }
        val rings = Rings(); b.notifier = rings
        val aLog = Collections.synchronizedList(mutableListOf<String>())
        val ice = mutableListOf<List<IceServer>>()
        a.calls.factory = fake(aLog, ice)

        // 1. połączenie głosowe: dzwoni, odbiera, rozmowa, rozłączenie
        a.calls.startCall(b.myPub, video = false)
        assertEquals(Phase.DIALING, a.calls.state.value!!.phase)
        waitFor("B dzwoni") { b.calls.state.value?.phase == Phase.RINGING }
        assertTrue(rings.events.any { it == "ring:Ala" })
        assertTrue("STUN w konfiguracji", ice.single().any { it.url.startsWith("stun:") })
        b.calls.answer()
        waitFor("połączeni po obu stronach") { a.calls.state.value?.phase == Phase.CONNECTED && b.calls.state.value?.phase == Phase.CONNECTED }
        assertTrue("A dostał odpowiedź B", aLog.contains("apply:v=0 odpowiedz"))
        a.calls.setMuted(true)
        assertTrue(a.calls.state.value!!.muted)
        delay(1100)
        a.calls.hangup()
        waitFor("rozłączeni") { b.calls.state.value?.phase == Phase.ENDED && a.calls.state.value?.phase == Phase.ENDED }
        assertTrue(aLog.contains("close"))
        waitFor("wpisy w historii rozmowy") { logs(a, b.myPub).any { it.startsWith("📞 Połączenie głosowe · 0:0") } && logs(b, a.myPub).any { it.startsWith("📞 Połączenie głosowe · 0:0") } }
        a.calls.clearEnded(); b.calls.clearEnded()

        // 2. odrzucenie (wideo)
        a.calls.startCall(b.myPub, video = true)
        waitFor("B dzwoni (wideo)") { b.calls.state.value?.let { it.phase == Phase.RINGING && it.video } == true }
        b.calls.decline()
        waitFor("A: odrzucono") { a.calls.state.value?.let { it.phase == Phase.ENDED && it.endReason == "Odrzucono" } == true }
        waitFor("historia: odrzucone") { logs(a, b.myPub).any { it == "📹 Rozmowa wideo · odrzucone" } }
        a.calls.clearEnded(); b.calls.clearEnded()

        // 3. zajęte: A rozmawia z B, C dzwoni do A
        a.calls.startCall(b.myPub, video = false)
        waitFor("B dzwoni") { b.calls.state.value?.phase == Phase.RINGING }
        b.calls.answer()
        waitFor("A-B połączeni") { a.calls.state.value?.phase == Phase.CONNECTED }
        c.calls.startCall(a.myPub, video = false)
        waitFor("C słyszy: zajęte") { c.calls.state.value?.let { it.phase == Phase.ENDED && it.endReason == "Zajęte" } == true }
        assertEquals("rozmowa A-B trwa", Phase.CONNECTED, a.calls.state.value!!.phase)
        b.calls.hangup()
        waitFor("A: rozmówca się rozłączył") { a.calls.state.value?.let { it.phase == Phase.ENDED && it.endReason == "Rozmówca się rozłączył" } == true }
        listOf(a, b, c).forEach { it.calls.clearEnded() }

        // 4. nieodebrane: nikt nie odbiera
        a.calls.ringMs = 2500; b.calls.ringMs = 2500
        rings.events.clear()
        a.calls.startCall(b.myPub, video = false)
        waitFor("A: brak odpowiedzi") { a.calls.state.value?.let { it.phase == Phase.ENDED && it.endReason == "Brak odpowiedzi" } == true }
        waitFor("B: nieodebrane + powiadomienie") { b.calls.state.value?.phase == Phase.ENDED && rings.events.contains("msg:📞 Nieodebrane połączenie") }
        assertTrue(rings.events.contains("stop"))
        a.calls.ringMs = 45_000; b.calls.ringMs = 45_000
        listOf(a, b).forEach { it.calls.clearEnded() }

        // 5. zablokowana osoba nie może dzwonić
        b.setBlocked(a.myPub, true)
        a.calls.startCall(b.myPub, video = false)
        delay(2000)
        assertTrue("u B cisza", b.calls.state.value == null)
        a.calls.hangup()
        b.setBlocked(a.myPub, false)
        listOf(a, b, c).forEach { it.stop() }
        inet.close()
        println("ROZMOWY OK")
    }

    @Test
    fun answerOnSecondDevice() = runBlocking {
        val inet = LocalRelay(ctx, "call2_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b1 = core("Bartek", url)
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b1.connectedRelays().isNotEmpty() }
        a.addContact(b1.myPub, "Bartek"); b1.addContact(a.myPub, "Ala")
        val b2 = core("Bartek", url, restoreFrom = b1)
        waitFor("B2 ma kontakty") { b2.db.contact(a.myPub) != null && b1.multiDevice }
        a.calls.startCall(b1.myPub, video = false)
        waitFor("dzwonią oba telefony B") { b1.calls.state.value?.phase == Phase.RINGING && b2.calls.state.value?.phase == Phase.RINGING }
        b2.calls.answer()
        waitFor("A połączony z B2") { a.calls.state.value?.phase == Phase.CONNECTED && b2.calls.state.value?.phase == Phase.CONNECTED }
        waitFor("B1 przestał dzwonić") { b1.calls.state.value?.let { it.phase == Phase.ENDED && it.endReason == "Odebrano na innym urządzeniu" } == true }
        assertEquals(b2.deviceId, a.calls.state.value!!.peerDev)
        a.calls.hangup()
        waitFor("B2 rozłączony") { b2.calls.state.value?.phase == Phase.ENDED }
        listOf(a, b1, b2).forEach { it.stop() }
        inet.close()
        println("DRUGIE URZĄDZENIE ODBIERA OK")
    }
}
