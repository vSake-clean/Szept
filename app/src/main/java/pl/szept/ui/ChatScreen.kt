package pl.szept.ui

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.Core
import pl.szept.core.Gifs
import pl.szept.core.LocationHelper
import pl.szept.core.MediaUtil
import pl.szept.core.VoicePlayer
import pl.szept.core.VoiceRecorder
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Extra
import pl.szept.data.Group
import pl.szept.data.GroupState
import pl.szept.data.Kind
import pl.szept.data.Loc
import pl.szept.data.Message
import pl.szept.data.St
import pl.szept.data.XMode
import pl.szept.service.MessengerService
import java.io.File

/** Kilka zdjęć wysłanych naraz (jeden wiersz listy). */
data class AlbumRow(val key: String, val msgs: List<Message>)

/** Łączy kolejne zdjęcia jednego albumu w jeden wiersz (lista od najnowszych). */
fun groupAlbums(newestFirst: List<Message>): List<Any> {
    val out = ArrayList<Any>(newestFirst.size)
    var i = 0
    while (i < newestFirst.size) {
        val m = newestFirst[i]
        val al = m.ex.album
        if (al != null && m.xmode == XMode.OFF && (m.kind == Kind.IMAGE || m.kind == Kind.VIDEO)) {
            var j = i
            val grp = ArrayList<Message>()
            while (j < newestFirst.size && newestFirst[j].ex.album == al && newestFirst[j].sender == m.sender && newestFirst[j].xmode == XMode.OFF) {
                grp.add(newestFirst[j]); j++
            }
            if (grp.size > 1) {
                out.add(AlbumRow("al:" + grp.last().id, grp.sortedBy { it.ex.albumIdx }))
                i = j
                continue
            }
        }
        out.add(m)
        i++
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(nav: Nav, chatId: String, act: MainActivity, focus: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // wysyłanie i przygotowanie plików — dokończy się także po wyjściu z czatu (np. przy dzwonieniu)
    val work = App.work
    val startCall = rememberCallStarter()
    val official = pl.szept.core.Admin.isAdmin(chatId.removePrefix("d:"))
    val startGroupCall = rememberGroupCallStarter()
    val knownCalls by App.core.groupCalls.known.collectAsState()
    val core = App.core
    var limit by remember { mutableIntStateOf(300) }
    val chat by rememberDb(chatId) { core.db.chat(chatId) }
    val group by rememberDb(chatId) { if (chatId.startsWith("g:")) core.db.group(chatId.removePrefix("g:")) else null }
    val msgs by rememberDb(chatId, limit) { core.db.messages(chatId, limit) }
    val names by rememberDb(chatId) { core.db.contacts(false).associate { it.pk to it.display } }
    val streakDays by rememberDb(chatId) { if (chatId.startsWith("d:")) core.streakDays(chatId) else 0 }
    val reactions by rememberDb(chatId) { core.db.reactionsForChat(chatId) }
    val votes by rememberDb(chatId) { core.db.votesForChat(chatId) }
    val pinned by rememberDb(chatId) { core.db.pins(chatId).mapNotNull { core.db.message(it.msg) } }
    val scheduled by rememberDb(chatId) { core.db.scheduled(chatId) }
    val typingAll by core.typing.collectAsState()

    DisposableEffect(chatId) {
        core.activeChat = chatId
        core.markChatRead(chatId)
        onDispose {
            if (core.activeChat == chatId) core.activeChat = null
            VoicePlayer.stop()
            RoundPlayer.stop()
        }
    }
    LaunchedEffect(msgs?.size) { core.markChatRead(chatId) }

    // treści znikające — blokada zrzutów ekranu
    val secure = (chat?.xmode ?: 0) != XMode.OFF || msgs?.any { it.xmode != XMode.OFF } == true
    DisposableEffect(secure) { act.setSecure(secure); onDispose { act.setSecure(false) } }

    var text by remember { mutableStateOf("") }
    var draftLoaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var draftBeforeEdit by remember { mutableStateOf("") }
    val mentionPicks = remember { mutableStateMapOf<String, String>() }
    var preparing by remember { mutableStateOf<String?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var timerDialog by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var pickDeleteFor by remember { mutableStateOf<Message?>(null) }
    var reactionsFor by remember { mutableStateOf<Pair<Message, List<pl.szept.data.Reaction>>?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var votersFor by remember { mutableStateOf<Message?>(null) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    // wyjście z czatu (połączenie, blokada, „wstecz”) — nagrywanie głosówki się kończy, mikrofon zostaje zwolniony
    DisposableEffect(Unit) { onDispose { try { recorder?.cancel() } catch (_: Exception) {} } }
    var recElapsed by remember { mutableLongStateOf(0L) }
    var pendingCamera by remember { mutableStateOf<(() -> Unit)?>(null) }
    // ścieżka przetrwa odtworzenie ekranu w czasie, gdy otwarta jest aplikacja aparatu
    var camPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }

    var replyTo by remember { mutableStateOf<Message?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Core.Attachment>?>(null) }
    var albumPreview by remember { mutableStateOf<List<Core.Attachment>?>(null) }
    // edytor zdjęcia: (indeks w albumie albo -1 = pojedyncze zdjęcie, plik)
    var gifPicker by remember { mutableStateOf(false) }
    var gamePicker by remember { mutableStateOf(false) }
    // tłumaczenie: (id wiadomości, wynik; null = trwa)
    var translation by remember { mutableStateOf<Pair<String, String?>?>(null) }
    // GIF z rozmowy: podgląd z opcją „Zapisz w moich”
    var gifFor by remember { mutableStateOf<Pair<File, Message>?>(null) }
    var schedulePick by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    var pollDialog by remember { mutableStateOf(false) }
    var videoNoteDialog by remember { mutableStateOf(false) }
    var locationDialog by remember { mutableStateOf(false) }
    var scheduledDialog by remember { mutableStateOf(false) }
    var voiceTimed by remember { mutableStateOf(false) }
    var sendMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIdx by remember { mutableIntStateOf(0) }
    var pinIdx by remember { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<Message?>(null) }
    var locPending by remember { mutableStateOf<Long?>(null) }
    // nagrywanie głosówki: 0 = brak, 1 = przytrzymany mikrofon, 2 = bez trzymania (zablokowane)
    var recMode by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val amps = remember { mutableStateListOf<Float>() }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelPx = with(density) { 110.dp.toPx() }
    val lockPx = with(density) { 70.dp.toPx() }
    val isGroup = chatId.startsWith("g:")

    // ---------- szkic: przywrócenie i zapis ----------
    LaunchedEffect(chat != null) {
        val c = chat
        if (!draftLoaded && c != null) { if (text.isEmpty()) text = c.draft; draftLoaded = true }
    }
    DisposableEffect(chatId) {
        // stan czytany wprost (nie z ostatniej kompozycji) — zapisuje nawet zmianę sprzed ułamka sekundy
        onDispose { if (draftLoaded) core.saveDraft(chatId, if (editing != null) draftBeforeEdit else text) }
    }
    LaunchedEffect(text, editing) {
        if (draftLoaded && editing == null) { delay(1500); withContext(Dispatchers.IO) { core.saveDraft(chatId, text) } }
    }

    // ---------- lista, przewijanie, doładowanie starszych ----------
    val listState = rememberLazyListState()
    val list = msgs?.asReversed() ?: emptyList()
    val rows = remember(list) { withDayHeaders(groupAlbums(list)) }
    val rowsNow by rememberUpdatedState(rows)
    val msgCount by rememberUpdatedState(msgs?.size ?: 0)
    fun rowIndexOf(id: String) = rowsNow.indexOfFirst { (it is Message && it.id == id) || (it is AlbumRow && it.msgs.any { m -> m.id == id }) }

    fun jumpTo(id: String) {
        scope.launch {
            val target = withContext(Dispatchers.IO) { core.db.message(id) }
            if (target == null || target.chat != chatId) { toast(ctx, "Tej wiadomości już nie ma"); return@launch }
            val need = withContext(Dispatchers.IO) { core.db.countNewer(chatId, target.ts) } + 40
            if (need > limit) limit = need + 100
            var idx = rowIndexOf(id)
            var tries = 0
            while (idx < 0 && tries++ < 50) { delay(100); idx = rowIndexOf(id) }
            if (idx < 0) return@launch
            listState.animateScrollToItem(idx)
            highlight = id
            delay(2500)
            if (highlight == id) highlight = null
        }
    }
    LaunchedEffect(focus) { if (focus != null) jumpTo(focus) }
    LaunchedEffect(list.firstOrNull()?.id) { if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= rowsNow.size - 3 && msgCount >= limit) limit += 300
        }
    }

    // ---------- „pisze…” ----------
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(typingAll) {
        while ((typingAll[chatId]?.values?.maxOrNull() ?: 0L) > System.currentTimeMillis()) { delay(1000); tick++ }
        tick++
    }
    val typers = remember(typingAll, tick) { (typingAll[chatId] ?: emptyMap()).filter { it.value > System.currentTimeMillis() }.keys.toList() }
    fun nameFor(pk: String) = if (pk == core.myPub) "Ty" else names?.get(pk) ?: pk.take(8)

    // ---------- wyszukiwanie w rozmowie ----------
    val hits by produceState(emptyList<Message>(), query, searchMode) {
        value = if (searchMode && query.trim().length >= 2) withContext(Dispatchers.IO) { core.db.search(query, chatId) } else emptyList()
    }
    LaunchedEffect(hits) { hitIdx = 0; hits.firstOrNull()?.let { jumpTo(it.id) } }

    // ---------- zapis do galerii ----------
    fun doSave(m: Message) {
        val path = m.path ?: return
        scope.launch {
            val ok = MediaUtil.saveToGallery(ctx, File(path), m.media?.mime ?: "image/jpeg")
            toast(ctx, if (ok) "Zapisano w galerii (folder Szept)" else "Nie udało się zapisać")
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val m = pendingSave
        pendingSave = null
        if (granted && m != null) doSave(m) else if (!granted) toast(ctx, "Brak zgody na zapis w pamięci")
    }
    fun saveToGallery(m: Message) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { pendingSave = m; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } else doSave(m)
    }

    /**
     * Przygotowuje załącznik. Zdjęcia i wideo trafiają najpierw do podglądu (podpis + opcja czasowe/zaplanuj),
     * pliki i głosówki są wysyłane od razu.
     */
    fun sendAttachment(
        kind: String, label: String, xmode: Int? = null, ttl: Int? = null,
        prep: suspend (MutableStateFlow<Int>) -> Core.Attachment,
    ) {
        work.launch {
            val progress = MutableStateFlow(-1)
            preparing = label
            val watcher = launch { progress.collect { if (it >= 0) preparing = "$label $it%" } }
            try {
                val att = prep(progress)
                if (kind == Kind.IMAGE || kind == Kind.VIDEO) {
                    preview = kind to att
                } else {
                    val caption = if (kind == Kind.VOICE) "" else text.trim()
                    if (kind != Kind.VOICE) text = ""
                    val r = replyTo
                    replyTo = null
                    core.send(chatId, kind, caption, att, xmode, ttl, r)
                }
            } catch (e: Core.SendException) {
                toast(ctx, e.message ?: "Błąd")
            } catch (e: Exception) {
                toast(ctx, "Nie udało się: ${e.message}")
            } finally {
                watcher.cancel()
                preparing = null
            }
        }
    }

    fun sendAlbum(atts: List<Core.Attachment>, caption: String, xmode: Int?, ttl: Int?) {
        val r = replyTo
        replyTo = null
        val aid = Crypto.randomBytes(6).hex()
        work.launch {
            atts.forEachIndexed { i, a ->
                try {
                    core.send(chatId, Kind.IMAGE, if (i == 0) caption else "", a, xmode, ttl, if (i == 0) r else null, Extra(album = aid, albumIdx = i, albumN = atts.size))
                } catch (e: Exception) { toast(ctx, sendError(e)); return@launch }
            }
        }
    }

    fun scheduleAtts(at: Long, kind: String, atts: List<Core.Attachment>, caption: String) {
        val aid = if (atts.size > 1) Crypto.randomBytes(6).hex() else null
        work.launch(Dispatchers.IO) {
            atts.forEachIndexed { i, a ->
                core.schedule(chatId, at, kind, if (i == 0) caption else "", a, XMode.OFF, null, null,
                    if (aid != null) Extra(album = aid, albumIdx = i, albumN = atts.size) else Extra.NONE)
            }
        }
        toast(ctx, "Zaplanowano na ${formatWhen(at)}")
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        when {
            uris.isEmpty() -> {}
            uris.size == 1 -> sendAttachment(Kind.IMAGE, "Przygotowywanie zdjęcia…") { MediaUtil.prepareImage(ctx, uris[0]) }
            else -> work.launch {
                try {
                    albumPreview = uris.mapIndexed { i, u -> preparing = "Przygotowywanie zdjęć ${i + 1}/${uris.size}…"; MediaUtil.prepareImage(ctx, u) }
                } catch (e: Exception) { toast(ctx, "Nie udało się: ${e.message}") } finally { preparing = null }
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) sendAttachment(Kind.VIDEO, "Kompresja wideo…") { p -> MediaUtil.prepareVideo(ctx, uri, p) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) sendAttachment(Kind.FILE, "Przygotowywanie pliku…") { MediaUtil.prepareFile(ctx, uri) }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camPath?.let { File(it) }
        if (ok && f != null) sendAttachment(Kind.IMAGE, "Przygotowywanie zdjęcia…") { MediaUtil.prepareImage(ctx, Uri.fromFile(f)).also { f.delete() } }
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = camPath?.let { File(it) }
        if (ok && f != null && f.length() > 0) sendAttachment(Kind.VIDEO, "Kompresja wideo…") { p -> MediaUtil.prepareVideo(ctx, Uri.fromFile(f), p).also { f.delete() } }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCamera?.invoke() else toast(ctx, "Brak dostępu do aparatu")
        pendingCamera = null
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(ctx, "Brak dostępu do mikrofonu")
    }

    // ---------- lokalizacja ----------
    fun doSendLocation(liveMs: Long) {
        if (!LocationHelper.anyProviderEnabled(ctx)) {
            toast(ctx, "Włącz lokalizację w telefonie")
            try { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            return
        }
        work.launch {
            preparing = "Ustalanie lokalizacji…"
            try {
                val l = LocationHelper.current(ctx)
                if (l == null) toast(ctx, "Nie udało się ustalić lokalizacji — spróbuj na zewnątrz")
                else {
                    core.sendLocation(chatId, l.latitude, l.longitude, l.accuracy, liveMs)
                    if (liveMs > 0) MessengerService.start(ctx)
                }
            } catch (e: Exception) { toast(ctx, sendError(e)) } finally { preparing = null }
        }
    }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val live = locPending
        locPending = null
        if (res.values.any { it } && live != null) doSendLocation(live) else toast(ctx, "Brak zgody na lokalizację")
    }
    fun sendLocation(liveMs: Long) {
        if (LocationHelper.hasPermission(ctx)) doSendLocation(liveMs)
        else { locPending = liveMs; locPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }

    // okrągła notatka wideo: kamera + mikrofon
    val noteRecorderPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) videoNoteDialog = true else toast(ctx, "Notatka wideo wymaga dostępu do aparatu i mikrofonu")
    }
    fun openVideoNote() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) videoNoteDialog = true
        else noteRecorderPerms.launch(perms)
    }
    fun sendVideoNote(file: File, dur: Long) {
        val r = replyTo
        replyTo = null
        work.launch {
            preparing = "Przygotowywanie notatki wideo…"
            try {
                val att = MediaUtil.prepareRoundVideo(ctx, file, dur)
                core.send(chatId, Kind.VIDEO, "", att, null, null, r, Extra(round = true))
            } catch (e: Exception) { toast(ctx, sendError(e)) } catch (e: Exception) { toast(ctx, "Nie udało się: ${e.message}") } finally { preparing = null }
        }
    }
    fun transcribe(m: Message) {
        val path = m.path ?: return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        scope.launch {
            preparing = "Zamiana głosówki na tekst (w telefonie)…"
            try {
                val t = pl.szept.core.Transcriber.transcribe(ctx, File(path), m.media?.dur ?: 60_000)
                withContext(Dispatchers.IO) { core.db.setBody(m.id, t) }
            } catch (e: pl.szept.core.Transcriber.TranscribeException) { toast(ctx, e.message ?: "Nie udało się") }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { toast(ctx, "Rozpoznawanie trwało za długo") }
            catch (e: Exception) { toast(ctx, "Nie udało się: ${e.message}") }
            finally { preparing = null }
        }
    }

    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingCamera = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }

    fun newCamFile(ext: String) = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.$ext").also { camPath = it.absolutePath }

    /** Zaczyna nagrywać; false, gdy brak zgody na mikrofon (wtedy o nią prosi). */
    fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        if (recorder != null) return true
        VoicePlayer.stop()
        val r = VoiceRecorder(ctx)
        if (!r.start()) { toast(ctx, "Nie można uruchomić nagrywania"); return false }
        amps.clear()
        recorder = r
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        return true
    }

    fun finishRecording(send: Boolean) {
        val r = recorder ?: run { recMode = 0; return }
        recorder = null
        recMode = 0
        dragX = 0f
        val elapsed = System.currentTimeMillis() - r.startedAt
        val timed = voiceTimed
        voiceTimed = false
        val f = r.stop()
        if (!send) { f?.delete(); haptic.performHapticFeedback(HapticFeedbackType.LongPress); return }
        if (elapsed < 700) { f?.delete(); toast(ctx, "Przytrzymaj mikrofon, aby nagrać — puść, aby wysłać"); return }
        if (f == null) { toast(ctx, "Nie udało się nagrać"); return }
        val dur = MediaUtil.audioDuration(f).takeIf { it > 0 } ?: elapsed
        val wave = VoiceRecorder.encodeWave(amps.toList())
        // głosówka czasowa: ukryta do dotknięcia, czas od otwarcia (wydłużany do długości nagrania)
        sendAttachment(Kind.VOICE, "Wysyłanie nagrania…", if (timed) XMode.AFTER_OPEN else null, if (timed) 10 else null) {
            Core.Attachment(f, "audio/mp4", "glosowka.m4a", null, 0, 0, dur, wave)
        }
    }

    fun currentMentions(t: String): List<String> = mentionPicks.filter { (name, _) -> t.contains("@$name") }.values.distinct()

    fun sendTextNow(xmode: Int? = null, ttl: Int? = null) {
        val t = text
        if (t.isBlank()) return
        val ed = editing
        if (ed != null) {
            editing = null
            text = draftBeforeEdit
            draftBeforeEdit = ""
            work.launch { try { core.editMessage(ed.id, t) } catch (e: Exception) { toast(ctx, sendError(e)) } }
            return
        }
        text = ""
        val r = replyTo
        replyTo = null
        val ex = Extra(mentions = currentMentions(t))
        mentionPicks.clear()
        work.launch {
            try { core.sendText(chatId, t, xmode, ttl, r, ex) } catch (e: Exception) { text = t; replyTo = r; toast(ctx, sendError(e)) }
        }
    }

    fun scheduleText(at: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""
        val r = replyTo
        replyTo = null
        val ex = Extra(mentions = currentMentions(t))
        mentionPicks.clear()
        work.launch(Dispatchers.IO) { core.schedule(chatId, at, Kind.TEXT, t, null, null, null, r, ex) }
        toast(ctx, "Zaplanowano na ${formatWhen(at)}")
    }

    LaunchedEffect(recorder) {
        while (true) {
            val r = recorder ?: break
            recElapsed = System.currentTimeMillis() - r.startedAt
            amps.add(r.amplitude())
            if (recElapsed > 15 * 60_000L) { finishRecording(true); break }
            delay(100)
        }
    }

    val g = group
    val canSend: String? = when {
        official -> "To oficjalne konto Szept — na te wiadomości nie można odpowiadać."
        g == null -> null
        g.state == GroupState.PENDING -> "Czekasz na akceptację administratora grupy…"
        g.state == GroupState.REMOVED -> "Nie jesteś już członkiem tej grupy."
        g.state == GroupState.LEFT -> "Opuściłeś(-aś) tę grupę."
        g.state == GroupState.CLOSED -> "Grupa została rozwiązana."
        g.adminsOnly && !g.isAdmin(core.myPub) -> "Tylko administratorzy mogą pisać w tej grupie."
        else -> null
    } ?: core.canWrite(chatId)
    val canInteract = g == null || g.state == GroupState.ACTIVE

    // podpowiedzi @wzmianek
    val mentionQuery: String? = remember(text, g) {
        if (g == null || canSend != null) null
        else text.substringAfterLast(' ').substringAfterLast('\n').takeIf { it.startsWith("@") && it.length <= 30 }?.drop(1)
    }
    val suggestions = remember(mentionQuery, g, names) {
        val gg = g ?: return@remember emptyList()
        val q = mentionQuery ?: return@remember emptyList()
        val people = gg.members.filter { it.pk != core.myPub }
            .map { it.pk to (names?.get(it.pk) ?: it.name).ifBlank { it.pk.take(8) } }
            .filter { it.second.contains(q, true) }
        (if (gg.isAdmin(core.myPub) && "wszyscy".startsWith(q.lowercase())) listOf("all" to "wszyscy") else emptyList()) + people
    }
    fun pickMention(pk: String, name: String) {
        val q = mentionQuery ?: return
        text = text.dropLast(q.length + 1) + "@" + name + " "
        mentionPicks[name] = pk
    }

    Scaffold(
        topBar = {
            if (searchMode) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { searchMode = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zamknij wyszukiwanie") } },
                    title = {
                        TextField(
                            query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Szukaj w rozmowie") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        )
                    },
                    actions = {
                        if (hits.isNotEmpty()) Text("${hitIdx + 1}/${hits.size}", style = MaterialTheme.typography.labelMedium)
                        else if (query.trim().length >= 2) Text("0", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { if (hitIdx < hits.size - 1) { hitIdx++; jumpTo(hits[hitIdx].id) } }, enabled = hitIdx < hits.size - 1) {
                            Icon(Icons.Default.KeyboardArrowUp, "Starsze")
                        }
                        IconButton(onClick = { if (hitIdx > 0) { hitIdx--; jumpTo(hits[hitIdx].id) } }, enabled = hitIdx > 0) {
                            Icon(Icons.Default.KeyboardArrowDown, "Nowsze")
                        }
                    },
                )
            } else TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val c = chat ?: return@clickable
                            if (c.isGroup) nav.go(Screen.GroupInfo(c.id.removePrefix("g:"))) else c.peer?.let { nav.go(Screen.ContactInfo(it)) }
                        },
                    ) {
                        val hdrImg = if (chat?.isGroup == true) group?.icon?.ifBlank { null } else chat?.peer?.let { core.db.contact(it)?.avatar?.ifBlank { null } }
                        Avatar(chat?.name ?: "", chat?.peer ?: chatId, 38.dp, chat?.isGroup == true, hdrImg)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(chat?.name ?: "", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                if (chat?.isGroup == false && core.isVerified(chat?.peer)) VerifiedBadge()
                            }
                            val streak = if (chat?.isGroup == false) (streakDays ?: 0) else 0
                            val typingText = when {
                                typers.isEmpty() -> null
                                !isGroup -> "pisze…"
                                typers.size == 1 -> "${nameFor(typers[0])} pisze…"
                                else -> "${typers.size} osoby piszą…"
                            }
                            val seen = if (chat?.isGroup == false && !official) rememberSeen(chat?.peer) else null
                            val seenText = seen?.let { (st, at) -> presenceText(st, at) + if (streak > 0) " · 🔥 $streak" else "" }
                            Text(
                                typingText ?: group?.let { "${it.members.size} członków · dotknij po szczegóły" }
                                    ?: seenText ?: if (streak > 0) "🔥 $streak dni z rzędu" else "🔒 szyfrowanie end-to-end",
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = when {
                                    typingText != null -> OkGreen
                                    seen != null -> presenceColor(seen.first)
                                    streak > 0 && group == null -> Fire
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                actions = {
                    if (chat?.isGroup == false && chat?.peer != null && !official) IconButton(onClick = { chat?.peer?.let { startCall(it, false) } }) {
                        Icon(Icons.Default.Call, "Zadzwoń")
                    }
                    if (chat?.isGroup == true && group?.state == pl.szept.data.GroupState.ACTIVE) IconButton(onClick = { startGroupCall(chatId.removePrefix("g:"), false) }) {
                        Icon(Icons.Default.Call, "Rozmowa grupowa")
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "Więcej") }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            if (!official) DropdownMenuItem({ Text(if ((chat?.xmode ?: 0) != 0) "Wiadomości czasowe (włączone)" else "Wiadomości czasowe") }, {
                                moreMenu = false; timerDialog = true
                            }, leadingIcon = { Icon(Icons.Default.Timer, null, tint = if ((chat?.xmode ?: 0) != 0) Fire else MaterialTheme.colorScheme.onSurface) })
                            if (chat?.isGroup == false && chat?.peer != null && !official) DropdownMenuItem({ Text("Rozmowa wideo") }, {
                                moreMenu = false; chat?.peer?.let { startCall(it, true) }
                            }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                            if (chat?.isGroup == true && group?.state == pl.szept.data.GroupState.ACTIVE) DropdownMenuItem({ Text("Rozmowa grupowa wideo") }, {
                                moreMenu = false; startGroupCall(chatId.removePrefix("g:"), true)
                            }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                            DropdownMenuItem({ Text("Szukaj w rozmowie") }, { moreMenu = false; searchMode = true }, leadingIcon = { Icon(Icons.Default.Search, null) })
                            DropdownMenuItem({ Text("Multimedia, pliki i linki") }, { moreMenu = false; nav.go(Screen.Media(chatId)) }, leadingIcon = { Icon(Icons.Default.Image, null) })
                            DropdownMenuItem({ Text("Zaplanowane (${scheduled?.size ?: 0})") }, { moreMenu = false; scheduledDialog = true }, leadingIcon = { Icon(Icons.Default.Timer, null) })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            if (chatId.startsWith("g:")) {
                val k = remember(knownCalls) { core.groupCalls.joinable(chatId.removePrefix("g:")) }
                if (k != null) Surface(color = Color(0xFF2E7D32), modifier = Modifier.fillMaxWidth().clickable { startGroupCall(chatId.removePrefix("g:"), k.video) }) {
                    Text(
                        (if (k.video) "📹" else "📞") + " Trwa rozmowa grupowa (${k.members.size} " + plural(k.members.size, "osoba", "osoby", "osób") + ") — dotknij, aby dołączyć",
                        color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            val pins = pinned ?: emptyList()
            if (pins.isNotEmpty()) {
                val i = pinIdx.coerceIn(0, pins.size - 1)
                PinnedBar(pins[i], i, pins.size, core.canPin(chatId), onClick = {
                    jumpTo(pins[i].id)
                    pinIdx = (i + 1) % pins.size
                }, onUnpin = { core.pinMessage(pins[i].id, false) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatWallpaper(Modifier.matchParentSize())
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true) {
                    items(rows, key = { r -> when (r) { is Message -> r.id; is AlbumRow -> r.key; else -> (r as DayHeader).key } }) { row ->
                        when (row) {
                            is DayHeader -> DayHeaderItem(row.label)
                            is AlbumRow -> {
                                val all = row.msgs.flatMap { reactions?.get(it.id) ?: emptyList() }
                                SwipeToReply(canSend == null, onReply = { replyTo = row.msgs.last(); editing = null }) { AlbumItem(
                                    row.msgs, if (isGroup) names?.get(row.msgs.first().sender) ?: row.msgs.first().sender.take(8) else null,
                                    highlight = row.msgs.any { it.id == highlight }, reactions = all,
                                    onOpen = { m ->
                                        when {
                                            m.mine && m.status == St.FAILED -> core.retry(m.id)
                                            !m.mine && m.status == St.DOWNLOAD_FAILED -> core.retry(m.id)
                                            m.path != null -> nav.go(Screen.Viewer(m.id))
                                        }
                                    },
                                    onLong = { m -> actionsFor = m },
                                    onReactionsClick = { reactionsFor = row.msgs.first() to all },
                                ) }
                            }
                            is Message -> {
                                val m = row
                                SwipeToReply(canSend == null && m.kind != Kind.SYSTEM, onReply = { replyTo = m; editing = null }) { MessageItem(
                                    m,
                                    senderName = if (isGroup) names?.get(m.sender) ?: m.sender.take(8) else null,
                                    replyName = m.reply?.let { nameFor(it.sender) },
                                    onReplyClick = { id -> jumpTo(id) },
                                    reactions = reactions?.get(m.id) ?: emptyList(),
                                    votes = votes?.get(m.id) ?: emptyList(),
                                    highlight = highlight == m.id,
                                    onReactionsClick = { reactionsFor = m to (reactions?.get(m.id) ?: emptyList()) },
                                    onVote = { sel -> if (canInteract) core.vote(m.id, sel) else toast(ctx, "Nie możesz już głosować w tej grupie") },
                                    onClick = {
                                        when {
                                            m.mine && m.status == St.FAILED -> core.retry(m.id)
                                            !m.mine && m.status == St.DOWNLOAD_FAILED -> core.retry(m.id)
                                            !m.mine && m.hiddenUntilOpened -> {
                                                // wiadomość czasowa: treść pojawia się dopiero teraz i od teraz liczy się czas
                                                if (m.media == null || m.path != null) {
                                                    core.openViewOnce(m.id)
                                                    when (m.kind) {
                                                        Kind.IMAGE, Kind.VIDEO -> nav.go(Screen.Viewer(m.id))
                                                        Kind.VOICE -> m.path?.let { VoicePlayer.toggle(m.id, it) }
                                                    }
                                                }
                                            }
                                            m.mine && m.xmode == XMode.AFTER_OPEN && m.path == null -> {}
                                            m.kind == Kind.VIDEO && m.ex.round && m.path != null -> RoundPlayer.toggle(m.id, m.path)
                                            // GIF: podgląd z przyciskiem „Zapisz w moich”
                                            m.kind == Kind.IMAGE && m.media?.mime == "image/gif" && m.path != null && m.xmode == XMode.OFF -> gifFor = File(m.path) to m
                                            (m.kind == Kind.IMAGE || m.kind == Kind.VIDEO) && m.path != null -> nav.go(Screen.Viewer(m.id))
                                            m.kind == Kind.UPDATE && m.path != null -> installApk(ctx, m.path)
                                            m.kind == Kind.FILE && m.path != null -> openFile(ctx, m.path, m.media?.mime ?: "")
                                            m.kind == Kind.LOCATION -> Loc.parse(m.body)?.let { openMap(ctx, it.lat, it.lon, nameFor(m.sender)) }
                                        }
                                    },
                                    onLongPress = { actionsFor = m },
                                ) }
                            }
                        }
                    }
                }
            }
            if ((chat?.xmode ?: 0) != XMode.OFF) {
                Text(
                    "⏱ " + (if (chat!!.xmode == XMode.AFTER_SEND) "Automatyczne usuwanie: wiadomości znikają ${formatTtl(chat!!.ttl)} po wysłaniu"
                    else "Wiadomości czasowe: widać je po dotknięciu, znikają ${formatTtl(chat!!.ttl)} po otwarciu"),
                    Modifier.fillMaxWidth().clickable { timerDialog = true }.background(Fire.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = Fire,
                )
            }
            if (!scheduled.isNullOrEmpty()) ScheduledBar(scheduled!!.size) { scheduledDialog = true }
            preparing?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (canSend == null) {
                editing?.let { e -> EditBar(e) { editing = null; text = draftBeforeEdit; draftBeforeEdit = "" } }
                if (editing == null) replyTo?.let { r -> ReplyBar(core.replyOf(r), nameFor(r.sender)) { replyTo = null } }
                MentionSuggestions(suggestions) { pk, name -> pickMention(pk, name) }
            }
            if (canSend != null) {
                Text(canSend, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (recMode == 2 && recorder != null) {
                LockedRecordingBar(recElapsed, amps, voiceTimed, { voiceTimed = !voiceTimed }, { finishRecording(false) }, { finishRecording(true) })
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    if (recMode == 1 && recorder != null) {
                        HoldRecordingIndicator(recElapsed, amps, dragX, cancelPx, Modifier.weight(1f))
                    } else {
                        if (editing == null) Box {
                            IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, "Załącz") }
                            DropdownMenu(attachMenu, { attachMenu = false }) {
                                DropdownMenuItem({ Text("Zdjęcia z galerii (do 10)") }, {
                                    attachMenu = false
                                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }, leadingIcon = { Icon(Icons.Default.Image, null) })
                                DropdownMenuItem({ Text("Zrób zdjęcie") }, {
                                    attachMenu = false
                                    withCamera { try { takePhoto.launch(fileUri(ctx, newCamFile("jpg"))) } catch (e: Exception) { toast(ctx, "Nie znaleziono aplikacji aparatu") } }
                                }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) })
                                DropdownMenuItem({ Text("Wideo z galerii") }, {
                                    attachMenu = false
                                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                }, leadingIcon = { Icon(Icons.Default.VideoLibrary, null) })
                                DropdownMenuItem({ Text("Nagraj wideo") }, {
                                    attachMenu = false
                                    withCamera { try { recordVideo.launch(fileUri(ctx, newCamFile("mp4"))) } catch (e: Exception) { toast(ctx, "Nie znaleziono aplikacji aparatu") } }
                                }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                                DropdownMenuItem({ Text("Notatka wideo (okrągła)") }, {
                                    attachMenu = false
                                    openVideoNote()
                                }, leadingIcon = { Icon(Icons.Default.Face, null) })
                                DropdownMenuItem({ Text("Plik") }, {
                                    attachMenu = false
                                    pickFile.launch(arrayOf("*/*"))
                                }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) })
                                DropdownMenuItem({ Text("GIF") }, { attachMenu = false; gifPicker = true }, leadingIcon = { Icon(Icons.Default.Gif, null) })
                                DropdownMenuItem({ Text("🎮 Gra") }, { attachMenu = false; gamePicker = true })
                                DropdownMenuItem({ Text("Ankieta") }, { attachMenu = false; pollDialog = true }, leadingIcon = { Icon(Icons.Default.BarChart, null) })
                                DropdownMenuItem({ Text("Lokalizacja") }, { attachMenu = false; locationDialog = true }, leadingIcon = { Icon(Icons.Default.LocationOn, null) })
                            }
                        } else Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            text,
                            { v ->
                                if (v.length > text.length && v.isNotBlank() && editing == null) core.sendTyping(chatId)
                                text = v.take(10_000)
                            },
                            Modifier.weight(1f).heightIn(max = 160.dp),
                            placeholder = { Text(if (editing != null) "Nowa treść" else "Wiadomość") }, shape = RoundedCornerShape(24.dp), maxLines = 6,
                            trailingIcon = if (editing == null && text.isBlank()) ({
                                TextButton(onClick = { gifPicker = true }, modifier = Modifier.semantics { contentDescription = "Wybierz GIF" }) { Text("GIF", fontWeight = FontWeight.Bold) }
                            }) else null,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    if ((text.isNotBlank() || editing != null) && recMode == 0) {
                        Box {
                            // dotknięcie = wyślij, przytrzymanie = czasowa albo zaplanuj
                            Surface(
                                shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp).size(44.dp)
                                    .combinedClickable(onClick = { sendTextNow() }, onLongClick = { if (editing == null) sendMenu = true }),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (editing != null) Icon(Icons.Default.Check, "Zapisz zmianę", tint = MaterialTheme.colorScheme.onPrimary)
                                    else Icon(Icons.AutoMirrored.Filled.Send, "Wyślij (przytrzymaj: czasowa lub zaplanuj)", tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            DropdownMenu(sendMenu, { sendMenu = false }) {
                                DropdownMenuItem({ Text("🕓 Zaplanuj wysłanie…") }, {
                                    sendMenu = false
                                    schedulePick = { at -> scheduleText(at) }
                                })
                                if (chat?.isGroup == false && !official) DropdownMenuItem({ Text("🟢 Wyślij, gdy będzie online") }, {
                                    sendMenu = false
                                    val t = text.trim()
                                    if (t.isNotEmpty()) {
                                        text = ""
                                        val r = replyTo; replyTo = null
                                        val s = core.scheduleWhenOnline(chatId, t, r)
                                        toast(ctx, if (s == null) "Rozmówca jest teraz w aplikacji — wysłano" else "Wyślę, gdy rozmówca otworzy Szepta")
                                    }
                                })
                                HorizontalDivider()
                                Text(
                                    "Wiadomość czasowa — odbiorca zobaczy ją po dotknięciu, potem zniknie po:",
                                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 260.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                listOf(5, 10, 30, 60).forEach { sec ->
                                    DropdownMenuItem({ Text("⏱ Czasowa — ${formatTtl(sec)} po otwarciu") }, {
                                        sendMenu = false; sendTextNow(XMode.AFTER_OPEN, sec)
                                    })
                                }
                            }
                        }
                    } else {
                        MicButton(
                            holding = recMode == 1 && recorder != null,
                            dragX = dragX,
                            cancelDistancePx = cancelPx,
                            lockDistancePx = lockPx,
                            onStart = { if (startRecording()) { recMode = 1; dragX = 0f; true } else false },
                            onDrag = { dragX = it.coerceAtMost(0f) },
                            onCancel = { finishRecording(false) },
                            onLock = { recMode = 2; dragX = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onRelease = { quick -> if (quick) { recMode = 2; dragX = 0f } else finishRecording(true) },
                        )
                    }
                }
            }
        }
    }

    if (timerDialog) TimerDialog(chat?.xmode ?: 0, chat?.ttl ?: 10, { timerDialog = false }) { mode, ttl ->
        core.setChatTimer(chatId, mode, ttl)
        timerDialog = false
    }

    actionsFor?.let { m ->
        MessageActionsDialog(
            m, group, canInteract, pinned?.any { it.id == m.id } == true,
            onDismiss = { actionsFor = null },
            onReply = if (canSend == null) ({ replyTo = m; editing = null; actionsFor = null }) else null,
            onEdit = {
                if (editing == null) draftBeforeEdit = text
                editing = m; replyTo = null; text = m.body; actionsFor = null
            },
            onForward = { actionsFor = null; nav.go(Screen.Forward(m.id)) },
            onInfo = { infoFor = m; actionsFor = null },
            onVoters = { votersFor = m; actionsFor = null },
            onSave = { saveToGallery(m); actionsFor = null },
            onSaveGif = if (m.media?.mime == "image/gif" && m.path != null && m.xmode == XMode.OFF) ({
                actionsFor = null
                work.launch(Dispatchers.IO) { try { Gifs.save(ctx, File(m.path!!)); toast(ctx, "Zapisano w Twoich GIF-ach") } catch (e: Exception) { toast(ctx, sendError(e)) } }
            }) else null,
            onPickPeople = { pickDeleteFor = m; actionsFor = null },
            onTranscribe = if (pl.szept.core.Transcriber.available(ctx)) ({ actionsFor = null; transcribe(m) }) else null,
            onTranslate = {
                actionsFor = null
                translation = m.id to null
                work.launch {
                    translation = try {
                        val r = pl.szept.core.MessageTranslator.translate(pl.szept.data.Db.hideSpoilers(m.body))
                        m.id to ("${pl.szept.core.MessageTranslator.languageName(r.from)} → ${pl.szept.core.MessageTranslator.languageName(r.to)}\n\n" + r.text)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        m.id to ("⚠ " + (e.message ?: "Nie udało się przetłumaczyć"))
                    }
                }
            },
        )
    }
    reactionsFor?.let { (m, l) -> ReactionsDialog(m, l) { reactionsFor = null } }
    infoFor?.let { m -> group?.let { gg -> ReadByDialog(m, gg) { infoFor = null } } ?: run { infoFor = null } }
    votersFor?.let { m -> VotersDialog(m, votes?.get(m.id) ?: emptyList()) { votersFor = null } }
    // podczas edycji zdjęcia okno podglądu jest ukryte (edytor ma cały ekran dla siebie)
    if (schedulePick == null) preview?.let { (kind, att) ->
        MediaPreviewDialog(
            kind, att, text.trim(), chat?.xmode ?: XMode.OFF, chat?.ttl ?: 10,
            onCancel = { att.file.delete(); preview = null },
            onSchedule = { caption ->
                // podgląd zostaje (ukryty), dopóki nie wybierzesz godziny — anulowanie do niego wraca
                schedulePick = { at -> preview = null; text = ""; scheduleAtts(at, kind, listOf(att), caption) }
            },
        ) { caption, xmode, ttl ->
            preview = null
            text = ""
            val r = replyTo
            replyTo = null
            work.launch {
                try { core.send(chatId, kind, caption, att, xmode, ttl, r) } catch (e: Exception) { toast(ctx, sendError(e)) }
            }
        }
    }
    if (schedulePick == null) albumPreview?.let { atts ->
        AlbumPreviewDialog(
            atts, text.trim(), chat?.xmode ?: XMode.OFF, chat?.ttl ?: 10,
            onCancel = { atts.forEach { it.file.delete() }; albumPreview = null },
            onSend = { caption, xmode, ttl -> albumPreview = null; text = ""; sendAlbum(atts, caption, xmode, ttl) },
            onSchedule = { caption ->
                schedulePick = { at -> albumPreview = null; text = ""; scheduleAtts(at, Kind.IMAGE, atts, caption) }
            },
        )
    }
    if (gamePicker) GamePickerDialog(onDismiss = { gamePicker = false }) { type ->
        gamePicker = false
        work.launch { try { core.startGame(chatId, type) } catch (e: Exception) { toast(ctx, sendError(e)) } }
    }
    translation?.let { (_, res) ->
        AlertDialog(
            onDismissRequest = { translation = null },
            title = { Text("🌐 Tłumaczenie") },
            text = {
                if (res == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text("Tłumaczę w telefonie… (za pierwszym razem pobieram pakiet języka)")
                } else androidx.compose.foundation.text.selection.SelectionContainer { Text(res) }
            },
            confirmButton = { TextButton(onClick = { translation = null }) { Text("Zamknij") } },
            dismissButton = { if (res != null && !res.startsWith("⚠")) TextButton(onClick = { copyText(ctx, res.substringAfter("\n\n")) }) { Text("Kopiuj") } },
        )
    }
    if (gifPicker) GifPickerDialog(onDismiss = { gifPicker = false }) { f ->
        gifPicker = false
        val r = replyTo
        replyTo = null
        work.launch {
            try { core.send(chatId, Kind.IMAGE, "", Gifs.attachment(ctx, f), null, null, r) }
            catch (e: Exception) { toast(ctx, sendError(e)) }
        }
    }
    gifFor?.let { (f, m) ->
        GifPreviewDialog(f, null, saved = remember(f) { Gifs.isSaved(ctx, f) }, onClose = { gifFor = null },
            onSave = { src -> work.launch(Dispatchers.IO) { try { Gifs.save(ctx, src); toast(ctx, "Zapisano w Twoich GIF-ach") } catch (e: Exception) { toast(ctx, sendError(e)) } } },
            onSend = null)
    }
    schedulePick?.let { pick ->
        ScheduleDialog(onDismiss = { schedulePick = null }) { at -> schedulePick = null; pick(at) }
    }
    if (videoNoteDialog) VideoNoteDialog(onDismiss = { videoNoteDialog = false }) { file, dur ->
        videoNoteDialog = false
        sendVideoNote(file, dur)
    }
    if (pollDialog) PollDialog(onDismiss = { pollDialog = false }) { poll ->
        pollDialog = false
        work.launch { try { core.sendPoll(chatId, poll) } catch (e: Exception) { toast(ctx, sendError(e)) } }
    }
    if (locationDialog) LocationDialog(onDismiss = { locationDialog = false }) { liveMs -> locationDialog = false; sendLocation(liveMs) }
    if (scheduledDialog) ScheduledListDialog(scheduled ?: emptyList()) { scheduledDialog = false }
    pickDeleteFor?.let { m ->
        val gg = group
        if (gg != null) DeleteForPeopleDialog(m, gg) { pickDeleteFor = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerDialog(mode0: Int, ttl0: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var mode by remember { mutableIntStateOf(mode0) }
    var ttl by remember { mutableIntStateOf(ttl0) }
    val options = listOf(5, 10, 30, 60, 300, 3600, 86400, 7 * 86400)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiadomości czasowe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable
                fun opt(value: Int, title: String, sub: String) {
                    Row(Modifier.fillMaxWidth().clickable { mode = value }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == value, { mode = value })
                        Column {
                            Text(title, fontWeight = FontWeight.Medium)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                opt(XMode.OFF, "Wyłączone", "Zwykłe wiadomości — zostają, dopóki ktoś ich nie usunie")
                opt(XMode.AFTER_OPEN, "Wiadomości czasowe", "Treść widać dopiero po dotknięciu. Czas liczy się od otwarcia — potem wiadomość znika")
                opt(XMode.AFTER_SEND, "Automatyczne usuwanie", "Wiadomości widać normalnie, a znikają u wszystkich po czasie od wysłania")
                Text(
                    "Wskazówka: pojedynczą wiadomość czasową wyślesz bez zmiany tego ustawienia — przytrzymaj przycisk Wyślij. " +
                        "Przy zdjęciach i wideo wybierz ⏱ Czasowe w podglądzie, a przy głosówce — ⏱ Czasowa podczas nagrywania.",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode != XMode.OFF) {
                    Spacer(Modifier.size(8.dp))
                    Text("Czas:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { o -> FilterChip(ttl == o, { ttl = o }, { Text(formatTtl(o)) }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mode, ttl) }) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun DeleteForPeopleDialog(m: Message, g: Group, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val selected = remember { mutableStateListOf<String>() }
    var alsoMe by remember { mutableStateOf(false) }
    val others = g.members.filter { it.pk != App.core.myPub }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usuń u wybranych") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                others.forEach { mem ->
                    Row(Modifier.fillMaxWidth().clickable { if (mem.pk in selected) selected.remove(mem.pk) else selected.add(mem.pk) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(mem.pk in selected, { on -> if (on) selected.add(mem.pk) else selected.remove(mem.pk) })
                        Text(mem.name.ifBlank { App.core.db.nameOf(mem.pk) })
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { alsoMe = !alsoMe }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoMe, { alsoMe = it }); Text("Usuń także u mnie")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (selected.isNotEmpty() || alsoMe) {
                    App.core.deleteFor(m.id, selected.toList(), alsoMe)
                    toast(ctx, "Usunięto u ${selected.size} os." + if (alsoMe) " i u Ciebie" else "")
                }
                onDismiss()
            }) { Text("Usuń") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/** Pełnoekranowy podgląd zdjęć i wideo z rozmowy — przesuwanie palcem między nimi. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(nav: Nav, msgId: String, act: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m0 by rememberDb(msgId) { App.core.db.message(msgId) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(m0) { if (m0 != null) seen = true else if (seen) nav.back() }
    // wiadomości już nie ma (usunięta / wygasła) — nie zostawiamy pustego ekranu
    LaunchedEffect(msgId) {
        val exists = withContext(Dispatchers.IO) { App.core.db.message(msgId) != null }
        if (!exists) nav.back()
    }
    val first = m0 ?: return
    val ephemeral = first.xmode != XMode.OFF
    // czasowych zdjęć i filmów nie da się utrwalić zrzutem ekranu
    DisposableEffect(ephemeral) { if (ephemeral) act.setSecure(true); onDispose { if (ephemeral) act.setSecure(false) } }
    // zwykłe zdjęcia i wideo z tej rozmowy (czasowe — tylko pojedynczo)
    val items by rememberDb(first.chat, ephemeral) {
        if (ephemeral) listOf(first) else App.core.db.chatMedia(first.chat, listOf(Kind.IMAGE, Kind.VIDEO)).filter { it.path != null }.reversed()
            // bardzo stare zdjęcie spoza ostatnich 2000 — pokazujemy właśnie je, nie pierwsze z listy
            .let { l -> if (l.none { it.id == msgId }) listOf(first) else l }
    }
    val list = items ?: return
    if (list.isEmpty()) { if (ephemeral) return else { LaunchedEffect(Unit) { nav.back() }; return } }
    val startIdx = remember { list.indexOfFirst { it.id == msgId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIdx) { list.size }
    val current = list.getOrNull(pager.currentPage) ?: first
    var pendingSave by remember { mutableStateOf(false) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingSave) scope.launch {
            toast(ctx, if (MediaUtil.saveToGallery(ctx, File(current.path ?: ""), current.media?.mime ?: "image/jpeg")) "Zapisano w galerii" else "Nie udało się")
        }
        pendingSave = false
    }
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }) { page ->
                val msg = list[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val path = msg.path
                    if (path != null) {
                        if (msg.kind == Kind.VIDEO) {
                            if (pager.currentPage == page) VideoPlayer(path, Modifier.align(Alignment.Center))
                            else {
                                remember(msg.media?.thumb) { msg.media?.thumb?.let { Images.thumb(it) } }?.let { BitmapImage(it, Modifier.fillMaxSize(), crop = false) }
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
                            }
                        } else if (msg.media?.mime == "image/gif") GifImage(path, Modifier.fillMaxSize(), crop = false)
                        else ZoomableImage(path)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(top = 32.dp, start = 4.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(if (current.mine) "Ty" else App.core.db.nameOf(current.sender), color = Color.White, fontWeight = FontWeight.Medium)
                    Text(
                        formatListTime(current.ts) + " " + formatTime(current.ts) + if (list.size > 1) "  ·  ${pager.currentPage + 1}/${list.size}" else "",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (current.expires > 0) Countdown(current.expires)
                val path = current.path
                if (!ephemeral && path != null) {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT < 29 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) { pendingSave = true; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                        else scope.launch { toast(ctx, if (MediaUtil.saveToGallery(ctx, File(path), current.media?.mime ?: "image/jpeg")) "Zapisano w galerii" else "Nie udało się") }
                    }) { Text("Zapisz", color = Color.White) }
                    TextButton(onClick = { shareFile(ctx, path, current.media?.mime ?: "") }) { Text("Udostępnij", color = Color.White) }
                }
            }
        }
    }
}

/**
 * Zdjęcie z powiększaniem: dwa palce = przybliżanie, podwójne dotknięcie = 2,5×.
 * Bez powiększenia przesunięcie jednym palcem przechodzi do sąsiedniego zdjęcia.
 */
@Composable
fun ZoomableImage(path: String) {
    val bmp = rememberImage(path, 2048) ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BitmapImage(
        bmp,
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinch = event.changes.count { it.pressed } >= 2
                        if (pinch || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        crop = false,
    )
}

/** Komunikat dla użytkownika po nieudanym wysłaniu (także dla błędów dysku itp.). */
fun sendError(e: Throwable): String = when (e) {
    is Core.SendException -> e.message ?: "Błąd"
    is java.io.IOException -> "Nie udało się zapisać pliku — sprawdź wolne miejsce w telefonie"
    else -> "Nie udało się: " + (e.message ?: e.javaClass.simpleName)
}
