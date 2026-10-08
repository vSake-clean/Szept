package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Core
import pl.szept.core.Gifs
import pl.szept.data.Kind
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** GIF-y: moja kolekcja, odpowiedź KLIPY, pobieranie, wysyłanie animacji bez przerabiania na JPEG. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GifTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    companion object {
        /** Najmniejszy poprawny GIF (1×1). */
        val GIF: ByteArray = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x21, 0xf9.toByte(), 4, 0, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b,
        )
        fun gifFile(dir: File, extra: Int = 0): File = File(dir, "t_${System.nanoTime()}.gif").apply { writeBytes(GIF + ByteArray(extra)) }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) { println("✔ $what"); return }; delay(150) }
        throw AssertionError("Timeout: $what")
    }

    @Test
    fun collection() = runBlocking {
        Gifs.saved(ctx).forEach { Gifs.remove(ctx, it) }
        val a = gifFile(ctx.cacheDir)
        val saved = Gifs.save(ctx, a)
        Gifs.save(ctx, gifFile(ctx.cacheDir))       // ten sam GIF — tylko raz
        assertEquals(1, Gifs.saved(ctx).size)
        assertTrue(Gifs.isSaved(ctx, a))
        val b = Gifs.save(ctx, gifFile(ctx.cacheDir, extra = 3))
        assertEquals(b, Gifs.saved(ctx).first())       // najnowszy pierwszy
        val jpg = File(ctx.cacheDir, "x.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0)) }
        try { Gifs.save(ctx, jpg); throw AssertionError("JPEG to nie GIF") } catch (e: Core.SendException) { }
        val att = Gifs.attachment(ctx, saved)
        assertEquals("image/gif", att.mime)
        assertTrue(att.file.exists() && att.file != saved && saved.exists())   // kopia — oryginał zostaje w kolekcji
        Gifs.remove(ctx, saved); Gifs.remove(ctx, b)
        assertTrue(Gifs.saved(ctx).isEmpty())
    }

    @Test
    fun klipyResponse() {
        val body = """{"result":true,"data":{"data":[
            {"id":1,"slug":"kot","title":"Kot","type":"gif","file":{
               "hd":{"gif":{"url":"https://static.klipy.com/hd.gif","width":498,"height":280}},
               "md":{"gif":{"url":"https://static.klipy.com/md.gif","width":320,"height":180}},
               "sm":{"gif":{"url":"https://static.klipy.com/sm.gif","width":200,"height":112}}}},
            {"type":"ad","content":"<html>"},
            {"slug":"pies","files":{"gif":{"url":"https://static.klipy.com/pies.gif","width":100,"height":100}}}
          ],"current_page":1,"per_page":24,"has_next":true}}"""
        val p = Gifs.parse(body)
        assertEquals(2, p.items.size)
        assertTrue(p.hasNext)
        assertEquals("https://static.klipy.com/sm.gif", p.items[0].preview)
        assertEquals("https://static.klipy.com/md.gif", p.items[0].full)
        assertEquals(320, p.items[0].w)
        assertEquals("https://static.klipy.com/pies.gif", p.items[1].full)
        try { Gifs.parse("""{"result":false,"errors":{"message":["The provided API key is invalid."]}}"""); throw AssertionError() }
        catch (e: Core.SendException) { assertEquals("Nieprawidłowy klucz KLIPY", e.message) }
    }

    @Test
    fun klipyRequestAndDownload() = runBlocking {
        var lastQuery = ""
        // prosty serwer HTTP udający KLIPY
        val srv = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!srv.isClosed) {
                val sock = try { srv.accept() } catch (e: Exception) { break }
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val target = reader.readLine()?.split(" ")?.getOrNull(1) ?: "/"
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    lastQuery = target
                    val path = target.substringBefore('?')
                    val (code, bytes) = when {
                        path.endsWith(".gif") -> 200 to GIF
                        path.contains("/zly/") -> 401 to """{"result":false,"errors":{"message":["The provided API key is invalid."]}}""".toByteArray()
                        else -> 200 to """{"result":true,"data":{"data":[{"slug":"a","file":{"sm":{"gif":{"url":"https://x/a.gif","width":1,"height":1}}}}],"has_next":false}}""".toByteArray()
                    }
                    val o = s.getOutputStream()
                    o.write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(bytes); o.flush()
                }
            }
        }
        val base = "http://127.0.0.1:${srv.localPort}/api/v1"
        try {
            val trending = Gifs.klipy(ctx, "klucz", "", 1, base)
            assertTrue(lastQuery, lastQuery.startsWith("/api/v1/klucz/gifs/trending?") && lastQuery.contains("customer_id="))
            assertEquals(1, trending.items.size)
            Gifs.klipy(ctx, "klucz", "śmieszny kot", 2, base)
            assertTrue(lastQuery, lastQuery.contains("/gifs/search?") && lastQuery.contains("page=2") && lastQuery.contains("q="))
            try { Gifs.klipy(ctx, "zly", "", 1, base); throw AssertionError() } catch (e: Core.SendException) { assertEquals("Nieprawidłowy klucz KLIPY", e.message) }
            val f = Gifs.download(ctx, "http://127.0.0.1:${srv.localPort}/a.gif")
            assertTrue(Gifs.isGif(f))
        } finally { srv.close() }
    }

    @Test
    fun sendAnimatedGif() = runBlocking {
        val inet = LocalRelay(ctx, "gif_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + inet.start(0)
        fun core(n: String) = Core(ctx, n + System.nanoTime()).apply { createIdentity(n); setRelays(listOf(url)); appVisible = true; start() }
        val a = core("Ala"); val b = core("Bartek")
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")
        // GIF wybrany z galerii nie jest przerabiany na JPEG
        val picked = gifFile(ctx.cacheDir)
        val att = pl.szept.core.MediaUtil.prepareImage(ctx, android.net.Uri.fromFile(picked))
        assertEquals("image/gif", att.mime)
        val m = a.send(dmChatId(b.myPub), Kind.IMAGE, "", att)
        waitFor("B pobrał GIF") { b.db.message(m.id)?.path != null }
        val got = b.db.message(m.id)!!
        assertEquals("image/gif", got.media?.mime)
        assertTrue(Gifs.isGif(File(got.path!!)))
        assertFalse(File(got.path!!).readBytes().contentEquals(ByteArray(0)))
        a.stop(); b.stop(); inet.close()
    }
}
