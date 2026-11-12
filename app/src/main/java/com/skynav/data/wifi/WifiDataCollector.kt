package com.skynav.data.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.skynav.domain.wifi.WifiNetwork

/**
 * Reads the latest Wi-Fi scan. Requires ACCESS_FINE_LOCATION.
 * [requestScan] asks for a new scan; Android throttles it to a few scans per two minutes.
 * The redacted BSSID `02:00:00:00:00:00` is dropped.
 */
class WifiDataCollector(context: Context) {

    private val wifi = context.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    fun snapshot(): List<WifiNetwork> =
        wifi?.scanResults.orEmpty().mapNotNull { result ->
            val bssid = result.BSSID ?: return@mapNotNull null
            if (bssid.equals(REDACTED_BSSID, ignoreCase = true)) return@mapNotNull null
            WifiNetwork(
                bssid = bssid,
                ssid = result.SSID.orEmpty(),
                rssiDbm = result.level,
                frequencyMhz = result.frequency,
            )
        }.sortedByDescending { it.rssiDbm }

    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = wifi?.startScan() == true

    private companion object {
        const val REDACTED_BSSID = "02:00:00:00:00:00"
    }
}
