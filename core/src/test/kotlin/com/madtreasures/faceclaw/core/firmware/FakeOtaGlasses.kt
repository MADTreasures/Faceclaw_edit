package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Arm
import com.madtreasures.faceclaw.core.protocol.BleLink
import com.madtreasures.faceclaw.core.protocol.Crc16
import com.madtreasures.faceclaw.core.protocol.Envelope
import com.madtreasures.faceclaw.core.protocol.FrameReassembler
import com.madtreasures.faceclaw.core.protocol.G2Gatt
import com.madtreasures.faceclaw.core.protocol.InboundMessage
import com.madtreasures.faceclaw.core.protocol.ProtoWriter
import com.madtreasures.faceclaw.core.protocol.Sid
import java.io.IOException

/**
 * A model of the stock firmware's update path for tests: authentication, prelude, settings reads,
 * the confirmation page with a scripted answer, and an OTA receiver that behaves like the real one
 * (no block index: every accepted block advances the write position; a disconnect resets the
 * session; END verifies the CRC-32C from the subheader). Replies are delivered synchronously.
 */
class FakeOtaGlasses(private val clock: () -> Long) : FirmwareLink {
    override var listener: BleLink.Listener? = null

    var version = "2.3.0.24"
    var extension: String? = null
    val battery = mutableMapOf(Arm.Left to 80, Arm.Right to 80)
    var mtu = 512
    val bonded = mutableMapOf<Arm, Boolean?>(Arm.Left to true, Arm.Right to true)
    /** When set, an unbonded arm becomes bonded at this time (the user accepted the dialog). */
    var bondAcceptedAt: Long? = null

    /** Answer to the confirmation page: item index, or null to never answer. */
    var promptAnswer: Int? = 1
    var promptText: String? = null
        private set
    var shutdowns = 0
        private set

    /** Firmware reported once both lenses have been flashed and restarted. */
    var extensionAfterFlash: String? = null
    var rebootMs = 8_000L

    // ---- fault injection (block numbers count accepted-or-not data messages per arm, from 1)
    /** These data messages are answered with a CRC_ERR NAK (not written). */
    val nakBlocks = HashSet<Int>()
    /** These data messages are written but their ack is lost. */
    val loseAckOfBlocks = HashSet<Int>()
    /** The link drops when this data message arrives. */
    val dropLinkAtBlocks = HashSet<Int>()
    /** END answers CHECK_FAIL for these END numbers (per arm, from 1). */
    val failEnds = HashSet<Int>()
    /** Arms that refuse connections. */
    val unreachable = HashSet<Arm>()

    // ---- observable state
    val connected = HashSet<Arm>()
    val otaEnabled = HashSet<Arm>()
    /** Components whose END passed, per arm, in order (name → written bytes). */
    val verified = mapOf(Arm.Left to ArrayList<Pair<String, ByteArray>>(), Arm.Right to ArrayList())
    val begins = mutableMapOf(Arm.Left to 0, Arm.Right to 0)
    var otaWrites = 0
        private set
    var controlWritesDuringOta = 0
        private set
    val completedLenses = ArrayList<Arm>()

    private var unreachableUntil = 0L
    private val reassemblers = HashMap<Arm, FrameReassembler>()
    private val dataCount = mutableMapOf(Arm.Left to 0, Arm.Right to 0)
    private val endCount = mutableMapOf(Arm.Left to 0, Arm.Right to 0)

    private class Session {
        var begun = false
        var component: Triple<String, Int, Long>? = null // name, ps, crc
        val written = java.io.ByteArrayOutputStream()
        var markerSeq = -1
        val assembling = HashMap<String, java.io.ByteArrayOutputStream>()
    }

    private val sessions = HashMap<Arm, Session>()

    override suspend fun connect(arm: Arm, ota: Boolean) {
        if (arm in unreachable || clock() < unreachableUntil) throw IOException("$arm not reachable")
        connected += arm
        if (ota) otaEnabled += arm else otaEnabled -= arm
        sessions[arm] = Session()
        reassemblers[arm] = FrameReassembler({ onControl(arm, it) }, {}, clock)
    }

    override fun mtu(arm: Arm): Int = mtu

    override fun isConnected(arm: Arm): Boolean = arm in connected

    override fun isBonded(arm: Arm): Boolean? {
        val at = bondAcceptedAt
        if (bonded[arm] == false && at != null && clock() >= at) bonded[arm] = true
        return bonded[arm]
    }

    override suspend fun disconnect(arm: Arm) {
        if (arm !in connected) return
        connected -= arm
        otaEnabled -= arm
        sessions.remove(arm) // "conn close reset"
        val main = verified.getValue(arm).lastOrNull()?.first == EvenOtaImage.MAIN_APP
        if (main && arm !in completedLenses) {
            completedLenses += arm
            // finishing a lens restarts both lenses
            unreachableUntil = clock() + rebootMs
            connected.clear()
            if (completedLenses.size == 2) extensionAfterFlash?.let { extension = it }
        }
    }

    private fun dropLink(arm: Arm) {
        connected -= arm
        otaEnabled -= arm
        sessions.remove(arm)
        listener?.onDisconnected(arm)
    }

    override suspend fun write(arm: Arm, characteristic: String, frames: List<ByteArray>) {
        if (arm !in connected) throw IOException("$arm not connected")
        when (characteristic) {
            G2Gatt.CONTROL_WRITE -> {
                if (sessions[arm]?.begun == true) controlWritesDuringOta++
                frames.forEach { reassemblers.getValue(arm).push(it) }
            }
            G2Gatt.OTA_WRITE -> {
                check(arm in otaEnabled) { "OTA notifications not enabled" }
                for (f in frames) {
                    if (arm !in connected) throw IOException("$arm not connected")
                    require(f.size <= mtu - 3) { "frame larger than the MTU allows" }
                    otaWrites++
                    onOtaFrame(arm, f)
                }
            }
            else -> error("unknown characteristic $characteristic")
        }
    }

    // ------------------------------------------------------------------ control channel

    private fun reply(arm: Arm, sid: Int, flag: Int, pb: ByteArray) {
        for (f in Envelope.frame(pb, sid, flag, 0)) {
            f[1] = Envelope.RX.toByte()
            listener?.onNotification(arm, G2Gatt.CONTROL_NOTIFY, f)
        }
    }

    private fun onControl(arm: Arm, m: InboundMessage) {
        val magic = m.magic
        when (m.sid) {
            Sid.DEV_CONFIG -> {
                // the immediate answer is never the success; that comes once the link is encrypted
                reply(arm, Sid.DEV_CONFIG, 0, ProtoWriter.build { uint(1, 4); uint(2, magic); message(3) { uint(1, 1) } })
                if (isBonded(arm) != false) reply(arm, Sid.DEV_CONFIG, 0, ProtoWriter.build { uint(1, 4); uint(2, magic); bytes(3, ByteArray(0)) })
            }
            Sid.DASHBOARD -> reply(arm, Sid.DASHBOARD, 0, ProtoWriter.build { uint(1, 2); uint(2, magic) })
            Sid.SETTINGS -> reply(arm, Sid.SETTINGS, 0, ProtoWriter.build {
                uint(1, 2)
                uint(2, magic)
                message(4) {
                    string(5, version)
                    string(6, version)
                    uint(12, battery.getValue(arm))
                    uint(13, 0)
                }
                extension?.let { string(100, it) }
            })
            Sid.EVENHUB -> {
                val cmd = m.command
                reply(arm, Sid.EVENHUB, 0, ProtoWriter.build { uint(1, cmd); uint(2, magic) })
                when (cmd) {
                    0 -> {
                        val page = m.proto?.message(3)
                        promptText = page?.message(3)?.string(12)
                        val answer = promptAnswer ?: return
                        val items = listOf("decline", "approve")
                        // list event; index 0 is omitted like proto3 does
                        reply(arm, Sid.EVENHUB, Envelope.FLAG_NOTIFY, ProtoWriter.build {
                            uint(1, 2)
                            message(13) {
                                message(1) {
                                    string(2, PromptMessages.LIST_NAME)
                                    string(3, items[answer])
                                    if (answer != 0) uint(4, answer)
                                }
                            }
                        })
                    }
                    9 -> shutdowns++
                }
            }
        }
    }

    // ------------------------------------------------------------------ OTA receiver

    private fun ack(arm: Arm, op: Int, status: Int) {
        val f = Envelope.frame(byteArrayOf(op.toByte(), status.toByte()), OtaProtocol.SID_DATA, 0, 0)[0]
        f[1] = Envelope.RX.toByte()
        listener?.onNotification(arm, G2Gatt.OTA_NOTIFY, f)
    }

    private fun onOtaFrame(arm: Arm, f: ByteArray) {
        val s = sessions.getValue(arm)
        val seq = f[2].toInt() and 0xFF
        val total = f[4].toInt() and 0xFF
        val idx = f[5].toInt() and 0xFF
        val sid = f[6].toInt() and 0xFF
        val key = "$sid:$seq"
        val buf = if (idx == 1) java.io.ByteArrayOutputStream().also { s.assembling[key] = it } else s.assembling[key] ?: return
        buf.write(f, Envelope.HEADER, f.size - Envelope.HEADER)
        if (idx != total) return
        s.assembling.remove(key)
        val stream = buf.toByteArray()
        val body = stream.copyOfRange(0, stream.size - 2)
        val crcOk = Crc16.compute(body) == ((stream[stream.size - 2].toInt() and 0xFF) or ((stream[stream.size - 1].toInt() and 0xFF) shl 8))
        when (sid) {
            OtaProtocol.SID_CONTROL -> if (crcOk) onOtaControl(arm, s, body, seq)
            OtaProtocol.SID_DATA -> onOtaData(arm, s, body, seq, crcOk)
        }
    }

    private fun onOtaControl(arm: Arm, s: Session, body: ByteArray, seq: Int) {
        when (body[0].toInt()) {
            OtaProtocol.OP_BEGIN -> {
                s.begun = true
                begins[arm] = begins.getValue(arm) + 1
                ack(arm, 0, 0)
            }
            OtaProtocol.OP_FILE_CHECK -> {
                if (!s.begun || body.size != 129) return ack(arm, 1, 1)
                val sub = body.copyOfRange(1, 129)
                fun u32(o: Int) = (0 until 4).fold(0L) { acc, k -> acc or ((sub[o + k].toLong() and 0xFF) shl (8 * k)) }
                val name = String(sub, 48, 80, Charsets.ISO_8859_1).substringBefore('\u0000')
                s.component = Triple(name, u32(8).toInt(), u32(12))
                s.written.reset()
                ack(arm, 1, 0)
            }
            OtaProtocol.OP_BLOCK -> s.markerSeq = seq
            OtaProtocol.OP_END -> {
                val c = s.component ?: return ack(arm, 3, 10)
                val n = endCount.getValue(arm) + 1
                endCount[arm] = n
                val bytes = s.written.toByteArray()
                val ok = n !in failEnds && bytes.size == c.second && Crc32cMsb.compute(bytes) == c.third
                if (ok) verified.getValue(arm) += c.first to bytes
                s.component = null
                ack(arm, 3, if (ok) 8 else 7)
            }
        }
    }

    private fun onOtaData(arm: Arm, s: Session, body: ByteArray, seq: Int, crcOk: Boolean) {
        if (s.markerSeq != seq || s.component == null) return // an incomplete block is never acked
        s.markerSeq = -1
        val n = dataCount.getValue(arm) + 1
        dataCount[arm] = n
        if (n in dropLinkAtBlocks) {
            dropLinkAtBlocks.remove(n)
            dropLink(arm)
            return
        }
        if (!crcOk || nakBlocks.remove(n)) return ack(arm, 2, 3)
        s.written.write(body)
        if (loseAckOfBlocks.remove(n)) return
        ack(arm, 2, 0)
    }
}
