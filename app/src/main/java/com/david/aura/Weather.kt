package com.david.aura

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val max: Int?,
    val min: Int?,
    val place: String,
    val unit: String,
) {
    /** Icono Reicon segun el codigo WMO. */
    val iconRes: Int
        get() = when (code) {
            0 -> if (isDay) R.drawable.ri_sun else R.drawable.ri_moon
            1, 2 -> if (isDay) R.drawable.ri_cloud_sun else R.drawable.ri_moon_cloud
            3 -> R.drawable.ri_clouds
            45, 48 -> R.drawable.ri_cloud_fog
            in 51..67, in 80..82 -> R.drawable.ri_cloud_rain
            in 71..77, 85, 86 -> R.drawable.ri_cloud_snow
            in 95..99 -> R.drawable.ri_cloud_bolt
            else -> R.drawable.ri_clouds
        }

    /** Nombre del icono para la web (mismos iconos Reicon). */
    val iconName: String
        get() = when (code) {
            0 -> if (isDay) "sun" else "moon"
            1, 2 -> if (isDay) "cloud-sun" else "moon-cloud"
            3 -> "clouds"
            45, 48 -> "cloud-fog"
            in 51..67, in 80..82 -> "cloud-rain"
            in 71..77, 85, 86 -> "cloud-snow"
            in 95..99 -> "cloud-bolt"
            else -> "clouds"
        }

    fun description(lang: String): String {
        val en = lang == "en"
        return when (code) {
            0 -> if (en) "Clear" else "Despejado"
            1 -> if (en) "Mostly clear" else "Casi despejado"
            2 -> if (en) "Partly cloudy" else "Parcialmente nublado"
            3 -> if (en) "Cloudy" else "Nublado"
            45, 48 -> if (en) "Fog" else "Niebla"
            in 51..57 -> if (en) "Drizzle" else "Llovizna"
            in 61..67 -> if (en) "Rain" else "Lluvia"
            in 71..77 -> if (en) "Snow" else "Nieve"
            in 80..82 -> if (en) "Showers" else "Chubascos"
            85, 86 -> if (en) "Snow showers" else "Chubascos de nieve"
            in 95..99 -> if (en) "Thunderstorm" else "Tormenta"
            else -> ""
        }
    }

    companion object {
        /** Open-Meteo: gratuito y sin clave. */
        suspend fun fetch(loc: WeatherLoc, unit: String): Weather? = withContext(Dispatchers.IO) {
            runCatching {
                val unitParam = if (unit == "F") "&temperature_unit=fahrenheit" else ""
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
                        "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                        "&timezone=auto&forecast_days=1$unitParam"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = Json.parseToJsonElement(body).jsonObject
                val cur = root["current"]!!.jsonObject
                val daily = root["daily"]?.jsonObject
                Weather(
                    temp = cur["temperature_2m"]!!.jsonPrimitive.double.roundToInt(),
                    code = cur["weather_code"]!!.jsonPrimitive.int,
                    isDay = cur["is_day"]!!.jsonPrimitive.int == 1,
                    max = daily?.get("temperature_2m_max")?.jsonArray?.firstOrNull()?.jsonPrimitive?.double?.roundToInt(),
                    min = daily?.get("temperature_2m_min")?.jsonArray?.firstOrNull()?.jsonPrimitive?.double?.roundToInt(),
                    place = loc.name,
                    unit = if (unit == "F") "F" else "C",
                )
            }.getOrNull()
        }
    }
}
