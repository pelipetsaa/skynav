package com.skynav.domain.replay

import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.RadioTechnology
import com.skynav.domain.model.Vec3

/**
 * Line-based drive log. One event per line, comma separated, empty fields are null:
 *
 * - `IMU,t,ax,ay,az,gx,gy,gz,grx,gry,grz`
 * - `OBD,t,speed_mps`
 * - `CELL,t,mcc,mnc,tech,cell_id,tac,pci,rsrp,rsrq,sinr,ta,registered`
 * - `GPS,t,lat,lon,accuracy_m`   (ground truth, test only)
 * - `WIFI,t,bssid,rssi,freq_mhz,lat,lon,accuracy_m`   (lat/lon empty until a catalog hit)
 *
 * `t` is elapsed-realtime milliseconds, the same clock for every source.
 */
sealed interface DriveEvent {
    val timestampMs: Long
}

data class ImuEvent(override val timestampMs: Long, val sample: ImuSample) : DriveEvent
data class ObdEvent(override val timestampMs: Long, val speedMps: Double) : DriveEvent
data class CellEvent(override val timestampMs: Long, val cell: CellMeasurement) : DriveEvent
data class WifiEvent(
    override val timestampMs: Long,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
) : DriveEvent

data class GpsTruthEvent(
    override val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
) : DriveEvent

object DriveLog {

    fun parse(lines: Sequence<String>): List<DriveEvent> =
        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .sortedBy { it.timestampMs }
            .toList()

    fun parseLine(line: String): DriveEvent? {
        val f = line.split(',').map { it.trim() }
        return runCatching {
            when (f[0]) {
                "IMU" -> ImuEvent(
                    f[1].toLong(),
                    ImuSample(
                        timestampMs = f[1].toLong(),
                        linearAcc = Vec3(f[2].toDouble(), f[3].toDouble(), f[4].toDouble()),
                        gyro = Vec3(f[5].toDouble(), f[6].toDouble(), f[7].toDouble()),
                        gravity = Vec3(f[8].toDouble(), f[9].toDouble(), f[10].toDouble()),
                    ),
                )
                "OBD" -> ObdEvent(f[1].toLong(), f[2].toDouble())
                "CELL" -> CellEvent(f[1].toLong(), parseCell(f))
                "WIFI" -> WifiEvent(
                    timestampMs = f[1].toLong(),
                    bssid = f[2],
                    rssiDbm = f[3].toInt(),
                    frequencyMhz = f[4].toInt(),
                    latitude = f.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toDouble(),
                    longitude = f.getOrNull(6)?.takeIf { it.isNotEmpty() }?.toDouble(),
                    accuracyM = f.getOrNull(7)?.takeIf { it.isNotEmpty() }?.toDouble(),
                )
                "GPS" -> GpsTruthEvent(f[1].toLong(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble())
                else -> null
            }
        }.getOrNull()
    }

    private fun parseCell(f: List<String>): CellMeasurement {
        fun int(i: Int): Int? = f.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toInt()
        val mcc = int(2)
        val mnc = int(3)
        val tech = f.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { RadioTechnology.valueOf(it) }
        val cellId = f.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toLong()
        val key = if (mcc != null && mnc != null && tech != null && cellId != null) {
            CellKey(mcc, mnc, tech, cellId)
        } else {
            null
        }
        return CellMeasurement(
            timestampMs = f[1].toLong(),
            key = key,
            tac = int(6),
            pci = int(7),
            rsrpDbm = int(8),
            rsrqDb = int(9),
            sinrDb = int(10),
            timingAdvance = int(11),
            registered = f.getOrNull(12) == "1",
        )
    }

    fun formatImu(s: ImuSample): String = listOf(
        "IMU", s.timestampMs,
        s.linearAcc.x, s.linearAcc.y, s.linearAcc.z,
        s.gyro.x, s.gyro.y, s.gyro.z,
        s.gravity.x, s.gravity.y, s.gravity.z,
    ).joinToString(",")

    fun formatObd(t: Long, speedMps: Double): String = "OBD,$t,$speedMps"

    fun formatCell(c: CellMeasurement): String = listOf(
        "CELL",
        c.timestampMs,
        c.key?.mcc ?: "",
        c.key?.mnc ?: "",
        c.key?.technology?.name ?: "",
        c.key?.cellId ?: "",
        c.tac ?: "",
        c.pci ?: "",
        c.rsrpDbm ?: "",
        c.rsrqDb ?: "",
        c.sinrDb ?: "",
        c.timingAdvance ?: "",
        if (c.registered) 1 else 0,
    ).joinToString(",")

    fun formatGps(t: Long, lat: Double, lon: Double, accuracyM: Float): String =
        "GPS,$t,$lat,$lon,$accuracyM"

    fun formatWifi(
        timestampMs: Long,
        bssid: String,
        rssiDbm: Int,
        frequencyMhz: Int,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Double?,
    ): String = listOf(
        "WIFI",
        timestampMs,
        bssid,
        rssiDbm,
        frequencyMhz,
        latitude ?: "",
        longitude ?: "",
        accuracyM ?: "",
    ).joinToString(",")
}
