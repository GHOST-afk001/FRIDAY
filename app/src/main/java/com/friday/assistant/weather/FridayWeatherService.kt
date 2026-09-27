package com.friday.assistant.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class FridayWeatherService(private val context: Context) {
    data class Request(val locationText: String?, val date: LocalDate?)

    fun getWeather(request: Request): String {
        val target = request.date ?: LocalDate.now()
        val location = resolveLocation(request.locationText)
            ?: return "Boss, mujhe weather ke liye location nahi mil rahi. City ka naam bata dijiye."
        val today = LocalDate.now(ZoneId.of(location.timezone))
        val json = when {
            !target.isBefore(today) -> fetchForecast(location.latitude, location.longitude, target, today)
            target.isAfter(today.minusDays(10)) -> fetchHistoricalForecast(location.latitude, location.longitude, target)
            else -> fetchHistoricalWeather(location.latitude, location.longitude, target)
        } ?: return "Boss, Open-Meteo se weather data abhi retrieve nahi ho paaya. Main guess nahi karungi."
        return formatWeather(json, location.name, target, today)
    }

    private data class Location(val name: String, val latitude: Double, val longitude: Double, val timezone: String)

    private fun resolveLocation(text: String?): Location? {
        val clean = text?.trim().orEmpty()
        if (clean.isBlank() || clean.equals("my location", true) || clean.equals("current location", true) ||
            clean.contains("mere yahan", true) || clean.contains("mere location", true)) return lastKnownLocation()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json"
        val root = getJson(url) ?: return null
        val result = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Location(result.optString("name").ifBlank { clean }, result.optDouble("latitude", Double.NaN),
            result.optDouble("longitude", Double.NaN), result.optString("timezone").ifBlank { "UTC" })
            .takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
    }

    private fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { provider ->
            runCatching { if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        return Location("your current location", best.latitude, best.longitude, ZoneId.systemDefault().id)
    }

    private fun fetchForecast(lat: Double, lon: Double, date: LocalDate, today: LocalDate): JSONObject? {
        val days = (java.time.temporal.ChronoUnit.DAYS.between(today, date) + 1).toInt().coerceIn(1, 16)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_probability_max,precipitation_sum,weather_code,sunrise,sunset" +
            "&forecast_days=" + days + "&timezone=auto&temperature_unit=celsius&wind_speed_unit=kmh"
        return getJson(url)
    }

    private fun fetchHistoricalForecast(lat: Double, lon: Double, date: LocalDate): JSONObject? {
        val d = date.toString()
        val url = "https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
            "&start_date=" + d + "&end_date=" + d + "&hourly=temperature_2m,apparent_temperature,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,weather_code" +
            "&timezone=auto&temperature_unit=celsius"
        return getJson(url)
    }

    private fun fetchHistoricalWeather(lat: Double, lon: Double, date: LocalDate): JSONObject? {
        val d = date.toString()
        val url = "https://archive-api.open-meteo.com/v1/archive?latitude=" + lat + "&longitude=" + lon +
            "&start_date=" + d + "&end_date=" + d + "&hourly=temperature_2m,apparent_temperature,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,weather_code" +
            "&timezone=auto&temperature_unit=celsius"
        return getJson(url)
    }

    private fun getJson(urlString: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 7000; readTimeout = 9000; useCaches = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val text = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(text)
        } catch (_: Throwable) { null } finally { connection?.disconnect() }
    }

    private fun formatWeather(root: JSONObject, locationName: String, date: LocalDate, today: LocalDate): String {
        val daily = root.optJSONObject("daily")
        val index = findDateIndex(daily?.optJSONArray("time"), date)
        val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(index, Double.NaN) ?: Double.NaN
        val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(index, Double.NaN) ?: Double.NaN
        val apparentMax = daily?.optJSONArray("apparent_temperature_max")?.optDouble(index, Double.NaN) ?: Double.NaN
        val apparentMin = daily?.optJSONArray("apparent_temperature_min")?.optDouble(index, Double.NaN) ?: Double.NaN
        val rain = daily?.optJSONArray("precipitation_sum")?.optDouble(index, Double.NaN) ?: Double.NaN
        val code = daily?.optJSONArray("weather_code")?.optInt(index, -1) ?: -1
        val label = when {
            date == today -> "aaj"; date == today.plusDays(1) -> "kal"; date.isAfter(today) -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
            date == today.minusDays(1) -> "kal (past)"; else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
        }
        val parts = mutableListOf<String>()
        if (max.isFinite() && min.isFinite()) parts += "temperature " + formatC(min) + " se " + formatC(max)
        else {
            val current = root.optJSONObject("current")?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
            if (current.isFinite()) parts += "current temperature " + formatC(current)
        }
        if (apparentMax.isFinite() && apparentMin.isFinite()) parts += "feels-like " + formatC(apparentMin) + " to " + formatC(apparentMax)
        weatherDescription(code).takeIf { it.isNotBlank() }?.let { parts += it }
        if (rain.isFinite()) parts += "precipitation " + String.format(Locale.US, "%.1f mm", rain)
        return if (parts.isEmpty()) "Boss, " + locationName + " ke liye " + label + " ka usable weather value nahi mila."
        else "Boss, " + locationName + " mein " + label + ": " + parts.joinToString(", ") + "."
    }

    private fun findDateIndex(times: org.json.JSONArray?, date: LocalDate): Int {
        if (times == null) return 0
        for (i in 0 until times.length()) if (times.optString(i).startsWith(date.toString())) return i
        return 0
    }

    private fun formatC(value: Double): String = String.format(Locale.US, "%.1f°C", value)

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "fog"
        51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"; 61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77, 85, 86 -> "snow"; 80, 81, 82 -> "rain showers"; 95, 96, 97, 99 -> "thunderstorm"; else -> ""
    }
}