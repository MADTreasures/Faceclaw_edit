package com.madtreasures.faceclaw.core.firmware

/** The bytes are not a flashable G2 firmware image. The message says why. */
class InvalidFirmwareException(message: String) : Exception(message)

/** One component of an EVENOTA container: a 128-byte subheader followed by its payload. */
class FirmwareComponent(
    val index: Int,
    /** File offset of the subheader. */
    val offset: Int,
    /** Payload size in bytes ("ps"). */
    val size: Int,
    /** CRC-32C (MSB-first) of the payload, as recorded in the table of contents. */
    val crc: Long,
    val name: String,
) {
    val payloadOffset: Int get() = offset + EvenOtaImage.SUBHEADER_SIZE
    val end: Int get() = payloadOffset + size
    val blockCount: Int get() = (size + EvenOtaImage.BLOCK_SIZE - 1) / EvenOtaImage.BLOCK_SIZE
}

/**
 * A validated EVENOTA firmware image (the G2's OTA container).
 *
 * Layout: 64-byte header (magic "EVENOTA\0", component count at +8), a 16-byte table-of-contents
 * entry per component (subheader offset +4, size +8, CRC +12), then per component a 128-byte
 * subheader (payload size +8, CRC +12, name +48) directly followed by the payload. Only the
 * subheaders and payloads are sent to the glasses.
 *
 * [parse] applies every check of docs/analysis/06-firmware.md §3.6, including the brick guards
 * on the main application (load address, programmed length, MRAM ceiling). An instance can only
 * exist for bytes that passed all of them.
 */
class EvenOtaImage private constructor(private val data: ByteArray, val components: List<FirmwareComponent>) {
    val size: Int get() = data.size
    val sha256: String by lazy { Digests.sha256(data) }

    /** SHA-256 of the bytes that will be streamed, computed now (for the check right before flashing). */
    fun recomputeSha256(): String = Digests.sha256(data)

    /** Sum of all payload sizes: the bytes a flash actually streams per lens. */
    val payloadBytes: Long get() = components.sumOf { it.size.toLong() }

    val mainApp: FirmwareComponent get() = components.single { it.name == MAIN_APP }

    fun subheader(c: FirmwareComponent): ByteArray = data.copyOfRange(c.offset, c.payloadOffset)

    /** Block [index] of [c]'s payload: [BLOCK_SIZE] bytes, the last one shorter and unpadded. */
    fun block(c: FirmwareComponent, index: Int): ByteArray {
        require(index in 0 until c.blockCount) { "block $index out of range" }
        val start = c.payloadOffset + index * BLOCK_SIZE
        return data.copyOfRange(start, minOf(c.end, start + BLOCK_SIZE))
    }

    fun payload(c: FirmwareComponent): ByteArray = data.copyOfRange(c.payloadOffset, c.end)

    fun bytes(): ByteArray = data.copyOf()

    companion object {
        val MAGIC = byteArrayOf(0x45, 0x56, 0x45, 0x4E, 0x4F, 0x54, 0x41, 0x00) // "EVENOTA\0"
        const val HEADER_SIZE = 0x40
        const val TOC_ENTRY_SIZE = 16
        const val SUBHEADER_SIZE = 128
        const val NAME_OFFSET = 48
        const val NAME_LENGTH = 80
        const val BLOCK_SIZE = 4096

        /** Firmware 2.2.4 has five components; 2.2.6 and later have six. */
        const val MIN_COMPONENTS = 5
        const val MAX_COMPONENTS = 6
        const val MAIN_APP = "ota/s200_firmware_ota.bin"

        /** Where the bootloader programs the main app; it trusts the preamble without bounds checks. */
        const val APP_LOAD_ADDRESS = 0x00438000L
        const val APP_PREAMBLE = 0x20

        /** Conservative ceiling below the OTA flag (0x7FE000) and the bond/NV band. */
        const val APP_MAX_END = 0x007F0000L

        /** Parses and fully validates a private copy of [bytes]. */
        fun parse(image: ByteArray): EvenOtaImage {
            val bytes = image.copyOf()
            val components = parseLayout(bytes)
            if (components.size !in MIN_COMPONENTS..MAX_COMPONENTS) {
                throw InvalidFirmwareException("expected $MIN_COMPONENTS–$MAX_COMPONENTS components, found ${components.size}")
            }
            for (c in components) {
                val computed = Crc32cMsb.compute(bytes, c.payloadOffset, c.size)
                val inSubheader = bytes.u32At(c.offset + 12)
                if (computed != c.crc || computed != inSubheader) {
                    throw InvalidFirmwareException(
                        "component ${c.name}: stored checksum does not match its contents " +
                            "(computed %08x, table %08x, subheader %08x)".format(computed, c.crc, inSubheader),
                    )
                }
            }
            val mains = components.filter { it.name == MAIN_APP }
            if (mains.isEmpty()) throw InvalidFirmwareException("the main application ($MAIN_APP) is missing")
            if (mains.size > 1) throw InvalidFirmwareException("the main application ($MAIN_APP) appears twice")
            checkMainApp(bytes, mains[0])
            return EvenOtaImage(bytes, components)
        }

        /** Container structure only (no checksums); throws [InvalidFirmwareException] on any bounds problem. */
        fun parseLayout(bytes: ByteArray): List<FirmwareComponent> {
            if (bytes.size < HEADER_SIZE) throw InvalidFirmwareException("the file is too small to be a firmware image")
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw InvalidFirmwareException("not an EVENOTA firmware image")
            val count = bytes.u32At(8)
            if (count <= 0 || count > 64) throw InvalidFirmwareException("implausible component count $count")
            val tocEnd = HEADER_SIZE + count.toInt() * TOC_ENTRY_SIZE
            if (tocEnd > bytes.size) throw InvalidFirmwareException("the table of contents runs past the end of the file")
            val out = ArrayList<FirmwareComponent>()
            for (i in 0 until count.toInt()) {
                val entry = HEADER_SIZE + i * TOC_ENTRY_SIZE
                val offset = bytes.u32At(entry + 4)
                val tocSize = bytes.u32At(entry + 8)
                val crc = bytes.u32At(entry + 12)
                if (offset < tocEnd || offset + SUBHEADER_SIZE > bytes.size) {
                    throw InvalidFirmwareException("component $i: subheader outside the file")
                }
                val ps = bytes.u32At(offset.toInt() + 8)
                if (ps <= 0 || offset + SUBHEADER_SIZE + ps > bytes.size) {
                    throw InvalidFirmwareException("component $i: payload outside the file")
                }
                if (tocSize != ps + SUBHEADER_SIZE) {
                    throw InvalidFirmwareException("component $i: table size $tocSize does not match payload size $ps")
                }
                val name = readName(bytes, offset.toInt() + NAME_OFFSET)
                val c = FirmwareComponent(i, offset.toInt(), ps.toInt(), crc, name)
                out.firstOrNull { c.offset < it.end && it.offset < c.end }?.let {
                    throw InvalidFirmwareException("components ${it.name} and ${c.name} overlap")
                }
                out += c
            }
            return out
        }

        private fun readName(bytes: ByteArray, offset: Int): String {
            val sb = StringBuilder()
            for (i in offset until minOf(bytes.size, offset + NAME_LENGTH)) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) break
                sb.append(b.toChar())
            }
            return sb.toString()
        }

        /**
         * The bootloader erases and programs `preamble[0] & 0xFFFFFF` bytes to the address in
         * `preamble[0x14]` without any bounds check, so these fields are the brick guard.
         */
        private fun checkMainApp(bytes: ByteArray, main: FirmwareComponent) {
            if (main.size < APP_PREAMBLE) throw InvalidFirmwareException("the main application is smaller than its preamble")
            val p = main.payloadOffset
            val length = bytes.u32At(p) and 0xFFFFFF
            val storedCrc = bytes.u32At(p + 4)
            val loadAddress = bytes.u32At(p + 0x14)
            if (loadAddress != APP_LOAD_ADDRESS) {
                throw InvalidFirmwareException("main application load address is 0x%x, expected 0x%x".format(loadAddress, APP_LOAD_ADDRESS))
            }
            if (length != main.size.toLong()) {
                throw InvalidFirmwareException("main application preamble length $length differs from its payload size ${main.size}")
            }
            val computedCrc = ZlibCrc32.compute(bytes, p + 8, main.size - 8)
            if (computedCrc != storedCrc) {
                throw InvalidFirmwareException("main application preamble checksum is stale (%08x, computed %08x)".format(storedCrc, computedCrc))
            }
            val programmedEnd = APP_LOAD_ADDRESS + main.size - APP_PREAMBLE
            if (programmedEnd > APP_MAX_END) {
                throw InvalidFirmwareException(
                    "main application is too large: it would end at 0x%x, %d bytes past the safe limit 0x%x (brick risk)"
                        .format(programmedEnd, programmedEnd - APP_MAX_END, APP_MAX_END),
                )
            }
        }
    }
}
