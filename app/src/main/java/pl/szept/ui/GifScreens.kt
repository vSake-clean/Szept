package pl.szept.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.Core
import pl.szept.core.Gifs
import java.io.File

// ======================= odtwarzanie GIF-ów =======================

/** Widok animowanego GIF-a: Android 9+ — AnimatedImageDrawable, starsze — Movie. */
private class GifPlayerView(ctx: Context) : View(ctx) {
    var crop = true
    private var drawable: Drawable? = null
    private var movie: Movie? = null
    private var start = 0L

    fun set(d: Drawable?, m: Movie?) {
        (drawable as? Animatable)?.stop()
        drawable = d; movie = m; start = 0L
        (d as? Animatable)?.start()
        d?.callback = this
        invalidate()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun onDetachedFromWindow() { (drawable as? Animatable)?.stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val d = drawable
        val m = movie
        val iw = (d?.intrinsicWidth ?: m?.width() ?: 0).toFloat()
        val ih = (d?.intrinsicHeight ?: m?.height() ?: 0).toFloat()
        if (iw <= 0 || ih <= 0) return
        val s = if (crop) maxOf(w / iw, h / ih) else minOf(w / iw, h / ih)
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.translate((w - iw * s) / 2, (h - ih * s) / 2)
        canvas.scale(s, s)
        if (d != null) { d.setBounds(0, 0, iw.toInt(), ih.toInt()); d.draw(canvas) }
        else if (m != null) {
            val now = SystemClock.uptimeMillis()
            if (start == 0L) start = now
            val dur = m.duration().takeIf { it > 0 } ?: 1000
            m.setTime(((now - start) % dur).toInt())
            m.draw(canvas, 0f, 0f)
            postInvalidateDelayed(40)
        }
        canvas.restore()
    }
}

/** Animowany GIF z pliku. [crop] — wypełnij (kafelki), inaczej dopasuj (podgląd). */
@Composable
fun GifImage(path: String, modifier: Modifier = Modifier, crop: Boolean = true) {
    var loaded by remember(path) { mutableStateOf<Pair<Drawable?, Movie?>?>(null) }
    LaunchedEffect(path) {
        loaded = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) to null
                else @Suppress("DEPRECATION") (null to Movie.decodeFile(path))
            } catch (t: Throwable) {
                // nieudane dekodowanie animacji — przynajmniej pierwsza klatka
                android.graphics.BitmapFactory.decodeFile(path)?.let { android.graphics.drawable.BitmapDrawable(null, it) to null }
            }
        }
    }
    Box(modifier.semantics { contentDescription = "GIF" }) {
        AndroidView(
            factory = { GifPlayerView(it) },
            update = { v -> v.crop = crop; val l = loaded; if (l != null) v.set(l.first, l.second) },
            modifier = Modifier.fillMaxSize(),
        )
        if (loaded == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), strokeWidth = 2.dp)
    }
}

/** Mały znaczek „GIF” na zdjęciu. */
@Composable
fun GifBadge(modifier: Modifier = Modifier) {
    Text(
        "GIF", modifier.clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
    )
}

// ======================= wybór GIF-a =======================

/** Co zrobić z wybranym GIF-em: wysłać (plik do wysłania). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GifPickerDialog(onDismiss: () -> Unit, onSend: (File) -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var ver by remember { mutableIntStateOf(0) }
    val mine = remember(ver) { Gifs.saved(ctx) }
    // podgląd przed wysłaniem: lokalny plik + (dla KLIPY) adres pełnej wersji
    var preview by remember { mutableStateOf<Pair<File, Gifs.Remote?>?>(null) }
    val addFromPhone = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try { Gifs.saveFromUri(ctx, uri); ver++; toast(ctx, "Dodano do Twoich GIF-ów") } catch (e: Exception) { toast(ctx, if (e is Core.SendException) e.message ?: "Błąd" else "Nie udało się odczytać pliku") }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).heightIn(max = 640.dp).imePadding(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("GIF-y", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij") }
                }
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Moje (${mine.size})") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Szukaj (KLIPY)") })
                }
                when (tab) {
                    0 -> Column(Modifier.padding(8.dp)) {
                        Text(
                            "Twoje GIF-y są zapisane tylko w tym telefonie. Dotknij, aby zobaczyć i wysłać; przytrzymaj, aby usunąć.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
                        )
                        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(min = 200.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                Box(
                                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                        .combinedClickable(onClick = { addFromPhone.launch("image/gif") }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Add, null)
                                        Text("Dodaj z telefonu", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            items(mine, key = { it.name }) { f ->
                                var ask by remember { mutableStateOf(false) }
                                Box(
                                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.08f))
                                        .combinedClickable(onClick = { preview = f to null }, onLongClick = { ask = true }),
                                ) { GifImage(f.absolutePath, Modifier.fillMaxSize()) }
                                if (ask) androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { ask = false },
                                    title = { Text("Usunąć ten GIF z Twoich?") },
                                    confirmButton = { TextButton(onClick = { Gifs.remove(ctx, f); ask = false; ver++ }) { Text("Usuń") } },
                                    dismissButton = { TextButton(onClick = { ask = false }) { Text("Anuluj") } },
                                )
                            }
                        }
                    }
                    else -> KlipyTab(core.gifKey()) { local, remote -> preview = local to remote }
                }
            }
        }
    }

    preview?.let { (file, remote) ->
        GifPreviewDialog(
            file, remote,
            saved = remember(file, ver) { Gifs.isSaved(ctx, file) },
            onClose = { preview = null },
            onSave = { f -> scope.launch(Dispatchers.IO) { try { Gifs.save(ctx, f); ver++; toast(ctx, "Zapisano w Twoich GIF-ach") } catch (e: Exception) { toast(ctx, sendError(e)) } } },
            onSend = { f -> preview = null; if (f.parentFile == Gifs.dir(ctx)) Gifs.touch(f); onSend(f) },
        )
    }
}

/** Wyszukiwarka KLIPY: popularne albo wyniki, animowane podglądy w siatce. */
@Composable
private fun KlipyTab(key: String, onPick: (File, Gifs.Remote) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyNow by remember { mutableStateOf(key) }
    if (keyNow.isBlank()) {
        var k by remember { mutableStateOf("") }
        Column(Modifier.padding(16.dp)) {
            Text("Wyszukiwarka GIF-ów KLIPY potrzebuje bezpłatnego klucza aplikacji.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Załóż konto na klipy.com → panel → „API key” i wklej go tutaj (albo administrator może go rozesłać wszystkim).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(k, { k = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Klucz KLIPY") })
            Button(onClick = { Gifs.setKlipyKey(ctx, k); keyNow = k }, enabled = k.length >= 8, modifier = Modifier.padding(top = 8.dp)) { Text("Zapisz") }
        }
        return
    }
    var query by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<Gifs.Remote>() }
    var page by remember { mutableIntStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    fun load(reset: Boolean) {
        if (loading && !reset) return
        val myGen = if (reset) ++gen else gen
        if (reset) { items.clear(); page = 1; hasNext = false }
        loading = true; error = null
        scope.launch {
            try {
                val p = Gifs.klipy(ctx, keyNow, query, page)
                if (myGen != gen) return@launch
                items.addAll(p.items.filter { n -> items.none { it.id == n.id } }); hasNext = p.hasNext; page++
            } catch (e: Exception) {
                if (myGen == gen) error = (e as? Core.SendException)?.message ?: "Brak połączenia z KLIPY"
            } finally { if (myGen == gen) loading = false }
        }
    }
    // wyszukiwanie z opóźnieniem (po przerwie w pisaniu)
    LaunchedEffect(query) { delay(if (query.isEmpty()) 0 else 450); load(true) }
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (hasNext && !loading && last >= items.size - 6) load(false)
        }
    }
    Column(Modifier.padding(8.dp)) {
        OutlinedTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Szukaj GIF-ów") })
        Text(
            "Wyszukiwanie łączy się z KLIPY (widzi Twój adres IP i wpisane słowa). Wysłany GIF idzie do rozmówcy zaszyfrowany, jak zdjęcie.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
        )
        error?.let { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(4.dp))
                TextButton(onClick = { load(true) }) { Text("Ponów") }
                if (e.contains("klucz", true)) TextButton(onClick = { Gifs.setKlipyKey(ctx, ""); keyNow = "" }) { Text("Zmień klucz") }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(min = 200.dp), state = grid,
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.id }) { g -> RemoteGifCell(g, onPick) }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp).size(24.dp))
        else if (items.isEmpty() && error == null) Text("Nic nie znaleziono.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteGifCell(g: Gifs.Remote, onPick: (File, Gifs.Remote) -> Unit) {
    val ctx = LocalContext.current
    var file by remember(g.preview) { mutableStateOf<File?>(null) }
    var failed by remember(g.preview) { mutableStateOf(false) }
    LaunchedEffect(g.preview) { try { file = Gifs.download(ctx, g.preview) } catch (e: Exception) { failed = true } }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.08f))
            .combinedClickableCompat { file?.let { onPick(it, g) } },
        contentAlignment = Alignment.Center,
    ) {
        val f = file
        when {
            f != null -> GifImage(f.absolutePath, Modifier.fillMaxSize())
            failed -> Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** Podgląd GIF-a przed wysłaniem (animowany). */
@Composable
fun GifPreviewDialog(
    file: File, remote: Gifs.Remote?, saved: Boolean,
    onClose: () -> Unit, onSave: (File) -> Unit, onSend: ((File) -> Unit)?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var isSaved by remember(saved) { mutableStateOf(saved) }
    // pełna (większa) wersja z KLIPY — pobieramy od razu, żeby podgląd był ostry
    var full by remember(file) { mutableStateOf(if (remote == null || remote.full == remote.preview) file else null) }
    LaunchedEffect(remote?.full) {
        if (remote != null && full == null) full = try { Gifs.download(ctx, remote.full) } catch (e: Exception) { file }
    }
    val shown = full ?: file
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val ratio = if (remote != null && remote.w > 0 && remote.h > 0) (remote.w.toFloat() / remote.h).coerceIn(0.5f, 2f) else 1f
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    GifImage(shown.absolutePath, Modifier.fillMaxSize(), crop = false)
                }
                if (!remote?.title.isNullOrBlank()) Text(remote!!.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSaved) OutlinedButton(enabled = full != null, onClick = { onSave(shown); isSaved = true }) { Text("Zapisz w moich") }
                    else Text("✓ W Twoich GIF-ach", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    if (onSend != null) Button(enabled = full != null && !busy, onClick = { busy = true; scope.launch { onSend(shown) } }) { Text("Wyślij") }
                }
                TextButton(onClick = onClose) { Text("Zamknij") }
            }
        }
    }
}
