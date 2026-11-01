package pl.szept.ui.style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.szept.ui.BackBar
import pl.szept.ui.Nav
import pl.szept.ui.Screen
import pl.szept.ui.UiPrefs
import pl.szept.ui.toast

/** Czy wariant jasny/ciemny stylu zgodnie z ustawieniem „Motyw”. */
@Composable
fun darkFor(spec: StyleSpec): Boolean {
    val sys = isSystemInDarkTheme()
    return when (spec.mode) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.FOLLOW -> when (UiPrefs.theme) { 1 -> false; 2, 3 -> true; else -> sys }
    }
}

/** Gotowy stan stylu do podglądu (bez zmiany stylu aplikacji). */
@Composable
fun previewState(spec: StyleSpec): StyleState {
    val dark = darkFor(spec)
    val pal = remember(spec, dark, UiPrefs.accent, UiPrefs.theme) { spec.palette(dark, UiPrefs.accent, UiPrefs.theme == 3) }
    return remember(spec, pal) { StyleState(spec, pal) }
}

/** Galeria stylów z podglądem, pogrupowana: klasyczny, telefony, komunikatory, komputery, inne. */
@Composable
fun StylesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val current = LocalStyle.current
    Scaffold(topBar = { BackBar("Styl aplikacji", nav) { TextButton(onClick = { nav.go(Screen.Animations) }) { Text("Animacje") } } }) { pad ->
        StyleGrid(current.spec.id, Modifier.padding(pad).fillMaxSize(), header = {
            Text(
                "${STYLES.size} wyglądów wzorowanych na prawdziwych telefonach, komunikatorach i komputerach — z ich kształtami, kolorami, " +
                    "paskami, polem wiadomości i własnym charakterem animacji. Dotknij, aby wypróbować: zmiana działa od razu w całej aplikacji. " +
                    "Jasną albo ciemną wersję wybierzesz w „Wyglądzie”.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }) { spec ->
            UiPrefs.style = spec.id
            UiPrefs.save(ctx)
        }
    }
}

/** Siatka stylów w grupach (używana też w kreatorze wyglądu). */
@Composable
fun StyleGrid(selected: String, modifier: Modifier, header: (@Composable () -> Unit)? = null, onPick: (StyleSpec) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(156.dp), modifier,
        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        Category.entries.forEach { cat ->
            val list = STYLES.filter { it.category == cat }
            if (list.isEmpty()) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }, key = "kat_" + cat.name) {
                Text(
                    cat.label, Modifier.padding(top = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                )
            }
            items(list, key = { it.id }) { spec -> StyleCard(spec, spec.id == selected) { onPick(spec) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleCard(spec: StyleSpec, selected: Boolean, onClick: () -> Unit) {
    val st = previewState(spec)
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Column {
            StylePreview(st, Modifier.fillMaxWidth().height(164.dp))
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected) Icon(Icons.Default.Check, "Wybrany", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Text(spec.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                val tags = buildList {
                    if (spec.motion.label.isNotEmpty()) add("ruch: " + spec.motion.label)
                    if (spec.moving) add("ruchome tło")
                    if (spec.dynamic) add("kolory z tapety")
                    when (spec.mode) { Mode.DARK -> add("zawsze ciemny"); Mode.LIGHT -> add("zawsze jasny"); else -> add("jasny i ciemny") }
                    if (spec.accent && !spec.dynamic) add("Twój kolor")
                }
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { t ->
                        Text(
                            t, Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** Miniatura stylu: pasek, rozmowa (seria dymków) i pole wiadomości w kształtach danego stylu. */
@Composable
fun StylePreview(st: StyleState, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        StyleBackground(Modifier.matchParentSize(), st, st.spec.chatBg ?: st.spec.bg, animate = false)
        Column(Modifier.matchParentSize()) {
            MiniBar(st)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.Bottom) {
                MiniBubble(st, false, "Cześć! 👋", first = true, last = false)
                Spacer(Modifier.height(2.dp))
                MiniBubble(st, false, "Jak leci?", first = false, last = true)
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { MiniBubble(st, true, "Super, a u Ciebie? 😄") }
            }
            MiniInput(st)
        }
    }
}

@Composable
private fun MiniBar(st: StyleState) {
    val plain = st.pal.bar.solid(st.pal.base) == st.pal.base
    val text = if (plain) st.pal.onBase else st.pal.bar.text
    when (st.spec.chrome) {
        Chrome.GLASS -> Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).liquidGlass(glassLookFor(st.dark), backdrop = null))
            Spacer(Modifier.weight(1f))
            Text("Ania", Modifier.liquidGlass(glassLookFor(st.dark), backdrop = null).padding(horizontal = 8.dp, vertical = 2.dp), color = st.pal.onBase, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(20.dp).liquidGlass(glassLookFor(st.dark), backdrop = null))
        }
        Chrome.IOS6 -> Box(Modifier.fillMaxWidth().height(28.dp).styleTopBar(st), contentAlignment = Alignment.Center) {
            Text("Ania", style = ios6Title(12))
        }
        else -> Row(Modifier.fillMaxWidth().height(28.dp).styleTopBar(st).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.spec.chrome != Chrome.TERMINAL) Box(Modifier.size(16.dp).clip(st.spec.avatar.shape()).background(Color(0xFFE57373)))
            Spacer(Modifier.width(6.dp))
            Text(if (st.spec.chrome == Chrome.TERMINAL) "> ania" else "Ania", color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = st.spec.font)
        }
    }
}

@Composable
private fun MiniInput(st: StyleState) {
    val primary = st.pal.primary
    Row(
        Modifier.fillMaxWidth().height(28.dp).then(if (st.spec.input == InputStyle.GLASS) Modifier else Modifier.styleInputBar(st))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (st.spec.input) {
            InputStyle.GLASS -> {
                Box(Modifier.size(20.dp).liquidGlass(glassLookFor(st.dark), backdrop = null))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).height(20.dp).liquidGlass(glassLookFor(st.dark), backdrop = null))
            }
            InputStyle.MESSENGER -> {
                repeat(3) { Box(Modifier.padding(end = 4.dp).size(10.dp).clip(CircleShape).background(primary)) }
                Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(if (st.dark) Color(0xFF3A3B3C) else Color(0xFFF0F2F5)))
                Spacer(Modifier.width(4.dp))
                Text("👍", fontSize = 11.sp)
            }
            InputStyle.WINDOW -> {
                Box(Modifier.weight(1f).height(18.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(width = 34.dp, height = 18.dp).background(Brush.verticalGradient(listOf(Color.White, Color(0xFFDCDCDC)))).border(1.dp, Color(0xFF707070)), contentAlignment = Alignment.Center) {
                    Text("Wyślij", fontSize = 7.sp, color = Color.Black)
                }
            }
            InputStyle.IOS6 -> {
                Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.White).border(1.dp, Color(0xFF8F99A8), RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(width = 30.dp, height = 18.dp).clip(RoundedCornerShape(9.dp)).background(Brush.verticalGradient(listOf(Color(0xFF8CBDFB), Color(0xFF2668D8)))), contentAlignment = Alignment.Center) {
                    Text("Wyślij", fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            InputStyle.BAR -> {
                Text("☺", fontSize = 11.sp, color = st.pal.muted)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(12.dp).clip(CircleShape).background(primary))
            }
            else -> {
                val outlined = st.spec.input == InputStyle.OUTLINED
                val shape = if (st.spec.corners[2] == 0) RectangleShape else RoundedCornerShape(10.dp)
                Box(
                    Modifier.weight(1f).height(18.dp).clip(shape)
                        .then(if (outlined) Modifier.border(1.dp, st.pal.line, shape) else Modifier.background(st.pal.chip.solid(st.pal.base))),
                )
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(18.dp).clip(if (st.spec.chrome == Chrome.TERMINAL) RectangleShape else CircleShape).background(if (st.spec.chrome == Chrome.WHATSAPP) Color(0xFF00A884) else primary))
            }
        }
    }
}

@Composable
private fun MiniBubble(st: StyleState, mine: Boolean, text: String, first: Boolean = true, last: Boolean = true) {
    if (st.spec.flatMessages) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (first || mine) Box(Modifier.size(14.dp).clip(CircleShape).background(if (mine) st.pal.primary else Color(0xFFE57373))) else Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(5.dp))
            Column {
                if (first || mine) Text(if (mine) "Ty" else "Ania", color = st.pal.onBase, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(text, color = st.fill(Role.THEIRS).text, fontSize = 10.sp, maxLines = 1)
            }
        }
        return
    }
    val role = if (mine) Role.MINE else Role.THEIRS
    val shape = remember(st.spec, mine, first, last) { st.spec.bubbleShape(mine, first, last) }
    Box(
        Modifier.widthIn(max = 140.dp).styleSurface(st, role, shape).clip(shape)
            .padding(start = if (mine) 9.dp else 9.dp + st.spec.tailSpace(), end = if (mine) 9.dp + st.spec.tailSpace() else 9.dp, top = 4.dp, bottom = 4.dp),
    ) { Text(text, color = st.fill(role).text, fontSize = 10.sp, fontFamily = st.spec.font, maxLines = 1) }
}

// ======================= animacje =======================

/** Ustawienia animacji: główny przełącznik, charakter ruchu, tempo, bateria, przejścia i „Animacje zaawansowane”. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimationsScreen(nav: Nav) {
    val ctx = LocalContext.current
    fun save() = UiPrefs.save(ctx)
    val sysOff = remember { systemAnimScale(ctx) == 0f }
    val power = rememberPowerSave()
    val cascade = rememberCascade()
    val st = LocalStyle.current
    Scaffold(topBar = { BackBar("Animacje", nav) { TextButton(onClick = { nav.go(Screen.Styles) }) { Text("Style") } } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                SwitchRow("Animacje w Szepcie", "Główny przełącznik. Wyłączony = aplikacja bez żadnego ruchu (szybciej i oszczędniej).", UiPrefs.anim, true) {
                    UiPrefs.anim = it; save()
                }
            }
            if (sysOff) item {
                Text(
                    "W ustawieniach telefonu animacje są wyłączone (Ułatwienia dostępu → Usuń animacje) — Szept je wtedy pomija.",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Section("Charakter ruchu")
                Text(
                    "Każdy styl porusza się inaczej — „${st.spec.name}”: ${st.spec.motion.label}. Możesz też wybrać inny charakter dla wszystkich stylów.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MotionChips(enabled = UiPrefs.anim) { save() }
            }
            item {
                Section("Tempo")
                SpeedChips(enabled = UiPrefs.anim) { save() }
            }
            item {
                SwitchRow(
                    "Oszczędzaj baterię", "W trybie oszczędzania energii wyłączone są ciągłe animacje (tło, pulsowanie, żyroskop)." +
                        if (power) " Tryb oszczędzania jest teraz włączony." else "",
                    UiPrefs.animBattery, UiPrefs.anim,
                ) { UiPrefs.animBattery = it; save() }
            }
            item {
                Section("Przejścia między ekranami")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Trans.NAMES.forEachIndexed { i, t ->
                        FilterChip(UiPrefs.transition == i, { UiPrefs.transition = i; save() }, { Text(t) }, enabled = UiPrefs.anim && Anim.SCREENS.key !in UiPrefs.animOff)
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 14.dp))
                val on = Anim.entries.count { it.key !in UiPrefs.animOff }
                Text(
                    "Animacje zaawansowane", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Włącz albo wyłącz każdą animację osobno ($on z ${Anim.entries.size} włączonych).",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { UiPrefs.animOff = emptySet(); save() }, enabled = UiPrefs.anim) { Text("Wszystkie") }
                    OutlinedButton(onClick = { UiPrefs.animOff = AnimFlags.DEFAULT_OFF; save() }, enabled = UiPrefs.anim) { Text("Domyślne") }
                    OutlinedButton(onClick = { UiPrefs.animOff = Anim.entries.map { it.key }.toSet(); save() }, enabled = UiPrefs.anim) { Text("Żadne") }
                }
            }
            AnimGroup.entries.forEach { g ->
                item(key = "grupa_" + g.name) { Section(g.title) }
                Anim.entries.filter { it.group == g }.forEach { a ->
                    item(key = a.key) {
                        Box(Modifier.cascade(cascade, a.key)) {
                            SwitchRow(a.title + if (a.continuous) " ⟳" else "", a.desc, a.key !in UiPrefs.animOff, UiPrefs.anim) { on ->
                                UiPrefs.animOff = if (on) UiPrefs.animOff - a.key else UiPrefs.animOff + a.key
                                save()
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "⟳ — animacja ciągła (wyłączana w trybie oszczędzania baterii).",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Wypróbuj efekty")
                Text(
                    "Takie efekty pojawiają się same, gdy Ty albo rozmówca wyślecie wiadomość z daną emotką lub życzeniami (np. „Wszystkiego najlepszego!”).",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenFx.Kind.entries.forEach { k ->
                        AssistChip(onClick = {
                            if (!UiPrefs.anim) toast(ctx, "Animacje są wyłączone") else ScreenFx.play(k)
                        }, label = { Text(k.emoji + " " + k.label) })
                    }
                }
            }
        }
    }
}

/** Wybór charakteru ruchu (jak w stylu albo jeden dla wszystkich). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionChips(enabled: Boolean, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onChange: () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MOTION_PRESETS.forEachIndexed { i, (name, _) ->
            FilterChip(UiPrefs.motion == i, { UiPrefs.motion = i; onChange() }, { Text(name) }, enabled = enabled)
        }
    }
}

/** Wybór tempa animacji. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedChips(enabled: Boolean, modifier: Modifier = Modifier.padding(horizontal = 16.dp), onChange: () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1.6f to "Wolniej", 1f to "Normalnie", 0.65f to "Szybciej", 0.4f to "Błyskawicznie").forEach { (v, t) ->
            FilterChip(UiPrefs.animSpeed == v, { UiPrefs.animSpeed = v; onChange() }, { Text(t) }, enabled = enabled)
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}
