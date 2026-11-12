package com.skynav.data.routing

import com.skynav.domain.geo.LatLon
import com.skynav.domain.positioning.RoadGraph
import com.skynav.domain.routing.Place
import com.skynav.domain.routing.Route
import com.skynav.domain.routing.parseNominatim
import com.skynav.domain.routing.parseOsrmRoute
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap endpoints used by the navigator.
 * The OSRM host is the public demo server and can be replaced here.
 */
object OsmEndpoints {
    const val NOMINATIM = "https://nominatim.openstreetmap.org"
    const val OSRM = "https://router.project-osrm.org"
    const val OVERPASS = "https://overpass-api.de/api/interpreter"
    const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val USER_AGENT = "SkyNav/0.1 (com.skynav)"
}

class NominatimClient(private val base: String = OsmEndpoints.NOMINATIM) {
    fun search(query: String): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$base/search?q=${encode(q)}&format=jsonv2&limit=5"
        return parseNominatim(get(url) ?: return emptyList())
    }
}

class OverpassClient(private val endpoint: String = OsmEndpoints.OVERPASS) {
    /** OSM XML of drivable ways inside the rectangle, including their nodes. */
    fun highways(south: Double, west: Double, north: Double, east: Double): String? {
        val kinds = RoadGraph.DRIVABLE_HIGHWAYS.joinToString("|")
        val query = """
            [out:xml][timeout:25];
            (
              way["highway"~"^($kinds)$"]($south,$west,$north,$east);
            );
            (._;>;);
            out;
        """.trimIndent()
        return post(endpoint, "data=${encode(query)}")
    }
}

class OsrmClient(private val base: String = OsmEndpoints.OSRM) {
    fun route(from: LatLon, to: LatLon, bearingDeg: Double? = null): Route? {
        val bearings = bearingDeg?.let { "&bearings=${it.toInt()},45;" }.orEmpty()
        val url = "$base/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&steps=true$bearings"
        return parseOsrmRoute(get(url) ?: return null)
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun post(url: String, body: String): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = 8_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.setRequestProperty("User-Agent", OsmEndpoints.USER_AGENT)
    return try {
        connection.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun get(url: String): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 12_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", OsmEndpoints.USER_AGENT)
    return try {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
