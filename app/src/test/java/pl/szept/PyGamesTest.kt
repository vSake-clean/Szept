package pl.szept

import org.json.JSONArray
import org.json.JSONObject
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
import pl.szept.core.GameCatalog
import pl.szept.core.PyGames
import java.io.File

/** Gry w Pythonie: lista z polityki administratora, pobrane pliki, wbudowany Wheelie Simulator. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PyGamesTest {
    private fun entry(id: String, sha: String, name: String = "Gra $id") = JSONObject().put("id", id).put("n", name).put("d", "opis").put("sk", "pts")
        .put("v", 1).put("m", JSONObject().put("id", "0".repeat(32)).put("key", "AAAA").put("sz", 10).put("c", 1).put("h", sha).put("name", "$id.py"))

    @Test
    fun syncInstallAndRemove() {
        val ctx = RuntimeEnvironment.getApplication()
        PyGames.init(ctx)
        // wbudowana gra jest zawsze
        val wheelie = PyGames.game("py:wheelie")
        assertNotNull(wheelie)
        assertTrue(wheelie!!.info().python)
        assertEquals(GameCatalog.Score.METERS, wheelie.score)
        assertNotNull(GameCatalog.byId("py:wheelie"))
        assertTrue(ctx.assets.open(wheelie.asset!!).use { it.readBytes() }.decodeToString().contains("szept.score("))
        listOf("runner.html", "runner.js", "boot.py").forEach { ctx.assets.open("pygame/$it").close() }

        // nowa gra z polityki czeka na pobranie
        val toDownload = PyGames.sync(JSONArray().put(entry("abc123", "a".repeat(64))).put(JSONObject().put("id", "../zle").put("n", "x")))
        assertEquals(listOf("py:abc123"), toDownload.map { it.id })
        assertNull(PyGames.game("py:abc123"))
        assertEquals(1, PyGames.pending().size)

        // pobrana — pojawia się na liście
        val f = File(ctx.cacheDir, "g.py").apply { writeText("print('hej')") }
        PyGames.install("py:abc123", f, "a".repeat(64))
        assertNotNull(PyGames.game("py:abc123"))
        assertTrue(PyGames.fileOf(PyGames.game("py:abc123")!!)!!.readText().contains("hej"))
        assertTrue(GameCatalog.forSolo().any { it.id == "py:abc123" })

        // nowa wersja (inna suma) — trzeba pobrać ponownie
        assertEquals(1, PyGames.sync(JSONArray().put(entry("abc123", "b".repeat(64)))).size)
        // usunięta z polityki — plik znika
        val path = File(ctx.filesDir, "pygames/abc123.py")
        PyGames.sync(JSONArray())
        assertNull(PyGames.game("py:abc123"))
        assertFalse(path.exists())
    }

    @Test
    fun runtimeFilesArePinned() {
        assertEquals(6, PyGames.RUNTIME.size)
        assertTrue(PyGames.RUNTIME.all { it.third.length == 64 && it.second > 0 })
        assertTrue(PyGames.RUNTIME_BYTES in 15_000_000L..25_000_000L)
        assertFalse(PyGames.runtimeReady(RuntimeEnvironment.getApplication()))
    }
}
