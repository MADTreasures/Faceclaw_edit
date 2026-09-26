package com.madtreasures.faceclaw.core.protocol

/** GATT identifiers of a G2 temple. All writes are Write Without Response. */
object G2Gatt {
    private const val BASE = "00002760-08c2-11e1-9073-0e8ac72e"
    const val CONTROL_WRITE = "${BASE}5401"
    const val CONTROL_NOTIFY = "${BASE}5402"
    const val AUDIO_NOTIFY = "${BASE}6402"
    const val OTA_WRITE = "${BASE}0001"
    const val OTA_NOTIFY = "${BASE}0002"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val REQUESTED_MTU = 512
}

/** Service ids (first header byte after the fragment fields). */
object Sid {
    const val DASHBOARD = 0x01
    const val EVEN_AI = 0x07
    const val NAVIGATION = 0x08
    const val SETTINGS = 0x09
    const val SYNC_INFO = 0x0D
    const val ONBOARDING = 0x10
    const val DEV_CONFIG = 0x80
    const val EVENHUB = 0xE0
    const val CFW = 0xF0
}

/** Builders for the stock messages the session sends. Each returns the protobuf payload. */
object G2Messages {
    /** Fixed magic of the session prelude. */
    const val PRELUDE_MAGIC = 156

    /** Security authentication request (sid 0x80, flag 0x00). Triggers OS pairing on an unbonded link. */
    fun authRequest(magic: Int): ByteArray = ProtoWriter.build {
        uint(1, 4)
        uint(2, magic)
        message(3) {
            uint(1, 1)
            uint(2, 4) // phone type: Android
        }
    }

    /** True for the auth success reply `f1=4, f3{}` (the reply before encryption has a non-empty f3). */
    fun isAuthSuccess(msg: InboundMessage): Boolean {
        val p = msg.proto ?: return false
        return p.int(1) == 4 && p.bytes(3)?.isEmpty() == true
    }

    /** Mandatory session prelude (sid 0x01, magic 156). */
    fun prelude(): ByteArray = ProtoWriter.build {
        uint(1, 2)
        uint(2, PRELUDE_MAGIC)
        message(4) {
            message(3) {
                message(2) {
                    message(2) {
                        uint(1, 0)
                        uint(2, 0)
                    }
                }
            }
        }
    }

    /** Settings read: battery, versions and (custom firmware) field 100 revision (sid 0x09). */
    fun settingsRead(magic: Int): ByteArray = ProtoWriter.build {
        uint(1, 2)
        uint(2, magic)
        message(4) { uint(1, 1) }
    }

    /** Enables the stock on-head detector (sid 0x09). */
    fun wearDetection(magic: Int, enabled: Boolean): ByteArray = ProtoWriter.build {
        uint(1, 1)
        uint(2, magic)
        message(3) { message(5) { uint(1, if (enabled) 1 else 0) } }
    }

    /**
     * Custom-firmware control record in settings field 101, magic 0, fire-and-forget.
     * Ops: 1/2 wake lease acquire/release, 3/4 wake claim/ready, 5/6 framebuffer lease acquire/release, 7 wear query.
     */
    fun cfwControl(op: Int, nonce: Int = 0): ByteArray = ProtoWriter.build {
        uint(1, 1)
        uint(2, 0)
        bytes(101, byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 1, op.toByte(), nonce.toByte(), (nonce ushr 8).toByte()))
    }

    object CfwOp {
        const val WAKE_ACQUIRE = 1
        const val WAKE_RELEASE = 2
        const val WAKE_CLAIM = 3
        const val WAKE_READY = 4
        const val FB_ACQUIRE = 5
        const val FB_RELEASE = 6
        const val WEAR_QUERY = 7
    }

    // ---------------------------------------------------------------- EvenHub (sid 0xE0)

    /**
     * Creates the event-capturing text page. Its pixels are never shown (the custom firmware
     * owns the framebuffer); it only exists so the glasses deliver input events.
     */
    fun createInputPage(magic: Int): ByteArray = ProtoWriter.build {
        uint(1, 0)
        uint(2, magic)
        message(3) {
            uint(1, 1)
            message(3) {
                uint(1, 0)
                uint(2, 0)
                uint(3, 576)
                uint(4, 288)
                uint(9, 1)
                string(10, "dashboard")
                uint(11, 1)
                string(12, " ")
            }
            uint(5, 10000)
        }
    }

    fun heartbeat(magic: Int): ByteArray = ProtoWriter.build {
        uint(1, 12)
        uint(2, magic)
        message(14) { uint(1, 0) }
    }

    fun shutdownPage(magic: Int, exitMode: Int = 0): ByteArray = ProtoWriter.build {
        uint(1, 9)
        uint(2, magic)
        message(11) { uint(1, exitMode) }
    }

    fun audioControl(magic: Int, enable: Boolean): ByteArray = ProtoWriter.build {
        uint(1, 15)
        uint(2, magic)
        message(18) { uint(1, if (enable) 1 else 0) }
    }
}
