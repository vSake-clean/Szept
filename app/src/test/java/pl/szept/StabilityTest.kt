package pl.szept

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.szept.core.CrashLog

/** Błędy, które nie mogą wyłączać aplikacji. */
class StabilityTest {
    private fun closed(vararg frames: Pair<String, String>) = IllegalStateException("closed").apply {
        stackTrace = frames.map { (c, m) -> StackTraceElement(c, m, "SourceFile", 1) }.toTypedArray()
    }

    @Test
    fun okHttpClosedSocketRaceIsNotACrash() {
        // stos z dziennika awarii z telefonu (Szept 3.1): zerwane połączenie w trakcie wysyłania skompresowanej ramki
        val e = closed(
            "okio.RealBufferedSink" to "emitCompleteSegments",
            "okio.DeflaterSink" to "deflate",
            "okhttp3.internal.ws.WebSocketWriter" to "writeMessageFrame",
            "okhttp3.internal.ws.RealWebSocket" to "writeOneFrame\$okhttp",
            "okhttp3.internal.concurrent.TaskRunner" to "access\$runTask",
        )
        assertTrue(CrashLog.isClosedSocketRace(Thread("OkHttp TaskRunner"), e))
        assertTrue(CrashLog.isClosedSocketRace(Thread("inny"), e))
        // bez kompresji: zamknięty strumień gniazda przy emit()
        assertTrue(CrashLog.isClosedSocketRace(Thread("OkHttp wss://relay writer"),
            closed("okio.RealBufferedSink" to "emit", "okhttp3.internal.ws.WebSocketWriter" to "writeMessageFrame")))
        // inne błędy dalej są awariami (trafiają do dziennika i wyłączają aplikację jak wcześniej)
        assertFalse(CrashLog.isClosedSocketRace(Thread("OkHttp x"), IllegalStateException("inny")))
        assertFalse(CrashLog.isClosedSocketRace(Thread("main"), closed("pl.szept.core.Core" to "send")))
        assertFalse(CrashLog.isClosedSocketRace(Thread("OkHttp x"), RuntimeException("closed")))
    }
}
