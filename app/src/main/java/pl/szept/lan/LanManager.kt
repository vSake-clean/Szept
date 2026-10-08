package pl.szept.lan

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tryb „bez internetu”: przekaźnik w tym telefonie + wyszukiwanie innych telefonów z Szeptem w tej samej sieci Wi-Fi
 * (albo w hotspocie utworzonym przez jeden z telefonów). Wiadomości idą wtedy prosto między telefonami.
 */
class LanManager(private val ctx: Context, private val host: Host, relayDb: String = "lan_relay.db") {

    /** To, czego menedżer potrzebuje od rdzenia aplikacji. */
    interface Host {
        val deviceTag: String
        fun addLocalRelay(url: String, client: OkHttpClient)
        fun removeLocalRelay(url: String)
        fun localRelayUrls(): Set<String>
        fun baseClient(): OkHttpClient
        /** Nowa koperta w przekaźniku tego telefonu. */
        fun onLanStored(origin: Int) {}
    }

    data class Hotspot(val ssid: String, val pass: String, val hostIp: String?, val port: Int)

    companion object {
        const val SERVICE_TYPE = "_szept._tcp."
        private const val TAG = "Szept"

        fun joinLink(h: Hotspot): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            return "szept://lan?s=${enc(h.ssid)}&p=${enc(h.pass)}" + (h.hostIp?.let { "&h=$it" } ?: "") + "&o=${h.port}"
        }

        fun wsUrl(addr: InetAddress, port: Int): String =
            if (addr is Inet4Address) "ws://${addr.hostAddress}:$port" else "ws://[${addr.hostAddress?.substringBefore('%')}]:$port"
    }

    val relay = LocalRelay(ctx, relayDb)
    val running = MutableStateFlow(false)
    val hotspot = MutableStateFlow<Hotspot?>(null)
    val stored = MutableStateFlow(0)
    /** Komunikat dla użytkownika (np. błąd hotspotu). */
    val status = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + pl.szept.core.CrashLog.handler)
    private val main = Handler(Looper.getMainLooper())
    private val cm get() = ctx.getSystemService(ConnectivityManager::class.java)
    private val nsd get() = ctx.getSystemService(NsdManager::class.java)
    @Volatile var selfUrl: String? = null
        private set
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    /** Nazwa usługi NSD -> adres przekaźnika. */
    private val found = ConcurrentHashMap<String, String>()
    @Volatile private var wifiNet: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val clients = ConcurrentHashMap<Network, OkHttpClient>()

    val selfName get() = "Szept-" + host.deviceTag.take(10)

    // ---------------- włączanie ----------------

    @Synchronized
    fun start() {
        if (running.value) return
        val port = try { relay.start() } catch (e: Exception) {
            Log.w(TAG, "lan: nie mogę uruchomić przekaźnika", e)
            status.value = "Nie udało się uruchomić przekaźnika: ${e.message}"
            return
        }
        relay.onStored = { origin -> stored.value = relay.count(); host.onLanStored(origin) }
        stored.value = relay.count()
        running.value = true
        selfUrl = ("ws://127.0.0.1:$port" + relay.selfPath).also { host.addLocalRelay(it, host.baseClient()) }
        register(port)
        discover()
        watchWifi()
        Log.i(TAG, "lan: przekaźnik na porcie $port")
    }

    @Synchronized
    fun stop() {
        if (!running.value) return
        running.value = false
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { discListener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        regListener = null; discListener = null
        wifiCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }; wifiCallback = null
        stopHotspot()
        leaveNetwork()
        host.localRelayUrls().forEach { host.removeLocalRelay(it) }
        found.clear()
        relay.stop()
        selfUrl = null
    }

    fun close() { stop(); scope.cancel(); relay.close() }

    /** Adresy IPv4 tego telefonu w sieciach lokalnych (do pokazania i do kodu QR). */
    fun myAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) { emptyList() }

    // ---------------- NSD (wykrywanie w sieci) ----------------

    private fun register(port: Int) {
        val n = nsd ?: return
        val info = NsdServiceInfo().apply { serviceName = selfName; serviceType = SERVICE_TYPE; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "lan: ogłoszono ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { Log.w(TAG, "lan: ogłoszenie nieudane $code") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        try { n.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); regListener = l } catch (e: Exception) { Log.w(TAG, "lan nsd", e) }
    }

    private fun discover() {
        val n = nsd ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "lan: wyszukiwanie nieudane $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceName == selfName || !si.serviceName.startsWith("Szept-")) return
                resolveQueue.add(si); resolveNext()
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                found.remove(si.serviceName)?.let { url -> if (url !in found.values) host.removeLocalRelay(url) }
            }
        }
        try { n.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l); discListener = l } catch (e: Exception) { Log.w(TAG, "lan nsd", e) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val n = nsd ?: return
        synchronized(this) { if (resolving) return; resolving = true }
        val si = resolveQueue.poll() ?: run { resolving = false; return }
        try {
            n.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) { resolving = false; resolveNext() }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    resolving = false
                    val addr = s.host
                    if (addr != null && s.port > 0 && running.value) {
                        val url = wsUrl(addr, s.port)
                        if (url !in myAddresses().map { "ws://$it:${relay.port}" }) {
                            found[s.serviceName] = url
                            host.addLocalRelay(url, clientFor(wifiNet))
                        }
                    }
                    resolveNext()
                }
            })
        } catch (e: Exception) { resolving = false; Log.w(TAG, "lan resolve", e) }
    }

    // ---------------- Wi-Fi ----------------

    private fun clientFor(net: Network?): OkHttpClient {
        if (net == null) return host.baseClient()
        return clients.getOrPut(net) { host.baseClient().newBuilder().socketFactory(net.socketFactory).build() }
    }

    /** Śledzi sieć Wi-Fi (także bez internetu) i szuka przekaźnika pod adresem bramy — tak działa hotspot innego telefonu. */
    private fun watchWifi() {
        val c = cm ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNet = network
                scope.launch { delay(1500); probeGateway(network, c.getLinkProperties(network)) }
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network == wifiNet) scope.launch { probeGateway(network, lp) }
            }
            override fun onLost(network: Network) {
                if (wifiNet == network) wifiNet = null
                clients.remove(network)
                // przekaźniki z tej sieci są już nieosiągalne — znajdziemy je od nowa
                host.localRelayUrls().filter { it != selfUrl }.forEach { host.removeLocalRelay(it) }
                found.clear()
            }
        }
        try { c.registerNetworkCallback(req, cb); wifiCallback = cb } catch (e: Exception) { Log.w(TAG, "lan wifi", e) }
    }

    private suspend fun probeGateway(net: Network, lp: LinkProperties?, extraHost: String? = null, port: Int = LocalRelay.DEFAULT_PORT) {
        val candidates = LinkedHashSet<String>()
        extraHost?.let { candidates.add(it) }
        lp?.routes?.forEach { r -> r.gateway?.let { g -> if (g is Inet4Address && !g.isAnyLocalAddress) g.hostAddress?.let(candidates::add) } }
        for (ip in candidates) {
            val url = "ws://$ip:$port"
            if (url in host.localRelayUrls()) continue
            val ok = try {
                net.socketFactory.createSocket().use { s -> s.connect(InetSocketAddress(ip, port), 1500); true }
            } catch (e: Exception) { false }
            if (ok && running.value) {
                Log.i(TAG, "lan: przekaźnik pod adresem bramy $url")
                host.addLocalRelay(url, clientFor(net))
            }
        }
    }

    // ---------------- hotspot ----------------

    /** Tworzy sieć Wi-Fi z tego telefonu (bez internetu). Wymaga uprawnień lokalizacji / urządzeń w pobliżu. */
    @SuppressLint("MissingPermission")
    fun startHotspot() {
        if (!running.value) start()
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
        status.value = "Uruchamiam sieć Wi-Fi…"
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, pass) = if (Build.VERSION.SDK_INT >= 30) {
                        val c = res.softApConfiguration
                        (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") c.ssid) to (c.passphrase ?: "")
                    } else {
                        @Suppress("DEPRECATION") val c = res.wifiConfiguration
                        (c?.SSID?.trim('"')) to (c?.preSharedKey?.trim('"') ?: "")
                    }
                    scope.launch {
                        delay(1500)   // interfejs hotspotu dostaje adres po chwili
                        hotspot.value = Hotspot(ssid ?: "", pass, myAddresses().firstOrNull { it.endsWith(".1") } ?: myAddresses().firstOrNull(), relay.port)
                        status.value = null
                    }
                }
                override fun onStopped() { hotspot.value = null; reservation = null }
                override fun onFailed(reason: Int) {
                    hotspot.value = null
                    status.value = when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "Telefon nie pozwala na tworzenie sieci (ustawienia operatora lub administratora)."
                        ERROR_INCOMPATIBLE_MODE -> "Wyłącz zwykły hotspot / udostępnianie internetu i spróbuj ponownie."
                        else -> "Nie udało się utworzyć sieci (kod $reason). Włącz Wi-Fi i lokalizację, wyłącz zwykły hotspot."
                    }
                }
            }, main)
        } catch (e: SecurityException) {
            status.value = "Brak uprawnienia do tworzenia sieci Wi-Fi."
        } catch (e: Exception) {
            status.value = "Nie udało się utworzyć sieci: ${e.message}"
        }
    }

    fun stopHotspot() {
        runCatching { reservation?.close() }
        reservation = null
        hotspot.value = null
    }

    /**
     * Dołącza do sieci utworzonej przez inny telefon (Android 10+: jedno potwierdzenie systemowe).
     * Na starszych telefonach trzeba połączyć się ręcznie w ustawieniach Wi-Fi — wtedy przekaźnik znajdziemy sami.
     */
    fun joinNetwork(ssid: String, pass: String, hostIp: String?, port: Int): Boolean {
        if (!running.value) start()
        if (Build.VERSION.SDK_INT < 29) return false
        val c = cm ?: return false
        leaveNetwork()
        // zła nazwa sieci / hasło z linku — nie wywracamy aplikacji
        val spec = try { android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build() }
            catch (e: IllegalArgumentException) { return false }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNet = network
                status.value = "Połączono z siecią „$ssid”"
                scope.launch { delay(1000); probeGateway(network, c.getLinkProperties(network), hostIp, port) }
            }
            override fun onUnavailable() { status.value = "Nie udało się połączyć z „$ssid”" }
            override fun onLost(network: Network) { if (wifiNet == network) wifiNet = null; status.value = "Rozłączono z „$ssid”" }
        }
        status.value = "Łączę z „$ssid”…"
        return try { c.requestNetwork(req, cb); joinCallback = cb; true } catch (e: Exception) { status.value = "Błąd: ${e.message}"; false }
    }

    fun leaveNetwork() {
        joinCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        joinCallback = null
    }

    /** Ręczne dodanie przekaźnika z sieci lokalnej (np. „192.168.1.20”). */
    fun addManual(address: String): String? {
        val a = address.trim().removePrefix("ws://").removeSuffix("/")
        val hostPart = a.substringBefore(':')
        val port = a.substringAfter(':', "").toIntOrNull() ?: LocalRelay.DEFAULT_PORT
        if (hostPart.isEmpty() || hostPart.contains('/')) return null
        val url = "ws://$hostPart:$port"
        if (port !in 1..65535 || !pl.szept.nostr.isValidRelayUrl(url)) return null
        if (!running.value) start()
        host.addLocalRelay(url, clientFor(wifiNet))
        return url
    }
}
