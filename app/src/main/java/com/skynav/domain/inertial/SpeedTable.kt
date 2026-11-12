package com.skynav.domain.inertial

import kotlin.math.abs

/**
 * Scale of the phone speed against trusted GPS, one ratio for each 10 km/h band.
 * The table is kept between trips. A band is used only after 300 m in it.
 * Thresholds match NoNGPS `SpeedTable` for the ratio; the OBD reporting lag is not used.
 */
class SpeedTable {
    private val carM = DoubleArray(RANGES)
    private val gpsM = DoubleArray(RANGES)

    fun onSample(carSpeedKmh: Double, gpsSpeedMps: Double, dtSec: Double, accelerationMps2: Double) {
        if (!isGoodSample(carSpeedKmh, gpsSpeedMps, dtSec, accelerationMps2)) return
        val index = range(carSpeedKmh)
        carM[index] += carSpeedKmh / 3.6 * dtSec
        gpsM[index] += gpsSpeedMps * dtSec
        if (carM[index] > MAX_DISTANCE_M) {
            val keep = MAX_DISTANCE_M / carM[index]
            carM[index] *= keep
            gpsM[index] *= keep
        }
    }

    /** Multiplier for the phone speed. 1 while the band is not measured yet. */
    fun scale(carSpeedKmh: Double): Double {
        val index = range(carSpeedKmh)
        if (carM[index] >= MIN_DISTANCE_M && carM[index] > 0.0) {
            return (gpsM[index] / carM[index]).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        val known = (0 until RANGES).filter { carM[it] >= MIN_DISTANCE_M }
        if (known.isEmpty()) return 1.0
        val nearest = known.minBy { abs(it - index) }
        return (gpsM[nearest] / carM[nearest]).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun serialize(): String = (0 until RANGES).joinToString(";") { "${carM[it]},${gpsM[it]}" }

    fun restore(text: String) {
        val parts = text.split(';')
        if (parts.size != RANGES) return
        val parsed = parts.map { item ->
            val bits = item.split(',')
            if (bits.size != 2) return
            val car = bits[0].toDoubleOrNull() ?: return
            val gps = bits[1].toDoubleOrNull() ?: return
            if (car < 0.0 || gps < 0.0) return
            car to gps
        }
        parsed.forEachIndexed { i, (car, gps) ->
            carM[i] = car
            gpsM[i] = gps
        }
    }

    companion object {
        const val RANGE_KMH = 10
        const val RANGES = 14
        const val MIN_SPEED_KMH = 5.0
        const val MIN_DISTANCE_M = 300.0
        const val MAX_DISTANCE_M = 10_000.0
        const val MAX_ACCELERATION = 1.0
        const val MAX_SAMPLE_DT_SEC = 2.0
        const val MAX_SPEED_DIFF_KMH = 3.0
        const val MAX_SPEED_DIFF_RATIO = 0.15
        const val MIN_SCALE = 0.8
        const val MAX_SCALE = 1.25

        fun range(carSpeedKmh: Double): Int = (carSpeedKmh / RANGE_KMH).toInt().coerceIn(0, RANGES - 1)

        fun isGoodSample(carSpeedKmh: Double, gpsSpeedMps: Double, dtSec: Double, accelerationMps2: Double): Boolean {
            if (carSpeedKmh < MIN_SPEED_KMH || dtSec <= 0.0 || dtSec > MAX_SAMPLE_DT_SEC) return false
            if (abs(accelerationMps2) > MAX_ACCELERATION) return false
            val gpsKmh = gpsSpeedMps * 3.6
            val diff = abs(gpsKmh - carSpeedKmh)
            return diff <= MAX_SPEED_DIFF_KMH || diff <= carSpeedKmh * MAX_SPEED_DIFF_RATIO
        }
    }
}
