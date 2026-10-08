package pl.szept.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * Lokalizacja bez usług Google — zwykły LocationManager (GPS i sieć).
 * Działa także na telefonach bez Sklepu Play.
 */
object LocationHelper {

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(lm: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .filter { runCatching { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }.getOrDefault(false) }

    fun anyProviderEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return providers(lm).isNotEmpty()
    }

    /** Słuchacz z wszystkimi metodami — na Androidzie 8–10 nie mają one domyślnych implementacji. */
    private class Listener(val onFix: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Bieżąca pozycja: świeża ostatnio znana albo nowy odczyt (czeka maks. [timeoutMs];
     * po pierwszym, niedokładnym odczycie czeka jeszcze najwyżej 6 s na lepszy z GPS).
     */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 25_000): Location? = withContext(Dispatchers.Main) {
        if (!hasPermission(ctx)) return@withContext null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return@withContext null
        val provs = providers(lm)
        var best: Location? = provs.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 120_000 }.minByOrNull { it.accuracy }
        if (best != null && best.accuracy <= 40) return@withContext best
        if (provs.isEmpty()) return@withContext best
        val ch = Channel<Location>(Channel.UNLIMITED)
        val listener = Listener { ch.trySend(it) }
        try {
            provs.forEach { p -> runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) } }
            var deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val l = withTimeoutOrNull(left) { ch.receive() } ?: break
                if (best == null || l.accuracy < best!!.accuracy) best = l
                if (l.accuracy <= 30) break
                deadline = min(deadline, System.currentTimeMillis() + 6000)
            }
        } finally {
            lm.removeUpdates(listener)
        }
        best
    }

    // ---------- śledzenie na żywo (włączane przez usługę, gdy trwa udostępnianie) ----------

    private var live: Listener? = null

    @SuppressLint("MissingPermission")
    fun startTracking(ctx: Context, onFix: (Location) -> Unit): Boolean {
        if (live != null) return true
        if (!hasPermission(ctx)) return false
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        val l = Listener(onFix)
        var ok = false
        providers(lm).forEach { p -> runCatching { lm.requestLocationUpdates(p, 15_000L, 10f, l, Looper.getMainLooper()); ok = true } }
        if (ok) live = l
        return ok
    }

    fun stopTracking(ctx: Context) {
        val l = live ?: return
        ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        live = null
    }
}
