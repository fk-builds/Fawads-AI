package com.fawads.ai.util

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight, keyless info providers (all public APIs, no sign-up needed):
 *  - Weather   via Open-Meteo
 *  - Crypto    via CoinGecko
 *  - News      via Google News RSS
 */
object InfoProviders {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    /** Resolve a city name to lat/lon via Open-Meteo geocoding (keyless). Falls back to Islamabad. */
    suspend fun geocode(city: String): Pair<Double, Double> = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext (33.6844 to 73.0479)
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, "UTF-8") + "&count=1&language=en"
            val json = JSONObject(get(url))
            val r = json.optJSONArray("results")?.optJSONObject(0) ?: return@withContext (33.6844 to 73.0479)
            r.getDouble("latitude") to r.getDouble("longitude")
        } catch (e: Exception) {
            33.6844 to 73.0479
        }
    }

    // ------------------------- WEATHER (Open-Meteo, no key) -------------------------
    suspend fun weather(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
        val json = JSONObject(get(url))
        val cur = json.getJSONObject("current")
        val temp = cur.getDouble("temperature_2m")
        val feel = cur.getDouble("apparent_temperature")
        val hum = cur.getInt("relative_humidity_2m")
        val wind = cur.getDouble("wind_speed_10m")
        val code = cur.getInt("weather_code")
        val desc = weatherCode(code)
        "Abhi $temp°C hai (feels like $feel°C), $hum% humidity, wind $wind km/h. $desc"
    }

    private fun weatherCode(code: Int): String = when (code) {
        in 0..1 -> "Safed mausam, dhoop hai ☀️"
        2 -> "Aasman halka cloudy hai"
        3 -> "Overcast hai"
        in 45..48 -> "Dhund (fog) hai"
        in 51..57 -> "Halki boond baandi ho rahi hai 🌧️"
        in 61..67 -> "Baarish ho rahi hai 🌧️"
        in 71..77 -> "Baraf gir rahi hai ❄️"
        in 80..82 -> "Baarish showers ho rahe hain 🌧️"
        in 95..99 -> "Thunderstorm hai ⛈️"
        else -> "Mausam acha hai"
    }

    // ------------------------- CRYPTO (CoinGecko, no key) -------------------------
    suspend fun crypto(coin: String): String = withContext(Dispatchers.IO) {
        val symbol = when (coin.lowercase()) {
            "bitcoin", "btc" -> "bitcoin"
            "ethereum", "eth" -> "ethereum"
            "dogecoin", "doge" -> "dogecoin"
            else -> coin.lowercase()
        }
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=" +
                URLEncoder.encode(symbol, "UTF-8") +
                "&vs_currencies=usd,inr&include_24hr_change=true"
        val json = JSONObject(get(url))
        val node = json.optJSONObject(symbol) ?: return@withContext "Mujhe \"$coin\" nahi mila."
        val usd = node.optDouble("usd", 0.0)
        val inr = node.optDouble("inr", 0.0)
        val chg = node.optDouble("usd_24h_change", 0.0)
        val sign = if (chg >= 0) "+" else ""
        "$coin ($symbol): \$$usd, ₹$inr, 24h change $sign$chg%"
    }

    // ------------------------- NEWS (Google News RSS, no key) -------------------------
    suspend fun news(topics: String = "top stories", limit: Int = 4): String = withContext(Dispatchers.IO) {
        val query = topics.split(" ").take(1).joinToString(" ").ifBlank { "world" }
        val url = "https://news.google.com/rss/search?q=" +
                URLEncoder.encode("$topics when:1d", "UTF-8") +
                "&hl=en-IN&gl=IN&ceid=IN:en"
        val rss = get(url)
        val titles = parseRssTitles(rss, limit)
        if (titles.isEmpty()) "Abhi koi taaza khabar nahi mili."
        else titles.joinToString("\n") { "• $it" }
    }

    private fun parseRssTitles(xml: String, limit: Int): List<String> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser: XmlPullParser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            val out = mutableListOf<String>()
            var inTitle = false
            var title = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "title") { inTitle = true; title = StringBuilder() }
                    XmlPullParser.TEXT -> if (inTitle) title.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "title" && inTitle) {
                        inTitle = false
                        val t = title.toString().trim()
                        if (t.isNotBlank()) out.add(t)
                    }
                }
                if (out.size >= limit && !inTitle) break
                event = parser.next()
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
