package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.Core.Seen
import pl.szept.lan.LocalRelay

/** Statusy: aktywny / w tle / zaraz wracam / nieaktywny — wymiana przez lokalny przekaźnik. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PresenceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private suspend fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(150)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun core(name: String, relay: String): Core = Core(ctx, name + System.nanoTime()).apply {
        createIdentity(name); setRelays(listOf(relay)); appVisible = true; start()
    }

    @Test
    fun statuses() = runBlocking {
        val relay = LocalRelay(ctx, "pres_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + relay.start(0)
        val a = core("Ala", url)
        val b = core("Bartek", url)
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")

        a.sendPresence(force = true)
        waitFor("aktywny") { b.seenOf(a.myPub)?.first == Seen.ACTIVE }

        a.appVisible = false
        a.sendPresence(force = true)
        waitFor("w tle") { b.seenOf(a.myPub)?.first == Seen.IDLE }

        a.setAway(true)
        waitFor("zaraz wraca") { b.seenOf(a.myPub)?.first == Seen.AWAY }
        a.setAway(false)
        waitFor("znowu w tle") { b.seenOf(a.myPub)?.first == Seen.IDLE }

        a.sendPresence(force = true, closing = true)
        waitFor("nieaktywny") { b.seenOf(a.myPub)?.first == Seen.INACTIVE }

        // brak sygnału przez długi czas = nieaktywny
        a.appVisible = true; a.sendPresence(force = true)
        waitFor("aktywny ponownie") { b.seenOf(a.myPub)?.first == Seen.ACTIVE }
        val at = b.seenOf(a.myPub)!!.second
        assertEquals(Seen.INACTIVE, b.seenOf(a.myPub, now = at + Core.PRESENCE_STALE_MS + 1)!!.first)

        // ukrycie statusu — znajomy przestaje go widzieć
        a.setShareStatus(false)
        waitFor("status ukryty") { b.seenOf(a.myPub) == null }
        a.stop(); b.stop(); relay.stop()
    }
}
