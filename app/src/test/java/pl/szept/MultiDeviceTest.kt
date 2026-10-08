package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import pl.szept.crypto.B64
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.crypto.unhex
import pl.szept.data.GroupState
import pl.szept.data.Kind
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.data.groupChatId
import pl.szept.lan.LocalRelay
import pl.szept.nostr.Event
import java.io.File

/**
 * Jedno konto na dwóch telefonach (kopie dla siebie, synchronizacja) i rotacja kluczy (forward secrecy).
 * „Internet” to przekaźnik uruchomiony lokalnie — test nie potrzebuje sieci.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiDeviceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private suspend fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(200)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun core(name: String, relay: String, restoreFrom: Core? = null): Core = Core(ctx, name + System.nanoTime()).apply {
        if (restoreFrom != null) assertTrue(restoreIdentity(restoreFrom.myPriv.hex(), name)) else createIdentity(name)
        setRelays(listOf(relay)); appVisible = true; start()
    }

    @Test
    fun twoDevices() = runBlocking {
        val inet = LocalRelay(ctx, "md_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a1 = core("Ala", url)
        val b = core("Bartek", url)
        waitFor("połączeni") { a1.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a1.addContact(b.myPub, "Bartek"); b.addContact(a1.myPub, "Ala")
        val g = a1.createGroup("Rodzina", listOf(b.myPub))
        val gid = g.removePrefix("g:")
        waitFor("B w grupie") { b.db.group(gid)?.state == GroupState.ACTIVE }
        assertFalse("bez drugiego urządzenia nie ma kopii", a1.multiDevice)

        // drugi telefon loguje się kodem konta — przedstawia się i dostaje kontakty oraz grupy
        val a2 = core("Ala", url, restoreFrom = a1)
        assertEquals(a1.myPub, a2.myPub)
        assertTrue(a1.deviceId != a2.deviceId)
        waitFor("A1 zna drugie urządzenie") { a1.multiDevice && a1.devices().any { it.id == a2.deviceId } }
        waitFor("A2 zna pierwsze urządzenie") { a2.devices().any { it.id == a1.deviceId } }
        waitFor("A2 dostał kontakty i grupę") { a2.db.contact(b.myPub)?.isContact == true && a2.db.group(gid)?.state == GroupState.ACTIVE }

        // wiadomość z A1 pojawia się na A2 jako moja
        val dm = dmChatId(b.myPub)
        val m1 = a1.sendText(dm, "Wysłane z pierwszego telefonu")
        waitFor("B dostał") { b.db.message(m1.id) != null }
        waitFor("A2 ma kopię jako moją") { a2.db.message(m1.id)?.let { it.mine && it.chat == dm } == true }
        // potwierdzenie od B trafia na oba telefony
        b.markChatRead(dmChatId(a1.myPub))
        waitFor("odczytane na obu", 20_000) { (a1.db.message(m1.id)?.status ?: 0) >= St.READ && (a2.db.message(m1.id)?.status ?: 0) >= St.READ }

        // odpowiedź od B dociera na oba telefony; przeczytanie na A2 gasi nieprzeczytane na A1
        a1.appVisible = false; a2.appVisible = false
        val r = b.sendText(dmChatId(a1.myPub), "Odpowiadam")
        waitFor("oba telefony mają odpowiedź") { a1.db.message(r.id) != null && a2.db.message(r.id) != null }
        assertTrue((a1.db.chat(dm)?.unread ?: 0) > 0)
        a2.markChatRead(dm)
        waitFor("A1: przeczytane na innym urządzeniu") { a1.db.chat(dm)?.unread == 0 }

        // reakcja i edycja z A1 widoczne na A2
        a1.react(r.id, "❤️")
        waitFor("reakcja na A2") { a2.db.reaction(r.id, a1.myPub)?.emoji == "❤️" }
        a1.editMessage(m1.id, "Wysłane z pierwszego telefonu (poprawione)")
        waitFor("edycja na A2 i u B") {
            a2.db.message(m1.id)?.body?.endsWith("(poprawione)") == true && b.db.message(m1.id)?.body?.endsWith("(poprawione)") == true
        }

        // A2 pisze w grupie — A1 widzi to jako swoją wiadomość
        val gm = a2.sendText(groupChatId(gid), "Z drugiego telefonu do grupy")
        waitFor("B i A1 mają wiadomość z A2") { b.db.message(gm.id) != null && a1.db.message(gm.id)?.mine == true }

        // usuwanie: u wszystkich oraz tylko u mnie (na obu moich telefonach)
        a1.deleteForEveryone(gm.id)
        waitFor("usunięte wszędzie") { a1.db.message(gm.id) == null && a2.db.message(gm.id) == null && b.db.message(gm.id) == null }
        a2.deleteForMe(r.id)
        waitFor("usunięte u mnie na obu telefonach") { a1.db.message(r.id) == null && a2.db.message(r.id) == null }
        assertTrue("u B zostaje", b.db.message(r.id) != null)

        // zdjęcie z A1: A2 pobiera je jako swoje
        val img = File(ctx.cacheDir, "md.jpg").apply { writeBytes(Crypto.randomBytes(90_000)) }
        val bytes = img.readBytes()
        val pm = a1.send(dm, Kind.IMAGE, "", Core.Attachment(img, "image/jpeg", "md.jpg", null, 4, 3, 0))
        waitFor("A2 pobrał swoje zdjęcie") { a2.db.message(pm.id)?.path?.let { File(it).exists() } == true }
        assertTrue(File(a2.db.message(pm.id)!!.path!!).readBytes().contentEquals(bytes))
        assertEquals(St.SENT, a2.db.message(pm.id)!!.status.coerceAtMost(St.SENT))

        // wyciszenie i nowy kontakt synchronizują się
        a1.setMuted(dm, true)
        waitFor("wyciszenie na A2") { a2.db.chat(dm)?.muted == true }
        val c = core("Celina", url)
        a2.addContact(c.myPub, "Celina")
        waitFor("nowy kontakt na A1") { a1.db.contact(c.myPub)?.isContact == true }

        // A1 opuszcza grupę — A2 też jest poza nią
        a1.leaveGroup(gid)
        waitFor("A2 poza grupą") { a2.db.group(gid)?.state == GroupState.LEFT }
        listOf(a1, a2, b, c).forEach { it.stop() }
        inet.close()
        println("DWA URZĄDZENIA OK")
    }

    /** Czy kopertę da się odszyfrować którymś z kluczy. */
    private fun canDecrypt(json: String, keys: List<ByteArray>): Boolean {
        val ev = Event.fromJson(json)
        return keys.any { k ->
            try { Crypto.decrypt(Crypto.conversationKey(k, ev.pubkey.unhex()), B64.dec(ev.content)); true } catch (e: Exception) { false }
        }
    }

    private fun prekeyPrivs(c: Core): List<ByteArray> {
        val a = JSONArray(c.prefs.getString("prekeys", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).getString("priv").unhex() }
    }

    @Test
    fun forwardSecrecy() = runBlocking {
        val inet = LocalRelay(ctx, "fs_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a = core("Ala", url)
        val b = core("Bartek", url)
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")

        a.setForwardSecrecy(true)
        assertTrue(a.prekeyCreated() > 0)
        waitFor("B zna klucz urządzenia A") { b.db.prekeys(a.myPub).isNotEmpty() }
        val m = b.sendText(dmChatId(a.myPub), "Tajne z rotacją kluczy")
        waitFor("A odebrał") { a.db.message(m.id)?.body == "Tajne z rotacją kluczy" }
        val wrapIds = b.db.wraps(m.id).map { it.second }
        val wraps = wrapIds.mapNotNull { id -> inet.query(JSONObject().put("ids", JSONArray().put(id))).firstOrNull() }
        assertTrue(wraps.isNotEmpty())
        // stały klucz konta NIE odszyfruje tej koperty — tylko klucz urządzenia
        wraps.forEach { w -> assertFalse("stały klucz nie wystarcza", canDecrypt(w, listOf(a.myPriv))) }
        val oldKeys = prekeyPrivs(a)
        assertTrue(wraps.all { canDecrypt(it, oldKeys) })

        // rotacja: nowy klucz, stary jeszcze trzymany (koperty w drodze)
        a.rotatePrekey(force = true)
        waitFor("B zna nowy klucz") { b.db.prekeys(a.myPub).single().pub != Crypto.publicKey(oldKeys.single()).hex() }
        val m2 = b.sendText(dmChatId(a.myPub), "Po rotacji")
        waitFor("A odebrał po rotacji") { a.db.message(m2.id) != null }
        assertEquals(2, prekeyPrivs(a).size)

        // po 35 dniach stary klucz jest kasowany — starej koperty nie odszyfruje już nikt (także po kradzieży telefonu)
        val arr = JSONArray(a.prefs.getString("prekeys", "[]"))
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optLong("sup") > 0) o.put("sup", System.currentTimeMillis() - 36L * 86400_000) }
        a.prefs.edit().putString("prekeys", arr.toString()).commit()
        a.rotatePrekey()
        assertEquals(1, prekeyPrivs(a).size)
        wraps.forEach { w -> assertFalse("stara koperta nie do odczytania", canDecrypt(w, prekeyPrivs(a) + listOf(a.myPriv))) }

        // wyłączenie: znajomi wracają do zwykłego klucza
        a.setForwardSecrecy(false)
        waitFor("B zapomniał klucz urządzenia A") { b.db.prekeys(a.myPub).isEmpty() }
        val m3 = b.sendText(dmChatId(a.myPub), "Bez rotacji")
        waitFor("A odebrał bez rotacji") { a.db.message(m3.id) != null }

        // starsza wersja aplikacji (bez rotacji) nadal może pisać — stały klucz zawsze działa
        a.setForwardSecrecy(true)
        waitFor("B znowu zna klucz") { b.db.prekeys(a.myPub).isNotEmpty() }
        b.db.removePrekeys(a.myPub)   // jak telefon z wersją 1.7
        val m4 = b.sendText(dmChatId(a.myPub), "Ze starej wersji")
        waitFor("A odebrał od starej wersji") { a.db.message(m4.id) != null }
        listOf(a, b).forEach { it.stop() }
        inet.close()
        println("ROTACJA KLUCZY OK")
    }

    @Test
    fun forwardSecrecyTwoDevices() = runBlocking {
        val inet = LocalRelay(ctx, "fs2_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        val a1 = core("Ala", url)
        val b = core("Bartek", url)
        waitFor("połączeni") { a1.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a1.addContact(b.myPub, "Bartek"); b.addContact(a1.myPub, "Ala")
        a1.setForwardSecrecy(true)
        waitFor("B zna klucz A1") { b.db.prekeys(a1.myPub).size == 1 }
        val a2 = core("Ala", url, restoreFrom = a1)
        // A2 dostaje ustawienie z A1, tworzy własny klucz i ogłasza go
        waitFor("A2 ma rotację kluczy") { a2.forwardSecrecy && a2.prekeyCreated() > 0 }
        waitFor("B zna klucze obu urządzeń") { b.db.prekeys(a1.myPub).size == 2 }
        val m = b.sendText(dmChatId(a1.myPub), "Do obu telefonów")
        waitFor("oba urządzenia odebrały") { a1.db.message(m.id) != null && a2.db.message(m.id) != null }
        // wiadomość z A2 na A1 (kopia szyfrowana do klucza A1)
        val own = a2.sendText(dmChatId(b.myPub), "Z drugiego, z rotacją")
        waitFor("A1 ma kopię, B odebrał") { a1.db.message(own.id)?.mine == true && b.db.message(own.id) != null }
        assertNull(a1.db.message("nie-ma"))
        listOf(a1, a2, b).forEach { it.stop() }
        inet.close()
        println("ROTACJA NA DWÓCH URZĄDZENIACH OK")
    }
}
