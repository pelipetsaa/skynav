package com.skynav.data.lookup

import com.skynav.domain.photo.StreetPhoto
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/**
 * Lists street photos near a point from the Mapillary Graph API.
 * The client token comes from https://www.mapillary.com/dashboard/developers and is sent in the
 * Authorization header, so it is not part of the URL. A blank token returns no photos.
 */
class MapillaryClient(private val token: String) {

    val configured: Boolean get() = token.isNotBlank()

    /** Photos inside a box of about [radiusM] metres around the point. The API caps a box at 0.01 degrees square. */
    fun photosAround(latitude: Double, longitude: Double, radiusM: Double = DEFAULT_RADIUS_M): List<StreetPhoto> {
        if (!configured) return emptyList()
        val box = boundingBox(latitude, longitude, radiusM)
        val url = "$ENDPOINT?fields=$FIELDS&limit=$LIMIT&bbox=$box"
        val body = get(url) ?: return emptyList()
        return parseMapillaryImages(body)
    }

    private fun get(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "OAuth $token")
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else null
            stream?.bufferedReader()?.use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://graph.mapillary.com/images"
        const val FIELDS = "id,geometry,compass_angle,captured_at,thumb_256_url"
        const val LIMIT = 200
        const val DEFAULT_RADIUS_M = 150.0
        const val MAX_BOX_DEG = 0.01

        /** left,bottom,right,top in degrees. The box is roughly [radiusM] on each side. */
        fun boundingBox(latitude: Double, longitude: Double, radiusM: Double): String {
            val dLat = radiusM / METRES_PER_DEG_LAT
            val dLon = radiusM / (METRES_PER_DEG_LAT * cos(Math.toRadians(latitude)))
            return listOf(longitude - dLon, latitude - dLat, longitude + dLon, latitude + dLat)
                .joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
        }

        private const val METRES_PER_DEG_LAT = 111_320.0
    }
}

/**
 * Parses a Mapillary `/images` body. Photos without a valid point are skipped.
 * Mapillary returns the point as GeoJSON, so the coordinates are `[longitude, latitude]`.
 */
fun parseMapillaryImages(json: String): List<StreetPhoto> {
    val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
    val photos = ArrayList<StreetPhoto>(data.length())
    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val coords = item.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        if (coords.length() < 2) continue
        val lon = coords.optDouble(0, Double.NaN)
        val lat = coords.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
        val compass = item.optDouble("compass_angle", Double.NaN).takeUnless { it.isNaN() }
        photos += StreetPhoto(
            id = item.optString("id"),
            latitude = lat,
            longitude = lon,
            compassDeg = compass,
            capturedMs = if (item.has("captured_at")) item.optLong("captured_at") else null,
            thumbUrl = item.optString("thumb_256_url").takeIf { it.isNotBlank() },
        )
    }
    return photos
}
