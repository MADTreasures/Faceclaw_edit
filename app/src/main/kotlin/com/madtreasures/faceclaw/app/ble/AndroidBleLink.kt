package com.madtreasures.faceclaw.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.BleLink
import com.madtreasures.faceclaw.core.protocol.G2Gatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * [BleLink] over Android's BluetoothGatt for the two temples.
 *
 * Every GATT operation of both links is serialised by one lock (Android's stack handles one
 * outstanding operation well, and fragments of different messages must never interleave).
 * Writes are Write Without Response, each awaited via onCharacteristicWrite so the
 * controller's buffers never overflow.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink(private val context: Context, private val addresses: Map<Arm, String>) : BleLink {
    companion object {
        private const val TAG = "AndroidBleLink"
        private val RETRY_DELAYS_MS = longArrayOf(1, 1, 1, 2, 4, 8, 12, 20, 35, 100, 200)
        private val CONTROL_WRITE: UUID = UUID.fromString(G2Gatt.CONTROL_WRITE)
        private val CONTROL_NOTIFY: UUID = UUID.fromString(G2Gatt.CONTROL_NOTIFY)
        private val AUDIO_NOTIFY: UUID = UUID.fromString(G2Gatt.AUDIO_NOTIFY)
        private val CCCD: UUID = UUID.fromString(G2Gatt.CCCD)
    }

    override var listener: BleLink.Listener? = null
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val lock = Mutex()
    private val arms = HashMap<Arm, ArmLink>()

    private inner class ArmLink(val arm: Arm) : BluetoothGattCallback() {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var connected = false
        @Volatile var mtu = 23
        var control: BluetoothGattCharacteristic? = null
        val connectResult = CompletableDeferred<Boolean>()
        @Volatile var mtuDone: CompletableDeferred<Int>? = null
        @Volatile var servicesDone: CompletableDeferred<Boolean>? = null
        @Volatile var descriptorDone: CompletableDeferred<Int>? = null
        @Volatile var writeDone: CompletableDeferred<Int>? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                connectResult.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = connected
                connected = false
                connectResult.complete(false)
                mtuDone?.complete(mtu)
                servicesDone?.complete(false)
                descriptorDone?.complete(-1)
                writeDone?.complete(-1)
                runCatching { g.close() }
                if (gatt === g) gatt = null
                Log.i(TAG, "$arm disconnected (status $status)")
                if (was) listener?.onDisconnected(arm)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            mtuDone?.complete(this.mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDone?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener?.onNotification(arm, c.uuid.toString(), value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = c.value ?: return
                listener?.onNotification(arm, c.uuid.toString(), v.copyOf())
            }
        }
    }

    override suspend fun connect(arm: Arm): Unit = lock.withLock {
        val address = addresses[arm] ?: throw IOException("no address for $arm")
        val existing = arms[arm]
        val link = if (existing != null && existing.connected) existing else {
            val l = ArmLink(arm)
            arms[arm] = l
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            l.gatt = device.connectGatt(context, false, l, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK)
            val ok = withTimeoutOrNull(8000) { l.connectResult.await() } ?: false
            if (!ok) {
                l.gatt?.let { runCatching { it.disconnect(); it.close() } }
                l.gatt = null
                throw IOException("$arm: could not connect")
            }
            l
        }
        val g = link.gatt ?: throw IOException("$arm: no GATT")
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        link.mtuDone = CompletableDeferred<Int>().also { d ->
            if (g.requestMtu(G2Gatt.REQUESTED_MTU)) withTimeoutOrNull(5000) { d.await() }
        }
        val services = CompletableDeferred<Boolean>()
        link.servicesDone = services
        if (!g.discoverServices()) throw IOException("$arm: service discovery refused")
        if (withTimeoutOrNull(8000) { services.await() } != true) throw IOException("$arm: service discovery failed")
        link.control = findCharacteristic(g, CONTROL_WRITE) ?: throw IOException("$arm: control characteristic missing")
        enableNotifications(link, CONTROL_NOTIFY, required = true)
        enableNotifications(link, AUDIO_NOTIFY, required = false)
        Log.i(TAG, "$arm connected, mtu ${link.mtu}")
        Unit
    }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services.firstNotNullOfOrNull { s -> s.characteristics.firstOrNull { it.uuid == uuid } }

    private suspend fun enableNotifications(link: ArmLink, uuid: UUID, required: Boolean) {
        val g = link.gatt ?: throw IOException("${link.arm}: no GATT")
        val ch = findCharacteristic(g, uuid)
        if (ch == null) {
            if (required) throw IOException("${link.arm}: $uuid missing") else return
        }
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: run { if (required) throw IOException("${link.arm}: CCCD missing") else return }
        val done = CompletableDeferred<Int>()
        link.descriptorDone = done
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        val status = if (started) withTimeoutOrNull(5000) { done.await() } else null
        if (status != BluetoothGatt.GATT_SUCCESS && required) throw IOException("${link.arm}: enabling notifications failed")
    }

    override fun mtu(arm: Arm): Int = arms[arm]?.mtu ?: 23

    override suspend fun write(arm: Arm, frames: List<ByteArray>): Unit = lock.withLock {
        val link = arms[arm]?.takeIf { it.connected } ?: throw IOException("$arm not connected")
        val ch = link.control ?: throw IOException("$arm has no control characteristic")
        for (f in frames) writeOne(link, ch, f)
    }

    private suspend fun writeOne(link: ArmLink, ch: BluetoothGattCharacteristic, frame: ByteArray) {
        var attempt = 0
        while (true) {
            val g = link.gatt ?: throw IOException("${link.arm} not connected")
            val done = CompletableDeferred<Int>()
            link.writeDone = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                ch.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (started) {
                val status = withTimeoutOrNull(2000) { done.await() } ?: throw IOException("${link.arm}: write timed out")
                if (status == BluetoothGatt.GATT_SUCCESS) return
            }
            if (attempt >= RETRY_DELAYS_MS.size) throw IOException("${link.arm}: write failed")
            delay(RETRY_DELAYS_MS[attempt++])
        }
    }

    override suspend fun disconnect(): Unit = lock.withLock {
        for (l in arms.values) {
            l.connected = false
            l.gatt?.let { runCatching { it.disconnect(); it.close() } }
            l.gatt = null
        }
        arms.clear()
    }

    override fun isBonded(arm: Arm): Boolean? {
        val address = addresses[arm] ?: return null
        return runCatching { adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }.getOrNull()
    }
}
