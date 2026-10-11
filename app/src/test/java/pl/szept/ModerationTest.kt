package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.Links
import pl.szept.data.GroupState
import pl.szept.data.dmChatId
import pl.szept.data.groupChatId
import pl.szept.lan.LocalRelay

/** Moderacja: sankcje administratora (wycisz/zablokuj/usuń konto), kody jednorazowe, hasło; wyciszanie i bany w grupach. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ModerationTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val pw get() = System.getProperty("szept.adminpw") ?: ""

    private suspend fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) { println("✔ $what"); return }; delay(200) }
        throw AssertionError("Timeout: $what")
    }

    private fun core(name: String, url: String) = Core(ctx, name + System.nanoTime()).apply {
        createIdentity(name); setRelays(listOf(url)); appVisible = true; start()
    }

    private suspend fun sendFails(c: Core, chat: String, text: String): Boolean =
        try { c.sendText(chat, text); false } catch (e: Core.SendException) { true }

    @Test
    fun adminSanctionsAndOtp() = runBlocking {
        assumeTrue("podaj -Dszept.adminpw", pw.isNotEmpty())
        val inet = LocalRelay(ctx, "mod_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Admin", url); val b = core("Bartek", url); val c = core("Celina", url); val d = core("Darek", url)
        val all = listOf(a, b, c, d)
        waitFor("połączeni") { all.all { it.connectedRelays().isNotEmpty() } }
        b.addContact(c.myPub, "Celina"); c.addContact(b.myPub, "Bartek")
        assertTrue(a.adminLogin(pw.toCharArray(), remember = false))
        // hasło zapisuje się zaszyfrowane kluczem z Android Keystore (w testach na komputerze go nie ma)
        if (pl.szept.data.DbCrypto.seal(byteArrayOf(1)) != null) assertEquals("hasło do podejrzenia w panelu", pw, a.adminSavedPassword())

        // wyciszenie: B nie może pisać, a C i tak odrzuca jego wiadomości
        a.adminSanction(b.myPub, "mute", System.currentTimeMillis() + 3600_000, "spam")
        waitFor("B wie o wyciszeniu") { b.mySanctionText()?.contains("wyciszył") == true }
        waitFor("C wie o wyciszeniu B") { c.sanctionOf(b.myPub)?.mode == "mute" }
        assertTrue(sendFails(b, dmChatId(c.myPub), "kup teraz"))
        assertTrue(b.canWrite(dmChatId(c.myPub))!!.contains("spam"))
        waitFor("B dostał wyjaśnienie od Szept") { b.db.messages(dmChatId(pl.szept.core.Admin.PUB), 10).any { it.body.contains("wyciszone") } }

        // zdjęcie ograniczeń — B znowu pisze, C odbiera
        a.adminSanction(b.myPub, "none", 0, "")
        waitFor("B może pisać") { b.mySanctionText() == null && c.sanctionOf(b.myPub) == null }
        val m = b.sendText(dmChatId(c.myPub), "przepraszam")
        waitFor("C dostał") { c.db.message(m.id) != null }

        // ban — na stałe
        a.adminSanction(b.myPub, "ban", 0, "")
        waitFor("B zablokowany") { b.mySanctionText()?.contains("zablokował") == true && c.sanctionOf(b.myPub)?.mode == "ban" }
        assertTrue(sendFails(b, dmChatId(c.myPub), "ej"))

        // usunięcie konta C — telefon C kasuje konto
        a.adminSanction(c.myPub, "del", 0, "regulamin")
        waitFor("C dostał polecenie usunięcia konta") { c.adminDeleted.value }
        c.wipeByAdmin()
        assertFalse(c.hasIdentity)

        // kod jednorazowy: działa raz
        val code = a.adminCreateOtp(3600_000, "Darek")
        assertTrue(Regex("^[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$").matches(code))
        waitFor("D zna kod") { d.adminOtps.isNotEmpty() }
        assertFalse(d.adminLoginOtp("AAAA-BBBB-CCCC-DDDD"))
        assertTrue(d.adminLoginOtp(code.lowercase()))
        assertTrue(d.adminViaOtp && d.adminSavedPassword() == null)
        waitFor("kod zużyty u wszystkich") { a.adminOtps.isEmpty() }
        val e = core("Ewa", url)
        waitFor("E połączona") { e.connectedRelays().isNotEmpty() }
        delay(1500)
        assertFalse("drugi raz nie działa", e.adminLoginOtp(code))
        // wymuszenie aktualizacji nie kasuje listy sankcji
        a.adminPolicy(1, "x", null, "2.7", 18)
        assertEquals("ban", a.sanctionOf(b.myPub)?.mode)
        // dwa serwery TURN od różnych dostawców, każdy z własnym loginem; adres bez „turn:” jest uzupełniany
        a.adminSetTurns(listOf(
            Core.TurnServer(listOf("free.expressturn.com:3478"), "u1", "p1"),
            Core.TurnServer(listOf("turn:global.relay.metered.ca:80", "turns:global.relay.metered.ca:443?transport=tcp"), "u2", "p2"),
        ))
        waitFor("E dostała serwery TURN") { e.adminTurn().size == 3 }
        val ice = e.callIceServers()
        assertTrue(ice.any { it.url == "turn:free.expressturn.com:3478" && it.user == "u1" && it.pass == "p1" })
        assertTrue(ice.any { it.url == "turns:global.relay.metered.ca:443?transport=tcp" && it.user == "u2" })
        (all + e).forEach { runCatching { it.stop() } }
        inet.close()
    }

    @Test
    fun groupMuteAndBan() = runBlocking {
        val inet = LocalRelay(ctx, "gmod_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url); val b = core("Bartek", url); val c = core("Celina", url)
        waitFor("połączeni") { listOf(a, b, c).all { it.connectedRelays().isNotEmpty() } }
        val chat = a.createGroup("Klasa", listOf(b.myPub, c.myPub))
        val gid = chat.removePrefix("g:")
        waitFor("B i C w grupie") { listOf(b, c).all { it.db.group(gid)?.state == GroupState.ACTIVE } }

        // wyciszenie B przez admina grupy
        a.muteMember(gid, b.myPub, System.currentTimeMillis() + 3600_000)
        waitFor("B wie, że jest wyciszony") { b.db.group(gid)?.isMuted(b.myPub, System.currentTimeMillis()) == true }
        assertNotNull(b.canWrite(groupChatId(gid)))
        assertTrue(sendFails(b, groupChatId(gid), "hej"))
        waitFor("C też to widzi") { c.db.group(gid)?.isMuted(b.myPub, System.currentTimeMillis()) == true }
        a.muteMember(gid, b.myPub, 0)
        waitFor("B znowu pisze") { b.canWrite(groupChatId(gid)) == null }
        val m = b.sendText(groupChatId(gid), "dzięki")
        waitFor("C dostał") { c.db.message(m.id) != null }

        // ban C — wylatuje i nie wraca z linku
        val link = a.groupInvite(gid)!!
        a.banMember(gid, c.myPub)
        waitFor("C usunięta") { c.db.group(gid)?.state == GroupState.REMOVED }
        c.joinGroup(Links.parse(link) as Links.Parsed.GroupInvite)
        delay(3000)
        assertFalse("zbanowana nie wraca z linku", a.db.group(gid)!!.isMember(c.myPub))
        a.unbanMember(gid, c.myPub)
        assertTrue(a.db.group(gid)!!.banned.isEmpty())
        assertNull(a.canWrite(groupChatId(gid)))
        listOf(a, b, c).forEach { it.stop() }
        inet.close()
    }
}
