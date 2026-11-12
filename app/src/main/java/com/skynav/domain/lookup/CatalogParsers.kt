package com.skynav.domain.lookup

/** A coordinate returned by an external catalog. [detail] is a short source note, not a guarantee. */
data class CatalogPoint(
    val latitude: Double,
    val longitude: Double,
    val rangeM: Int? = null,
    val samples: Int? = null,
    val label: String? = null,
)

/**
 * Parses an OpenCelliD `/cell/get?format=json` body.
 * A missing cell comes back as HTTP 200 with no `lat`, and this returns null.
 */
fun parseOpenCellId(json: String): CatalogPoint? {
    val lat = jsonNumber(json, "lat") ?: return null
    val lon = jsonNumber(json, "lon") ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return CatalogPoint(
        latitude = lat,
        longitude = lon,
        rangeM = jsonNumber(json, "range")?.toInt(),
        samples = jsonNumber(json, "samples")?.toInt(),
    )
}

/**
 * Parses a WiGLE `/api/v2/network/search` body and returns the first result that has a position.
 * WiGLE names the fields `trilat` and `trilong`.
 */
fun parseWigleSearch(json: String): CatalogPoint? {
    val lat = jsonNumber(json, "trilat") ?: return null
    val lon = jsonNumber(json, "trilong") ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return CatalogPoint(latitude = lat, longitude = lon, label = jsonString(json, "ssid"))
}

private fun jsonNumber(json: String, key: String): Double? =
    Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

private fun jsonString(json: String, key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
