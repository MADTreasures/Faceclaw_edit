package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.BleLink

/**
 * GATT access for firmware work (version check, on-glasses confirmation, OTA update).
 *
 * Separate from [BleLink] because an update connects one temple at a time and also uses the OTA
 * characteristics. The Android app implements both on the same GATT code.
 */
interface FirmwareLink {
    /** Receives notifications (control and OTA characteristics) and disconnects. */
    var listener: BleLink.Listener?

    /**
     * Connects [arm]: GATT connect, MTU 512, discover, enable notifications on the control
     * notify characteristic and, when [ota] is true, on the OTA notify characteristic.
     * Throws when the arm cannot be reached.
     */
    suspend fun connect(arm: Arm, ota: Boolean)

    /** Negotiated ATT MTU (23 when unknown). */
    fun mtu(arm: Arm): Int

    /** Writes [frames] in order to [characteristic] (write without response). Throws when not connected. */
    suspend fun write(arm: Arm, characteristic: String, frames: List<ByteArray>)

    suspend fun disconnect(arm: Arm)

    fun isConnected(arm: Arm): Boolean

    /** true/false when the OS knows whether [arm] is bonded, null when unknown. */
    fun isBonded(arm: Arm): Boolean?
}
