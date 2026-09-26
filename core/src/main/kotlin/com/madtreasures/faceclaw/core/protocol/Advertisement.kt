package com.madtreasures.faceclaw.core.protocol

/** One advertising G2 temple. */
data class G2Advert(
    val address: String,
    val name: String?,
    val side: Arm?,
    /** 14-character serial shared by both temples of a pair. */
    val serial: String?,
    val rssi: Int? = null,
    val bonded: Boolean = false,
)

/** Two temples that belong together (same serial). */
data class GlassesPair(val key: String, val left: G2Advert?, val right: G2Advert?) {
    val complete: Boolean get() = left != null && right != null
    val displayName: String
        get() = serial?.let { "G2 · $it" } ?: (right?.name ?: left?.name ?: "G2 glasses")
    val serial: String? get() = left?.serial ?: right?.serial
    val bestRssi: Int? get() = listOfNotNull(left?.rssi, right?.rssi).maxOrNull()
}

/**
 * Parses G2 advertisements. Manufacturer data (company id 0x5245, "ER") holds the serial,
 * the temple's MAC (reversed) and a flag byte; the name is `Even G2_<n>_<L|R>_<mac6>`.
 */
object G2Advertisement {
    const val COMPANY_ID = 0x5245

    fun side(name: String?): Arm? {
        name ?: return null
        val l = name.contains("_L_")
        val r = name.contains("_R_")
        return when {
            l && !r -> Arm.Left
            r && !l -> Arm.Right
            else -> null
        }
    }

    fun looksLikeG2(name: String?, manufacturerData: ByteArray?): Boolean =
        manufacturerData != null || (name?.uppercase()?.contains("G2") == true && side(name) != null)

    /**
     * [manufacturerData] is the company-specific payload without the 2 company-id bytes
     * (what Android's ScanRecord.getManufacturerSpecificData returns).
     */
    fun parse(address: String, name: String?, manufacturerData: ByteArray?, rssi: Int?, bonded: Boolean = false): G2Advert? {
        if (!looksLikeG2(name, manufacturerData)) return null
        val side = side(name) ?: return null
        val serial = manufacturerData?.let { serial(it) }
        return G2Advert(normalizeAddress(address), name, side, serial, rssi, bonded)
    }

    private fun serial(d: ByteArray): String? {
        if (d.size < 14) return null
        val sb = StringBuilder()
        for (i in 0 until 14) {
            val b = d[i].toInt() and 0xFF
            if (b <= 0x1F || b == 0x7F) continue
            if (b > 0x7E) return null
            sb.append(b.toChar())
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    fun normalizeAddress(a: String): String {
        val hex = a.filter { it.isLetterOrDigit() }.uppercase()
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else a.trim().uppercase()
    }

    /** Groups temples by serial; temples without serial stay alone. Newest report per side wins. */
    fun pairUp(adverts: Collection<G2Advert>): List<GlassesPair> {
        val groups = LinkedHashMap<String, Pair<G2Advert?, G2Advert?>>()
        for (a in adverts) {
            val key = a.serial?.uppercase()?.takeWhile { it.isLetterOrDigit() } ?: a.address
            val (l, r) = groups[key] ?: (null to null)
            groups[key] = if (a.side == Arm.Left) a to r else l to a
        }
        return groups.map { (k, v) -> GlassesPair(k, v.first, v.second) }
            .sortedWith(compareByDescending<GlassesPair> { it.complete }.thenByDescending { it.bestRssi ?: -200 })
    }
}
