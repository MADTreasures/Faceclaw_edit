package com.madtreasures.faceclaw.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.madtreasures.faceclaw.app.platform.AndroidCalendar
import com.madtreasures.faceclaw.app.platform.AndroidMedia
import com.madtreasures.faceclaw.app.platform.AndroidNotificationSource
import com.madtreasures.faceclaw.app.platform.AndroidWeather
import com.madtreasures.faceclaw.app.service.ConnectionManager
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.platform.BeepKind
import com.madtreasures.faceclaw.core.platform.DeviceStatus
import com.madtreasures.faceclaw.core.platform.FileSettingsStorage
import com.madtreasures.faceclaw.core.platform.GlassesControl
import com.madtreasures.faceclaw.core.platform.Heading
import com.madtreasures.faceclaw.core.platform.LinkState
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.platform.SensorSource
import com.madtreasures.faceclaw.core.platform.Services
import com.madtreasures.faceclaw.core.platform.Settings
import com.madtreasures.faceclaw.core.platform.SystemClock
import com.madtreasures.faceclaw.core.protocol.CfwDraw
import com.madtreasures.faceclaw.core.protocol.SessionEvent
import com.madtreasures.faceclaw.core.protocol.SessionPhase
import com.madtreasures.faceclaw.core.runtime.GlassesRuntime
import com.madtreasures.faceclaw.core.services.TimerService
import com.madtreasures.faceclaw.core.shell.Frame
import com.madtreasures.faceclaw.core.shell.FrameSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class FaceclawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph(this).start()
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: AppGraph? = null

        /** The process-wide object graph (created on first use, also from services). */
        fun graph(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}

/** Owns the glasses runtime, the phone data sources and the connection. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val uiScope = CoroutineScope(SupervisorJob() + uiDispatcher)

    val settings = Settings(FileSettingsStorage(File(context.filesDir, "settings.json")))
    val notifications = AndroidNotificationSource(context)
    val media = AndroidMedia(context)
    val calendar = AndroidCalendar(context, appScope)
    val weather = AndroidWeather(context, appScope)
    val connection = ConnectionManager(context, this)

    private val phoneBattery = MutableStateFlow<Pair<Int?, Boolean>>(null to false)

    val deviceStatus: StateFlow<DeviceStatus> = combine(connection.status, phoneBattery) { s, (batt, charging) ->
        if (connection.hasSession || s.phase != SessionPhase.Stopped) {
            GlassesRuntime.deviceStatus(s, batt, charging)
        } else {
            DeviceStatus(link = LinkState.Disconnected, phoneBattery = batt, phoneCharging = charging)
        }
    }.stateIn(appScope, SharingStarted.Eagerly, DeviceStatus())

    private val glassesControl = object : GlassesControl {
        override fun setBrightness(percent: Int, auto: Boolean) {
            connection.session?.setBrightness(percent)
        }

        override fun beep(kind: BeepKind) {
            val s = connection.session ?: return
            val t = { f: Int, ms: Int -> CfwDraw.Tone(f, if (f == 0) 0 else 50, ms) }
            val tones = when (kind) {
                BeepKind.Tick -> listOf(t(2400, 18))
                BeepKind.Confirm -> listOf(t(1800, 40), t(0, 30), t(2400, 60))
                BeepKind.Alert -> listOf(t(2000, 120), t(0, 80), t(2000, 120))
                BeepKind.Alarm -> (0 until 4).flatMap { listOf(t(2600, 150), t(0, 90), t(2600, 150), t(0, 400)) }
            }
            s.sendCfw(CfwDraw.toneSequence(tones))
        }
    }

    /** Heading from the glasses' magnetometer (custom firmware), while someone listens. */
    private val glassesCompass = object : SensorSource {
        override val heading: Flow<Heading> = callbackFlow {
            val session = connection.session
            if (session == null) {
                awaitClose {}
                return@callbackFlow
            }
            session.sendCfw(CfwDraw.compass(true))
            val job = launch {
                session.events.collect { e ->
                    if (e is SessionEvent.Compass) trySend(Heading(e.headingDegrees.toFloat(), 3, System.currentTimeMillis()))
                }
            }
            awaitClose {
                job.cancel()
                session.sendCfw(CfwDraw.compass(false))
            }
        }
    }

    val services = Services(
        settings = settings,
        clock = SystemClock,
        status = deviceStatus,
        notifications = notifications,
        media = media,
        weather = weather,
        calendar = calendar,
        sensors = glassesCompass,
        glasses = glassesControl,
    )

    val timers = TimerService(SystemClock, uiScope)
    val runtime = GlassesRuntime(services, uiScope, timers, BuildConfig.VERSION_NAME)

    private val _preview = MutableStateFlow<GrayBitmap?>(null)
    /** Latest frame for the phone's live preview. */
    val preview: StateFlow<GrayBitmap?> = _preview.asStateFlow()
    private val _displayOn = MutableStateFlow(true)
    val displayOn: StateFlow<Boolean> = _displayOn.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        runtime.start()
        runtime.shell.addFrameSink(object : FrameSink {
            override fun onFrame(frame: Frame) {
                _preview.value = frame.bitmap
            }

            override fun onDisplayPower(on: Boolean) {
                _displayOn.value = on
            }
        })
        media.refresh()
        calendar.start()
        weather.start()
        watchPhoneBattery()
        if (settings[Prefs.autoConnect] && connection.hasPairedGlasses) connection.connect()
    }

    private fun watchPhoneBattery() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                phoneBattery.value = (if (level >= 0) level * 100 / scale else null) to
                    (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
}
