package pl.szept.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import pl.szept.App
import pl.szept.call.CallManager
import pl.szept.call.CallManager.Phase
import pl.szept.call.GroupCallManager.Phase as GPhase
import pl.szept.call.WebRtcEngine
import pl.szept.service.CallAudio
import pl.szept.service.CallService

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** Zwraca funkcję „zadzwoń” (prosi o mikrofon/kamerę, gdy trzeba). */
@Composable
fun rememberCallStarter(): (String, Boolean) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // dzwonienie działa w tle aplikacji (nie w ekranie czatu, który znika, gdy pojawia się ekran rozmowy)
    fun go(peer: String, video: Boolean) = App.core.startCall(peer, video) { msg -> toast(ctx, msg) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (has(ctx, Manifest.permission.RECORD_AUDIO)) go(p.first, p.second && has(ctx, Manifest.permission.CAMERA))
        else toast(ctx, "Rozmowa wymaga dostępu do mikrofonu")
    }
    return { peer, video ->
        if (!App.core.groupCalls.available) toast(ctx, "Rozmowy nie działają na tym urządzeniu")
        else {
            val need = listOf(Manifest.permission.RECORD_AUDIO) + if (video) listOf(Manifest.permission.CAMERA) else emptyList()
            if (need.all { has(ctx, it) }) go(peer, video) else { pending = peer to video; perm.launch(need.toTypedArray()) }
        }
    }
}

/** Obraz z kamery (moja lub rozmówcy). */
@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(WebRtcEngine.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                view = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = { v -> runCatching { track.removeSink(v) }; v.release() },
        modifier = modifier,
    )
    DisposableEffect(track, view) {
        val v = view
        if (v != null) track.addSink(v)
        onDispose { if (v != null) runCatching { track.removeSink(v) } }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, bg: Color, onClick: () -> Unit, size: Int = 60) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.45).dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
    }
}

private val Red = Color(0xFFE53935)
private val Green = Color(0xFF2E7D32)
private val Dim = Color.White.copy(alpha = 0.18f)
private val On = Color.White.copy(alpha = 0.45f)

/** Ekran rozmowy (także nad blokadą PIN-em — odebranie nie pokazuje wiadomości). */
@Composable
fun CallScreen(act: MainActivity, call: CallManager.Call) {
    val core = App.core
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = remember(call.peer) { core.db.nameOf(call.peer) }
    val avatar = remember(call.peer) { core.db.contact(call.peer)?.avatar?.ifBlank { null } }
    val speaker by CallAudio.speaker.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val engine = core.calls.currentEngine() as? WebRtcEngine
    val remote by (engine?.remoteVideo ?: MutableStateFlow(null)).collectAsState()
    val front by (engine?.frontCamera ?: MutableStateFlow(true)).collectAsState()

    LaunchedEffect(call.phase) {
        when (call.phase) {
            Phase.DIALING, Phase.CONNECTING, Phase.CONNECTED -> CallService.start(ctx)
            else -> {}
        }
        while (call.phase == Phase.CONNECTED) { now = System.currentTimeMillis(); delay(1000) }
    }
    // rozmowa głosowa przy uchu: ekran gaśnie (czujnik zbliżeniowy)
    val ear = !call.video && !speaker && call.phase in setOf(Phase.DIALING, Phase.CONNECTING, Phase.CONNECTED)
    DisposableEffect(ear) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val lock = if (ear && pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true)
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "szept:call").apply { setReferenceCounted(false); acquire(3 * 3600_000L) }
        else null
        onDispose { runCatching { lock?.release() } }
    }

    val answerPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (has(ctx, Manifest.permission.RECORD_AUDIO)) scope.launch { core.calls.answer() }
        else toast(ctx, "Bez mikrofonu nie można odebrać")
    }
    fun answer() {
        val need = listOf(Manifest.permission.RECORD_AUDIO) + if (call.video) listOf(Manifest.permission.CAMERA) else emptyList()
        if (need.all { has(ctx, it) }) scope.launch { core.calls.answer() } else answerPerm.launch(need.toTypedArray())
    }
    LaunchedEffect(act.answerRequest, call.phase) {
        if (act.answerRequest && call.phase == Phase.RINGING) { act.answerRequest = false; answer() }
    }
    BackHandler(enabled = call.phase != Phase.RINGING) { act.callHidden = true }

    val status = when (call.phase) {
        Phase.DIALING -> "Dzwonię…"
        Phase.RINGING -> if (call.video) "Rozmowa wideo przychodząca" else "Połączenie przychodzące"
        Phase.CONNECTING -> "Łączenie…"
        Phase.CONNECTED -> formatDuration(now - call.connectedAt)
        Phase.ENDED -> call.endReason
    }
    val showRemote = call.video && remote != null && call.phase == Phase.CONNECTED
    Surface(color = Color(0xFF0E1216), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            remote?.let { if (showRemote) VideoView(it, mirror = false, overlay = false, modifier = Modifier.fillMaxSize()) }
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = if (showRemote) 16.dp else 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!showRemote) {
                    Avatar(name, call.peer, 120.dp, image = avatar)
                    Spacer(Modifier.height(16.dp))
                }
                Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(status, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                if (call.phase != Phase.ENDED && !showRemote) Text(
                    "🔒 szyfrowane end-to-end", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val local = engine?.localVideo
            if (call.video && local != null && call.camOn && call.phase != Phase.ENDED && call.phase != Phase.RINGING) {
                VideoView(local, mirror = front, overlay = true,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(108.dp, 160.dp).clip(RoundedCornerShape(12.dp)))
            }
            if (call.phase != Phase.RINGING && call.phase != Phase.ENDED) {
                IconButton(onClick = { act.callHidden = true }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Wróć do aplikacji", tint = Color.White)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (call.phase) {
                    Phase.RINGING -> Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        RoundAction(Icons.Default.CallEnd, "Odrzuć", Red, { scope.launch { core.calls.decline() } }, 72)
                        RoundAction(if (call.video) Icons.Default.Videocam else Icons.Default.Call, "Odbierz", Green, { answer() }, 72)
                    }
                    Phase.ENDED -> {}
                    else -> {
                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            RoundAction(if (call.muted) Icons.Default.MicOff else Icons.Default.Mic, if (call.muted) "Włącz mikrofon" else "Wycisz",
                                if (call.muted) On else Dim, { core.calls.setMuted(!call.muted) })
                            RoundAction(Icons.Default.VolumeUp, "Głośnik", if (speaker) On else Dim, { CallAudio.setSpeaker(ctx, !speaker) })
                            if (call.video) {
                                RoundAction(if (call.camOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Kamera",
                                    if (call.camOn) Dim else On, { core.calls.setCamera(!call.camOn) })
                                RoundAction(Icons.Default.Cameraswitch, "Obróć", Dim, { core.calls.switchCamera() })
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        RoundAction(Icons.Default.CallEnd, "Rozłącz", Red, { scope.launch { core.calls.hangup() } }, 72)
                    }
                }
            }
        }
    }
}

/** Pasek „trwa rozmowa” nad innymi ekranami. */
@Composable
fun CallBanner(call: CallManager.Call, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Surface(color = Green, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            "📞 " + App.core.db.nameOf(call.peer) + " · " + (if (call.phase == Phase.CONNECTED) formatDuration(now - call.connectedAt) else "łączenie…") +
                " · dotknij, aby wrócić",
            color = Color.White, modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Ustawienia rozmów: własny serwer TURN. */
@Composable
fun CallSettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    var url by remember { mutableStateOf(core.turnUrl) }
    var user by remember { mutableStateOf(core.turnUser) }
    var pass by remember { mutableStateOf(core.turnPass) }
    Scaffold(topBar = { BackBar("Rozmowy", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Rozmowy głosowe i wideo są szyfrowane end-to-end (DTLS-SRTP) i płyną bezpośrednio między telefonami — bez serwera. " +
                    "Zadzwonisz z rozmowy prywatnej albo grupy (ikona słuchawki u góry; wideo w menu ⋮). W grupie mieści się do 6 osób (głos) lub 4 (wideo).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "• Rozmówca, z którym łączysz się bezpośrednio, może poznać Twój adres IP.\n" +
                    "• Do ustalenia adresu telefon pyta publiczne serwery STUN (Google, Cloudflare) — nie przechodzi przez nie dźwięk ani obraz.\n" +
                    "• W części sieci komórkowych i firmowych połączenie bezpośrednie jest zablokowane — wtedy pomaga Wi-Fi albo własny serwer TURN poniżej.\n" +
                    "• Telefon w trybie oszczędnym (Ustawienia → Działanie w tle) może nie dzwonić od razu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("Własny serwer TURN (opcjonalnie)", fontWeight = FontWeight.Bold)
            Text(
                "Np. coturn na Raspberry Pi lub serwerze: docker run -d --network=host coturn/coturn -n --lt-cred-mech --user szept:HASLO --realm szept",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Adres, np. turn:moj.serwer.pl:3478") })
            OutlinedTextField(user, { user = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Użytkownik") })
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Hasło") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row {
                TextButton(onClick = {
                    if (url.isNotEmpty() && !url.startsWith("turn:") && !url.startsWith("turns:")) { toast(ctx, "Adres musi zaczynać się od turn: lub turns:"); return@TextButton }
                    core.setTurn(url, user, pass); toast(ctx, "Zapisano")
                }) { Text("Zapisz") }
                if (core.turnUrl.isNotEmpty()) TextButton(onClick = { url = ""; user = ""; pass = ""; core.setTurn("", "", ""); toast(ctx, "Usunięto") }) { Text("Usuń") }
            }
            if (!core.calls.available) Text("Na tym urządzeniu rozmowy nie są dostępne (nietypowy procesor).", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ======================= rozmowy grupowe =======================

/** Zwraca funkcję „rozmowa w grupie” — dołącza do trwającej albo zaczyna nową. */
@Composable
fun rememberGroupCallStarter(): (String, Boolean) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun go(gid: String, video: Boolean) {
        App.core.scope.launch {
            val gc = App.core.groupCalls
            try { if (gc.joinable(gid) != null) gc.join(gid) else gc.start(gid, video) }
            catch (e: IllegalStateException) { toast(ctx, e.message ?: "Nie udało się zacząć rozmowy") }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (has(ctx, Manifest.permission.RECORD_AUDIO)) go(p.first, p.second && has(ctx, Manifest.permission.CAMERA))
        else toast(ctx, "Rozmowa wymaga dostępu do mikrofonu")
    }
    return { gid, video0 ->
        val gc = App.core.groupCalls
        if (!gc.available) toast(ctx, "Rozmowy nie działają na tym urządzeniu")
        else {
            val video = gc.joinable(gid)?.video ?: video0
            val need = listOf(Manifest.permission.RECORD_AUDIO) + if (video) listOf(Manifest.permission.CAMERA) else emptyList()
            if (need.all { has(ctx, it) }) go(gid, video) else { pending = gid to video; perm.launch(need.toTypedArray()) }
        }
    }
}

@Composable
private fun Tile(name: String, key: String, avatar: String?, track: VideoTrack?, mirror: Boolean, connecting: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
        if (track != null && !connecting) VideoView(track, mirror, overlay = false, modifier = Modifier.fillMaxSize())
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulseAvatar(name, key, avatar, 72.dp, pulsing = connecting)
            if (connecting) Text("łączenie…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            name, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Awatar z pulsującymi kręgami (dzwonienie / łączenie). */
@Composable
private fun PulseAvatar(name: String, key: String, avatar: String?, size: androidx.compose.ui.unit.Dp, pulsing: Boolean) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "puls")
    val p by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600), androidx.compose.animation.core.RepeatMode.Restart),
        label = "p",
    )
    val tint = avatarColor(key)
    Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            listOf(p, (p + 0.5f) % 1f).forEach { q ->
                Box(
                    Modifier.size(size * (1f + 0.5f * q)).clip(CircleShape).background(tint.copy(alpha = 0.35f * (1f - q))),
                )
            }
        } else Box(Modifier.size(size * 1.12f).clip(CircleShape).background(tint.copy(alpha = 0.25f)))
        Avatar(name, key, size, image = avatar)
    }
}

/** Uczestnik rozmowy głosowej (krąg z imieniem). */
@Composable
private fun Participant(name: String, key: String, avatar: String?, note: String?, dim: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp).padding(vertical = 6.dp).then(if (dim) Modifier.androidxAlpha(0.55f) else Modifier),
    ) {
        PulseAvatar(name, key, avatar, 64.dp, pulsing = note != null)
        Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (note != null) Text(note, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
    }
}

private fun Modifier.androidxAlpha(a: Float) = this.alpha(a)

/** Tytuł rozmowy: grupa albo osoby, z którymi rozmawiam. */
fun callTitle(call: pl.szept.call.GroupCallManager.GroupCall): String {
    val core = App.core
    if (!call.isRoom) return core.db.group(call.gid)?.name ?: "Grupa"
    val others = (call.room.ifEmpty { setOf(call.from) } - core.myPub).ifEmpty { call.peers.values.map { it.pk }.toSet() }
    val names = others.map { core.db.nameOf(it) }
    return when {
        names.isEmpty() -> "Rozmowa"
        names.size <= 3 -> names.joinToString(", ")
        else -> names.take(2).joinToString(", ") + " i ${names.size - 2} inne"
    }
}

/** Ekran rozmowy (prywatnej i grupowej): obraz lub awatary, przyciski, dodawanie osób. */
@Composable
fun GroupCallScreen(act: MainActivity, call: pl.szept.call.GroupCallManager.GroupCall) {
    val core = App.core
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gc = core.groupCalls
    val title = callTitle(call)
    val speaker by CallAudio.speaker.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var talkSince by remember(call.id) { mutableLongStateOf(0L) }
    var addDialog by remember { mutableStateOf(false) }
    val session = gc.currentSession() as? pl.szept.call.WebRtcSession
    val front by (session?.frontCamera ?: MutableStateFlow(true)).collectAsState()
    val connected = call.connected
    if (connected > 0 && talkSince == 0L) talkSince = System.currentTimeMillis()

    LaunchedEffect(call.phase) {
        if (call.phase == GPhase.ACTIVE) CallService.start(ctx)
        while (call.phase == GPhase.ACTIVE) { now = System.currentTimeMillis(); delay(1000) }
    }
    // rozmowa głosowa przy uchu: ekran gaśnie (czujnik zbliżeniowy)
    val ear = !call.video && !speaker && call.phase == GPhase.ACTIVE
    DisposableEffect(ear) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val lock = if (ear && pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true)
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "szept:gcall").apply { setReferenceCounted(false); acquire(3 * 3600_000L) }
        else null
        onDispose { runCatching { lock?.release() } }
    }
    val joinPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (has(ctx, Manifest.permission.RECORD_AUDIO)) core.scope.launch { gc.join() } else toast(ctx, "Bez mikrofonu nie można odebrać")
    }
    fun join() {
        val need = listOf(Manifest.permission.RECORD_AUDIO) + if (call.video) listOf(Manifest.permission.CAMERA) else emptyList()
        if (need.all { has(ctx, it) }) core.scope.launch { gc.join() } else joinPerm.launch(need.toTypedArray())
    }
    LaunchedEffect(act.groupJoinRequest, call.phase) {
        if (act.groupJoinRequest && call.phase == GPhase.RINGING) { act.groupJoinRequest = false; join() }
    }
    BackHandler(enabled = call.phase == GPhase.ACTIVE) { act.callHidden = true }

    val others = call.peers.values.sortedBy { it.id }
    val single = others.singleOrNull()
    val singleTrack = if (call.video && single != null) (gc.peerLink(single.id) as? pl.szept.call.WebRtcSession.Peer)?.remoteVideo?.collectAsState()?.value else null
    // kolor tła z awatara rozmówcy (albo grupy)
    val key = when {
        call.phase == GPhase.RINGING -> call.from
        single != null -> single.pk
        call.isRoom -> (call.room - core.myPub).firstOrNull() ?: call.gid
        else -> call.gid
    }
    val bg = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(avatarColor(key).copy(alpha = 0.55f), Color(0xFF0E1216), Color(0xFF0B0E11)))
    val status = when (call.phase) {
        GPhase.RINGING -> (if (call.video) "Rozmowa wideo" else "Połączenie głosowe") +
            if (call.isRoom) "" else " · " + core.db.nameOf(call.from) + " zaprasza do rozmowy"
        GPhase.ACTIVE -> when {
            connected > 0 -> (if (!call.isRoom || others.size > 1) "${others.size + 1} " + plural(others.size + 1, "osoba", "osoby", "osób") + " · " else "") +
                formatDuration(now - talkSince)
            others.isNotEmpty() -> "Łączenie…"
            call.invited.isNotEmpty() -> "Dzwonię…"
            else -> "Czekam, aż ktoś dołączy…"
        }
        GPhase.ENDED -> call.endReason
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E11)).background(bg)) {
        // rozmowa wideo w dwie osoby: obraz rozmówcy na cały ekran, mój w rogu
        val fullVideo = call.phase == GPhase.ACTIVE && call.video && single != null && singleTrack != null && single.phase == pl.szept.call.GroupCallManager.PeerPhase.CONNECTED
        if (fullVideo) VideoView(singleTrack!!, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                if (call.phase == GPhase.ACTIVE) IconButton(onClick = { act.callHidden = true }) {
                    Icon(Icons.Default.KeyboardArrowDown, "Wróć do aplikacji", tint = Color.White)
                } else Spacer(Modifier.width(48.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (call.phase == GPhase.ACTIVE && (fullVideo || others.size > 1 || (call.video && others.isNotEmpty()))) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(status, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    } else Text("🔒 szyfrowane end-to-end", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                }
                if (call.phase == GPhase.ACTIVE) IconButton(onClick = { addDialog = true }) {
                    Icon(Icons.Default.PersonAdd, "Dodaj osobę", tint = Color.White)
                } else Spacer(Modifier.width(48.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    call.phase == GPhase.ENDED -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                        Icon(Icons.Default.CallEnd, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(call.endReason.ifBlank { "Rozmowa zakończona" }, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    }
                    call.phase == GPhase.RINGING || (call.phase == GPhase.ACTIVE && !fullVideo && others.size <= 1 && !(call.video && others.isNotEmpty())) -> {
                        // jedna osoba: duży awatar, imię i stan
                        val pk = if (call.phase == GPhase.RINGING) call.from else single?.pk ?: (call.room - core.myPub).firstOrNull() ?: call.from
                        val name = if (call.isRoom || call.phase != GPhase.RINGING) core.db.nameOf(pk) else title
                        val avatar = if (call.isRoom || call.phase != GPhase.RINGING) core.db.contact(pk)?.avatar?.ifBlank { null } else core.db.group(call.gid)?.icon?.ifBlank { null }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                            PulseAvatar(name, if (call.isRoom || call.phase != GPhase.RINGING) pk else call.gid, avatar, 132.dp, pulsing = connected == 0)
                            Spacer(Modifier.height(18.dp))
                            Text(name, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(6.dp))
                            Text(status, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            if (call.phase == GPhase.ACTIVE && call.invited.isNotEmpty() && others.isNotEmpty()) Text(
                                "Dzwonię też do: " + call.invited.joinToString { core.db.nameOf(it) },
                                color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    call.phase == GPhase.ACTIVE && fullVideo -> {
                        val local = session?.localVideo
                        if (local != null && call.camOn) VideoView(local, mirror = front, overlay = true,
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(104.dp, 156.dp).clip(RoundedCornerShape(14.dp)))
                    }
                    call.phase == GPhase.ACTIVE && call.video -> {
                        // kafelki: ja + pozostali, po dwa w rzędzie
                        val tiles = buildList {
                            add(Triple("me", core.myName, null as pl.szept.call.GroupCallManager.Peer?))
                            others.forEach { add(Triple(it.id, core.db.nameOf(it.pk), it)) }
                        }
                        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tiles.chunked(2).forEach { row ->
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { (id, name, peer) ->
                                        val track = if (peer == null) session?.localVideo?.takeIf { call.camOn }
                                            else (gc.peerLink(id) as? pl.szept.call.WebRtcSession.Peer)?.remoteVideo?.collectAsState()?.value
                                        val avatar = if (peer == null) null else core.db.contact(peer.pk)?.avatar?.ifBlank { null }
                                        Tile(if (peer == null) "Ty" else name, peer?.pk ?: core.myPub, avatar, track,
                                            mirror = peer == null && front, connecting = peer?.phase == pl.szept.call.GroupCallManager.PeerPhase.CONNECTING,
                                            modifier = Modifier.weight(1f).fillMaxSize())
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    call.phase == GPhase.ACTIVE -> {
                        // rozmowa głosowa kilku osób: kręgi z imionami
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Participant("Ty", core.myPub, core.myAvatarB64(), if (call.muted) "wyciszony(-a)" else null, dim = false)
                            others.forEach { p ->
                                Participant(core.db.nameOf(p.pk), p.pk, core.db.contact(p.pk)?.avatar?.ifBlank { null },
                                    if (p.phase == pl.szept.call.GroupCallManager.PeerPhase.CONNECTING) "łączenie…" else null, dim = false)
                            }
                            call.invited.forEach { pk ->
                                Participant(core.db.nameOf(pk), pk, core.db.contact(pk)?.avatar?.ifBlank { null }, "dzwonię…", dim = true)
                            }
                        }
                    }
                    else -> {}
                }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp, top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (call.phase) {
                    GPhase.RINGING -> Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        RoundAction(Icons.Default.CallEnd, "Odrzuć", Red, { core.scope.launch { gc.decline() } }, 72)
                        RoundAction(if (call.video) Icons.Default.Videocam else Icons.Default.Call, "Odbierz", Green, { join() }, 72)
                    }
                    GPhase.ACTIVE -> {
                        Surface(color = Color.Black.copy(alpha = 0.35f), shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                                RoundAction(if (call.muted) Icons.Default.MicOff else Icons.Default.Mic, if (call.muted) "Włącz" else "Wycisz",
                                    if (call.muted) On else Dim, { gc.setMuted(!call.muted) }, 54)
                                RoundAction(Icons.Default.VolumeUp, "Głośnik", if (speaker) On else Dim, { CallAudio.setSpeaker(ctx, !speaker) }, 54)
                                if (call.video) {
                                    RoundAction(if (call.camOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Kamera",
                                        if (call.camOn) Dim else On, { gc.setCamera(!call.camOn) }, 54)
                                    RoundAction(Icons.Default.Cameraswitch, "Obróć", Dim, { gc.switchCamera() }, 54)
                                } else RoundAction(Icons.Default.PersonAdd, "Dodaj", Dim, { addDialog = true }, 54)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        RoundAction(Icons.Default.CallEnd, "Rozłącz", Red, { core.scope.launch { gc.leave() } }, 70)
                    }
                    GPhase.ENDED -> {}
                }
            }
        }
    }
    if (addDialog && call.phase == GPhase.ACTIVE) AddToCallDialog(call) { addDialog = false }
}

/** Dodawanie osób do trwającej rozmowy — dzwoni do nich (także ponownie). */
@Composable
private fun AddToCallDialog(call: pl.szept.call.GroupCallManager.GroupCall, onDismiss: () -> Unit) {
    val core = App.core
    val inCall = call.peers.values.map { it.pk }.toSet()
    val candidates = remember(call.id, inCall) {
        if (call.isRoom) core.knownPeople().filter { !it.blocked && it.isContact && it.pk !in inCall && !pl.szept.core.Admin.isAdmin(it.pk) }.map { it.pk }
        else core.db.group(call.gid)?.members?.map { it.pk }?.filter { it != core.myPub && it !in inCall } ?: emptyList()
    }
    val free = pl.szept.call.GroupCallManager.max(call.video) - 1 - call.peers.size
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj do rozmowy") },
        text = {
            Column {
                Text(
                    if (call.isRoom) "Wybrane osoby dostaną dzwonek i dołączą do tej rozmowy." else "Zadzwoń jeszcze raz do osób z grupy, których nie ma w rozmowie.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Wolne miejsca: ${free.coerceAtLeast(0)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                if (candidates.isEmpty()) Text("Nie ma kogo dodać.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(300.dp)) {
                    items(candidates.size) { i ->
                        val pk = candidates[i]
                        val on = pk in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (on) picked.remove(pk) else if (picked.size < free) picked.add(pk)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(on, { v -> if (!v) picked.remove(pk) else if (picked.size < free) picked.add(pk) })
                            Avatar(core.db.nameOf(pk), pk, 36.dp, image = core.db.contact(pk)?.avatar?.ifBlank { null })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(core.db.nameOf(pk), maxLines = 1)
                                if (pk in call.invited) Text("dzwonię…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = {
                val list = picked.toList()
                core.scope.launch { core.groupCalls.invite(list) }
                onDismiss()
            }) { Text("Zadzwoń") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/** Pasek „trwa rozmowa” nad innymi ekranami. */
@Composable
fun GroupCallBanner(call: pl.szept.call.GroupCallManager.GroupCall, onClick: () -> Unit) {
    Surface(color = Green, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            "📞 " + callTitle(call) + (if (call.peers.isNotEmpty()) " · ${call.peers.size + 1} " + plural(call.peers.size + 1, "osoba", "osoby", "osób") else " · dzwonię…") +
                " · dotknij, aby wrócić",
            color = Color.White, modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, maxLines = 1,
        )
    }
}
