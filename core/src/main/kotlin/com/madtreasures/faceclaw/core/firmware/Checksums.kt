package com.madtreasures.faceclaw.core.firmware

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Component checksum of EVENOTA images: polynomial 0x1EDC6F41 (Castagnoli), but MSB-first
 * with init 0 and no final XOR. This is *not* the common reflected iSCSI CRC-32C, so a
 * library implementation gives wrong values. Check value "123456789" = 0xC052A8C8.
 */
object Crc32cMsb {
    private val table = IntArray(256) { b ->
        var c = b shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x1EDC6F41 else c shl 1 }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range out of bounds" }
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc.toLong() and 0xFFFFFFFFL
    }
}

/** zlib CRC-32 (ISO-HDLC), used for the main-app preamble checksum. Check value 0xCBF43926. */
object ZlibCrc32 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range out of bounds" }
        return CRC32().apply { update(data, offset, length) }.value
    }
}

/** Hex digests of whole images (the allow-list and the CDN file names use them). */
object Digests {
    fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))
    fun md5(data: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(data))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

internal fun ByteArray.u32At(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) throw InvalidFirmwareException("image is truncated at 0x${offset.toString(16)}")
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}
