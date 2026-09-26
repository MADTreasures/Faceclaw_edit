package com.madtreasures.faceclaw.sim

import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.platform.Clock
import com.madtreasures.faceclaw.core.platform.DeviceStatus
import com.madtreasures.faceclaw.core.platform.FileSettingsStorage
import com.madtreasures.faceclaw.core.platform.LinkState
import com.madtreasures.faceclaw.core.platform.MemorySettingsStorage
import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.platform.Settings
import com.madtreasures.faceclaw.core.platform.SystemClock
import com.madtreasures.faceclaw.core.platform.demo.DemoCalendar
import com.madtreasures.faceclaw.core.platform.demo.DemoMedia
import com.madtreasures.faceclaw.core.platform.demo.DemoNotifications
import com.madtreasures.faceclaw.core.platform.demo.DemoSensors
import com.madtreasures.faceclaw.core.platform.demo.DemoWeather
import com.madtreasures.faceclaw.core.runtime.GlassesRuntime
import com.madtreasures.faceclaw.core.services.TimerService
import com.madtreasures.faceclaw.core.shell.Frame
import com.madtreasures.faceclaw.core.shell.FrameSink
import com.madtreasures.faceclaw.core.ui.Gesture
import com.madtreasures.faceclaw.core.ui.InputEvent
import com.madtreasures.faceclaw.core.ui.InputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

const val VERSION = "0.1.0"

/** Builds the runtime with demo data sources. */
class SimHost(settingsFile: File?, clock: Clock = SystemClock) {
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "glasses-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val settings = Settings(settingsFile?.let { FileSettingsStorage(it) } ?: MemorySettingsStorage())
    val notifications = DemoNotifications(clock).also { it.seed(3) }
    val status = MutableStateFlow(DeviceStatus(link = LinkState.Simulated, leftBattery = 78, rightBattery = 81, ringBattery = 64, phoneBattery = 57, wearing = true, firmwareVersion = "2.3.0.24", customFirmware = "Simulator"))
    val services = Services(
        settings = settings,
        clock = clock,
        status = status,
        notifications = notifications,
        media = DemoMedia(clock),
        weather = DemoWeather(clock),
        calendar = DemoCalendar(clock),
        sensors = DemoSensors(),
    )
    val timers = TimerService(clock, scope)
    val runtime = GlassesRuntime(services, scope, timers, VERSION)
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--screenshots") {
        Screenshots.renderAll(File(args.getOrElse(1) { "docs/screenshots" }))
        return
    }
    val home = File(System.getProperty("user.home"), ".faceclaw-edit")
    val host = SimHost(File(home, "simulator-settings.json"))
    SwingUtilities.invokeLater { SimulatorWindow(host).show() }
    host.runtime.start()
}

/** Window with the glasses display (green on black) and keyboard controls. */
class SimulatorWindow(private val host: SimHost) {
    private val scale = 2
    @Volatile private var frame: GrayBitmap? = null
    @Volatile private var displayOn = true
    private val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)

    private val panel = object : JPanel() {
        init {
            preferredSize = Dimension(640 * scale, 480 * scale)
            background = Color.BLACK
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val f = frame ?: return
            val px = f.pixels
            for (i in px.indices) {
                val v = if (displayOn) px[i].toInt() and 0xFF else 0
                // green micro-LED look
                image.setRGB(i % 640, i / 640, ((v * 60 / 255) shl 16) or (v shl 8) or (v * 90 / 255))
            }
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g2.drawImage(image, 0, 0, 640 * scale, 480 * scale, null)
        }
    }

    fun show() {
        val window = JFrame("Faceclaw Edit — glasses simulator")
        window.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        window.layout = BorderLayout()
        window.add(panel, BorderLayout.CENTER)
        val help = JLabel(
            "<html>↑/↓ scroll · Enter/Space tap · Backspace/Esc double tap · L long press · Q tap-then-hold · " +
                "N new notification · D display on/off · W take glasses off/on</html>",
        )
        help.border = EmptyBorder(8, 12, 8, 12)
        help.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        window.add(help, BorderLayout.SOUTH)
        window.pack()
        window.setLocationRelativeTo(null)
        window.isVisible = true

        host.runtime.shell.addFrameSink(object : FrameSink {
            override fun onFrame(frame: Frame) {
                this@SimulatorWindow.frame = frame.bitmap
                panel.repaint()
            }

            override fun onDisplayPower(on: Boolean) {
                displayOn = on
                panel.repaint()
            }
        })

        window.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val shell = host.runtime.shell
                fun send(g: Gesture) = shell.dispatch(InputEvent(g, InputSource.Keyboard, System.currentTimeMillis()))
                when (e.keyCode) {
                    KeyEvent.VK_UP -> send(Gesture.ScrollUp)
                    KeyEvent.VK_DOWN -> send(Gesture.ScrollDown)
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> send(Gesture.Tap)
                    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_ESCAPE -> send(Gesture.DoubleTap)
                    KeyEvent.VK_L -> send(Gesture.LongPress)
                    KeyEvent.VK_Q -> send(Gesture.TapThenLong)
                    KeyEvent.VK_N -> host.notifications.post()
                    KeyEvent.VK_D -> shell.post { setDisplay(!displayOn) }
                    KeyEvent.VK_W -> host.status.value = host.status.value.copy(wearing = host.status.value.wearing != true)
                }
            }
        })
    }
}
