package pl.szept.ui

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.szept.core.VoicePlayer
import pl.szept.data.Message
import java.io.File

const val VIDEO_NOTE_MAX_MS = 60_000L

/**
 * Nagrywanie okrągłej notatki wideo (jak w Telegramie): podgląd z przedniej kamery w kółku,
 * dotknięcie = start, drugie = stop (maks. 60 s). Potem plik jest wycinany do kwadratu i wysyłany.
 */
@SuppressLint("MissingPermission")
@Composable
fun VideoNoteDialog(onDismiss: () -> Unit, onRecorded: (File, Long) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var cancelled by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val previewView = remember {
        PreviewView(ctx).apply {
            // TextureView (tryb zgodności) — tylko taki podgląd da się przyciąć do kółka
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }

    DisposableEffect(front) {
        val main = ContextCompat.getMainExecutor(ctx)
        // okno zamknięte, zanim kamera zdążyła się uruchomić — nie włączamy jej już
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, selector, preview, vc)
                capture = vc
                cameraError = null
            } catch (e: Exception) {
                cameraError = "Nie można uruchomić kamery: ${e.message}"
            }
        }, main)
        onDispose {
            disposed = true
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            else providerFuture.addListener({ runCatching { providerFuture.get().unbindAll() } }, main)
        }
    }

    LaunchedEffect(recording) {
        while (recording != null) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(100)
        }
    }

    fun start() {
        val vc = capture ?: return
        VoicePlayer.stop(); RoundPlayer.stop()
        val out = File(ctx.cacheDir, "vnote_${System.nanoTime()}.mp4")
        cancelled = false
        startedAt = System.currentTimeMillis()
        elapsed = 0
        recording = vc.output
            .prepareRecording(ctx, FileOutputOptions.Builder(out).setDurationLimitMillis(VIDEO_NOTE_MAX_MS).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recording = null
                    val ok = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    val dur = ev.recordingStats.recordedDurationNanos / 1_000_000
                    when {
                        cancelled -> out.delete()
                        ok && out.length() > 0 && dur >= 700 -> onRecorded(out, dur)
                        ok -> { out.delete(); toast(ctx, "Za krótko — dotknij, aby zacząć, i jeszcze raz, aby zakończyć") }
                        else -> { out.delete(); toast(ctx, "Nie udało się nagrać (błąd ${ev.error})") }
                    }
                }
            }
    }

    Dialog(onDismissRequest = { cancelled = true; recording?.stop(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black.copy(alpha = 0.92f), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Notatka wideo", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(290.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(270.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                        AndroidView({ previewView }, Modifier.fillMaxSize())
                        cameraError?.let { Text(it, color = Color.White, modifier = Modifier.padding(24.dp)) }
                    }
                    if (recording != null) {
                        val frac = (elapsed.toFloat() / VIDEO_NOTE_MAX_MS).coerceIn(0f, 1f)
                        Canvas(Modifier.size(290.dp)) {
                            drawArc(Color(0xFFE53935), -90f, 360f * frac, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (recording != null) "● " + formatDuration(elapsed) + " / 1:00" else "Dotknij czerwony przycisk, aby nagrywać (maks. 1 min)",
                    color = Color.White, fontWeight = if (recording != null) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { cancelled = true; recording?.stop(); onDismiss() }) { Icon(Icons.Default.Close, "Anuluj", tint = Color.White) }
                    FilledIconButton(
                        onClick = { if (recording == null) start() else recording?.stop() },
                        enabled = capture != null,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935)),
                    ) {
                        Icon(if (recording == null) Icons.Default.FiberManualRecord else Icons.Default.Stop,
                            if (recording == null) "Nagrywaj" else "Zakończ i wyślij", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { front = !front }, enabled = recording == null) {
                        Icon(Icons.Default.Cameraswitch, "Przełącz kamerę", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Odtwarzanie okrągłej notatki wideo w dymku (jedna naraz). */
object RoundPlayer {
    val playing = MutableStateFlow<String?>(null)
    val position = MutableStateFlow(0f)
    private var player: MediaPlayer? = null
    private var path: String? = null
    private var ticker: Job? = null

    fun toggle(id: String, p: String) {
        if (playing.value == id) stop() else { stop(); VoicePlayer.stop(); path = p; playing.value = id; position.value = 0f }
    }

    /** Wywoływane, gdy powierzchnia wideo w dymku jest gotowa. */
    fun attach(surface: Surface) {
        val p = path ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(p); setSurface(surface)
                setOnCompletionListener { stop() }
                prepare(); start()
            }
            ticker?.cancel()
            ticker = CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    player?.let { if (it.duration > 0) position.value = it.currentPosition.toFloat() / it.duration }
                    delay(100)
                }
            }
        } catch (e: Exception) { stop() }
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        runCatching { player?.stop() }
        player?.release(); player = null
        playing.value = null; position.value = 0f
    }
}

/** Okrągła notatka w dymku: miniatura, a po dotknięciu — odtwarzanie w kółku z paskiem postępu. */
@Composable
fun RoundVideoContent(m: Message) {
    val playing by RoundPlayer.playing.collectAsState()
    val pos by RoundPlayer.position.collectAsState()
    val isMe = playing == m.id
    DisposableEffect(m.id) { onDispose { if (RoundPlayer.playing.value == m.id) RoundPlayer.stop() } }
    val thumb = remember(m.media?.thumb) { m.media?.thumb?.let { Images.thumb(it) } }
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(200.dp).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            if (isMe && m.path != null) {
                AndroidView(
                    factory = { c ->
                        TextureView(c).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = RoundPlayer.attach(Surface(st))
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { if (RoundPlayer.playing.value == m.id) RoundPlayer.stop(); return true }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                if (thumb != null) BitmapImage(thumb, Modifier.fillMaxSize())
                if (m.path == null) CircularProgressIndicator(Modifier.size(36.dp))
                else Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Odtwórz notatkę", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (isMe) Canvas(Modifier.size(210.dp)) {
            drawArc(Color.White.copy(alpha = 0.85f), -90f, 360f * pos, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        val dur = m.media?.dur ?: 0
        if (dur > 0) Text(
            formatDuration(dur), Modifier.align(Alignment.BottomCenter).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
    }
}
