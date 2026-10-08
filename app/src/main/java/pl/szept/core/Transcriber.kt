package pl.szept.core

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Zamiana głosówki na tekst w samym telefonie: rozpoznawanie mowy offline (Android 13+).
 * Nagranie nie jest nigdzie wysyłane — dźwięk trafia wprost do lokalnego modelu mowy.
 */
object Transcriber {
    class TranscribeException(msg: String) : Exception(msg)

    fun available(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    /** Dekoduje nagranie (AAC/M4A) do PCM 16-bit mono 16 kHz. */
    fun decodeToPcm16k(file: File): ByteArray {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw TranscribeException("Nagranie nie zawiera dźwięku")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val mono = ShortArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var eosIn = false
        var eosOut = false
        try {
            while (!eosOut) {
                if (!eosIn) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.asShortBuffer()
                    val frames = sb.remaining() / channels.coerceAtLeast(1)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += sb.get()
                        mono.add((sum / channels).toShort())
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        return resampleTo16k(mono.toArray(), rate)
    }

    /** Proste przepróbkowanie (interpolacja liniowa) do 16 kHz, wynik jako bajty little-endian. */
    fun resampleTo16k(src: ShortArray, rate: Int): ByteArray {
        val outLen = if (rate == 16_000) src.size else (src.size.toLong() * 16_000 / rate).toInt()
        val out = ByteArrayOutputStream(outLen * 2)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * rate / 16_000
            val a = pos.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val t = pos - a
            val v = (src[a] * (1 - t) + src[b] * t).toInt().coerceIn(-32768, 32767)
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
        }
        return out.toByteArray()
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(16_000)
        private var n = 0
        fun add(v: Short) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray(): ShortArray = a.copyOf(n)
    }

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nie rozpoznano mowy w nagraniu"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Język polski nie jest jeszcze pobrany do rozpoznawania offline. Rozpoczęto pobieranie — spróbuj za kilka minut."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Brak zgody na mikrofon — zezwól i spróbuj ponownie"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznawanie mowy jest zajęte — spróbuj za chwilę"
        else -> "Rozpoznawanie mowy w telefonie jest niedostępne (błąd $error)"
    }

    private fun intent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Zamienia nagranie na tekst. Rzuca TranscribeException z opisem po polsku. */
    @RequiresApi(33)
    suspend fun transcribe(ctx: Context, file: File, durationMs: Long, lang: String = "pl-PL"): String {
        val pcm = withContext(Dispatchers.IO) { decodeToPcm16k(file) }
        if (pcm.size < 16_000) throw TranscribeException("Nagranie jest za krótkie")
        return withTimeout(durationMs * 2 + 45_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    val pipe = ParcelFileDescriptor.createPipe()
                    val text = StringBuilder()
                    var finished = false
                    fun finish(err: Throwable?) {
                        if (finished) return
                        finished = true
                        runCatching { rec.destroy() }
                        runCatching { pipe[0].close() }
                        if (!cont.isActive) return
                        when {
                            err != null -> cont.resumeWithException(err)
                            text.isBlank() -> cont.resumeWithException(TranscribeException("Nie rozpoznano mowy w nagraniu"))
                            else -> cont.resume(text.toString().trim())
                        }
                    }
                    rec.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onResults(results: Bundle?) {
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.append(it).append(' ') }
                            finish(null)
                        }
                        override fun onSegmentResults(segmentResults: Bundle) {
                            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text.append(it).append(' ') }
                        }
                        override fun onEndOfSegmentedSession() = finish(null)
                        override fun onError(error: Int) {
                            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).triggerModelDownload(intent(lang)) }
                            }
                            if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && text.isNotBlank()) finish(null)
                            else finish(TranscribeException(message(error)))
                        }
                    })
                    val i = intent(lang).apply {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    }
                    rec.startListening(i)
                    // dźwięk wpisujemy do potoku w tle; zamknięcie potoku = koniec nagrania
                    Thread {
                        try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } } catch (_: Exception) { }
                    }.start()
                    cont.invokeOnCancellation { finished = true; runCatching { rec.destroy() } }
                }
            }
        }
    }
}
