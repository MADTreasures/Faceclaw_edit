package com.madtreasures.faceclaw.core.firmware

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Building an image failed (wrong base image, hash mismatch...). The message is user-presentable. */
class FirmwareBuildException(message: String) : Exception(message)

/** Writes [new] at [offset] after checking that [old] is there; an empty [old] appends at end of file. */
class PatchOp(val offset: Int, val old: ByteArray, val new: ByteArray, val description: String)

/**
 * A byte-level patch set that turns one exact stock image into one exact custom image, in the
 * format of g2flash's `patches/cfw_patches.json`. Both ends are pinned by SHA-256, so applying it
 * either reproduces the reviewed image bit for bit or fails.
 */
class PatchSet(
    val baseName: String,
    val baseSha256: String,
    val outputSha256: String,
    val ops: List<PatchOp>,
) {
    /**
     * Applies the patches to [base] (not modified). Verifies the base hash first, every op's
     * expected bytes while applying, and the output hash at the end.
     */
    fun apply(base: ByteArray, onProgress: (applied: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        val baseHash = Digests.sha256(base)
        if (baseHash != baseSha256) {
            throw FirmwareBuildException("The stock firmware is not the version this patch set was made for.\nexpected $baseSha256\ngot      $baseHash")
        }
        var buf = base.copyOf()
        ops.forEachIndexed { i, op ->
            val tag = "patch #$i at 0x${op.offset.toString(16)} (${op.description})"
            if (op.offset < 0) throw FirmwareBuildException("$tag has a negative offset")
            if (op.old.isNotEmpty()) {
                if (op.offset + op.old.size > buf.size || op.old.size != op.new.size) {
                    throw FirmwareBuildException("$tag lies outside the image")
                }
                val current = buf.copyOfRange(op.offset, op.offset + op.old.size)
                when {
                    current.contentEquals(op.new) && !current.contentEquals(op.old) -> Unit // already applied
                    !current.contentEquals(op.old) -> throw FirmwareBuildException("$tag: unexpected bytes in the stock image")
                    else -> op.new.copyInto(buf, op.offset)
                }
            } else {
                val end = op.offset + op.new.size
                val alreadyThere = end <= buf.size && buf.copyOfRange(op.offset, end).contentEquals(op.new)
                if (!alreadyThere) {
                    if (op.offset != buf.size) throw FirmwareBuildException("$tag: append offset does not match the image length")
                    val grown = buf.copyOf(buf.size + op.new.size)
                    op.new.copyInto(grown, buf.size)
                    buf = grown
                }
            }
            onProgress(i + 1, ops.size)
        }
        val outHash = Digests.sha256(buf)
        if (outHash != outputSha256) {
            throw FirmwareBuildException("The patched firmware failed verification.\nexpected $outputSha256\ngot      $outHash")
        }
        return buf
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PatchSet {
            val spec = json.decodeFromString(Spec.serializer(), text)
            return PatchSet(
                baseName = spec.base,
                baseSha256 = spec.baseSha256.lowercase(),
                outputSha256 = spec.outputSha256.lowercase(),
                ops = spec.patches.map { PatchOp(it.offset, strictHex(it.old), strictHex(it.new), it.desc) },
            )
        }

        /** Loads a patch set from the classpath (e.g. "/firmware/cfw_patches.json"). */
        fun fromResource(path: String): PatchSet {
            val text = PatchSet::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw FirmwareBuildException("patch set $path is missing from the app")
            return parse(text)
        }

        /** Patch data is not user input: reject malformed hex instead of skipping characters. */
        private fun strictHex(s: String): ByteArray {
            if (s.length % 2 != 0 || s.any { Character.digit(it, 16) < 0 }) throw FirmwareBuildException("invalid hex in patch set")
            return ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
        }
    }

    @Serializable
    private class Spec(
        val base: String,
        @SerialName("base_sha256") val baseSha256: String,
        @SerialName("output_sha256") val outputSha256: String,
        val patches: List<OpSpec>,
    )

    @Serializable
    private class OpSpec(val offset: Int, val old: String = "", val new: String, val desc: String = "")
}
