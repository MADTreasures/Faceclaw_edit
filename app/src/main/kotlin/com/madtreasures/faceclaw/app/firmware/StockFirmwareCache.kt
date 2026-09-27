package com.madtreasures.faceclaw.app.firmware

import android.content.Context
import com.madtreasures.faceclaw.app.BuildConfig
import com.madtreasures.faceclaw.core.firmware.Digests
import com.madtreasures.faceclaw.core.firmware.FirmwareBuildException
import com.madtreasures.faceclaw.core.firmware.FirmwareCatalog
import com.madtreasures.faceclaw.core.firmware.FirmwareKind
import com.madtreasures.faceclaw.core.firmware.StockImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The stock image from Even's CDN, kept in app-private storage so later installs need no
 * download. Even's firmware is never bundled with or shared by the app. Whatever is read or
 * downloaded is checked against the pinned SHA-256 before it is returned (and again by the
 * installer).
 */
class StockFirmwareCache(context: Context) : StockImageSource {
    private val dir = File(context.filesDir, "firmware")
    private val file = File(dir, FirmwareCatalog.fileName(FirmwareKind.Stock))

    val isCached: Boolean get() = file.isFile && file.length() == FirmwareCatalog.STOCK_SIZE.toLong()

    override suspend fun load(onProgress: (downloaded: Long, total: Long) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        if (isCached) {
            val bytes = file.readBytes()
            if (Digests.sha256(bytes) == FirmwareCatalog.STOCK_SHA256) return@withContext bytes
            file.delete()
        }
        val bytes = download(onProgress)
        val hash = Digests.sha256(bytes)
        if (hash != FirmwareCatalog.STOCK_SHA256) {
            throw FirmwareBuildException("The downloaded firmware failed verification.\nexpected ${FirmwareCatalog.STOCK_SHA256}\ngot      $hash")
        }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not store the firmware")
        }
        bytes
    }

    fun clear() {
        file.delete()
    }

    private suspend fun download(onProgress: (Long, Long) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(FirmwareCatalog.STOCK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "FaceclawEdit/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw IOException("Could not reach Even's firmware server: ${e.message}", e)
            }
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Firmware download failed (HTTP $code)")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: FirmwareCatalog.STOCK_SIZE.toLong()
            if (total > 64L * 1024 * 1024) throw IOException("unexpected firmware size $total")
            val out = ByteArrayOutputStream(total.toInt())
            val buf = ByteArray(64 * 1024)
            var done = 0L
            conn.inputStream.use { input ->
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done > 64L * 1024 * 1024) throw IOException("firmware download too large")
                    onProgress(done, total)
                }
            }
            out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
