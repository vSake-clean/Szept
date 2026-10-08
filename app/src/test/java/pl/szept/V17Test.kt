package pl.szept

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Transcriber
import pl.szept.crypto.Crypto
import pl.szept.data.Db
import pl.szept.data.DbCrypto
import pl.szept.data.Extra
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Funkcje 1.7 dające się sprawdzić bez telefonu: okrągłe wideo, przepróbkowanie dźwięku, szyfrowanie bazy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class V17Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun roundVideoFlag() {
        val e = Extra(round = true)
        val json = org.json.JSONObject(e.toJson()!!)
        assertEquals(1, json.getInt("vn"))
        assertEquals(e, Extra.from(json))
        // przekazana dalej notatka zostaje okrągła
        val f = Extra(fwd = true, round = true)
        assertEquals(f, Extra.from(org.json.JSONObject(f.toJson()!!)))
        // starsze wersje nie wysyłają "vn" — zwykłe wideo
        assertFalse(Extra.from(org.json.JSONObject("""{"fw":1}""")).round)
    }

    @Test
    fun resample() {
        // 1 s sinusa 440 Hz przy 48 kHz → 16 000 próbek, ta sama częstotliwość
        val rate = 48_000
        val src = ShortArray(rate) { (sin(2 * PI * 440 * it / rate) * 10_000).toInt().toShort() }
        val out = Transcriber.resampleTo16k(src, rate)
        assertEquals(16_000 * 2, out.size)
        fun sample(i: Int) = ((out[2 * i].toInt() and 0xff) or (out[2 * i + 1].toInt() shl 8)).toShort().toInt()
        for (i in listOf(0, 100, 5_000, 15_999)) {
            val expected = sin(2 * PI * 440 * i / 16_000.0) * 10_000
            assertTrue("próbka $i: ${sample(i)} vs $expected", abs(sample(i) - expected) < 400)
        }
        // 16 kHz bez zmian, little-endian
        val same = Transcriber.resampleTo16k(shortArrayOf(1, -2, 0x1234), 16_000)
        assertEquals(listOf(1, 0, 0xfe, 0xff, 0x34, 0x12), same.map { it.toInt() and 0xff })
        assertEquals(0, Transcriber.resampleTo16k(ShortArray(0), 44_100).size)
        // 44,1 kHz: długość proporcjonalna
        assertEquals(16_000 * 2, Transcriber.resampleTo16k(ShortArray(44_100), 44_100).size)
    }

    @Test
    fun dbCryptoHelpers() {
        val key = ByteArray(32) { it.toByte() }
        val pass = String(DbCrypto.passphrase(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", pass)

        // zwykła baza SQLite jest rozpoznawana, a losowe bajty (jak w zaszyfrowanej) nie
        val name = "plain_${System.nanoTime()}.db"
        val db = Db(ctx, name)
        db.dbVersion; db.close()
        assertTrue(DbCrypto.isPlaintext(ctx.getDatabasePath(name)))
        val enc = File(ctx.cacheDir, "enc.db").apply { writeBytes(Crypto.randomBytes(4096)) }
        assertFalse(DbCrypto.isPlaintext(enc))
        assertFalse(DbCrypto.isPlaintext(File(ctx.cacheDir, "brak.db")))
    }

    @Test
    fun fallbackWithoutCipher() {
        // Na komputerze nie ma biblioteki SQLCipher — baza musi działać bez niej (tak jak na nietypowych telefonach).
        val name = "enc_${System.nanoTime()}.db"
        val db = Db(ctx, name, encrypt = true)
        assertEquals(DbCrypto.libraryAvailable(), db.encrypted)
        db.ensureChat("d:" + "b".repeat(64), false, "b".repeat(64), "Bartek")
        assertEquals(1, db.chats().size)
        db.close()
        val again = Db(ctx, name, encrypt = true)
        assertEquals("dane przetrwały ponowne otwarcie", 1, again.chats().size)
        again.close()
    }
}
