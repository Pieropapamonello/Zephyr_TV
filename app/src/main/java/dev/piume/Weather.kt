package dev.piume

import java.net.URLEncoder

/**
 * The weather under the clock. The place comes from the public IP (a TV has no GPS and Android TV no location
 * service) or from a city picked by hand; the weather from Open-Meteo. All three services are free and keyless.
 * Network: call these off the UI thread.
 */
internal object Weather {
    class Place(val name: String, val where: String, val lat: Float, val lon: Float)

    class Reading(val celsius: Float, val code: Int)

    /** Where the IP seems to be, or null when the service can't tell. An IP's place can be a region off. */
    fun locate(): Place? {
        val geo = Http.json("https://ipwho.is/?fields=success,city,latitude,longitude")
        if (!geo.optBoolean("success")) return null
        return Place(geo.getString("city"), "", geo.getDouble("latitude").toFloat(), geo.getDouble("longitude").toFloat())
    }

    /** The weather there now. Always Celsius: switching units needs no new request. */
    fun now(lat: Float, lon: Float): Reading {
        val now = Http.json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
            .getJSONObject("current")
        return Reading(now.getDouble("temperature_2m").toFloat(), now.getInt("weather_code"))
    }

    /** Places called [query] (up to 8), named in [language] where Open-Meteo knows it. */
    fun search(query: String, language: String): List<Place> {
        val r = Http.json("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
            "&language=$language&name=${URLEncoder.encode(query, "UTF-8")}")
        val list = r.optJSONArray("results") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val c = list.getJSONObject(i)
            Place(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
        }
    }
}
