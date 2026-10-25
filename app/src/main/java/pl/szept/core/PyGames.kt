package pl.szept.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.crypto.hex
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Gry w Pythonie (pygame): wbudowane (w plikach aplikacji) i rozsyłane przez administratora
 * w podpisanej polityce („pyg”). Uruchamia je [pl.szept.ui.PyGameActivity] — Python działa w telefonie (Pyodide).
 */
object PyGames {
    /** Opis gry z polityki administratora (albo wbudowanej). */
    data class Game(
        val id: String,
        val name: String,
        val desc: String,
        /** Rodzaj wyniku: pts, time, timehi, m, none, custom. */
        val sk: String,
        val unit: String,
        val higher: Boolean,
        val decimals: Int,
        /** Argumenty uruchomienia (sys.argv). */
        val args: String,
        /** Plik .zip (z main.py i zasobami) zamiast pojedynczego .py. */
        val zip: Boolean,
        val ver: Long,
        /** Plik w paczce aplikacji (gry wbudowane). */
        val asset: String? = null,
        /** Opis pliku do pobrania (gry od administratora). */
        val media: JSONObject? = null,
    ) {
        val builtin get() = asset != null
        val sha: String get() = media?.optString("h").orEmpty()

        val score: GameCatalog.Score get() = when (sk) {
            "pts" -> GameCatalog.Score.POINTS
            "time" -> GameCatalog.Score.TIME_LOW
            "timehi" -> GameCatalog.Score.TIME_HIGH
            "m" -> GameCatalog.Score.METERS
            "none" -> GameCatalog.Score.NONE
            else -> GameCatalog.Score(GameCatalog.Score.Kind.NUMBER, higher, if (unit.isBlank()) emptyList() else listOf(unit, unit, unit), decimals.coerceIn(0, 3))
        }

        fun info() = GameCatalog.Info(
            id, name, desc, GameCatalog.Cat.PYTHON, solo = true,
            chat = if (score.has) GameCatalog.Chat.CHALLENGE else GameCatalog.Chat.NONE,
            score = score, players = "1+", python = true,
        )

        fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("d", desc).put("sk", sk).put("u", unit).put("hi", higher)
            .put("dp", decimals).put("a", args).put("z", zip).put("v", ver).apply { media?.let { put("m", it) } }

        companion object {
            fun fromJson(o: JSONObject): Game? {
                val raw = o.optString("id")
                if (!raw.matches(Regex("[a-z0-9]{3,24}"))) return null
                val name = o.optString("n").trim().take(40)
                if (name.isEmpty()) return null
                return Game(
                    "py:$raw", name, o.optString("d").trim().take(400), o.optString("sk", "pts").take(8), o.optString("u").trim().take(12),
                    o.optBoolean("hi", true), o.optInt("dp", 0), o.optString("a").replace(Regex("[^A-Za-z0-9 _=.-]"), "").take(80),
                    o.optBoolean("z"), o.optLong("v"), media = o.optJSONObject("m"),
                )
            }
        }
    }

    val BUILTIN = listOf(
        Game(
            "py:wheelie", "Wheelie Simulator 2D",
            "Symulator jazdy na tylnym kole: ścigacz, Sur-Ron, pit bike i hulajnogi Kukirin. Fizyka, sprzęgło, biegi, wiatr — wheelie jest celowo trudne. " +
                "Wynik to najdłuższe wheelie w metrach. Telefon trzymaj poziomo.",
            "m", "m", true, 1, "--telefon", false, 1, asset = "pygame/games/wheelie.py",
        ),
    )

    private var ctx: Context? = null
    /** Gry od administratora (ostatnia polityka) — także te jeszcze pobierane. */
    @Volatile private var remote: List<Game> = emptyList()
    /** Pobrane pliki: id → (suma kontrolna, ścieżka). */
    @Volatile private var files: Map<String, Pair<String, String>> = emptyMap()
    /** Zmienia się, gdy zmieni się lista gier. */
    val changes = MutableStateFlow(0)

    @Synchronized
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = prefs() ?: return
        try {
            val o = JSONObject(p.getString("state", "{}")!!)
            remote = o.optJSONArray("g")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Game::fromJson) } } ?: emptyList()
            val f = o.optJSONObject("f") ?: JSONObject()
            files = f.keys().asSequence().associateWith { k -> f.getJSONObject(k).let { it.optString("h") to it.optString("p") } }
                .filter { File(it.value.second).isFile }
        } catch (e: Exception) { CrashLog.record("Gry Python", e) }
    }

    private fun prefs() = ctx?.getSharedPreferences("pygames", Context.MODE_PRIVATE)

    private fun save() {
        val f = JSONObject()
        files.forEach { (k, v) -> f.put(k, JSONObject().put("h", v.first).put("p", v.second)) }
        prefs()?.edit()?.putString("state", JSONObject().put("g", JSONArray(remote.map { it.toJson().put("id", it.id.removePrefix("py:")) })).put("f", f).toString())?.apply()
        changes.value++
    }

    fun dir(): File? = ctx?.let { File(it.filesDir, "pygames").apply { mkdirs() } }

    /** Gry gotowe do uruchomienia (wbudowane + pobrane). */
    fun all(): List<Game> = BUILTIN + remote.filter { isReady(it) }
    /** Gry od administratora czekające na pobranie. */
    fun pending(): List<Game> = remote.filter { !isReady(it) }
    fun remoteGames(): List<Game> = remote

    fun isReady(g: Game) = g.builtin || files[g.id]?.let { it.first == g.sha && File(it.second).isFile } == true

    fun game(id: String): Game? = all().firstOrNull { it.id == id }
    fun infos(): List<GameCatalog.Info> = all().map { it.info() }
    fun info(id: String): GameCatalog.Info? = game(id)?.info()

    /** Plik gry do uruchomienia (gry wbudowane czyta Activity z zasobów). */
    fun fileOf(g: Game): File? = files[g.id]?.second?.let(::File)?.takeIf { it.isFile }

    /**
     * Nowa lista gier z polityki administratora. Zwraca gry, które trzeba pobrać.
     * Pliki gier usuniętych z listy są kasowane.
     */
    @Synchronized
    fun sync(arr: JSONArray?): List<Game> {
        val list = arr?.let { a -> (0 until minOf(a.length(), 100)).mapNotNull { a.optJSONObject(it)?.let(Game::fromJson) } }?.distinctBy { it.id } ?: emptyList()
        val ids = list.map { it.id }.toSet()
        val gone = files.keys.filter { it !in ids }
        gone.forEach { files[it]?.second?.let { p -> File(p).delete() } }
        files = files - gone.toSet()
        remote = list
        save()
        return list.filter { !isReady(it) && it.media != null }
    }

    /** Pobrany (albo właśnie wysłany przez administratora) plik gry. */
    @Synchronized
    fun install(id: String, src: File, sha: String, move: Boolean = true): Boolean {
        val g = remote.firstOrNull { it.id == id }
        val d = dir() ?: return false
        val dest = File(d, id.removePrefix("py:") + if (g?.zip == true) ".zip" else ".py")
        if (move) { if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() } } else src.copyTo(dest, overwrite = true)
        files = files + (id to (sha to dest.absolutePath))
        save()
        return true
    }

    /** Gra od administratora dopisana od razu u niego (bez pobierania z przekaźników). */
    @Synchronized
    fun addLocal(g: Game, src: File, sha: String) {
        remote = remote.filter { it.id != g.id } + g
        install(g.id, src, sha, move = false)
    }

    // ================= środowisko Pythona (Pyodide) =================

    const val PYODIDE = "0.27.7"
    private const val CDN = "https://cdn.jsdelivr.net/pyodide/v$PYODIDE/full/"
    const val PYGAME_WHEEL = "pygame_ce-2.4.1-cp312-cp312-pyodide_2024_0_wasm32.whl"

    /** Pliki środowiska z sumami SHA-256 — pobrane pliki o innej sumie są odrzucane. */
    val RUNTIME = listOf(
        Triple("pyodide.js", 14_913L, "b4cb23a53aba19c221659b9fb40a2f18281d685691dc06647fb0afd0681baf98"),
        Triple("pyodide.asm.js", 1_255_688L, "6b4c90de5b7172873f04f21884d0e9d2274e305fe32116558fe3e4fbe3618d51"),
        Triple("pyodide.asm.wasm", 10_105_545L, "a50dd1843f805a0b7c45b61037ee0d7b26dfe85efe0e18ef95a34ad24e401f5f"),
        Triple("python_stdlib.zip", 2_360_737L, "16611534726e5d8ac2bd8f926410b2dcb8d6f49aa24913463533b457a2115c16"),
        Triple("pyodide-lock.json", 112_205L, "9c45b916001a750f4102fc287494f3eab215909c7535c626a20db80fd6333e2c"),
        Triple(PYGAME_WHEEL, 4_217_850L, "4ce0d35621b6a67bd66db8d3d4f3531774a8cf80edc0faecf76e33762ff71ef1"),
    )
    val RUNTIME_BYTES = RUNTIME.sumOf { it.second }

    fun runtimeDir(c: Context) = File(c.filesDir, "pyodide-$PYODIDE")

    fun runtimeReady(c: Context): Boolean {
        val d = runtimeDir(c)
        return File(d, ".ok").isFile && RUNTIME.all { File(d, it.first).length() == it.second }
    }

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Pobiera środowisko Pythona (raz, ok. 18 MB). [progress] — pobrane bajty. */
    suspend fun downloadRuntime(c: Context, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val d = runtimeDir(c).apply { mkdirs() }
        // stare wersje środowiska nie są już potrzebne
        c.filesDir.listFiles()?.filter { it.name.startsWith("pyodide-") && it.name != d.name }?.forEach { it.deleteRecursively() }
        var done = 0L
        for ((name, size, sha) in RUNTIME) {
            val f = File(d, name)
            if (f.length() == size && sha256(f) == sha) { done += size; progress(done); continue }
            val tmp = File(d, "$name.part")
            http.newCall(Request.Builder().url(CDN + name).build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Serwer odpowiedział ${r.code}")
                val body = r.body ?: throw java.io.IOException("Pusta odpowiedź")
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n; progress(done)
                            if (tmp.length() > size) throw java.io.IOException("Plik jest za duży")
                        }
                    }
                }
            }
            if (sha256(tmp) != sha) { tmp.delete(); throw java.io.IOException("Plik $name ma złą sumę kontrolną") }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
        File(d, ".ok").writeText(PYODIDE)
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().hex()
    }
}
