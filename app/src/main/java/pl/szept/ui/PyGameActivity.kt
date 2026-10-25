package pl.szept.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.GameCatalog
import pl.szept.core.PyGames
import pl.szept.ui.games.GameBadge
import pl.szept.ui.games.TotalsDialog
import pl.szept.ui.games.fmtMove
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Gra w Pythonie (pygame) na pełnym ekranie. Python działa w telefonie (Pyodide w WebView);
 * wszystkie pliki podaje aplikacja, a połączenia z internetem z gry są blokowane.
 */
class PyGameActivity : ComponentActivity() {
    private var web: WebView? = null
    private lateinit var gameId: String
    private var chatMsg: String? = null
    private var chatId: String? = null
    private var stoppedWithLock = false
    private val main = Handler(Looper.getMainLooper())
    /** Ostatni wynik z gry (pokazywany na chwilę u góry). */
    private var lastScore by mutableStateOf<Pair<Double, Boolean>?>(null)
    private var scoreSeq by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        chatMsg = intent.getStringExtra(EXTRA_MSG)
        chatId = intent.getStringExtra(EXTRA_CHAT)
        val game = PyGames.game(gameId) ?: run { toast(this, "Tej gry nie ma już w telefonie"); finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        setContent { SzeptTheme { Screen(game) } }
    }

    private fun hideBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    private fun Screen(game: PyGames.Game) {
        var ready by remember { mutableStateOf(PyGames.runtimeReady(this)) }
        var menu by remember { mutableStateOf(false) }
        var totals by remember { mutableStateOf(false) }
        val info = remember { game.info() }
        BackHandler { if (ready) menu = true else finish() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!ready) RuntimeDownload(game, onReady = { ready = true }, onCancel = { finish() })
            else {
                AndroidView(factory = { c ->
                    try { createWebView(c, game) } catch (e: Exception) {
                        // brak albo wyłączony Android System WebView
                        pl.szept.core.CrashLog.record("Gra Python: WebView", e)
                        toast(c, "Gry w Pythonie potrzebują Android System WebView — włącz lub zaktualizuj go w Sklepie Play")
                        finish()
                        android.widget.FrameLayout(c)
                    }
                }, modifier = Modifier.fillMaxSize())
                // wynik z gry — na chwilę u góry ekranu
                val ls = lastScore
                if (ls != null && info.score.has) {
                    LaunchedEffect(scoreSeq) { delay(2500); lastScore = null }
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ls.second) { Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                        Text((if (ls.second) "Rekord! " else "Wynik: ") + info.score.fmt(ls.first), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (menu) AlertDialog(
            onDismissRequest = { menu = false },
            icon = { GameBadge(info) },
            title = { Text(game.name) },
            text = { Text("Gra jest wstrzymana tylko na czas tego okna.") },
            confirmButton = { TextButton(onClick = { menu = false }) { Text("Wróć do gry") } },
            dismissButton = {
                Row {
                    if (info.score.has) TextButton(onClick = { menu = false; totals = true }) { Text("Łącznie") }
                    TextButton(onClick = { menu = false; finish() }) { Text("Zakończ") }
                }
            },
        )
        if (totals) TotalsDialog(info, chatMsg, chatId) { totals = false }
    }

    @Composable
    private fun RuntimeDownload(game: PyGames.Game, onReady: () -> Unit, onCancel: () -> Unit) {
        val scope = rememberCoroutineScope()
        var progress by remember { mutableFloatStateOf(-1f) }
        var error by remember { mutableStateOf<String?>(null) }
        fun start() {
            error = null; progress = 0f
            scope.launch {
                try {
                    PyGames.downloadRuntime(this@PyGameActivity) { done -> progress = (done.toFloat() / PyGames.RUNTIME_BYTES).coerceIn(0f, 1f) }
                    onReady()
                } catch (e: Exception) {
                    progress = -1f
                    error = "Nie udało się pobrać: ${e.message ?: e.javaClass.simpleName}. Sprawdź internet i spróbuj ponownie."
                }
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Card(Modifier.padding(24.dp).widthIn(max = 460.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(game.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gry w Pythonie potrzebują środowiska Pythona (ok. ${PyGames.RUNTIME_BYTES / 1_000_000} MB). Pobierzemy je jeden raz z cdn.jsdelivr.net " +
                            "i sprawdzimy sumy kontrolne — potem wszystkie gry w Pythonie działają bez internetu.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%", Modifier.padding(top = 6.dp))
                    } else {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp)) }
                        Button(onClick = { start() }, Modifier.fillMaxWidth()) { Text("Pobierz i zagraj") }
                        TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Anuluj") }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(c: Context, game: PyGames.Game): WebView {
        val w = WebView(c)
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // IndexedDB — zapisy gier (rekordy)
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
        }
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.addJavascriptInterface(Bridge(), "SzeptGame")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) pl.szept.core.CrashLog.note("Gra Python: " + m.message().take(500))
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse = serve(req, game)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = req.url.host != HOST
        }
        val args = java.net.URLEncoder.encode(game.args, "UTF-8")
        w.loadUrl("https://$HOST/runner/runner.html?id=${game.id.removePrefix("py:")}&kind=${if (game.zip) "zip" else "py"}&args=$args")
        w.requestFocus()
        web = w
        return w
    }

    /** Pliki dla gry: runner (z aplikacji), środowisko Pythona (pobrane) i plik gry. Reszta — zablokowana. */
    private fun serve(req: WebResourceRequest, game: PyGames.Game): WebResourceResponse {
        val url = req.url
        if (url.host != HOST) return blocked()
        val path = url.path ?: return blocked()
        return try {
            when {
                path.startsWith("/runner/") -> {
                    val name = path.removePrefix("/runner/")
                    if (name !in setOf("runner.html", "runner.js", "boot.py")) blocked() else ok(name, assets.open("pygame/$name"))
                }
                path.startsWith("/py/") -> {
                    val name = path.removePrefix("/py/")
                    if (PyGames.RUNTIME.none { it.first == name }) blocked()
                    else ok(name, FileInputStream(File(PyGames.runtimeDir(this), name)))
                }
                path == "/game/main.py" && !game.zip -> ok("main.py", gameStream(game))
                path == "/game/game.zip" && game.zip -> ok("game.zip", gameStream(game))
                else -> blocked()
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        }
    }

    private fun gameStream(game: PyGames.Game): InputStream =
        game.asset?.let { assets.open(it) } ?: FileInputStream(PyGames.fileOf(game) ?: throw java.io.FileNotFoundException())

    private fun ok(name: String, data: InputStream): WebResourceResponse {
        val mime = when (name.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "text/javascript"; "wasm" -> "application/wasm"; "json" -> "application/json"
            "zip", "whl" -> "application/zip"; "py" -> "text/plain"; else -> "application/octet-stream"
        }
        val text = mime.startsWith("text/") || mime == "application/json"
        return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-store"), data)
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), "".byteInputStream())

    /** Wywołania z gry (JavaScript → aplikacja). */
    private inner class Bridge {
        @JavascriptInterface
        fun score(v: Double, label: String) {
            if (v.isNaN() || v.isInfinite()) return
            main.post { onScore(v, label.take(80)) }
        }

        @JavascriptInterface
        fun ready(w: Int, h: Int) {
            main.post {
                if (w > 0 && h > 0) requestedOrientation = if (w > h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }

        @JavascriptInterface
        fun exit() { main.post { finish() } }

        @JavascriptInterface
        fun error(t: String) { pl.szept.core.CrashLog.note("Gra Python ($gameId): " + t.take(2000)) }
    }

    private fun onScore(v: Double, label: String) {
        val info = GameCatalog.byId(gameId) ?: return
        val msg = chatMsg
        val chat = chatId
        App.work.launch {
            val record = withContext(Dispatchers.IO) {
                val db = App.core.db
                val prev = info.score.best(db.gameScores(gameId).map { it.score })
                db.addGameScore(gameId, v, chat?.let { "chat:$it" } ?: "solo", label)
                // w wyzwaniu wysyłamy tylko poprawiony wynik (gra może zgłaszać ich wiele)
                if (msg != null) {
                    val mine = db.gameMoves(msg).filter { it.sender == App.core.myPub }.mapNotNull { it.mv.toDoubleOrNull() }
                    val best = info.score.best(mine)
                    if (best == null || info.score.better(v, best)) App.core.gameMove(msg, fmtMove(v))
                }
                prev == null || info.score.better(v, prev)
            }
            lastScore = v to record
            scoreSeq++
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        hideBars()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // z blokadą PIN-em po powrocie do aplikacji pokazujemy najpierw ekran blokady (gra się zamyka)
        stoppedWithLock = App.real.lockEnabled && !isChangingConfigurations
    }

    override fun onRestart() {
        super.onRestart()
        if (stoppedWithLock) finish()
    }

    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MSG = "msg"
        private const val EXTRA_CHAT = "chat"

        fun launch(ctx: Context, id: String, chatMsg: String? = null, chatId: String? = null) {
            ctx.startActivity(Intent(ctx, PyGameActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_MSG, chatMsg).putExtra(EXTRA_CHAT, chatId))
        }
    }
}
