package com.skynav.presentation

import android.app.Application
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skynav.BuildConfig
import com.skynav.data.lookup.OpenCellIdClient
import com.skynav.data.lookup.WigleClient
import com.skynav.data.telephony.CellularDataCollector
import com.skynav.data.wifi.WifiDataCollector
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.wifi.WifiNetwork
import com.skynav.domain.wifi.wifiCentroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One poll of the radio. [polledAtMs] changes every poll so identical cell lists still refresh the UI. */
data class CellScan(
    val cells: List<CellMeasurement>,
    val polledAtMs: Long,
)

data class LookupState(
    val tower: String = "OpenCelliD: waiting for a serving cell",
    val wifiNote: String = "",
    val wifi: List<WifiNetwork> = emptyList(),
)

/** Polls the radio every 5 s for the diagnostic table. Works without the navigation service. */
class DiagnosticViewModel(app: Application) : AndroidViewModel(app) {

    private val collector = CellularDataCollector(app.getSystemService(TelephonyManager::class.java))
    private val wifiCollector = WifiDataCollector(app)
    private val openCellId = OpenCellIdClient(BuildConfig.OPENCELLID_TOKEN)
    private val wigle = WigleClient(BuildConfig.WIGLE_API_NAME, BuildConfig.WIGLE_API_TOKEN)
    private val towerCache = HashMap<CellKey, String>()
    private val wifiCache = HashMap<String, WifiNetwork>()

    private val _scan = MutableStateFlow(CellScan(emptyList(), 0L))
    val scan: StateFlow<CellScan> = _scan

    private val _lookup = MutableStateFlow(LookupState())
    val lookup: StateFlow<LookupState> = _lookup

    /** Age of the measurement at the moment of the last poll, in ms. */
    fun ageMs(m: CellMeasurement): Long = _scan.value.polledAtMs - m.timestampMs

    init {
        wifiCollector.requestScan()
        viewModelScope.launch {
            while (isActive) {
                val cells = runCatching { withTimeout(8_000) { collector.requestFresh() } }
                    .getOrElse { runCatching { collector.snapshot() }.getOrDefault(emptyList()) }
                _scan.value = CellScan(cells, SystemClock.elapsedRealtime())
                val visible = runCatching { wifiCollector.snapshot() }.getOrDefault(emptyList())
                refreshLookups(cells, visible)
                delay(5_000)
            }
        }
    }

    private suspend fun refreshLookups(cells: List<CellMeasurement>, visible: List<WifiNetwork>) {
        val serving = cells.firstOrNull { it.registered && it.key != null }
        val tower = serving?.key?.let { key ->
            towerCache[key] ?: lookupTower(serving).also { towerCache[key] = it }
        } ?: "OpenCelliD: no serving cell with an identity"

        val located = visible.take(MAX_WIFI_LOOKUPS).map { network ->
            wifiCache[network.bssid] ?: lookupWifi(network).also { wifiCache[network.bssid] = it }
        }
        val note = wifiNote(visible.size, located)
        _lookup.value = LookupState(tower = tower, wifiNote = note, wifi = located)
    }

    private suspend fun lookupTower(cell: CellMeasurement): String = withContext(Dispatchers.IO) {
        val key = cell.key ?: return@withContext "OpenCelliD: no identity"
        val tac = cell.tac ?: return@withContext "OpenCelliD: TAC missing"
        if (BuildConfig.OPENCELLID_TOKEN.isBlank()) return@withContext "OpenCelliD: token not set"
        val point = runCatching {
            openCellId.lookup(key.mcc, key.mnc, tac, key.cellId, key.technology.name)
        }.getOrNull()
        if (point == null) {
            "OpenCelliD: ${key.mcc}/${key.mnc} CI=${key.cellId} not found"
        } else {
            "OpenCelliD: ${point.latitude}, ${point.longitude} range ${point.rangeM ?: "-"} m, samples ${point.samples ?: "-"}"
        }
    }

    private suspend fun lookupWifi(network: WifiNetwork): WifiNetwork {
        if (!wigle.configured) return network
        val point = withContext(Dispatchers.IO) { runCatching { wigle.lookup(network.bssid) }.getOrNull() }
        return if (point == null) network else network.copy(latitude = point.latitude, longitude = point.longitude)
    }

    private fun wifiNote(visible: Int, located: List<WifiNetwork>): String {
        val catalog = if (wigle.configured) "WiGLE" else "WiGLE token not set (wigle.net/account)"
        val withCoordinates = located.count { it.latitude != null }
        val centroid = located.firstOrNull { it.latitude != null && it.longitude != null }?.let { origin ->
            val frame = LocalFrame(LatLon(origin.latitude!!, origin.longitude!!))
            wifiCentroid(located, frame)?.let { frame.toLatLon(it) }
        }
        val centroidText = if (centroid == null) "" else " centroid ${"%.5f".format(centroid.lat)}, ${"%.5f".format(centroid.lon)}"
        return "Wi-Fi visible $visible, looked up ${located.size}, with coordinates $withCoordinates. $catalog.$centroidText"
    }

    private companion object {
        const val MAX_WIFI_LOOKUPS = 5
    }
}
