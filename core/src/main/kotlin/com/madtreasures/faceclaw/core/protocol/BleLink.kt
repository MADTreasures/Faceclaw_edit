package com.madtreasures.faceclaw.core.protocol

/** The two temples. Each is its own BLE peripheral. */
enum class Arm { Left, Right }

/**
 * Platform access to the two GATT links. The Android app implements this with
 * BluetoothGatt; tests and the loopback mode use [FakeGlasses].
 */
interface BleLink {
    /** Receives notifications and disconnects. Set by the session before [connect]. */
    var listener: Listener?

    /**
     * Connects [arm]: GATT connect, request MTU 512 and high priority, discover services,
     * enable notifications on the control notify characteristic (and audio, best effort).
     * Throws when the arm cannot be reached.
     */
    suspend fun connect(arm: Arm)

    /** Negotiated ATT MTU (23 when unknown). */
    fun mtu(arm: Arm): Int

    /** Writes [frames] in order to the control write characteristic (write without response). Throws on failure. */
    suspend fun write(arm: Arm, frames: List<ByteArray>)

    /** Disconnects and releases both links. */
    suspend fun disconnect()

    /** true/false when the OS knows whether [arm] is bonded, null when unknown. */
    fun isBonded(arm: Arm): Boolean? = null

    interface Listener {
        fun onNotification(arm: Arm, characteristic: String, value: ByteArray)
        fun onDisconnected(arm: Arm)
    }
}
