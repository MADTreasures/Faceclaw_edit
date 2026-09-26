package com.madtreasures.faceclaw.core.gfx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RenderSheetTest {
    @Test
    fun rendersSampleSheet() {
        val bmp = GrayBitmap(640, 480)
        val c = Canvas(bmp)
        var y = 30f
        for (name in listOf("inter-regular-16", "inter-regular-20", "inter-medium-20", "inter-semibold-24", "inter-bold-32", "mono-regular-18")) {
            val f = FontLibrary.get(name)
            c.drawText("$name Äpfel & Übermut — AVA Tä 12:45 …", 12f, y, f, 255)
            y += f.lineHeight + 4
        }
        c.drawText("09:41", 12f, y + 70f, FontLibrary.get("inter-display-light-96"), 255)
        val icons = FontLibrary.get("icons-32")
        var x = 300f
        for (cp in listOf(Icons.Home, Icons.MusicNote, Icons.Notifications, Icons.Settings, Icons.BatteryFull, Icons.Explore)) {
            c.drawIcon(cp, x, y + 20f, 32, icons, 255)
            x += 40f
        }
        c.fillRoundRect(300f, y + 70f, 160f, 44f, 22f, 60)
        c.strokeRoundRect(300f, y + 70f, 160f, 44f, 22f, 2f, 255)
        c.drawTextIn("Selected", IntRect.of(300, (y + 70f).toInt(), 160, 44), FontLibrary.get("inter-semibold-20"), 255, HAlign.Center)
        c.drawArc(560f, 400f, 50f, 8f, 0f, 250f, 255)
        c.fillCircle(560f, 400f, 6f, 180)
        c.drawLine(20f, 460f, 280f, 420f, 3f, 200)
        c.fillRegularPolygon(620f, 40f, 12f, 3, 90f, 255)
        val out = File("build/render-sheet.png")
        out.writeBytes(Png.encode(bmp, 0x40FF60))
        assertTrue(out.length() > 0)
        val back = Png.decode(Png.encode(bmp))
        assertTrue(back.contentEquals(bmp))
    }
}
