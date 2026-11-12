package com.skynav.domain.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Local East-North coordinates in meters. */
data class Enu(val x: Double, val y: Double) {
    operator fun plus(o: Enu) = Enu(x + o.x, y + o.y)
    operator fun minus(o: Enu) = Enu(x - o.x, y - o.y)
    operator fun times(k: Double) = Enu(x * k, y * k)
    fun norm(): Double = sqrt(x * x + y * y)
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val h = s1 * s1 + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }
}

/**
 * Equirectangular local tangent plane around [origin].
 * Error is well below 0.1% for distances up to a few tens of km.
 */
class LocalFrame(val origin: LatLon) {
    private val cosLat0 = cos(Math.toRadians(origin.lat))

    fun toEnu(p: LatLon): Enu = Enu(
        x = Math.toRadians(p.lon - origin.lon) * Geo.EARTH_RADIUS_M * cosLat0,
        y = Math.toRadians(p.lat - origin.lat) * Geo.EARTH_RADIUS_M,
    )

    fun toLatLon(e: Enu): LatLon = LatLon(
        lat = origin.lat + Math.toDegrees(e.y / Geo.EARTH_RADIUS_M),
        lon = origin.lon + Math.toDegrees(e.x / (Geo.EARTH_RADIUS_M * cosLat0)),
    )
}
