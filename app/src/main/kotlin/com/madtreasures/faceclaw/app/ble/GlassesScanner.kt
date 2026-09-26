package com.madtreasures.faceclaw.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import com.madtreasures.faceclaw.core.protocol.G2Advert
import com.madtreasures.faceclaw.core.protocol.G2Advertisement
import com.madtreasures.faceclaw.core.protocol.GlassesPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Scans for G2 temples and groups them into pairs by serial number. */
@SuppressLint("MissingPermission")
class GlassesScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val seen = HashMap<String, Pair<G2Advert, Long>>()
    private val _pairs = MutableStateFlow<List<GlassesPair>>(emptyList())
    val pairs: StateFlow<List<GlassesPair>> = _pairs.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = ingest(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { ingest(it) }
        override fun onScanFailed(errorCode: Int) {
            _error.value = "Scan failed ($errorCode)"
            _scanning.value = false
        }
    }

    fun start() {
        if (_scanning.value) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            _error.value = "Bluetooth is off"
            return
        }
        _error.value = null
        seen.clear()
        // Already bonded temples show up even when they are not advertising right now.
        for (d in adapter.bondedDevices.orEmpty()) {
            G2Advertisement.parse(d.address, d.name, null, null, bonded = true)?.let { seen[it.address] = it to SystemClock.elapsedRealtime() }
        }
        publish()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setLegacy(false)
            .build()
        scanner.startScan(null, settings, callback)
        _scanning.value = true
    }

    fun stop() {
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }

    private fun ingest(r: ScanResult) {
        val record = r.scanRecord
        val name = record?.deviceName ?: r.device.name
        val mfg = record?.getManufacturerSpecificData(G2Advertisement.COMPANY_ID)
        val prev = seen[G2Advertisement.normalizeAddress(r.device.address)]?.first
        val advert = G2Advertisement.parse(r.device.address, name ?: prev?.name, mfg, r.rssi) ?: return
        val merged = if (advert.serial == null && prev?.serial != null) advert.copy(serial = prev.serial) else advert
        seen[merged.address] = merged to SystemClock.elapsedRealtime()
        publish()
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        seen.entries.removeIf { (_, v) -> !v.first.bonded && now - v.second > 12_000 }
        _pairs.value = G2Advertisement.pairUp(seen.values.map { it.first })
    }
}
