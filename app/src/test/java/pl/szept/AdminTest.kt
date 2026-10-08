package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Admin
import pl.szept.core.Core
import pl.szept.crypto.Crypto
import pl.szept.data.Kind
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import java.io.File

/**
 * Konto oficjalne: hasło, wiadomości i aktualizacje do wybranych (bez historii u nadawcy, bez odpowiedzi),
 * znaczek weryfikacji, wymuszona aktualizacja. Hasło: ./gradlew testDebugUnitTest -Dszept.adminpw=…
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminTest {
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

    @Test
    fun names() {
        assertEquals("Szept", Admin.cleanName("Szept ✔"))
        assertEquals("Ola", Admin.cleanName("Ola ✅"))
        assertNull(Admin.unlock("zle-haslo".toCharArray()))
    }

    @Test
    fun officialAccount() = runBlocking {
        assumeTrue("podaj -Dszept.adminpw", pw.isNotEmpty())
        val inet = LocalRelay(ctx, "adm_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Admin", url); val b = core("Bartek", url); val c = core("Celina", url); val d = core("Darek", url)
        waitFor("połączeni") { listOf(a, b, c, d).all { it.connectedRelays().isNotEmpty() } }
        assertFalse(a.adminLogin("zle".toCharArray()))
        assertTrue(a.adminLogin(pw.toCharArray(), remember = false))

        // wiadomość z konta oficjalnego do wybranych (B i C nie są znajomymi admina)
        assertEquals(2, a.adminSend(listOf(b.myPub, c.myPub), "Witajcie w Szepcie!"))
        val official = dmChatId(Admin.PUB)
        waitFor("B i C dostali wiadomość od Szept") {
            listOf(b, c).all { x -> x.db.messages(official, 10).any { it.body == "Witajcie w Szepcie!" && it.sender == Admin.PUB } }
        }
        assertTrue("nadawca nie ma tego w historii", a.db.chats().none { it.id == official } && a.db.messages(dmChatId(b.myPub), 10).isEmpty())
        assertTrue(b.isVerified(Admin.PUB))
        try { b.sendText(official, "odpowiedź"); throw AssertionError("nie wolno odpowiadać") } catch (e: Core.SendException) { }

        // aktualizacja do wybranych: plik wysłany raz, odbiorca może pobrać
        val apk = File(ctx.cacheDir, "fake.apk").apply { writeBytes(Crypto.randomBytes(120_000)) }
        a.adminSend(listOf(b.myPub), "Nowa wersja", apk, "9.0")
        waitFor("B pobrał aktualizację", 40_000) { b.db.messages(official, 10).any { it.kind == Kind.UPDATE && it.path != null } }

        // zwykły użytkownik nie może wysłać „aktualizacji”
        d.addContact(b.myPub, "Bartek")
        val apk2 = File(ctx.cacheDir, "evil.apk").apply { writeBytes(Crypto.randomBytes(1000)) }
        try { d.send(dmChatId(b.myPub), Kind.UPDATE, "6.6", Core.Attachment(apk2, "application/vnd.android.package-archive", "x.apk", null, 0, 0, 0)) } catch (_: Exception) {}
        delay(4000)
        assertTrue("fałszywa aktualizacja odrzucona", b.db.messages(dmChatId(d.myPub), 10).none { it.kind == Kind.UPDATE })

        // znaczek weryfikacji: nadany B, widoczny u C (znajomej B)
        b.addContact(c.myPub, "Celina"); c.addContact(b.myPub, "Bartek")
        waitFor("kontakty") { c.db.contact(b.myPub) != null }
        a.adminBadge(b.myPub, true)
        waitFor("B ma znaczek") { b.myBadge }
        waitFor("C widzi znaczek B") { c.isVerified(b.myPub) }
        assertFalse(c.isVerified(d.myPub))
        a.adminBadge(b.myPub, false)
        waitFor("znaczek odebrany") { !b.myBadge && !c.isVerified(b.myPub) }

        // wymuszona aktualizacja: wersje starsze niż 99 muszą się zaktualizować
        assertFalse(b.updateRequired)
        a.adminPolicy(99, "Zaktualizuj, proszę", apk, "9.9", 99)
        waitFor("B musi zaktualizować", 40_000) { b.updateRequired && d.updateRequired }
        waitFor("B ma plik nowej wersji", 40_000) { b.db.message("upd:99")?.path != null }
        // nowy telefon dostaje politykę od razu po połączeniu
        val e = core("Ewa", url)
        waitFor("nowy telefon zna politykę") { e.updateRequired }
        listOf(a, b, c, d, e).forEach { it.stop() }
        inet.close()
        println("KONTO OFICJALNE OK")
    }
}
