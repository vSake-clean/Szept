package pl.szept.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.szept.App
import pl.szept.core.Calc

/** Przełączanie ikony: zwykły Szept albo „Kalkulator”. */
object Disguise {
    private fun launcher(ctx: Context) = ComponentName(ctx.packageName, "pl.szept.Launcher")
    private fun calc(ctx: Context) = ComponentName(ctx.packageName, "pl.szept.Calculator")

    fun isOn(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(calc(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(ctx: Context, on: Boolean) {
        val pm = ctx.packageManager
        // najpierw włączamy nową ikonę, potem wyłączamy starą — żeby przez chwilę nie zniknęły obie
        val (enable, disable) = if (on) calc(ctx) to launcher(ctx) else launcher(ctx) to calc(ctx)
        pm.setComponentEnabledSetting(enable, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(disable, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

/** Działający kalkulator. Kod ustawiony w Szepcie + „=” otwiera właściwą aplikację. */
class CalculatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CalculatorScreen(onCode = ::openSzept) }
    }

    private fun openSzept() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

private val OPS = setOf('+', '−', '×', '÷')

@Composable
fun CalculatorScreen(onCode: () -> Unit) {
    var expr by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; result = "" }
            "⌫" -> expr = expr.dropLast(1)
            "=" -> {
                if (expr.length in 4..8 && expr.all { it.isDigit() } && App.real.isCalcCode(expr)) { expr = ""; result = ""; onCode(); return }
                result = try { Calc.eval(expr).also { expr = it.replace("-", "−") } ; "" } catch (e: Calc.CalcException) { e.message ?: "Błąd" }
            }
            else -> {
                result = ""
                val c = k[0]
                if (c in OPS || c == '%') {
                    if (expr.isEmpty() && c != '−') return
                    if (expr.last() in OPS) expr = expr.dropLast(1)
                }
                if (expr.length < 40) expr += k
            }
        }
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF121212) else Color(0xFFF5F5F5)
    val fg = if (dark) Color.White else Color(0xFF202124)
    val keyBg = if (dark) Color(0xFF2C2C2C) else Color.White
    val opBg = Color(0xFFFF9800)
    Column(Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
        Text(expr.ifEmpty { "0" }, Modifier.fillMaxWidth().padding(8.dp), color = fg, fontSize = 44.sp, textAlign = TextAlign.End, maxLines = 2)
        Text(result, Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = Color(0xFFE53935), fontSize = 18.sp, textAlign = TextAlign.End)
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("00", "0", ",", "="),
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    val op = k in listOf("÷", "×", "−", "+", "=")
                    Box(
                        Modifier.weight(1f).padding(vertical = 5.dp).aspectRatio(1.15f).clip(CircleShape)
                            .background(if (op) opBg else keyBg).clickable { press(k) },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = if (op) Color.White else fg, fontSize = 26.sp, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}
