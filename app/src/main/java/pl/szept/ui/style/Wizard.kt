package pl.szept.ui.style

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.szept.ui.ACCENTS
import pl.szept.ui.ACCENT_NAMES
import pl.szept.ui.UiPrefs

/*
 * Kreator wyglądu — pokazywany po instalacji i raz po aktualizacji do wersji z nowymi stylami.
 * Prowadzi przez: tryb (jasny/ciemny), styl, animacje (pełne/delikatne/wyłączone, charakter i tempo), kolor i tekst.
 * Każdy wybór działa od razu — kreator sam zmienia wygląd na żywo.
 */

/** Wersja kreatora (kod wersji aplikacji, w której się pojawił). */
const val WIZARD_VERSION = 24

/** W testach kreator jest domyślnie wyłączony (testy zaczynają od listy rozmów). */
object WizardGate {
    @Volatile var enabledInTests = false
}

/** Czy pokazać kreator przy starcie. */
fun shouldShowWizard(): Boolean =
    UiPrefs.wizard < WIZARD_VERSION && (Build.FINGERPRINT != "robolectric" || WizardGate.enabledInTests)

/** Poziom animacji wybierany w kreatorze. */
private enum class AnimLevel(val title: String, val desc: String, val icon: ImageVector) {
    FULL("Pełne", "Wszystkie przejścia, efekty i ruchome tła", Icons.Default.AutoAwesome),
    GENTLE("Delikatne", "Płynne przejścia, bez ciągłego ruchu i efektów w tle", Icons.Default.Spa),
    OFF("Wyłączone", "Bez żadnego ruchu — szybko i oszczędnie", Icons.Default.MotionPhotosOff),
}

/** Animacje wyłączane w trybie „delikatnym”: wszystko, co trwa cały czas, i iskry. */
private val GENTLE_OFF: Set<String> = Anim.entries.filter { it.continuous && it != Anim.TYPING || it == Anim.SPARKS }.map { it.key }.toSet()

private fun currentLevel(): AnimLevel = when {
    !UiPrefs.anim -> AnimLevel.OFF
    UiPrefs.animOff.containsAll(GENTLE_OFF) -> AnimLevel.GENTLE
    else -> AnimLevel.FULL
}

private fun applyLevel(l: AnimLevel) {
    when (l) {
        AnimLevel.FULL -> { UiPrefs.anim = true; UiPrefs.animOff = AnimFlags.DEFAULT_OFF }
        AnimLevel.GENTLE -> { UiPrefs.anim = true; UiPrefs.animOff = GENTLE_OFF }
        AnimLevel.OFF -> UiPrefs.anim = false
    }
}

private const val PAGES = 6

/** Kreator wyglądu na cały ekran. [onDone] — zakończono albo pominięto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceWizard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val st = LocalStyle.current
    val anim = LocalAnim.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    fun save() = UiPrefs.save(ctx)
    BackHandler(page > 0) { page-- }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize().background(st.pal.base.copy(alpha = 0.55f)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics { contentDescription = "Krok ${page + 1} z $PAGES" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES) { i ->
                    val w by animateDpAsState(if (i == page) 22.dp else 8.dp, label = "kropka")
                    Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape).background(if (i <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            if (page < PAGES - 1) TextButton(onClick = onDone) { Text("Pomiń") }
        }
        AnimatedContent(
            page, Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (!anim[Anim.SCREENS]) EnterTransition.None togetherWith ExitTransition.None
                else {
                    val forward = targetState > initialState
                    val d = anim.ms(st.motion.duration.coerceIn(220, 450))
                    (slideInHorizontally(tween(d, easing = st.motion.easing)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(d))) togetherWith
                        (slideOutHorizontally(tween(d, easing = st.motion.easing)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(d / 2)))
                }
            },
            label = "kreator",
        ) { p ->
            when (p) {
                0 -> WelcomePage()
                1 -> ModePage { save() }
                2 -> StylePage { save() }
                3 -> MotionPage { save() }
                4 -> ColorPage { save() }
                else -> DonePage()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (page) {
                0 -> TextButton(onClick = { UiPrefs.style = "classic"; save(); onDone() }) { Text("Zostaw klasyczny") }
                else -> OutlinedButton(onClick = { page-- }) { Text("Wstecz") }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (page < PAGES - 1) page++ else onDone() }) {
                Text(
                    when (page) {
                        0 -> "Zaczynamy"
                        PAGES - 1 -> "Gotowe"
                        else -> "Dalej"
                    },
                )
            }
        }
    }
    }
}

@Composable
private fun PageTitle(title: String, sub: String) {
    Text(title, Modifier.padding(horizontal = 20.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Text(sub, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomePage() {
    val anim = LocalAnim.current
    val fan = listOf("telegram", "liquid_sky", "nothing")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            fan.forEachIndexed { i, id ->
                val enter = remember { Animatable(if (anim[Anim.APPEAR]) 0f else 1f) }
                LaunchedEffect(Unit) {
                    // karty wjeżdżają po kolei, z lekkim „przeskokiem” na końcu
                    enter.animateTo(1f, tween(anim.ms(620), delayMillis = anim.ms(110 * i), easing = androidx.compose.animation.core.CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)))
                }
                val angle = (i - 1) * 11f
                StylePreview(
                    previewState(styleById(id)),
                    Modifier.size(width = 128.dp, height = 196.dp)
                        .graphicsLayer {
                            rotationZ = angle * enter.value
                            translationX = (i - 1) * 78.dp.toPx() * enter.value
                            translationY = (1f - enter.value) * 120.dp.toPx() + if (i == 1) -8.dp.toPx() else 6.dp.toPx()
                            alpha = (enter.value * 1.6f).coerceIn(0f, 1f)
                            shadowElevation = 10.dp.toPx()
                            shape = RoundedCornerShape(18.dp)
                            clip = true
                        },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Nowy wygląd Szepta", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            "${STYLES.size} stylów wzorowanych na prawdziwych telefonach, komunikatorach i komputerach — ze szkłem, które rozmywa treść pod spodem, " +
                "dymkami z ogonkami, paskami i polem wiadomości jak w oryginałach. Do tego nowe animacje, każda do wyłączenia.\n\n" +
                "Ustawmy wygląd w kilku krokach. Wszystko zmienisz później w Ustawieniach → Styl i animacje.",
            Modifier.padding(horizontal = 24.dp, vertical = 10.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModePage(save: () -> Unit) {
    val st = LocalStyle.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Jasny czy ciemny?", "Tryb działa we wszystkich stylach, które mają obie wersje.")
        val options = listOf(
            Triple(0, "Jak w telefonie", Icons.Default.PhoneAndroid),
            Triple(1, "Jasny", Icons.Default.LightMode),
            Triple(2, "Ciemny", Icons.Default.DarkMode),
            Triple(3, "Czarny (AMOLED)", Icons.Default.Nightlight),
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { (v, t, icon) ->
                val sys = androidx.compose.foundation.isSystemInDarkTheme()
                val dark = when (v) { 1 -> false; 2, 3 -> true; else -> sys }
                val pal = remember(st.spec, dark, v, UiPrefs.accent) { st.spec.palette(if (st.spec.mode == Mode.FOLLOW) dark else st.spec.mode == Mode.DARK, UiPrefs.accent, v == 3) }
                val prev = remember(st.spec, pal) { StyleState(st.spec, pal) }
                OptionCard(UiPrefs.theme == v, { UiPrefs.theme = v; save() }, Modifier.width(150.dp)) {
                    StylePreview(prev, Modifier.fillMaxWidth().height(150.dp))
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (st.spec.mode != Mode.FOLLOW) Text(
            "Styl „${st.spec.name}” jest zawsze ${if (st.dark) "ciemny" else "jasny"} — tryb zadziała w innych stylach.",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StylePage(save: () -> Unit) {
    val st = LocalStyle.current
    Column(Modifier.fillMaxSize()) {
        PageTitle("Wybierz styl", "Dotknij, aby wypróbować — cała aplikacja zmienia się od razu. Teraz: „${st.spec.name}”.")
        StyleGrid(st.spec.id, Modifier.weight(1f).fillMaxWidth()) { spec -> UiPrefs.style = spec.id; save() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MotionPage(save: () -> Unit) {
    val st = LocalStyle.current
    val ctx = LocalContext.current
    val phoneOff = remember { systemAnimScale(ctx) == 0f }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Animacje", "Każdy styl ma własny charakter ruchu — podgląd poniżej odtwarza się sam. Pojedyncze animacje wyłączysz później w „Animacjach zaawansowanych”.")
        // żywy podgląd: przejście ekranów, wjazd wiadomości i dotknięcie — zmienia się od razu po wyborze
        MotionPreview(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (phoneOff) Text(
            "Telefon ma wyłączone animacje (ustawienia lub oszczędzanie energii). W Szepcie działają mimo to — zgodnie z wyborem poniżej.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimLevel.entries.forEach { l ->
                OptionCard(currentLevel() == l, { applyLevel(l); save() }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(l.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.title, style = MaterialTheme.typography.titleMedium)
                            Text(l.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (currentLevel() == l) Icon(Icons.Default.Check, "Wybrane", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Text(
            "Charakter ruchu — teraz: ${st.motion.label}", Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        MotionChips(enabled = UiPrefs.anim) { save() }
        Text("Tempo", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SpeedChips(enabled = UiPrefs.anim) { save() }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPage(save: () -> Unit) {
    val st = LocalStyle.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Kolor i tekst", "Dopasuj kolor akcentu i wielkość liter.")
        Text("Kolor", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (!st.spec.accent) Text(
            "Styl „${st.spec.name}” ma własne kolory, jak w oryginale. Wybrany kolor zadziała w stylach: " + STYLES.filter { it.accent }.joinToString { it.name } + ".",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (st.spec.dynamic) Text(
            "Pierwszy kolor oznacza kolory z tapety telefonu (Android 12 i nowsze).",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCENTS.forEachIndexed { i, c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { UiPrefs.accent = i; save() }.padding(4.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(c).semantics { contentDescription = ACCENT_NAMES[i] },
                        contentAlignment = Alignment.Center,
                    ) { if (UiPrefs.accent == i) Icon(Icons.Default.Check, "Wybrany", tint = Color.White) }
                    Text(ACCENT_NAMES[i], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Rozmiar tekstu", Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.85f to "Mały", 1f to "Normalny", 1.15f to "Duży", 1.3f to "Bardzo duży").forEach { (v, t) ->
                FilterChip(UiPrefs.fontScale == v, { UiPrefs.fontScale = v; save() }, { Text(t) })
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))) {
            StyleBackground(Modifier.matchParentSize(), st, st.spec.chatBg ?: st.spec.bg, animate = false)
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                StyledBubble(false) { Text("Tak wygląda tekst w rozmowie.", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    StyledBubble(true) { Text("Czytelnie! 👍", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DonePage() {
    val st = LocalStyle.current
    val anim = LocalAnim.current
    val pop = remember { Animatable(if (anim[Anim.APPEAR]) 0f else 1f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        if (anim[Anim.CHAT_FX]) ScreenFx.play(ScreenFx.Kind.CONFETTI)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(96.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Check, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.height(20.dp))
        Text("Gotowe!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        val mode = when (UiPrefs.theme) { 1 -> "jasny"; 2 -> "ciemny"; 3 -> "czarny (AMOLED)"; else -> "jak w telefonie" }
        val level = currentLevel().title.lowercase()
        val motion = MOTION_PRESETS.getOrNull(UiPrefs.motion)?.takeIf { UiPrefs.motion != 0 }?.first?.lowercase() ?: st.spec.motion.label
        listOf("Styl: ${st.spec.name}", "Tryb: $mode", "Animacje: $level · $motion").forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Zmienisz to w każdej chwili: Ustawienia → Styl i animacje. Kreator uruchomisz ponownie w „Wyglądzie”.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

/** Karta do wyboru (z obwódką, gdy wybrana). */
@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clip(shape).clickable(onClick = onClick),
    ) { Column { content() } }
}
