package pl.szept

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.VoiceRecorder
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Db
import pl.szept.data.Kind
import pl.szept.data.XMode
import java.io.File

/** Wiadomości czasowe (bez sieci): nic nie zdradza treści przed otwarciem, czas wystarcza na odtworzenie. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TimedMessageTest {
    private fun file(name: String) = File(RuntimeEnvironment.getApplication().cacheDir, name).apply { writeBytes(Crypto.randomBytes(2000)) }

    @Test
    fun timedMediaAndWave() = runBlocking {
        val c = Core(RuntimeEnvironment.getApplication(), "timed" + System.nanoTime())
        c.createIdentity("Ja")
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        val chat = c.openDm(peer)

        val timedImg = c.send(chat, Kind.IMAGE, "", Core.Attachment(file("a.jpg"), "image/jpeg", "a.jpg", "MINIATURA", 10, 10, 0), XMode.AFTER_OPEN, 10)
        assertNull("zdjęcie czasowe nie zdradza miniatury", c.db.message(timedImg.id)!!.media!!.thumb)
        assertEquals("⏱ Zdjęcie czasowe", Db.preview(c.db.message(timedImg.id)!!))

        val normalImg = c.send(chat, Kind.IMAGE, "", Core.Attachment(file("b.jpg"), "image/jpeg", "b.jpg", "MINIATURA", 10, 10, 0), XMode.OFF, null)
        assertEquals("MINIATURA", c.db.message(normalImg.id)!!.media!!.thumb)

        val wave = VoiceRecorder.encodeWave(List(200) { (it % 17) / 17f })
        assertNotNull(wave); assertEquals(96, wave!!.length)
        assertEquals(48, VoiceRecorder.decodeWave(wave)!!.size)
        val voice = c.send(chat, Kind.VOICE, "", Core.Attachment(file("g.m4a"), "audio/mp4", "g.m4a", null, 0, 0, 30_000, wave), XMode.AFTER_OPEN, 10)
        val v = c.db.message(voice.id)!!
        assertEquals("głosówka 30 s nie zniknie w trakcie odsłuchu", 35, v.ttl)
        assertEquals(wave, v.media!!.wave)
        assertTrue("ukryta do dotknięcia u odbiorcy", v.copy(mine = false, opened = 0).hiddenUntilOpened)

        val video = c.send(chat, Kind.VIDEO, "", Core.Attachment(file("w.mp4"), "video/mp4", "w.mp4", "MINI", 10, 10, 62_000), XMode.AFTER_OPEN, 5)
        assertEquals(67, c.db.message(video.id)!!.ttl)

        val text = c.sendText(chat, "tajne", XMode.AFTER_OPEN, 10)
        assertEquals("⏱ Wiadomość czasowa", Db.preview(text))
        assertFalse("treść nie trafia do podglądu", Db.preview(text).contains("tajne"))
        assertEquals("cytat też nie zdradza treści", "⏱ Wiadomość czasowa", c.replyOf(text).text)
        assertNull("brak przebiegu dla ciszy", VoiceRecorder.encodeWave(List(50) { 0f }))
        println("TIMED OK")
    }
}
