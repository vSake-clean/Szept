package pl.szept.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.szept.I18n
import pl.szept.tr

/** Flaga języka rysowana w aplikacji (wygląda tak samo na każdym telefonie). */
@Composable
fun LanguageFlag(code: String, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    val shape = RoundedCornerShape(4.dp)
    Canvas(
        modifier.size(width, width * 0.66f).clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .semantics { contentDescription = I18n.LANGS.firstOrNull { it.code == code }?.name ?: code },
    ) {
        val w = size.width
        val h = size.height
        when (code) {
            // Polska: biel nad czerwienią
            "pl" -> {
                drawRect(Color.White, Offset.Zero, Size(w, h / 2))
                drawRect(Color(0xFFDC143C), Offset(0f, h / 2), Size(w, h / 2))
            }
            // Anglia: czerwony krzyż św. Jerzego na białym tle
            "en" -> {
                drawRect(Color.White)
                val red = Color(0xFFCE1124)
                val t = h * 0.2f
                drawRect(red, Offset(0f, (h - t) / 2), Size(w, t))
                drawRect(red, Offset((w - t) / 2, 0f), Size(t, h))
            }
            else -> drawRect(Color.LightGray)
        }
    }
}

/** Lista języków do wyboru: flaga i nazwa w tym języku. */
@Composable
fun LanguageList(selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        I18n.LANGS.forEach { l ->
            val on = l.code == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(l.code) },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LanguageFlag(l.code)
                    Spacer(Modifier.width(14.dp))
                    Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * Uruchamia Szept od nowa — teksty liczone raz przy starcie (np. lista gier) zmieniają się
 * na nowy język dopiero w świeżym procesie. Konto i wszystkie dane zostają.
 */
fun restartApp(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(launch)
    (ctx as? Activity)?.finishAffinity()
    // chwila na zapis ustawień (apply) przed zamknięciem procesu
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 400)
}

/** Czy po zmianie języka trzeba uruchomić aplikację od nowa (w testach — nigdy). */
fun languageNeedsRestart(): Boolean =
    I18n.lang != I18n.startLang && android.os.Build.FINGERPRINT != "robolectric"

/** Ustawienia → Język. */
@Composable
fun LanguageScreen(nav: Nav) {
    val ctx = LocalContext.current
    Scaffold(topBar = { BackBar(tr("Język") + " / Language", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                tr("Wybierz język aplikacji. Twoje konto, nazwa, ID, rozmowy i grupy zostają bez zmian."),
                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LanguageList(I18n.lang) { code ->
                if (code == I18n.lang) return@LanguageList
                I18n.set(ctx, code)
                if (languageNeedsRestart()) {
                    toast(ctx, tr("Zmieniam język…"))
                    restartApp(ctx)
                }
            }
        }
    }
}
