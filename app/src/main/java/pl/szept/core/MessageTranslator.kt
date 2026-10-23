package pl.szept.core

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tłumaczenie wiadomości w telefonie (Google ML Kit). Treść nie opuszcza telefonu —
 * z internetu pobierany jest tylko raz pakiet danego języka (ok. 30 MB).
 */
object MessageTranslator {
    class TranslateException(msg: String) : Exception(msg)

    data class Result(val text: String, val from: String, val to: String)

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    /** Język telefonu (gdy wiadomość już w nim jest — tłumaczymy na angielski). */
    private fun target(source: String): String {
        val phone = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.POLISH
        return if (source == phone) (if (phone == TranslateLanguage.ENGLISH) TranslateLanguage.POLISH else TranslateLanguage.ENGLISH) else phone
    }

    fun languageName(code: String): String = Locale(code).getDisplayLanguage(Locale("pl")).ifBlank { code }

    suspend fun translate(text: String): Result {
        val idClient = LanguageIdentification.getClient()
        val tag = try { idClient.identifyLanguage(text).await() } finally { idClient.close() }
        if (tag == "und") throw TranslateException("Nie rozpoznano języka wiadomości")
        val source = TranslateLanguage.fromLanguageTag(tag) ?: throw TranslateException("Ten język nie jest obsługiwany (${languageName(tag)})")
        val to = target(source)
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(to).build())
        try {
            // pierwszy raz: pobranie pakietu językowego (potem działa bez internetu)
            withTimeout(180_000) { tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            val out = withTimeout(30_000) { tr.translate(text).await() }
            return Result(out, source, to)
        } catch (e: TranslateException) { throw e }
        catch (e: Exception) {
            throw TranslateException("Nie udało się przetłumaczyć — za pierwszym razem potrzebny jest internet do pobrania pakietu językowego")
        } finally { tr.close() }
    }
}
