package com.madtreasures.faceclaw.core.protocol

import java.util.zip.Inflater

/**
 * A software model of a pair of G2 glasses running the custom firmware, speaking the same
 * protocol as the real hardware. Used by tests and by the app's loopback mode to exercise
 * the whole session (auth, prelude, settings, leases, page, heartbeats, compressed CFW
 * transport, drawing and presenting) without hardware.
 */
class FakeGlasses(
    var firmwareExtension: String? = "Faceclaw/34",
    var battery: Int = 80,
    var charging: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) : BleLink {
    override var listener: BleLink.Listener? = null

    /** What the phone has drawn into the screen buffer (4-bit levels, 640×480). */
    val screen = ByteArray(FrameEncoder.WIDTH * FrameEncoder.HEIGHT)
    /** What the lenses show after the last PRESENT. */
    val composition = ByteArray(FrameEncoder.WIDTH * FrameEncoder.HEIGHT)
    var presents = 0
        private set
    var brightness: Triple<Int, Boolean, Int>? = null
        private set
    var heartbeats = 0
        private set
    var pageCreated = false
        private set
    var cleanedUp = false
        private set
    val connected = HashSet<Arm>()
    /** When set, the next CFW message is NACKed (to exercise replay). */
    var nackNext = false
    /** Drops every n-th CFW ACK notification when > 0 (to exercise timeouts). */
    var dropAckEvery = 0
    private var ackCounter = 0

    private val leaseUntil = HashMap<Arm, Long>()
    private val reassemblers = HashMap<Arm, FrameReassembler>()
    private var txSeq = 0

    // Custom-firmware receive state per receiving lens (both lenses get every packet).
    private class LensState(val lens: Int) {
        var active = false
        var nextSeq = 0
        var streamId = 0
        var options = 0
        val record = ByteWriter()
        var inflater: Inflater? = null
        var contextValid = false
    }

    private val lenses = listOf(LensState(CfwEncoder.LENS_LEFT), LensState(CfwEncoder.LENS_RIGHT))

    override suspend fun connect(arm: Arm) {
        connected += arm
        reassemblers[arm] = FrameReassembler({ onStockMessage(arm, it) }, { onCfwPacket(arm, it) }, clock)
    }

    override fun mtu(arm: Arm): Int = 512

    override suspend fun write(arm: Arm, frames: List<ByteArray>) {
        check(arm in connected) { "$arm not connected" }
        for (f in frames) reassemblers.getValue(arm).push(f)
    }

    override suspend fun disconnect() {
        connected.clear()
    }

    /** Simulates a gesture as the right temple reports it. */
    fun gesture(type: Int, source: Int = 1) {
        val pb = ProtoWriter.build { uint(1, 2); message(13) { message(3) { uint(1, type); uint(2, source) } } }
        notify(Arm.Right, Sid.EVENHUB, Envelope.FLAG_NOTIFY, pb)
    }

    fun wear(onHead: Boolean) {
        notify(Arm.Right, Sid.ONBOARDING, 0, hexBytes("080310002a04080110" + if (onHead) "01" else "00"))
    }

    fun dropLink(arm: Arm) {
        connected -= arm
        listener?.onDisconnected(arm)
    }

    private fun notify(arm: Arm, sid: Int, flag: Int, pb: ByteArray) {
        for (frame in Envelope.frame(pb, sid, flag, txSeq++)) {
            frame[1] = Envelope.RX.toByte()
            listener?.onNotification(arm, G2Gatt.CONTROL_NOTIFY, frame)
        }
    }

    private fun leaseValid(arm: Arm) = (leaseUntil[arm] ?: 0L) > clock()

    // ------------------------------------------------------------------ stock messages

    private fun onStockMessage(arm: Arm, m: InboundMessage) {
        val p = m.proto ?: return
        val magic = p.int(2) ?: 0
        when (m.sid) {
            Sid.DEV_CONFIG -> if (p.int(1) == 4) notify(arm, Sid.DEV_CONFIG, 0, hexBytes("080410") + varint(magic) + hexBytes("1a00"))
            Sid.DASHBOARD -> notify(arm, Sid.DASHBOARD, 0, hexBytes("080210") + varint(magic))
            Sid.SETTINGS -> {
                p.bytes(101)?.let { c ->
                    when (c[3].toInt()) {
                        G2Messages.CfwOp.FB_ACQUIRE -> leaseUntil[arm] = clock() + 90_000
                        G2Messages.CfwOp.FB_RELEASE -> leaseUntil.remove(arm)
                        G2Messages.CfwOp.WEAR_QUERY -> if (arm == Arm.Right) wear(true)
                    }
                    return
                }
                when (p.int(1)) {
                    2 -> {
                        val reply = ProtoWriter.build {
                            uint(1, 4)
                            uint(2, magic)
                            message(4) {
                                uint(1, 1)
                                string(5, "2.3.0.24")
                                string(6, "2.3.0.24")
                                uint(12, battery)
                                uint(13, if (charging) 1 else 0)
                            }
                            firmwareExtension?.let { string(100, it) }
                        }
                        notify(arm, Sid.SETTINGS, 0, reply)
                    }
                    1 -> notify(arm, Sid.SETTINGS, 0, ProtoWriter.build { uint(1, 4); uint(2, magic) })
                }
            }
            Sid.EVENHUB -> when (p.int(1)) {
                0 -> {
                    pageCreated = true
                    notify(arm, Sid.EVENHUB, 0, ProtoWriter.build { uint(1, 1); uint(2, magic); message(4) { uint(1, 0) } })
                }
                12 -> {
                    heartbeats++
                    notify(arm, Sid.EVENHUB, 0, ProtoWriter.build { uint(1, 13); uint(2, magic) })
                }
                9 -> {
                    pageCreated = false
                    notify(arm, Sid.EVENHUB, 0, ProtoWriter.build { uint(1, 10); uint(2, magic) })
                }
                else -> notify(arm, Sid.EVENHUB, 0, ProtoWriter.build { uint(1, (p.int(1) ?: 0) + 1); uint(2, magic) })
            }
        }
    }

    private fun varint(v: Int): ByteArray = ProtoWriter.build { uint(1, v) }.copyOfRange(1, ProtoWriter.build { uint(1, v) }.size)

    // ------------------------------------------------------------------ custom firmware transport

    private fun onCfwPacket(ingress: Arm, p: ByteArray) {
        val len = p.size
        if (len < 11 || p.u8(3) != len - 8) return
        if (Crc16.compute(p, 8, len - 10) != p.u16le(len - 2)) return
        val opt = p.u8(8)
        for (ls in lenses) {
            if (opt and ls.lens == 0) continue
            receive(ls, ingress, p, opt)
        }
    }

    private fun receive(ls: LensState, ingress: Arm, p: ByteArray, opt: Int) {
        val seq = p.u8(2)
        if (opt and CfwEncoder.OPT_RESET != 0) {
            ls.active = true
            ls.nextSeq = seq
            ls.streamId = seq
            ls.options = opt and 3
            ls.record.let { }
            recordBuf[ls.lens] = ByteWriter()
        }
        if (!ls.active || seq != ls.nextSeq || (opt and 3) != ls.options) return
        ls.nextSeq = (ls.nextSeq + 1) and 0xFF
        recordBuf.getValue(ls.lens).bytes(p, 9, p.size - 11)
        if (opt and CfwEncoder.OPT_END != 0) {
            ls.active = false
            complete(ls, ingress, recordBuf.getValue(ls.lens).toByteArray())
        }
    }

    private val recordBuf = HashMap<Int, ByteWriter>()

    private fun complete(ls: LensState, ingress: Arm, rec: ByteArray) {
        if (rec.size < 5) return nack(ls, ingress)
        val flags = rec.u8(0)
        val bodyLen = rec.u16le(1)
        val crc = rec.u16le(3)
        if (rec.size != 5 + bodyLen) return nack(ls, ingress)
        if (flags and CfwEncoder.REC_RESET_CONTEXT != 0) {
            ls.inflater?.end()
            ls.inflater = null
            ls.contextValid = true
        }
        if (!ls.contextValid) return nack(ls, ingress)
        val body = rec.copyOfRange(5, rec.size)
        val message = if (flags and CfwEncoder.REC_COMPRESSED != 0) {
            val inf = ls.inflater ?: Inflater().also { ls.inflater = it }
            inf.setInput(body)
            val out = ByteWriter(4096)
            val buf = ByteArray(8192)
            while (true) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.bytes(buf, 0, n)
            }
            out.toByteArray()
        } else body
        if (Crc16.compute(message) != crc) {
            ls.contextValid = false
            return nack(ls, ingress)
        }
        if (nackNext) {
            nackNext = false
            ls.contextValid = false
            return nack(ls, ingress)
        }
        val armOfLens = if (ls.lens == CfwEncoder.LENS_LEFT) Arm.Left else Arm.Right
        val ok = runCatching { execute(message, armOfLens, ls.lens) }.getOrDefault(false)
        if (!ok) {
            ls.contextValid = false
            return nack(ls, ingress)
        }
        ackCounter++
        if (dropAckEvery > 0 && ackCounter % dropAckEvery == 0) return
        listener?.onNotification(ingress, G2Gatt.CONTROL_NOTIFY, CfwAcks.build(1, ls.streamId, ls.lens, message.size, crc, txSeq = txSeq++))
    }

    private fun nack(ls: LensState, ingress: Arm) {
        listener?.onNotification(ingress, G2Gatt.CONTROL_NOTIFY, CfwAcks.build(3, ls.streamId, ls.lens, 0, 0, txSeq = txSeq++))
    }

    /** Executes a message on one lens. Both lenses share the buffers in this model; only the right lens mutates. */
    private fun execute(m: ByteArray, arm: Arm, lens: Int): Boolean {
        val type = m.u8(0) and 0x7F
        val mutate = lens == CfwEncoder.LENS_RIGHT
        return when (type) {
            CfwType.DRAW_CALLS -> leaseValid(arm) && drawCalls(m, mutate)
            CfwType.PRESENT -> {
                if (!leaseValid(arm) || m.size != 1) return false
                if (mutate) {
                    System.arraycopy(screen, 0, composition, 0, screen.size)
                    presents++
                }
                true
            }
            CfwType.BRIGHTNESS -> {
                if (!leaseValid(arm) || m.size != 6) return false
                if (mutate) brightness = Triple(m.u8(2), m.u8(3) == 1, m.u16le(4))
                true
            }
            CfwType.CLEANUP -> {
                if (mutate) cleanedUp = true
                leaseUntil.remove(arm)
                true
            }
            CfwType.BUZZER, CfwType.DIAGNOSTICS, CfwType.COMPASS, CfwType.AMBIENT_LIGHT, CfwType.RING_BATTERY -> true
            CfwType.SET_ROOT -> leaseValid(arm) && m.size == 3
            else -> false
        }
    }

    private fun drawCalls(m: ByteArray, mutate: Boolean): Boolean {
        var p = 1
        val count = m.u16le(p)
        p += 2
        val target = if (mutate) screen else screen.copyOf()
        repeat(count) {
            val len = m.u16le(p)
            p += 2
            val call = m.copyOfRange(p, p + len)
            p += len
            if (!drawCall(call, target)) return false
        }
        return p == m.size
    }

    private fun drawCall(c: ByteArray, target: ByteArray): Boolean {
        if (c.size < 2 || c.u8(1) != 0) return false
        when (c.u8(0)) {
            CfwDraw.OP_CLEAR -> {
                target.fill((c.u8(2) and 0x0F).toByte())
                return c.size == 3
            }
            CfwDraw.OP_BBOX -> {
                val bf = c.u8(2)
                val x: Int
                val y: Int
                val w: Int
                val h: Int
                var p: Int
                if (bf == 0) {
                    x = c.u8(3) * 4; y = c.u8(4) * 2; w = c.u8(5) * 4; h = c.u8(6) * 2; p = 7
                } else if (bf == 1) {
                    x = c.u16le(3); y = c.u16le(5); w = c.u16le(7); h = c.u16le(9); p = 11
                } else return false
                if (w <= 0 || h <= 0 || x + w > FrameEncoder.WIDTH || y + h > FrameEncoder.HEIGHT) return false
                var i = 0
                val total = w * h
                while (i < total) {
                    if (p >= c.size) return false
                    val t = c.u8(p++)
                    val color = t and 0x0F
                    var n = t ushr 4
                    if (n == 0) {
                        if (p >= c.size) return false
                        n = c.u8(p++)
                        if (n == 0) {
                            if (p + 1 >= c.size) return false
                            n = c.u16le(p)
                            p += 2
                            if (n == 0) return false
                        }
                    }
                    if (i + n > total) return false
                    repeat(n) {
                        val px = x + (i % w)
                        val py = y + (i / w)
                        target[py * FrameEncoder.WIDTH + px] = color.toByte()
                        i++
                    }
                }
                return p == c.size
            }
            else -> return false
        }
    }
}
