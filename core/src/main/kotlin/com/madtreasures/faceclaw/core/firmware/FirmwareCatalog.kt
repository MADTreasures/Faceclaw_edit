package com.madtreasures.faceclaw.core.firmware

/** What can be installed on the glasses. */
enum class FirmwareKind {
    /** Even Realities' unmodified firmware (removes the custom firmware). */
    Stock,

    /** The Faceclaw custom firmware this app needs. */
    Custom,
}

/**
 * The firmware images this app will ever flash, pinned by SHA-256.
 *
 * Even's firmware is never shipped with the app: the stock image is downloaded from Even's CDN
 * and the custom image is produced on the phone by applying the bundled patch set
 * (`/firmware/cfw_patches.json`, from g2flash, GPLv3) to it.
 */
object FirmwareCatalog {
    const val STOCK_VERSION = "2.3.0.24"
    const val STOCK_URL = "https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin"

    /** The CDN names files by MD5; it doubles as a quick check of a download. */
    const val STOCK_MD5 = "1dbdf37b03a1169c384945e94d671371"
    const val STOCK_SHA256 = "187ccf2bcc5c17a212106e8a376745511e8289c4232b634a7ea94b9bf25a0979"
    const val STOCK_SIZE = 4_537_963

    /** Revision string the custom firmware reports in settings field 100. */
    const val CUSTOM_REVISION = 34
    const val CUSTOM_SHA256 = "7d8764f8b720252354dcd1695d23578b11f9b7cf5df9895d08b9f6668e9d0ee7"
    const val CUSTOM_SIZE = 4_607_691

    const val PATCH_SET_RESOURCE = "/firmware/cfw_patches.json"

    val patchSet: PatchSet by lazy {
        val p = PatchSet.fromResource(PATCH_SET_RESOURCE)
        check(p.baseSha256 == STOCK_SHA256 && p.outputSha256 == CUSTOM_SHA256) { "bundled patch set does not match the catalog" }
        p
    }

    /** The allow-list: which firmware [sha256] is, or null when it must never be flashed. */
    fun kindOf(sha256: String): FirmwareKind? = when (sha256.lowercase()) {
        STOCK_SHA256 -> FirmwareKind.Stock
        CUSTOM_SHA256 -> FirmwareKind.Custom
        else -> null
    }

    fun fileName(kind: FirmwareKind): String = when (kind) {
        FirmwareKind.Stock -> "g2_$STOCK_VERSION.bin"
        FirmwareKind.Custom -> "g2_${STOCK_VERSION}_cfw.bin"
    }

    /**
     * Turns the verified stock image into the image for [kind]: the stock image itself, or the
     * patched custom image. The result is fully validated and on the allow-list.
     */
    fun prepare(kind: FirmwareKind, stock: ByteArray, onPatch: (applied: Int, total: Int) -> Unit = { _, _ -> }): EvenOtaImage {
        val stockHash = Digests.sha256(stock)
        if (stockHash != STOCK_SHA256) {
            throw FirmwareBuildException("The downloaded firmware failed verification.\nexpected $STOCK_SHA256\ngot      $stockHash")
        }
        val bytes = when (kind) {
            FirmwareKind.Stock -> stock
            FirmwareKind.Custom -> patchSet.apply(stock, onPatch)
        }
        val image = EvenOtaImage.parse(bytes)
        if (kindOf(image.sha256) != kind) throw FirmwareBuildException("The prepared image is not the expected firmware")
        return image
    }
}

/** How the glasses' current firmware relates to what this app can install. */
enum class InstalledFirmware {
    /** Faceclaw custom firmware at the revision the app needs (or newer). */
    CurrentCustom,

    /** An older Faceclaw revision (including pre-revision "EVENCFW" builds). */
    OlderCustom,

    /** Custom firmware from some other project. */
    ForeignCustom,

    /** Stock firmware at or below the version the custom image is built from. */
    Stock,

    /** Stock firmware newer than the base: installing is a downgrade nobody has tested. */
    NewerStock,

    /** Nothing could be read. */
    Unknown,
}

/** Firmware as reported by a settings read: per-arm versions and the settings field 100 string. */
data class ReportedFirmware(val leftVersion: String?, val rightVersion: String?, val extension: String?) {
    /** The higher of the two reported versions. */
    val version: String?
        get() = listOfNotNull(leftVersion?.trim()?.takeIf { it.isNotEmpty() }, rightVersion?.trim()?.takeIf { it.isNotEmpty() })
            .maxWithOrNull { a, b -> compareVersions(a, b) }

    /** "Faceclaw/<n>" revision, strictly parsed (digits only). */
    val customRevision: Int?
        get() {
            val ext = extension?.trim() ?: return null
            if (!ext.startsWith("Faceclaw/")) return null
            val digits = ext.removePrefix("Faceclaw/")
            return if (digits.isNotEmpty() && digits.all(Char::isDigit)) digits.toIntOrNull() else null
        }

    fun classify(requiredRevision: Int = FirmwareCatalog.CUSTOM_REVISION): InstalledFirmware {
        val ext = extension?.trim().orEmpty()
        return when {
            ext.startsWith("Faceclaw/") -> {
                val rev = customRevision
                if (rev != null && rev >= requiredRevision) InstalledFirmware.CurrentCustom
                else if (rev != null) InstalledFirmware.OlderCustom
                else InstalledFirmware.ForeignCustom
            }
            ext.startsWith("EVENCFW") -> InstalledFirmware.OlderCustom
            ext.isNotEmpty() -> InstalledFirmware.ForeignCustom
            version == null -> InstalledFirmware.Unknown
            compareVersions(version!!, FirmwareCatalog.STOCK_VERSION) <= 0 -> InstalledFirmware.Stock
            else -> InstalledFirmware.NewerStock
        }
    }

    companion object {
        /** Component-wise numeric compare of dotted versions; missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
