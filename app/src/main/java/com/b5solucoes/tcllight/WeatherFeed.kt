package com.b5solucoes.tcllight

import android.content.SharedPreferences
import android.os.Handler
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

internal data class WeatherSnapshot(val temperature: Double, val code: Int, val at: Long) {
    fun label(now: Long): String {
        if (now < at || now - at > 6 * 60 * 60 * 1000L) return "Clima indisponível"
        val condition = when (code) {
            0 -> "Céu limpo"; 1, 2 -> "Poucas nuvens"; 3 -> "Nublado"
            45, 48 -> "Neblina"; 51, 53, 55, 56, 57 -> "Garoa"
            61, 63, 65, 66, 67, 80, 81, 82 -> "Chuva"
            95, 96, 99 -> "Trovoadas"; else -> "Tempo local"
        }
        return "${temperature.roundToInt()}°C · $condition" +
            if (now - at > 90 * 60 * 1000L) " · anterior" else ""
    }

    companion object {
        fun parse(body: String, now: Long): WeatherSnapshot {
            val current = JSONObject(body).getJSONObject("current")
            val temperature = current.getDouble("temperature_2m")
            require(temperature.isFinite() && temperature in -60.0..65.0)
            val code = current.getInt("weather_code")
            require(code in 0..99)
            return WeatherSnapshot(temperature, code, now)
        }
    }
}

/** Fixed city, no location permission. Small HTTPS requests, a 45-minute cache and bounded retries. */
internal class WeatherFeed(private val prefs: SharedPreferences, private val main: Handler,
    private val update: (WeatherSnapshot?) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "tv-weather")
    }
    private var active = false
    private var closed = false
    private var loading = false
    private var nextAttempt = 0L
    private val refresh = Runnable { request() }
    private fun cached(): WeatherSnapshot? = try {
        if (!prefs.contains("weather.at")) null else WeatherSnapshot(
            prefs.getString("weather.temperature", "")!!.toDouble(),
            prefs.getInt("weather.code", 0), prefs.getLong("weather.at", 0L))
    } catch (_: Exception) { null }

    fun start() { active = true; update(cached()); request() }
    fun stop() { active = false; main.removeCallbacks(refresh) }

    private fun request() {
        if (!active || closed || loading) return
        val now = System.currentTimeMillis()
        val saved = cached()
        val due = maxOf(nextAttempt, (saved?.at ?: 0L) + 45 * 60 * 1000L)
        if (now < due && due - now < 60 * 60 * 1000L) {
            main.removeCallbacks(refresh); main.postDelayed(refresh, due - now); return
        }
        loading = true
        worker.execute {
            var connection: HttpsURLConnection? = null
            val snapshot = try {
                connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
                connection.connectTimeout = 4500; connection.readTimeout = 4500
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "TCL-Light/0.7 (Android TV)")
                require(connection.responseCode == 200)
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(1024)
                    val result = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        require(result.length + count <= 8192)
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
                WeatherSnapshot.parse(body, System.currentTimeMillis())
            } catch (_: Exception) { null } finally { connection?.disconnect() }
            if (snapshot != null) prefs.edit().putString("weather.temperature", snapshot.temperature.toString())
                .putInt("weather.code", snapshot.code).putLong("weather.at", snapshot.at).apply()
            main.post {
                loading = false
                if (closed) return@post
                nextAttempt = System.currentTimeMillis() + if (snapshot == null) 15 * 60 * 1000L else 0L
                if (active) { update(snapshot ?: cached()); request() }
            }
        }
    }

    fun close() { closed = true; stop(); worker.shutdownNow() }

    companion object {
        // Sede de Garrafão do Norte, PA. Open-Meteo returns only current modelled weather.
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast?latitude=-1.93&longitude=-47.05&current=temperature_2m,weather_code&timezone=America%2FBelem&forecast_days=1"
    }
}
