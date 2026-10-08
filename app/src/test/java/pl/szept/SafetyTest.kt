package pl.szept

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.CrashLog
import pl.szept.core.Links
import pl.szept.crypto.B64
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.crypto.isPubKey
import pl.szept.data.MediaMeta
import pl.szept.nostr.isValidRelayUrl

/** Zabezpieczenia przed danymi z sieci i błędnymi ustawieniami (2.6). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafetyTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun meta(id: String = "0123456789abcdef0123456789abcdef", key: String = B64.enc(ByteArray(32)), sz: Long = 1000, c: Int = 1) =
        JSONObject().put("id", id).put("key", key).put("sz", sz).put("c", c).put("h", "a".repeat(64)).put("name", "x.jpg")

    private fun rejected(o: JSONObject) = try { MediaMeta.fromNetwork(o); false } catch (e: Exception) { true }

    @Test
    fun mediaFromNetworkIsValidated() {
        assertEquals("x.jpg", MediaMeta.fromNetwork(meta()).name)
        assertTrue("ścieżka ../ w id", rejected(meta(id = "../../")))
        assertTrue("zły klucz", rejected(meta(key = "!!")))
        assertTrue("pusty klucz", rejected(meta(key = "")))
        assertTrue("krótki klucz", rejected(meta(key = B64.enc(ByteArray(5)))))
        assertTrue("za dużo kawałków", rejected(meta(c = 100_000)))
        assertTrue("brak kawałków", rejected(meta(c = 0)))
        assertEquals("nazwa bez katalogów", "evil.apk", MediaMeta.fromNetwork(meta().put("name", "../../evil.apk")).name)
    }

    @Test
    fun relayAddresses() {
        assertTrue(isValidRelayUrl("wss://relay.damus.io"))
        assertTrue(isValidRelayUrl("ws://192.168.1.20:7447"))
        assertFalse(isValidRelayUrl("wss://"))
        assertFalse(isValidRelayUrl("ws.myrelay.org"))
        assertFalse(isValidRelayUrl("wss://a b.pl"))
        assertFalse(isValidRelayUrl("ws://host:99999"))
        // zły adres w ustawieniach nie wywraca startu
        val core = Core(ctx, "s" + System.nanoTime()).apply { createIdentity("Ala") }
        core.setRelays(listOf("wss://", "wss://relay.example.org"))
        assertEquals(listOf("wss://relay.example.org"), core.configuredRelays())
        core.start(); core.stop()
    }

    @Test
    fun publicKeysOnCurve() {
        val good = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        assertTrue(good.isPubKey())
        // ok. połowa losowych ciągów hex to nie klucze — szukamy takiego
        val bad = generateSequence { Crypto.randomBytes(32).hex() }.first { !Crypto.isValidPub(it) }
        assertFalse(bad.isPubKey())
        assertNull("literówka w kluczu nie jest kontaktem", Links.parse(bad))
        val core = Core(ctx, "k" + System.nanoTime()).apply { createIdentity("Ala") }
        assertNull(core.addContact(bad, "Zły"))
        assertTrue(core.addContact(good, "Dobry") != null)
    }

    @Test
    fun crashLogKeepsErrors() {
        CrashLog.init(ctx); CrashLog.clear()
        CrashLog.record("test", IllegalStateException("bum"))
        assertTrue(CrashLog.read().contains("bum"))
        CrashLog.clear()
        assertEquals("", CrashLog.read())
    }
}
