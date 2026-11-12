package com.skynav.domain.basestation

import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.RadioTechnology

/**
 * Import format for base station coordinates. One sector per line:
 *
 * `mcc,mnc,tech,cell_id,lat,lon,azimuth_deg,sector_width_deg,range_m,accuracy_m,source,updated_ms`
 *
 * Empty optional fields are null. Lines starting with `#` and the header are skipped.
 * Rows without `accuracy_m` are imported with the default accuracy and marked unverified.
 * Rows with `source=crowdsourced` are always unverified.
 */
object BaseStationCsv {

    const val HEADER = "mcc,mnc,tech,cell_id,lat,lon,azimuth_deg,sector_width_deg,range_m,accuracy_m,source,updated_ms"

    data class Result(
        val stations: List<BaseStation>,
        val rejected: List<String>,
    )

    fun parse(text: String): Result {
        val stations = ArrayList<BaseStation>()
        val rejected = ArrayList<String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("mcc,") }
            .forEach { line ->
                val station = parseLine(line)
                if (station != null) stations.add(station) else rejected.add(line)
            }
        return Result(stations, rejected)
    }

    internal fun parseLine(line: String): BaseStation? {
        val f = line.split(',').map { it.trim() }
        if (f.size < 10) return null

        val mcc = f[0].toIntOrNull() ?: return null
        val mnc = f[1].toIntOrNull() ?: return null
        val tech = runCatching { RadioTechnology.valueOf(f[2].uppercase()) }.getOrNull() ?: return null
        val cellId = f[3].toLongOrNull() ?: return null
        val lat = f[4].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = f[5].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null

        val source = f.getOrNull(10).orEmpty()
        val accuracy = f.getOrNull(9)?.toDoubleOrNull()?.takeIf { it > 0 }

        return BaseStation(
            key = CellKey(mcc, mnc, tech, cellId),
            latitude = lat,
            longitude = lon,
            azimuthDeg = f.getOrNull(6)?.toDoubleOrNull(),
            sectorWidthDeg = f.getOrNull(7)?.toDoubleOrNull(),
            rangeM = f.getOrNull(8)?.toDoubleOrNull()?.takeIf { it > 0 },
            accuracyM = accuracy ?: BaseStation.DEFAULT_ACCURACY_M,
            verified = accuracy != null && source != "crowdsourced",
        )
    }
}
