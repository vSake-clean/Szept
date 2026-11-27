package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.data.Kind
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import java.io.File
import java.security.MessageDigest

/**
 * Duże pliki (np. aktualizacja) przez przekaźniki, które oddają ograniczoną liczbę zdarzeń na zapytanie —
 * tak jak prawdziwe (500, niektóre 100). Przed 3.3.5 pobieranie stawało wtedy w połowie.
 * Przekaźniki „w internecie” to tu osobne przekaźniki na 127.0.0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MediaSpreadTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private suspend fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(200)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun bigFileThroughLimitedRelays() = runBlocking {
        val cap = 60
        val nets = List(4) { LocalRelay(ctx, "spread_${it}_${System.nanoTime()}.db").apply { maxLimit = cap } }
        val urls = nets.map { "ws://127.0.0.1:" + it.start(0) }
        fun core(name: String) = Core(ctx, name + System.nanoTime()).apply {
            createIdentity(name); setRelays(urls); appVisible = true; start()
        }
        val a = core("Nadawca")
        val b = core("Odbiorca")
        waitFor("nadawca zna limity przekaźników") { urls.all { a.mediaCapOf(it) == cap } }
        a.addContact(b.myPub, "Odbiorca"); b.addContact(a.myPub, "Nadawca")
        val dm = dmChatId(b.myPub)

        // 1) zwykła wysyłka (bez szybkiej): plik ~100 paczek, a przekaźnik oddaje najwyżej 60
        a.setFastSendMode(2)
        val f1 = File(ctx.cacheDir, "duzy1.bin").apply { writeBytes(ByteArray(100 * 44_000 - 1234).also { java.util.Random(1).nextBytes(it) }) }
        val sha1 = sha(f1)   // plik źródłowy znika po wysłaniu (przenoszony do Szepta)
        val m1 = a.send(dm, Kind.FILE, "duży plik", Core.Attachment(f1, "application/octet-stream", "duzy1.bin", null, 0, 0, 0))
        waitFor("odbiorca pobrał duży plik", 120_000) { b.db.message(m1.id)?.path != null }
        assertEquals(sha1, sha(File(b.db.message(m1.id)!!.path!!)))
        val plan1 = b.db.message(m1.id)!!.media!!.chunkPlan()!!
        println("plan 1: ${plan1.relays.size} przekaźniki, ${plan1.copies} kopie")
        assertTrue("za mało kopii", plan1.copies >= 2)

        // każdy przekaźnik ma najwyżej tyle paczek pliku, ile odda na jedno zapytanie,
        // więc stara wersja (pyta o cały plik naraz) też dostaje komplet
        fun legacyUnion(media: String, chunks: Int): Set<Int> {
            val got = HashSet<Int>()
            nets.forEach { r ->
                val f = JSONObject().put("kinds", JSONArray().put(Core.CHUNK_KIND)).put("#x", JSONArray().put(media)).put("limit", chunks + 20)
                r.query(f).forEach { j -> JSONObject(j).getJSONArray("tags").let { t -> for (i in 0 until t.length()) if (t.getJSONArray(i).getString(0) == "c") got.add(t.getJSONArray(i).getString(1).toInt()) } }
            }
            return got
        }
        val meta1 = b.db.message(m1.id)!!.media!!
        waitFor("wszystkie kopie wysłane") { legacyUnion(meta1.id, meta1.chunks).size == meta1.chunks }
        assertEquals((0 until meta1.chunks).toSet(), legacyUnion(meta1.id, meta1.chunks))

        // 2) szybka wysyłka: plik rozłożony na wszystkie przekaźniki
        a.setFastSendMode(1)
        val f2 = File(ctx.cacheDir, "duzy2.bin").apply { writeBytes(ByteArray(90 * 44_000).also { java.util.Random(2).nextBytes(it) }) }
        val sha2 = sha(f2)
        val m2 = a.send(dm, Kind.FILE, "szybko", Core.Attachment(f2, "application/octet-stream", "duzy2.bin", null, 0, 0, 0))
        waitFor("odbiorca pobrał plik z szybkiej wysyłki", 120_000) { b.db.message(m2.id)?.path != null }
        assertEquals(sha2, sha(File(b.db.message(m2.id)!!.path!!)))
        val plan2 = b.db.message(m2.id)!!.media!!.chunkPlan()!!
        assertEquals("szybka wysyłka — wszystkie przekaźniki", urls.size, plan2.relays.size)
        plan2.relays.forEach { assertTrue(plan2.sizeOf(it) <= cap) }

        listOf(a, b).forEach { it.stop() }
        nets.forEach { it.close() }
        println("DUŻE PLIKI PRZEZ PRZEKAŹNIKI Z LIMITEM OK")
    }

    /** Jeden przekaźnik z planu w ogóle nie odpowiada — jego część przejmują pozostałe (zastój nie blokuje wysyłki). */
    @Test
    fun deadRelayDoesNotBlockUpload() = runBlocking {
        val cap = 30
        val nets = List(3) { LocalRelay(ctx, "dead_${it}_${System.nanoTime()}.db").apply { maxLimit = cap } }
        // martwy przekaźnik na początku listy (port, na którym nic nie słucha)
        val dead = "ws://127.0.0.1:" + java.net.ServerSocket(0).use { it.localPort }
        val urls = listOf(dead) + nets.map { "ws://127.0.0.1:" + it.start(0) }
        fun core(name: String) = Core(ctx, name + System.nanoTime()).apply {
            createIdentity(name); setRelays(urls); appVisible = true; stallMs = 3_000; start()
        }
        val a = core("Nadawca")
        val b = core("Odbiorca")
        waitFor("nadawca zna limity") { urls.drop(1).all { a.mediaCapOf(it) == cap } }
        waitFor("nadawca połączony z działającymi przekaźnikami") { a.connectedRelays().size >= 3 }
        a.addContact(b.myPub, "Odbiorca"); b.addContact(a.myPub, "Nadawca")
        a.setFastSendMode(2)
        val f = File(ctx.cacheDir, "martwy.bin").apply { writeBytes(ByteArray(100 * 44_000).also { java.util.Random(3).nextBytes(it) }) }
        val sha0 = sha(f)
        val m = a.send(dmChatId(b.myPub), Kind.FILE, "mimo martwego przekaźnika", Core.Attachment(f, "application/octet-stream", "martwy.bin", null, 0, 0, 0))
        waitFor("odbiorca pobrał plik mimo martwego przekaźnika", 120_000) { b.db.message(m.id)?.path != null }
        assertEquals(sha0, sha(File(b.db.message(m.id)!!.path!!)))
        val plan = b.db.message(m.id)!!.media!!.chunkPlan()!!
        println("plan: ${plan.relays.size} przekaźniki, kopie ${plan.copies}, przejęte: ${plan.extra}")
        // 3.4.2: przekaźnik bez połączenia w ogóle nie dostaje części pliku
        assertTrue("martwy przekaźnik nie powinien być w planie", dead !in plan.relays)
        listOf(a, b).forEach { it.stop() }
        nets.forEach { it.close() }
        println("MARTWY PRZEKAŹNIK NIE BLOKUJE WYSYŁKI OK")
    }

    /**
     * Jak na zrzucie z 3.4.1: jeden przekaźnik banuje („banned: too many rate-limit violations”),
     * drugi co chwilę każe zwolnić. Wysyłka ma się skończyć, zbanowany przekaźnik ma być omijany.
     */
    @Test
    fun bannedAndSlowRelays() = runBlocking {
        val cap = 60
        val nets = List(4) { LocalRelay(ctx, "ban_${it}_${System.nanoTime()}.db").apply { maxLimit = cap } }
        nets[0].refuseWith = "banned: too many rate-limit violations, try again later"
        nets[1].slowEvery = 3
        val urls = nets.map { "ws://127.0.0.1:" + it.start(0) }
        fun core(name: String) = Core(ctx, name + System.nanoTime()).apply {
            createIdentity(name); setRelays(urls); appVisible = true; stallMs = 5_000; start()
        }
        val a = core("Nadawca")
        val b = core("Odbiorca")
        waitFor("nadawca zna limity") { urls.all { a.mediaCapOf(it) == cap } }
        a.addContact(b.myPub, "Odbiorca"); b.addContact(a.myPub, "Nadawca")
        a.setFastSendMode(1)
        val f = File(ctx.cacheDir, "ban.bin").apply { writeBytes(ByteArray(100 * 44_000).also { java.util.Random(4).nextBytes(it) }) }
        val sha0 = sha(f)
        val m = a.send(dmChatId(b.myPub), Kind.FILE, "mimo bana", Core.Attachment(f, "application/octet-stream", "ban.bin", null, 0, 0, 0))
        waitFor("odbiorca pobrał plik mimo bana i limitów", 150_000) { b.db.message(m.id)?.path != null }
        assertEquals(sha0, sha(File(b.db.message(m.id)!!.path!!)))
        assertTrue("zbanowany przekaźnik powinien być omijany", a.mediaCooldownUntil(urls[0]) > System.currentTimeMillis())
        // 3.4.3: po wysłaniu wysyłka się kończy — zapasowa kopia odpuszcza przekaźnik, który każe zwolnić (nic nie wisi na 100%)
        var lastPrint = 0L
        waitFor("wysyłka zakończona (także zapasowe kopie)", 90_000) {
            val st = a.uploadRelays(m.id)
            if (System.currentTimeMillis() - lastPrint > 20_000) { lastPrint = System.currentTimeMillis(); println("  stan: " + st.joinToString { "${it.host.takeLast(5)} ${it.done}/${it.need} rej=${it.rejected} err=${it.error}" }) }
            st.all { it.done >= it.need || it.rejected }
        }
        val st = a.uploadRelays(m.id)
        println(st.joinToString("\n") { "${it.host}: ${it.done}/${it.need} rejected=${it.rejected} err=${it.error}" })
        listOf(a, b).forEach { it.stop() }
        nets.forEach { it.close() }
        println("BAN I LIMITY NIE BLOKUJĄ WYSYŁKI OK")
    }
}
