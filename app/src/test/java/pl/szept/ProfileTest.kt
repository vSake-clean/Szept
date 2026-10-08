package pl.szept

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.data.shortId

/** Profil, alias, stały ID, streak, wygląd grupy, blokada i kod-wipe — bez sieci. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProfileTest {
    private fun core() = Core(RuntimeEnvironment.getApplication(), "prof" + System.nanoTime()).apply { createIdentity("Ja") }

    @Test fun stableId() {
        val pk = "a".repeat(64)
        assertEquals(shortId(pk), shortId(pk))                 // niezmienny
        assertTrue(shortId(pk).startsWith("SZPT-"))
        assertNotEquals(shortId(pk), shortId("b".repeat(64)))
    }

    @Test fun aliasOverridesName() {
        val c = core()
        val pk = "c".repeat(64)
        c.db.saveProfile(pk, "Krzysztof", "", "", null, null, 100)
        c.openDm(pk)
        assertEquals("Krzysztof", c.db.nameOf(pk))
        c.setAlias(pk, "Krzysiek z pracy")
        assertEquals("Krzysiek z pracy", c.db.nameOf(pk))
        assertEquals("Krzysiek z pracy", c.db.chat(dmChatId(pk))?.name)
        // starszy profil nie nadpisuje nowszej nazwy, alias zostaje
        c.db.saveProfile(pk, "Stare Imię", null, null, null, null, 50)
        assertEquals("Krzysiek z pracy", c.db.nameOf(pk))
    }

    @Test fun profileNewestWins() {
        val c = core(); val pk = "d".repeat(64)
        c.db.saveProfile(pk, "A", "status A", "bio", null, null, 100)
        c.db.saveProfile(pk, "B", "status B", null, null, null, 200)
        assertEquals("B", c.db.contact(pk)!!.name)
        assertEquals("status B", c.db.contact(pk)!!.status)
        c.db.saveProfile(pk, "C", "stary", null, null, null, 150) // starszy — ignorowany
        assertEquals("B", c.db.contact(pk)!!.name)
    }

    @Test fun streakCounting() {
        val c = core(); val pk = "e".repeat(64); val chat = c.openDm(pk)
        val day = 86_400_000L
        val noon = (System.currentTimeMillis() / day) * day + 43_200_000L - java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        fun msg(id: String, ts: Long, mine: Boolean) = c.db.insertMessage(Message(id, chat, if (mine) c.myPub else pk, ts, Kind.TEXT, "x", null, null, St.RECEIVED, 0, 0, 0, 0, 0, mine))
        // dziś i wczoraj oboje piszą → streak 2
        msg("a", noon, true); msg("b", noon, false)
        msg("c", noon - day, true); msg("d", noon - day, false)
        assertEquals(2, c.db.streakDays(chat))
        // przedwczoraj tylko ja → przerwa, więc dalej 2
        msg("e", noon - 2 * day, true)
        assertEquals(2, c.db.streakDays(chat))
        // brak dzisiejszej wiadomości od rozmówcy, ale wczoraj komplet → liczymy od wczoraj = 1
        val c2 = core(); val pk2 = "f".repeat(64); val chat2 = c2.openDm(pk2)
        c2.db.insertMessage(Message("g", chat2, c2.myPub, noon - day, Kind.TEXT, "x", null, null, St.RECEIVED, 0, 0, 0, 0, 0, true))
        c2.db.insertMessage(Message("h", chat2, pk2, noon - day, Kind.TEXT, "x", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        assertEquals(1, c2.db.streakDays(chat2))
    }

    @Test fun groupLookPersists() = kotlinx.coroutines.runBlocking {
        val c = core()
        val chat = c.createGroup("Ekipa", emptyList())
        val gid = chat.removePrefix("g:")
        c.setGroupLook(gid, "🎮", 42)
        assertEquals("🎮", c.db.group(gid)!!.icon)
        assertEquals(42, c.db.group(gid)!!.color)
    }

    @Test fun pinAndWipe() {
        val c = core()
        val pk = Crypto.publicKey(Crypto.newPrivateKey()).hex(); c.addContact(pk, "Ktoś"); c.openDm(pk)
        c.db.insertMessage(Message("m1", dmChatId(pk), pk, 1, Kind.TEXT, "cześć", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        c.setPin("1234")
        assertTrue(c.verifyPin("1234")); assertFalse(c.verifyPin("0000"))
        c.setWipeCode("9999", JSONObject().put("scope", "all").put("block", "all").put("account", false))
        assertTrue(c.isWipeCode("9999")); assertFalse(c.isWipeCode("1234"))
        // wipe bez usuwania konta: czaty znikają, kontakt zablokowany, konto zostaje
        val removedAccount = c.applyWipe()
        assertFalse(removedAccount)
        assertTrue(c.hasIdentity)
        assertNull(c.db.message("m1"))
        assertTrue(c.db.contact(pk)!!.blocked)
        // wipe z usunięciem konta
        c.setWipeCode("8888", JSONObject().put("scope", "all").put("block", "none").put("account", true))
        assertTrue(c.applyWipe())
        assertFalse(c.hasIdentity)
    }

    @Test fun selectiveWipe() {
        val c = core()
        val a = Crypto.publicKey(Crypto.newPrivateKey()).hex(); val b = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        c.addContact(a, "A"); c.addContact(b, "B")
        val chatA = c.openDm(a); val chatB = c.openDm(b)
        c.db.insertMessage(Message("ma", chatA, a, 1, Kind.TEXT, "x", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        c.db.insertMessage(Message("mb", chatB, b, 1, Kind.TEXT, "x", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        c.setWipeCode("7777", JSONObject().put("scope", "sel").put("chats", JSONArray(listOf(chatA)))
            .put("block", "sel").put("pks", JSONArray(listOf(b))).put("account", false))
        c.applyWipe()
        assertNull("czat A skasowany", c.db.message("ma"))
        assertNotEquals("czat B zostaje", null, c.db.message("mb"))
        assertFalse("A nie blokowany", c.db.contact(a)!!.blocked)
        assertTrue("B zablokowany", c.db.contact(b)!!.blocked)
    }
}
