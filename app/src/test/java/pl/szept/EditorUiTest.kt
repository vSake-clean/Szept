package pl.szept

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.szept.core.Core
import pl.szept.ui.ImageEditorDialog
import java.io.File

/** Edytor zdjęć w interfejsie: kadr ustawiony w edytorze jest zapisywany po „Gotowe” (wcześniej ginął). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w320dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorUiTest {
    @get:Rule val rule = createComposeRule()

    private fun photo(w: Int, h: Int): File {
        val f = File(RuntimeEnvironment.getApplication().cacheDir, "p_${System.nanoTime()}.jpg")
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(40, 120, 200)) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { check(System.currentTimeMillis() < end) { what }; rule.waitForIdle(); Thread.sleep(50) }
    }

    @Test
    fun cropAppliedOnDone() {
        var out: Core.Attachment? = null
        val src = photo(400, 200)
        // jak przy zdjęciu profilowym: od razu kadr 1:1, użytkownik dotyka tylko „Gotowe”
        rule.setContent { ImageEditorDialog(src, onCancel = {}, startRatio = 1f) { out = it } }
        rule.waitForIdle()
        waitUntil("edytor otwarty") { runCatching { rule.onNodeWithText("Gotowe").assertExists(); true }.getOrDefault(false) }
        rule.onNodeWithText("Przytnij").assertExists()   // przycisk widoczny bez przewijania
        rule.onNodeWithText("Gotowe").performClick()
        waitUntil("zapisano") { out != null }
        val o = BitmapFactory.decodeFile(out!!.file.absolutePath)
        assertEquals("kadr 1:1 zastosowany", o.width, o.height)
        assertEquals(200, o.height)
    }

    @Test
    fun cropButtonAndTools() {
        var out: Core.Attachment? = null
        rule.setContent { ImageEditorDialog(photo(300, 300), onCancel = {}) { out = it } }
        waitUntil("edytor otwarty") { runCatching { rule.onNodeWithText("Gotowe").assertExists(); true }.getOrDefault(false) }
        rule.onNodeWithContentDescription("Kadruj").performClick()
        rule.onNodeWithText("16:9").performScrollTo().performClick()
        rule.onNodeWithText("Przytnij").performClick()
        rule.waitForIdle()
        println("PO PRZYCIĘCIU: " + runCatching { rule.onNodeWithText("Przytnij").assertExists(); "nadal kadr" }.getOrElse { "kadr zamknięty" })
        rule.onNodeWithContentDescription("Obróć").performClick()
        rule.onNodeWithText("Gotowe").performClick()
        waitUntil("zapisano") { out != null }
        val o = BitmapFactory.decodeFile(out!!.file.absolutePath)
        // 16:9 z 300×300 = 300×168, po obrocie 168×300
        assertTrue("obrócony kadr: ${o.width}x${o.height}", o.width < o.height && o.height == 300)
    }
}
