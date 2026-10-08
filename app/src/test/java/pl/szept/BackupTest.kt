package pl.szept

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Backup
import pl.szept.core.Core
import pl.szept.crypto.Crypto
import pl.szept.data.Kind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Kopia zapasowa: zapis, przywrócenie na „nowym telefonie” (inny profil), błędne hasło, obcięty plik. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun roundTrip() = runBlocking {
        val a = Core(ctx, "bkA" + System.nanoTime())
        a.createIdentity("Ala")
        val g = a.createGroup("Notatki", emptyList())
        val m1 = a.sendText(g, "Zażółć gęślą jaźń — wiadomość do kopii")
        val img = File(ctx.cacheDir, "kopia.jpg").apply { writeBytes(Crypto.randomBytes(120_000)) }
        val imgBytes = img.readBytes()
        val m2 = a.send(g, Kind.IMAGE, "fotka", Core.Attachment(img, "image/jpeg", "kopia.jpg", null, 4, 3, 0))
        a.avatarFile.writeBytes(Crypto.randomBytes(3000))
        a.schedule(g, System.currentTimeMillis() + 3600_000, Kind.TEXT, "zaplanowana z kopii")
        a.db.setReaction(m1.id, a.myPub, "👍", 5)

        val out = ByteArrayOutputStream()
        var lastProgress = 0
        Backup.export(a, out, "tajne-haslo-123".toCharArray(), withMedia = true, appVersion = "test") { lastProgress = it }
        assertEquals(100, lastProgress)
        val bytes = out.toByteArray()
        assertTrue("kopia zawiera zdjęcie", bytes.size > 120_000)
        assertFalse("treść nie jest jawna", String(bytes, Charsets.ISO_8859_1).contains("jaźń"))

        // błędne hasło: nic się nie zmienia
        val bProfile = "bkB" + System.nanoTime()
        val b0 = Core(ctx, bProfile)
        try {
            Backup.restore(b0, ByteArrayInputStream(bytes), "zle-haslo-000".toCharArray())
            fail("powinno odrzucić błędne hasło")
        } catch (e: Backup.BadPassword) { }
        assertFalse(b0.hasIdentity)
        // obcięty plik
        try {
            Backup.restore(b0, ByteArrayInputStream(bytes.copyOf(bytes.size - 100)), "tajne-haslo-123".toCharArray())
            fail("powinno wykryć obcięty plik")
        } catch (e: IOException) { assertFalse(e is Backup.BadPassword) }
        assertFalse(b0.hasIdentity)
        assertEquals("Ala", Backup.peek(ByteArrayInputStream(bytes), "tajne-haslo-123".toCharArray()).name)

        // przywrócenie na „nowym telefonie”
        val info = Backup.restore(b0, ByteArrayInputStream(bytes), "tajne-haslo-123".toCharArray())
        assertEquals("Ala", info.name)
        val b = Core(ctx, bProfile)   // nowy rdzeń, jak po App.resetCore()
        assertTrue(b.hasIdentity)
        assertEquals(a.myPub, b.myPub)
        assertEquals("Ala", b.myName)
        assertEquals("Zażółć gęślą jaźń — wiadomość do kopii", b.db.message(m1.id)!!.body)
        val r2 = b.db.message(m2.id)!!
        assertNotNull(r2.path)
        assertTrue("ścieżka wskazuje nowy katalog", r2.path!!.startsWith(b.mediaDir.absolutePath))
        assertArrayEquals(imgBytes, File(r2.path!!).readBytes())
        assertTrue(b.avatarFile.exists())
        assertEquals("👍", b.db.reaction(m1.id, a.myPub)!!.emoji)
        val sched = b.db.scheduled(g).single()
        assertEquals("zaplanowana z kopii", sched.body)
        assertEquals("Notatki", b.db.group(g.removePrefix("g:"))!!.name)

        // kopia bez mediów jest mniejsza
        val small = ByteArrayOutputStream()
        Backup.export(a, small, "tajne-haslo-123".toCharArray(), withMedia = false, appVersion = "test")
        assertTrue(small.size() < bytes.size - 100_000)
        println("BACKUP OK (${bytes.size / 1024} KB)")
    }

    @Test
    fun shortPasswordRejected() {
        val a = Core(ctx, "bkC" + System.nanoTime())
        a.createIdentity("Cezary")
        try {
            Backup.export(a, ByteArrayOutputStream(), "krotkie".toCharArray(), withMedia = false, appVersion = "test")
            fail("za krótkie hasło")
        } catch (e: IllegalArgumentException) { }
    }
}
