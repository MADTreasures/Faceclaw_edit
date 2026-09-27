package com.madtreasures.faceclaw.core.firmware

import com.madtreasures.faceclaw.core.protocol.Envelope

/** Acknowledgement of an OTA request, from the OTA notify characteristic. */
data class OtaAck(val opcode: Int, val status: Int)

/**
 * The stock over-the-air update protocol (OTA write/notify characteristics …0001/…0002).
 *
 * Control messages go on sid 0xC0 with the opcode as the first payload byte; a block is a
 * one-byte marker message on 0xC0 followed immediately by the data message on 0xC1, both
 * sharing one envelope sequence number. The receiver has no block index and no de-duplication,
 * so a block may only be resent after an explicit NAK (see docs/analysis/06-firmware.md §7).
 */
object OtaProtocol {
    const val SID_CONTROL = 0xC0
    const val SID_DATA = 0xC1
    const val FLAG = 0x00

    const val OP_BEGIN = 0
    const val OP_FILE_CHECK = 1
    const val OP_BLOCK = 2
    const val OP_END = 3

    /** Every OTA frame is a full 240-byte write, so the ATT MTU must allow 240 + 3 bytes. */
    const val WRITE_SIZE = 240
    const val MIN_MTU = WRITE_SIZE + 3

    /** END results that mean "component verified": SUCCESS, UPDATING (the usual one), SYS_RESTART. */
    val END_OK = setOf(0, 8, 9)

    private val STATUS_NAMES = listOf(
        "SUCCESS", "HEADER_ERR", "PATH_ERR", "CRC_ERR", "TIMEOUT", "NO_RESOURCES",
        "FLASH_WRITE_ERR", "CHECK_FAIL", "UPDATING", "SYS_RESTART", "FAIL",
    )

    fun statusName(status: Int): String = STATUS_NAMES.getOrNull(status) ?: "0x%02x".format(status)

    fun begin(seq: Int): List<ByteArray> = control(OP_BEGIN, ByteArray(0), seq)

    fun fileCheck(subheader: ByteArray, seq: Int): List<ByteArray> {
        require(subheader.size == EvenOtaImage.SUBHEADER_SIZE) { "subheader must be ${EvenOtaImage.SUBHEADER_SIZE} bytes" }
        return control(OP_FILE_CHECK, subheader, seq)
    }

    /** Marker and data frames of one block; they must be written back to back. */
    fun block(data: ByteArray, seq: Int): List<ByteArray> {
        require(data.size in 1..EvenOtaImage.BLOCK_SIZE) { "block size ${data.size}" }
        return control(OP_BLOCK, ByteArray(0), seq) + Envelope.frame(data, SID_DATA, FLAG, seq, WRITE_SIZE)
    }

    fun end(seq: Int): List<ByteArray> = control(OP_END, ByteArray(0), seq)

    private fun control(op: Int, body: ByteArray, seq: Int): List<ByteArray> {
        val pb = ByteArray(1 + body.size)
        pb[0] = op.toByte()
        body.copyInto(pb, 1)
        return Envelope.frame(pb, SID_CONTROL, FLAG, seq, WRITE_SIZE)
    }

    /**
     * Parses a notification from the OTA notify characteristic: an `AA 12` frame whose payload
     * starts with `[opcode, status]`. Only the opcode is used for matching (as both reference
     * flashers do); the frame's sid and sequence are not documented.
     */
    fun parseAck(value: ByteArray): OtaAck? {
        if (value.size < Envelope.HEADER + 2) return null
        if (value[0] != 0xAA.toByte() || (value[1].toInt() and 0xFF) != Envelope.RX) return null
        val len = value[3].toInt() and 0xFF
        if (len < 2) return null
        return OtaAck(value[Envelope.HEADER].toInt() and 0xFF, value[Envelope.HEADER + 1].toInt() and 0xFF)
    }
}
