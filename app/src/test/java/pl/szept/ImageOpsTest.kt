package pl.szept

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.szept.ui.ImageOps

/** Edytor zdjęć: obrót, kadrowanie, zamazywanie, rysowanie (prawdziwe operacje na pikselach). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOpsTest {
    private fun stripes(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if ((x / 2 + y / 2) % 2 == 0) Color.BLACK else Color.WHITE)
        return b
    }

    @Test
    fun operations() {
        val b = stripes(200, 100)
        val r = ImageOps.rotate90(b)
        assertEquals(100, r.width); assertEquals(200, r.height)
        assertTrue(r.isMutable)
        val c = ImageOps.crop(b, Rect(10, 20, 110, 70))
        assertEquals(100, c.width); assertEquals(50, c.height)
        assertEquals("kadr spoza obrazu przycinany do krawędzi", 200, ImageOps.crop(b, Rect(150, 0, 400, 100)).width + 150)

        // zamazanie: drobna szachownica w obszarze znika (sąsiednie piksele mają ten sam kolor)
        val p = stripes(200, 100)
        val before = (0 until 40).count { x -> p.getPixel(50 + x, 50) != p.getPixel(51 + x, 50) }
        ImageOps.pixelate(p, Rect(40, 30, 160, 90))
        val after = (0 until 40).count { x -> p.getPixel(50 + x, 50) != p.getPixel(51 + x, 50) }
        assertTrue("przed: $before, po: $after", after <= before / 2)
        assertEquals("poza obszarem bez zmian", stripes(200, 100).getPixel(5, 5), p.getPixel(5, 5))

        // rysowanie
        val d = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        ImageOps.drawStroke(d, listOf(Offset(10f, 50f), Offset(90f, 50f)), Color.RED, 8f)
        assertEquals(Color.RED, d.getPixel(50, 50))
        assertNotEquals(Color.RED, d.getPixel(50, 10))

        // wykrywanie twarzy nie wywraca się na obrazie bez twarzy
        assertTrue(ImageOps.findFaces(stripes(64, 64)).isEmpty())
        println("IMAGE OPS OK")
    }
}
