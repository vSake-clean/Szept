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
import pl.szept.call.GroupCallManager.Phase
import pl.szept.call.GroupCallManager.PeerPhase
import pl.szept.call.RtcEngine
import pl.szept.call.RtcPeer
import pl.szept.call.RtcSession
import pl.szept.call.RtcSessionFactory
import pl.szept.core.Core
import pl.szept.crypto.hex
import pl.szept.data.GroupState
import pl.szept.data.Kind
import pl.szept.data.groupChatId
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import java.util.Collections

/** Rozmowy grupowe (siatka): dzwonienie do grupy, dołączanie, wychodzenie, limit osób, drugie urządzenie. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GroupCallTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    class FakeSession(val log: MutableList<String>) : RtcSession {
        override fun newPeer(listener: RtcEngine.Listener): RtcPeer = object : RtcPeer {
            override suspend fun createOffer() = "oferta".also { log += "offer" }
            override suspend fun createAnswer(offer: String): String { log += "answer"; listener.onConnected(); return "odpowiedz" }
            override suspend fun applyAnswer(answer: String) { log += "apply"; listener.onConnected() }
            override fun close() { log += "peer-close" }
        }
        override fun setMuted(muted: Boolean) { log += "mute:$muted" }
        override fun setVideoEnabled(on: Boolean) {}
        override fun switchCamera() {}
        override fun close() { log += "close" }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(100)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun core(name: String, url: String, restoreFrom: Core? = null) = Core(ctx, name + System.nanoTime()).apply {
        if (restoreFrom != null) restoreIdentity(restoreFrom.myPriv.hex(), name) else createIdentity(name)
        setRelays(listOf(url)); appVisible = true; start()
        groupCalls.factory = RtcSessionFactory { _, _ -> FakeSession(Collections.synchronizedList(mutableListOf())) }
        groupCalls.endedLingerMs = 600_000
    }

    private fun connected(c: Core) = c.groupCalls.state.value?.takeIf { it.phase == Phase.ACTIVE }?.peers?.values?.count { it.phase == PeerPhase.CONNECTED } ?: -1

    @Test
    fun meshCall() = runBlocking {
        val inet = LocalRelay(ctx, "gc_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b = core("Bartek", url); val c = core("Celina", url); val d = core("Darek", url)
        val all = listOf(a, b, c, d)
        waitFor("połączeni") { all.all { it.connectedRelays().isNotEmpty() } }
        val g = a.createGroup("Ekipa", listOf(b.myPub, c.myPub, d.myPub))
        val gid = g.removePrefix("g:")
        waitFor("wszyscy w grupie") { listOf(b, c, d).all { it.db.group(gid)?.state == GroupState.ACTIVE } }

        // A dzwoni do grupy — dzwonią B, C, D
        a.groupCalls.start(gid, video = false)
        waitFor("dzwoni u B, C, D") { listOf(b, c, d).all { it.groupCalls.state.value?.phase == Phase.RINGING } }
        b.groupCalls.join()
        waitFor("A–B połączeni") { connected(a) == 1 && connected(b) == 1 }
        c.groupCalls.join()
        waitFor("C połączony z A i B") { connected(c) == 2 && connected(a) == 2 && connected(b) == 2 }
        d.groupCalls.decline()
        assertEquals(null, d.groupCalls.state.value)
        // D zmienia zdanie i dołącza do trwającej rozmowy z czatu grupy
        waitFor("D widzi trwającą rozmowę") { d.groupCalls.joinable(gid) != null }
        d.groupCalls.join(gid)
        waitFor("pełna siatka 4 osób") { all.all { connected(it) == 3 } }

        // B wychodzi — pozostali tracą tylko B
        b.groupCalls.leave()
        waitFor("bez B") { listOf(a, c, d).all { connected(it) == 2 } }
        assertTrue(b.db.messages(g, 50).any { it.kind == Kind.SYSTEM && it.body.startsWith("📞 Rozmowa grupowa · 0:") })
        // A (który zaczął) wychodzi — C i D rozmawiają dalej
        a.groupCalls.leave()
        waitFor("C i D dalej rozmawiają") { connected(c) == 1 && connected(d) == 1 }
        c.groupCalls.leave(); d.groupCalls.leave()
        waitFor("koniec") { all.all { it.groupCalls.state.value?.active != true } }
        all.forEach { it.stop() }
        inet.close()
        println("ROZMOWA GRUPOWA OK")
    }

    @Test
    fun cancelLimitAndDevices() = runBlocking {
        val inet = LocalRelay(ctx, "gc2_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b1 = core("Bartek", url)
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b1.connectedRelays().isNotEmpty() }
        val gid = a.createGroup("Dom", listOf(b1.myPub)).removePrefix("g:")
        waitFor("B w grupie") { b1.db.group(gid)?.state == GroupState.ACTIVE }
        b1.addContact(a.myPub, "Ala")
        val b2 = core("Bartek", url, restoreFrom = b1)
        waitFor("B2 zna grupę") { b2.db.group(gid)?.state == GroupState.ACTIVE && b1.multiDevice }

        // dzwonią oba telefony B; odbiór na B2 ucisza B1
        a.groupCalls.start(gid, video = true)
        waitFor("dzwonią B1 i B2") { b1.groupCalls.state.value?.phase == Phase.RINGING && b2.groupCalls.state.value?.phase == Phase.RINGING }
        b2.groupCalls.join()
        waitFor("A–B2 połączeni") { connected(a) == 1 && connected(b2) == 1 }
        waitFor("B1 przestał dzwonić") { b1.groupCalls.state.value?.endReason == "Odebrano na innym urządzeniu" }
        a.groupCalls.leave(); b2.groupCalls.leave()
        listOf(a, b1, b2).forEach { it.groupCalls.clearEnded() }

        // odwołanie, zanim ktoś odebrał
        a.groupCalls.start(gid, video = false)
        waitFor("B1 dzwoni") { b1.groupCalls.state.value?.phase == Phase.RINGING }
        a.groupCalls.leave()
        waitFor("B1 przestał dzwonić (bez okna)") { b1.groupCalls.state.value == null }
        waitFor("historia: nieodebrana") { b1.db.messages(groupChatId(gid), 50).any { it.body == "📞 Rozmowa grupowa · nieodebrana" } }

        // w trakcie rozmowy grupowej rozmowa 1:1 jest „zajęta”
        listOf(a, b1, b2).forEach { it.groupCalls.clearEnded() }
        a.calls.factory = pl.szept.call.RtcFactory { _, _, l -> CallTest.FakeEngine(l, mutableListOf()) }
        a.groupCalls.start(gid, video = false)
        try { a.calls.startCall(b1.myPub, false); throw AssertionError("powinno być zajęte") } catch (e: IllegalStateException) { }
        a.groupCalls.leave()
        listOf(a, b1, b2).forEach { it.stop() }
        inet.close()
        println("ODWOŁANIE / URZĄDZENIA / ZAJĘTE OK")
    }

    /** Rozmowa z czatu prywatnego: dzwonienie, odebranie, dodanie trzeciej osoby, odrzucenie, brak odpowiedzi. */
    @Test
    fun privateCallsAndAdding() = runBlocking {
        val inet = LocalRelay(ctx, "gc3_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b = core("Bartek", url); val c = core("Celina", url)
        val all = listOf(a, b, c)
        waitFor("połączeni") { all.all { it.connectedRelays().isNotEmpty() } }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")
        a.addContact(c.myPub, "Celina"); c.addContact(a.myPub, "Ala")
        waitFor("kontakty") { b.db.contact(a.myPub) != null && c.db.contact(a.myPub) != null }

        // A dzwoni do B z czatu prywatnego (tak jak przycisk słuchawki)
        a.startCall(b.myPub, false) { throw AssertionError(it) }
        waitFor("dzwoni u B") { b.groupCalls.state.value?.phase == Phase.RINGING }
        assertTrue(b.groupCalls.state.value!!.isRoom)
        assertEquals(setOf(b.myPub), a.groupCalls.state.value!!.invited)
        b.groupCalls.join()
        waitFor("A–B połączeni") { connected(a) == 1 && connected(b) == 1 }
        assertTrue(a.groupCalls.state.value!!.invited.isEmpty())

        // A dodaje C do trwającej rozmowy — C dzwoni, po odebraniu łączy się z A i B
        a.groupCalls.invite(listOf(c.myPub))
        waitFor("dzwoni u C") { c.groupCalls.state.value?.phase == Phase.RINGING }
        c.groupCalls.join()
        waitFor("rozmowa we troje") { all.all { connected(it) == 2 } }

        // wszyscy wychodzą — B i C zostają sami, więc rozmowa kończy się sama
        a.groupCalls.leave()
        waitFor("B i C dalej") { connected(b) == 1 && connected(c) == 1 }
        b.groupCalls.leave()
        waitFor("C: koniec, gdy nikogo nie ma") { c.groupCalls.state.value?.active != true }
        waitFor("historia u B") { b.db.messages(dmChatId(a.myPub), 50).any { it.kind == Kind.SYSTEM && it.body.startsWith("📞 Połączenie głosowe · 0:") } }
        all.forEach { it.groupCalls.clearEnded() }

        // B odrzuca — A od razu wie
        a.startCall(b.myPub, true) { throw AssertionError(it) }
        waitFor("dzwoni u B (2)") { b.groupCalls.state.value?.phase == Phase.RINGING }
        b.groupCalls.decline()
        waitFor("A: odrzucono") { a.groupCalls.state.value?.let { !it.active && it.endReason == "Odrzucono" } == true }
        all.forEach { it.groupCalls.clearEnded() }

        // nikt nie odbiera — po czasie dzwonienia ekran po prostu znika (bez okna „Nikt nie odebrał”)
        a.groupCalls.ringMs = 1500; b.groupCalls.ringMs = 60_000
        a.startCall(b.myPub, false) { throw AssertionError(it) }
        waitFor("dzwoni u B (3)") { b.groupCalls.state.value?.phase == Phase.RINGING }
        waitFor("A: koniec po cichu") { a.groupCalls.state.value == null }
        waitFor("B przestał dzwonić") { b.groupCalls.state.value == null }
        assertTrue(a.db.messages(dmChatId(b.myPub), 50).any { it.body == "📞 Połączenie głosowe · brak odpowiedzi" })
        assertTrue(b.db.messages(dmChatId(a.myPub), 50).any { it.body == "📞 Połączenie głosowe · nieodebrana" })

        // obca osoba (zablokowana) nie może dzwonić
        b.setBlocked(a.myPub, true)
        a.startCall(b.myPub, false) { }
        delay(1500)
        assertEquals(null, b.groupCalls.state.value)
        a.groupCalls.leave()
        all.forEach { it.stop() }
        inet.close()
        println("ROZMOWY PRYWATNE OK")
    }

    @Test
    fun removeFriend() = runBlocking {
        val inet = LocalRelay(ctx, "gc4_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url)
        val pk = pl.szept.crypto.Crypto.publicKey(pl.szept.crypto.Crypto.newPrivateKey()).hex()
        a.addContact(pk, "Ola")
        assertTrue(a.knownPeople().any { it.pk == pk })
        a.removeFriend(pk, alsoChat = true)
        waitFor("usunięty") { a.db.contact(pk)?.isContact == false && a.db.chat(dmChatId(pk)) == null }
        assertTrue(a.knownPeople().none { it.pk == pk })
        a.stop(); inet.close()
    }
}
