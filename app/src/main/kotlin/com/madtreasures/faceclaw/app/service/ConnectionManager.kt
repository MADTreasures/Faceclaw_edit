package com.madtreasures.faceclaw.app.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.madtreasures.faceclaw.app.AppGraph
import com.madtreasures.faceclaw.app.ble.AndroidBleLink
import com.madtreasures.faceclaw.core.platform.Prefs
import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.GlassesSession
import com.madtreasures.faceclaw.core.protocol.SessionPhase
import com.madtreasures.faceclaw.core.protocol.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Starts and stops the glasses session and keeps the foreground service in sync with it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManager(private val context: Context, private val graph: AppGraph) {
    private val _status = MutableStateFlow(SessionStatus(detail = "Not connected"))
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    @Volatile var session: GlassesSession? = null
        private set
    private var sessionScope: CoroutineScope? = null

    val hasSession: Boolean get() = session != null

    val hasPairedGlasses: Boolean
        get() = graph.settings[Prefs.pairedLeft].isNotBlank() && graph.settings[Prefs.pairedRight].isNotBlank()

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bluetoothEnabled(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    @Synchronized
    fun connect() {
        if (session != null) return
        val left = graph.settings[Prefs.pairedLeft]
        val right = graph.settings[Prefs.pairedRight]
        if (left.isBlank() || right.isBlank()) {
            _status.value = SessionStatus(detail = "No glasses paired")
            return
        }
        if (!hasBluetoothPermission()) {
            _status.value = SessionStatus(detail = "Bluetooth permission missing")
            return
        }
        if (!bluetoothEnabled()) {
            _status.value = SessionStatus(detail = "Bluetooth is off")
            return
        }
        GlassesService.start(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val link = AndroidBleLink(context, mapOf(Arm.Left to left, Arm.Right to right))
        val s = GlassesSession(link, scope, log = { Log.i("GlassesSession", it) })
        session = s
        sessionScope = scope
        graph.runtime.attach(s, scope)
        scope.launch {
            s.status.collect { st ->
                _status.value = st
                if (st.phase == SessionPhase.IncompatibleFirmware) release()
            }
        }
        s.start()
    }

    fun disconnect() {
        val s = session ?: return
        graph.appScope.launch {
            s.stop()
            release()
            _status.value = SessionStatus(detail = "Disconnected")
        }
    }

    @Synchronized
    private fun release() {
        sessionScope?.cancel()
        sessionScope = null
        session = null
        GlassesService.stop(context)
    }
}
