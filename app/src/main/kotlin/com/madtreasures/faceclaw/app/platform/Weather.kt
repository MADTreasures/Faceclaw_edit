package com.madtreasures.faceclaw.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.madtreasures.faceclaw.core.platform.WeatherCondition
import com.madtreasures.faceclaw.core.platform.WeatherNow
import com.madtreasures.faceclaw.core.platform.WeatherSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Current weather from Open-Meteo (free, no API key) for the phone's last known location. */
class AndroidWeather(private val context: Context, private val scope: CoroutineScope) : WeatherSource {
    private val _current = MutableStateFlow<WeatherNow?>(null)
    override val current: StateFlow<WeatherNow?> = _current
    @Volatile private var lastFetch = 0L

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                fetch()
                delay(30 * 60_000)
            }
        }
    }

    override fun refresh() {
        if (System.currentTimeMillis() - lastFetch < 5 * 60_000) return
        scope.launch(Dispatchers.IO) { fetch() }
    }

    private fun location(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            addAll(listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER))
        }
        return providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun fetch() {
        val loc = location() ?: return
        lastFetch = System.currentTimeMillis()
        runCatching {
            val url = URL(
                String.format(
                    Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                    loc.latitude, loc.longitude,
                ),
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = Json.parseToJsonElement(body).jsonObject
            val current = json["current"]!!.jsonObject
            val daily = json["daily"]?.jsonObject
            val code = current["weather_code"]!!.jsonPrimitive.int
            val (condition, text) = describe(code)
            _current.value = WeatherNow(
                temperatureC = current["temperature_2m"]!!.jsonPrimitive.double,
                condition = condition,
                description = text,
                highC = daily?.first("temperature_2m_max"),
                lowC = daily?.first("temperature_2m_min"),
                place = place(loc),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun JsonObject.first(key: String): Double? = runCatching { this[key]!!.jsonArray[0].jsonPrimitive.double }.getOrNull()

    @Suppress("DEPRECATION")
    private fun place(loc: Location): String? = runCatching {
        Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
    }.getOrNull()

    companion object {
        /** WMO weather interpretation codes. */
        fun describe(code: Int): Pair<WeatherCondition, String> = when (code) {
            0 -> WeatherCondition.Clear to "Clear"
            1 -> WeatherCondition.PartlyCloudy to "Mostly clear"
            2 -> WeatherCondition.PartlyCloudy to "Partly cloudy"
            3 -> WeatherCondition.Cloudy to "Overcast"
            45, 48 -> WeatherCondition.Fog to "Fog"
            51, 53, 55, 56, 57 -> WeatherCondition.Drizzle to "Drizzle"
            61, 63, 65, 66, 67 -> WeatherCondition.Rain to "Rain"
            80, 81, 82 -> WeatherCondition.Rain to "Showers"
            71, 73, 75, 77, 85, 86 -> WeatherCondition.Snow to "Snow"
            95, 96, 99 -> WeatherCondition.Thunderstorm to "Thunderstorm"
            else -> WeatherCondition.Unknown to "—"
        }
    }
}
