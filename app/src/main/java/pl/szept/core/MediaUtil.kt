package pl.szept.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import pl.szept.crypto.B64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Przygotowanie zdjęć, wideo, plików i nagrań głosowych do wysłania. */
object MediaUtil {

    private fun tmp(ctx: Context, ext: String) = File(ctx.cacheDir, "out_${System.nanoTime()}.$ext")

    fun thumbB64(bmp: Bitmap, maxSide: Int, quality: Int): String {
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        val t = if (scale < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * scale).roundToInt()), max(1, (bmp.height * scale).roundToInt()), true) else bmp
        val bos = ByteArrayOutputStream()
        t.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return B64.enc(bos.toByteArray())
    }

    /** Zdjęcie: obrót wg EXIF, max 1600 px, JPEG 80%. */
    suspend fun prepareImage(ctx: Context, uri: Uri): Core.Attachment = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        // GIF zostaje animowany — wysyłamy oryginał (bez przerabiania na JPEG)
        val isGif = cr.getType(uri) == "image/gif" || try {
            cr.openInputStream(uri)?.use { s -> val b = ByteArray(4); s.read(b) == 4 && String(b, Charsets.US_ASCII) == "GIF8" } == true
        } catch (e: Exception) { false }
        if (isGif) {
            val tmpGif = tmp(ctx, "gif")
            cr.openInputStream(uri)?.use { i -> tmpGif.outputStream().use { o -> i.copyTo(o) } }
            if (tmpGif.length() > Gifs.MAX_GIF) { tmpGif.delete(); throw Core.SendException("GIF jest za duży (max 8 MB)") }
            return@withContext Gifs.attachment(ctx, tmpGif).also { tmpGif.delete() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw Core.SendException("Nie można odczytać zdjęcia")
        val rot = try { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (e: Exception) { 0 }
        val scale = 1600f / max(bmp.width, bmp.height)
        if (rot != 0 || scale < 1f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rot != 0) m.postRotate(rot.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val out = tmp(ctx, "jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        Core.Attachment(out, "image/jpeg", "zdjecie.jpg", thumbB64(bmp, 48, 40), bmp.width, bmp.height, 0)
    }

    /** Kwadratowe zdjęcie profilowe: przycięte do środka, maks. 256 px, JPEG. Zwraca plik + base64. */
    suspend fun prepareAvatar(ctx: Context, uri: Uri): Pair<File, String> = withContext(Dispatchers.IO) {
        var bmp = decodeScaled(ctx, uri, 256) ?: throw Core.SendException("Nie można odczytać zdjęcia")
        val side = min(bmp.width, bmp.height)
        bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        if (side > 256) bmp = Bitmap.createScaledBitmap(bmp, 256, 256, true)
        val out = tmp(ctx, "jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        out to B64.enc(out.readBytes())
    }

    /** Zdjęcie do edycji (kadrowania): obrócone według EXIF, do 1600 px. */
    suspend fun prepareForEdit(ctx: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val bmp = decodeScaled(ctx, uri, 1600) ?: throw Core.SendException("Nie można odczytać zdjęcia")
        tmp(ctx, "jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    /** Baner profilu: szeroki pas ~1024×340, JPEG. */
    suspend fun prepareBanner(ctx: Context, uri: Uri): Pair<File, String> = withContext(Dispatchers.IO) {
        var bmp = decodeScaled(ctx, uri, 1024) ?: throw Core.SendException("Nie można odczytać zdjęcia")
        val targetRatio = 3f
        val w = bmp.width; val h = bmp.height
        val cropH = (w / targetRatio).toInt().coerceAtMost(h)
        bmp = Bitmap.createBitmap(bmp, 0, (h - cropH) / 2, w, cropH)
        if (w > 1024) bmp = Bitmap.createScaledBitmap(bmp, 1024, (1024 / targetRatio).toInt(), true)
        val out = tmp(ctx, "jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        if (out.length() > 150_000) out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it) }
        out to B64.enc(out.readBytes())
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (e: Exception) { 0 }
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        return bmp
    }

    data class VideoInfo(val w: Int, val h: Int, val dur: Long, val thumb: String?)

    fun videoInfo(ctx: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = r.getFrameAtTime(0)
            VideoInfo(w, h, dur, frame?.let { thumbB64(it, 200, 50) })
        } catch (e: Exception) { VideoInfo(0, 0, 0, null) } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** Wideo: kompresja do ~480p (H.264 ~1,1 Mb/s), żeby mieściło się w limicie. */
    @OptIn(UnstableApi::class)
    suspend fun prepareVideo(ctx: Context, uri: Uri, progress: MutableStateFlow<Int>? = null): Core.Attachment {
        val info = withContext(Dispatchers.IO) { videoInfo(ctx, uri) }
        val out = tmp(ctx, "mp4")
        val ok = try { transcode(ctx, uri, out, info, progress) } catch (e: Exception) { false }
        val file = if (ok && out.length() > 0) out else {
            out.delete()
            withContext(Dispatchers.IO) { copyToCache(ctx, uri, "mp4") }
        }
        if (file.length() > Core.MAX_MEDIA) { file.delete(); throw Core.SendException("Wideo jest za długie — limit to ok. 16 MB (ok. 1,5 min po kompresji)") }
        val finalInfo = withContext(Dispatchers.IO) { videoInfo(ctx, Uri.fromFile(file)) }
        return Core.Attachment(file, "video/mp4", "wideo.mp4", info.thumb ?: finalInfo.thumb,
            finalInfo.w.takeIf { it > 0 } ?: info.w, finalInfo.h.takeIf { it > 0 } ?: info.h, max(finalInfo.dur, info.dur))
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(ctx: Context, uri: Uri, out: File, info: VideoInfo, progress: MutableStateFlow<Int>?): Boolean {
        val short = 480
        val effects = if (info.w > 0 && info.h > 0 && minOf(info.w, info.h) > short) {
            val s = short.toFloat() / minOf(info.w, info.h)
            val tw = ((info.w * s).roundToInt() / 2) * 2
            val th = ((info.h * s).roundToInt() / 2) * 2
            Effects(listOf(), listOf(Presentation.createForWidthAndHeight(tw, th, Presentation.LAYOUT_SCALE_TO_FIT)))
        } else Effects.EMPTY
        return runTransformer(ctx, uri, out, effects, 1_100_000, progress)
    }

    /**
     * Okrągła notatka wideo: kwadrat 384×384 wycięty ze środka kadru (ok. 0,8 Mb/s — minuta to ok. 6 MB).
     * Plik z kamery jest potem usuwany.
     */
    @OptIn(UnstableApi::class)
    suspend fun prepareRoundVideo(ctx: Context, file: File, durMs: Long): Core.Attachment {
        val out = tmp(ctx, "mp4")
        val effects = Effects(listOf(), listOf(Presentation.createForWidthAndHeight(384, 384, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
        val ok = try { runTransformer(ctx, Uri.fromFile(file), out, effects, 800_000, null) } catch (e: Exception) { false }
        file.delete()
        if (!ok || out.length() == 0L) { out.delete(); throw Core.SendException("Nie udało się przygotować notatki wideo") }
        if (out.length() > Core.MAX_MEDIA) { out.delete(); throw Core.SendException("Notatka jest za długa") }
        val info = withContext(Dispatchers.IO) { videoInfo(ctx, Uri.fromFile(out)) }
        return Core.Attachment(out, "video/mp4", "notatka.mp4", info.thumb, 384, 384, max(info.dur, durMs))
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformer(ctx: Context, uri: Uri, out: File, effects: Effects, bitrate: Int, progress: MutableStateFlow<Int>?): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var poll: Job? = null
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            poll?.cancel()
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            poll?.cancel()
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
                transformer.start(item, out.absolutePath)
                if (progress != null) {
                    poll = CoroutineScope(Dispatchers.Main).launch {
                        val holder = androidx.media3.transformer.ProgressHolder()
                        while (true) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress.value = holder.progress
                            delay(300)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    poll?.cancel()
                    CoroutineScope(Dispatchers.Main).launch { try { transformer.cancel() } catch (_: Exception) {} }
                }
            }
        }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "plik"
    }

    private fun copyToCache(ctx: Context, uri: Uri, ext: String): File {
        val out = tmp(ctx, ext)
        (ctx.contentResolver.openInputStream(uri) ?: throw Core.SendException("Nie można odczytać pliku")).use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        }
        return out
    }

    suspend fun prepareFile(ctx: Context, uri: Uri): Core.Attachment = withContext(Dispatchers.IO) {
        val name = displayName(ctx, uri).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "bin")
        val f = copyToCache(ctx, uri, ext)
        if (f.length() > Core.MAX_MEDIA) { f.delete(); throw Core.SendException("Plik za duży (max 16 MB)") }
        Core.Attachment(f, mime, name, null, 0, 0, 0)
    }

    /**
     * Zapis zdjęcia/wideo/pliku do galerii telefonu (folder „Szept”).
     * Android 10+: MediaStore bez uprawnień; Android 8–9: wymaga zgody na zapis w pamięci.
     */
    suspend fun saveToGallery(ctx: Context, src: File, mime: String): Boolean = withContext(Dispatchers.IO) {
        if (!src.exists()) return@withContext false
        val isVideo = mime.startsWith("video"); val isImage = mime.startsWith("image")
        val ext = src.name.substringAfterLast('.', if (isVideo) "mp4" else if (isImage) "jpg" else "bin")
        val name = "Szept_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + "_" + (System.nanoTime() % 1000) + "." + ext
        val dirName = when { isVideo -> android.os.Environment.DIRECTORY_MOVIES; isImage -> android.os.Environment.DIRECTORY_PICTURES; else -> android.os.Environment.DIRECTORY_DOWNLOADS }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val vol = android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                val collection = when {
                    isVideo -> android.provider.MediaStore.Video.Media.getContentUri(vol)
                    isImage -> android.provider.MediaStore.Images.Media.getContentUri(vol)
                    else -> android.provider.MediaStore.Downloads.getContentUri(vol)
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/Szept")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(collection, values) ?: return@withContext false
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(dirName), "Szept").apply { mkdirs() }
                val out = File(dir, name)
                src.copyTo(out, overwrite = true)
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mime), null)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("Szept", "zapis do galerii", e)
            false
        }
    }

    fun audioDuration(file: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            release(); d
        }
    } catch (e: Exception) { 0 }
}

/** Nagrywanie wiadomości głosowych (AAC, mono, ~32 kb/s). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    var startedAt = 0L
        private set

    fun start(): Boolean {
        val f = File(ctx.cacheDir, "voice_${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(15 * 60_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            r.release(); f.delete(); false
        }
    }

    fun stop(): File? {
        val r = rec ?: return null
        rec = null
        val f = try { r.stop(); r.release(); file } catch (e: Exception) { r.release(); file?.delete(); null }
        return f?.takeIf { it.exists() && it.length() > 0 }
    }

    fun cancel() { stop()?.delete() }

    /** Bieżąca głośność 0..1 (do wskaźnika podczas nagrywania). */
    fun amplitude(): Float = try { (rec?.maxAmplitude ?: 0) / 32767f } catch (e: Exception) { 0f }

    companion object {
        /** Przebieg nagrania: 48 słupków 0..255 zapisanych szesnastkowo (do dymka głosówki). */
        fun encodeWave(samples: List<Float>, bars: Int = 48): String? {
            if (samples.size < 2) return null
            val raw = FloatArray(bars) { b ->
                val from = b * samples.size / bars
                val to = maxOf(from + 1, (b + 1) * samples.size / bars).coerceAtMost(samples.size)
                var m = 0f
                for (i in from until to) m = maxOf(m, samples[i])
                kotlin.math.sqrt(m.coerceIn(0f, 1f))
            }
            val peak = raw.maxOrNull()?.takeIf { it > 0.01f } ?: return null
            val sb = StringBuilder(bars * 2)
            raw.forEach { v -> sb.append("%02x".format((v / peak * 255).toInt().coerceIn(0, 255))) }
            return sb.toString()
        }

        fun decodeWave(hex: String?): FloatArray? = try {
            if (hex.isNullOrEmpty() || hex.length % 2 != 0 || hex.length > 400) null
            else FloatArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) / 255f }
        } catch (e: Exception) { null }
    }
}

/** Odtwarzacz wiadomości głosowych — jedna naraz. */
object VoicePlayer {
    val playing = MutableStateFlow<String?>(null)
    val position = MutableStateFlow(0f)
    /** Prędkość odtwarzania: 1×, 1,5×, 2×. */
    val speed = MutableStateFlow(1f)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun cycleSpeed() {
        speed.value = when (speed.value) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        applySpeed()
    }

    private fun applySpeed() {
        val p = player ?: return
        try { if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(speed.value) } catch (_: Exception) {}
    }

    /** Przewija do miejsca (0..1); jeśli inna głosówka gra — zaczyna tę. */
    fun seek(id: String, path: String, fraction: Float) {
        if (playing.value != id) toggle(id, path)
        val p = player ?: return
        try { if (p.duration > 0) p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toInt()) } catch (_: Exception) {}
    }

    fun toggle(id: String, path: String) {
        if (playing.value == id) { stop(); return }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.prepare()
            p.setOnCompletionListener { stop() }
            p.start()
            player = p
            applySpeed()
            playing.value = id
            ticker = CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    val pl = player ?: break
                    position.value = if (pl.duration > 0) pl.currentPosition.toFloat() / pl.duration else 0f
                    delay(150)
                }
            }
        } catch (e: Exception) { stop() }
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        playing.value = null
        position.value = 0f
    }
}
