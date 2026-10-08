package pl.szept.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import pl.szept.App
import pl.szept.core.Links

class NavVm : ViewModel() {
    val nav = Nav()
    var unlocked = false
    var lastPause = 0L
}

class MainActivity : FragmentActivity() {
    private val vm: NavVm by viewModels()
    val nav get() = vm.nav
    private var locked by mutableStateOf(false)
    /** Ekran rozmowy schowany (użytkownik wrócił do czatów w trakcie rozmowy). */
    var callHidden by mutableStateOf(false)
    /** Odbierz z powiadomienia — ekran rozmowy poprosi o uprawnienia i odbierze. */
    var answerRequest by mutableStateOf(false)
    /** Dołącz do rozmowy grupowej z powiadomienia. */
    var groupJoinRequest by mutableStateOf(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        locked = App.core.hasIdentity && App.core.lockEnabled && !vm.unlocked
        UiPrefs.load(this)
        setSecure(false)
        setContent { SzeptTheme { Root(this) } }
        // przychodzące połączenie: pokaż się nad ekranem blokady i zapal ekran
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(App.core.calls.state, App.core.groupCalls.state) { c, g -> c to g }.collect { (c, g) ->
                val on = c?.active == true || g?.active == true
                if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on) }
                else @Suppress("DEPRECATION") {
                    val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    if (on) window.addFlags(f) else window.clearFlags(f)
                }
                when (c?.phase) {
                    pl.szept.call.CallManager.Phase.RINGING, pl.szept.call.CallManager.Phase.DIALING -> callHidden = false
                    // zakończona w tle (ekran schowany) — nie ma czego pokazywać
                    pl.szept.call.CallManager.Phase.ENDED -> if (callHidden) App.core.calls.clearEnded()
                    else -> {}
                }
                when (g?.phase) {
                    pl.szept.call.GroupCallManager.Phase.RINGING -> if (c?.active != true) callHidden = false
                    pl.szept.call.GroupCallManager.Phase.ENDED -> if (callHidden) App.core.groupCalls.clearEnded()
                    else -> {}
                }
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        vm.lastPause = SystemClock.elapsedRealtime()
    }

    /** Android 14+: system informuje o zrzucie ekranu — wysyłamy informację uczestnikom otwartej rozmowy. */
    private var shotCallback: Any? = null

    override fun onStart() {
        super.onStart()
        if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("shotwatch", false)) startShotWatcher()
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val cb = ScreenCaptureCallback { App.core.activeChat?.let { App.core.onScreenshot(it) } }
                registerScreenCaptureCallback(mainExecutor, cb)
                shotCallback = cb
            } catch (e: Exception) { android.util.Log.w("Szept", "zrzuty ekranu", e) }
        }
    }

    /**
     * Android 13 i starsze nie mają systemowego powiadomienia o zrzucie ekranu — patrzymy, czy w galerii
     * pojawił się nowy plik „Screenshot…” (wymaga zgody na dostęp do zdjęć; włącza się w Prywatności).
     */
    private var shotObserver: android.database.ContentObserver? = null
    private var lastShot = 0L

    fun startShotWatcher() {
        if (Build.VERSION.SDK_INT >= 34 || shotObserver != null) return
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return
        val obs = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val chat = App.core.activeChat ?: return
                val now = System.currentTimeMillis()
                if (now - lastShot < 3000) return
                try {
                    contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME, android.provider.MediaStore.Images.Media.DATE_ADDED),
                        null, null, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC",
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val name = c.getString(0)?.lowercase() ?: ""
                            val added = c.getLong(1) * 1000
                            if (name.contains("screenshot") && now - added < 15_000) { lastShot = now; App.core.onScreenshot(chat) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        shotObserver = obs
    }

    override fun onStop() {
        shotObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        shotObserver = null
        if (Build.VERSION.SDK_INT >= 34) {
            (shotCallback as? ScreenCaptureCallback)?.let { try { unregisterScreenCaptureCallback(it) } catch (_: Exception) {} }
            shotCallback = null
        }
        super.onStop()
    }

    /** Wybierak zdjęć, aparat, skaner itp. — powrót z nich nie jest „wyjściem z aplikacji”, więc nie blokujemy. */
    private var externalLaunch = false

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        externalLaunch = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startIntentSenderForResult(intent: android.content.IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {
        externalLaunch = true
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    override fun onResume() {
        super.onResume()
        if (externalLaunch) { externalLaunch = false; vm.lastPause = 0L }
        // ponowna blokada po czasie bezczynności (autoLockMs; 0 = natychmiast)
        if (App.core.hasIdentity && App.core.lockEnabled && vm.unlocked) {
            val idle = SystemClock.elapsedRealtime() - vm.lastPause
            if (vm.lastPause > 0 && idle >= App.core.autoLockMs) { vm.unlocked = false; locked = true }
        }
    }

    fun isLocked() = locked
    fun unlock() { vm.unlocked = true; locked = false }
    fun relock() { vm.unlocked = false; locked = App.core.lockEnabled }

    /** Po wpisaniu kodu-wipe, który usunął konto — wróć do ekranu powitalnego. */
    fun onWiped() {
        vm.unlocked = true
        locked = false
        nav.home()
        (application as App).resetCore()
        recreate()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getIntExtra("call_answer", 0) == 1) { answerRequest = true; callHidden = false; i.removeExtra("call_answer") }
        if (i.getIntExtra("call", 0) == 1) { callHidden = false; i.removeExtra("call") }
        if (i.getIntExtra("gcall_join", 0) == 1) { groupJoinRequest = true; callHidden = false; i.removeExtra("gcall_join") }
        if (i.getIntExtra("gcall", 0) == 1) { callHidden = false; i.removeExtra("gcall") }
        i.getStringExtra("chat")?.let { nav.home(); nav.go(Screen.Chat(it)); i.removeExtra("chat") }
        i.data?.takeIf { it.scheme == "szept" }?.let { nav.go(Screen.AddContact(it.toString())) }
        if (i.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { if (Links.parse(it) != null) nav.go(Screen.AddContact(it)) }
        }
    }

    /** Blokada zrzutów ekranu: na życzenie ekranu albo zawsze (ustawienie „Zakaz zrzutów ekranu”). */
    fun setSecure(on0: Boolean) {
        val on = on0 || UiPrefs.noShots
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun askNotifications() {
        if (hasNotificationPermission(this)) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) {}
        }
    }

    companion object {
        fun hasNotificationPermission(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun ignoresBattery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

        @SuppressLint("BatteryLife")
        fun askBattery(ctx: Context) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            } catch (e: Exception) {
                try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
        }
    }
}

@Composable
fun Root(act: MainActivity) {
    var hasId by remember { mutableStateOf(App.core.hasIdentity) }
    // administrator usunął to konto — kasujemy je i wracamy do ekranu powitalnego
    val deletedByAdmin by App.core.adminDeleted.collectAsState()
    androidx.compose.runtime.LaunchedEffect(deletedByAdmin) {
        if (deletedByAdmin) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { App.core.wipeByAdmin() }
            act.onWiped()
        }
    }
    if (!hasId) {
        OnboardingScreen {
            (act.application as App).startBackground()
            act.getSharedPreferences("szept_notice", Context.MODE_PRIVATE).edit().remove("deleted").apply()
            // nowe konto — „Co nowego” nie ma sensu
            act.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("seen_version", pl.szept.BuildConfig.VERSION_NAME).apply()
            hasId = true
            act.askNotifications()
        }
        return
    }
    // wersja za stara (administrator wymusił aktualizację)
    val pol by App.core.policy.collectAsState()
    if (pol != null && App.core.updateRequired) { ForcedUpdateScreen(); return }
    // rozmowa: ekran nad wszystkim (także nad blokadą PIN-em)
    val call by App.core.calls.state.collectAsState()
    val c = call
    if (c != null && (!act.callHidden || c.phase == pl.szept.call.CallManager.Phase.RINGING)) {
        CallScreen(act, c)
        return
    }
    val gcall by App.core.groupCalls.state.collectAsState()
    val g = gcall
    if (g != null && (!act.callHidden || g.phase == pl.szept.call.GroupCallManager.Phase.RINGING)) {
        GroupCallScreen(act, g)
        return
    }
    if (act.isLocked()) {
        LockScreen(act) { act.unlock() }
        return
    }
    val nav = act.nav
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    // „Co nowego” — raz po każdej aktualizacji
    val uiPrefs = remember { act.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var whatsNew by remember { mutableStateOf(uiPrefs.getString("seen_version", null) != pl.szept.BuildConfig.VERSION_NAME) }
    val notes = CHANGELOG.firstOrNull { it.first == pl.szept.BuildConfig.VERSION_NAME }?.second
    if (whatsNew) {
        fun done() { whatsNew = false; uiPrefs.edit().putString("seen_version", pl.szept.BuildConfig.VERSION_NAME).apply() }
        if (notes == null) done()
        else androidx.compose.material3.AlertDialog(
            onDismissRequest = { done() },
            title = { androidx.compose.material3.Text("Co nowego w wersji ${pl.szept.BuildConfig.VERSION_NAME}") },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    notes.forEach { androidx.compose.material3.Text("• $it", Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { done() }) { androidx.compose.material3.Text("Super!") } },
        )
    }
    val s = nav.current
    Column(Modifier.fillMaxSize()) {
        if (c != null && c.active) CallBanner(c) { act.callHidden = false }
        else if (g != null && g.phase == pl.szept.call.GroupCallManager.Phase.ACTIVE) GroupCallBanner(g) { act.callHidden = false }
        // osobny stan dla każdego ekranu (np. przejście z jednej rozmowy prosto do innej)
        Box(Modifier.weight(1f)) { key(s) { Route(s, nav, act) } }
    }
}

@Composable
private fun Route(s: Screen, nav: Nav, act: MainActivity) {
    when (s) {
        Screen.Home -> HomeScreen(nav)
        is Screen.Chat -> ChatScreen(nav, s.id, act, s.focus)
        is Screen.GroupInfo -> GroupInfoScreen(nav, s.gid)
        is Screen.ContactInfo -> ProfileView(nav, s.pk)
        Screen.NewChat -> NewChatScreen(nav)
        Screen.NewGroup -> PickPeopleScreen(nav, null)
        is Screen.AddMembers -> PickPeopleScreen(nav, s.gid)
        is Screen.AddContact -> AddContactScreen(nav, s.prefill)
        Screen.MyCode -> MyCodeScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        is Screen.Viewer -> ViewerScreen(nav, s.msgId, act)
        Screen.MyProfile -> MyProfileScreen(nav)
        Screen.People -> PeopleScreen(nav)
        Screen.AccessCode -> AccessCodeScreen(nav)
        Screen.Security -> SecurityScreen(nav, act)
        Screen.Archive -> ArchiveScreen(nav)
        Screen.Search -> SearchScreen(nav)
        is Screen.Stories -> StoryViewer(nav, s.pk)
        Screen.Broadcast -> BroadcastScreen(nav)
        is Screen.Forward -> ForwardScreen(nav, s.msgId)
        is Screen.Media -> MediaGalleryScreen(nav, s.chatId)
        Screen.Appearance -> AppearanceScreen(nav)
        Screen.OwnRelay -> OwnRelayScreen(nav)
        Screen.Backup -> BackupScreen(nav)
        Screen.Lan -> LanScreen(nav)
        Screen.Devices -> DevicesScreen(nav)
        Screen.CallSettings -> CallSettingsScreen(nav)
        Screen.Admin -> AdminScreen(nav)
        Screen.WhatsNew -> WhatsNewScreen(nav)
        Screen.Connection -> ConnectionScreen(nav)
        Screen.Help -> HelpScreen(nav)
    }
}
