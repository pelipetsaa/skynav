package com.skynav.domain.wifi

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import kotlin.math.max

/** One visible access point. Coordinates are filled only after a catalog lookup. */
data class WifiNetwork(
    val bssid: String,
    val ssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * RSSI-weighted centroid of access points that have catalog coordinates.
 *
 * This is not lateration. RSSI is only a weight: a stronger beacon pulls the centroid
 * toward that access point. Returns null when nothing has a coordinate.
 */
fun wifiCentroid(networks: List<WifiNetwork>, frame: LocalFrame): Enu? {
    val located = networks.mapNotNull { network ->
        val lat = network.latitude ?: return@mapNotNull null
        val lon = network.longitude ?: return@mapNotNull null
        network to frame.toEnu(LatLon(lat, lon))
    }
    if (located.isEmpty()) return null
    var weightSum = 0.0
    var x = 0.0
    var y = 0.0
    for ((network, enu) in located) {
        val weight = max(1.0, (network.rssiDbm + 100).toDouble())
        weightSum += weight
        x += weight * enu.x
        y += weight * enu.y
    }
    return Enu(x / weightSum, y / weightSum)
}
