package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** A known station plus what the phone measured towards it. */
data class CellObservation(
    val station: BaseStation,
    val timingAdvance: Int?,
    val rsrpDbm: Int?,
    val registered: Boolean,
)

data class Cov2(val xx: Double, val xy: Double, val yy: Double)

/**
 * One catalogued access point. [accuracyM] is the uncertainty of the catalog coordinate.
 * RSSI is not a range and does not shrink [accuracyM].
 */
data class WifiObservation(
    val latitude: Double,
    val longitude: Double,
    val rssiDbm: Int,
    val accuracyM: Double,
) {
    companion object {
        /** A single crowdsourced hit, such as WiGLE, is not a surveyed antenna. */
        const val CROWDSOURCED_ACCURACY_M = 200.0
    }
}

data class CellularEstimate(
    val center: Enu,
    val cov: Cov2,
    /** RMS radius, sqrt(var_x + var_y). Not a 95% bound. */
    val accuracyM: Double,
    val usedObservations: Int,
)

/**
 * Grid posterior over the plane around the serving station.
 *
 * Each observation contributes a log-likelihood term:
 * - LTE Timing Advance: distance ring at (TA + 0.5) * 78.12 m with sigma of half a TA step plus a multipath term.
 *   TA of 0 is ignored. Several phone modems, including the test Samsung, report 0 when they did not measure TA, and a real reading of 0 cannot be told apart from that.
 * - Otherwise: soft coverage disk of the station's range (or a default range).
 * - Known sector azimuth: soft penalty for points outside the sector.
 * - RSRP and registration status only scale the observation weight. They are not distances.
 * - Wi-Fi: a Gaussian blob at the catalog coordinate, with the catalog accuracy as sigma.
 *   RSSI only scales the weight. An access point whose coordinate lies outside every cell's
 *   coverage is dropped, so a distant catalog hit cannot drag the fix.
 *
 * The posterior mean is the estimate and its covariance is the uncertainty.
 * Returns null for quality gate failures (no usable observations, accuracy too poor).
 */
class CellularPositionEstimator(
    private val frame: LocalFrame,
    private val maxAccuracyM: Double = 3_000.0,
    private val maxGridCells: Int = 150,
) {
    fun estimate(
        observations: List<CellObservation>,
        wifi: List<WifiObservation> = emptyList(),
    ): CellularEstimate? {
        if (observations.isEmpty() && wifi.isEmpty()) return null

        val anchor = observations.firstOrNull { it.registered } ?: observations.firstOrNull()
        val anchorPos = if (anchor != null) {
            frame.toEnu(LatLon(anchor.station.latitude, anchor.station.longitude))
        } else {
            val first = wifi.first()
            frame.toEnu(LatLon(first.latitude, first.longitude))
        }
        val acceptedWifi = wifi.filter { insideCellCoverage(observations, it) }
        val models = observations.map { Model.of(frame, it) } + acceptedWifi.map { Model.wifi(frame, it) }
        if (models.isEmpty()) return null

        val half = models
            .maxOf { m -> (m.center - anchorPos).norm() + m.extentM }
            .coerceIn(MIN_HALF_M, MAX_HALF_M)
        val step = max(MIN_STEP_M, 2 * half / maxGridCells)
        val n = floor(2 * half / step).toInt() + 1

        val logL = DoubleArray(n * n)
        var maxLog = -Double.MAX_VALUE
        for (iy in 0 until n) {
            for (ix in 0 until n) {
                val p = Enu(anchorPos.x - half + ix * step, anchorPos.y - half + iy * step)
                var l = 0.0
                for (m in models) l += m.logLikelihood(p)
                logL[iy * n + ix] = l
                if (l > maxLog) maxLog = l
            }
        }

        var sumW = 0.0
        var mx = 0.0
        var my = 0.0
        val w = DoubleArray(n * n)
        for (k in logL.indices) {
            w[k] = exp(logL[k] - maxLog)
            sumW += w[k]
        }
        for (k in logL.indices) {
            val px = anchorPos.x - half + (k % n) * step
            val py = anchorPos.y - half + (k / n) * step
            mx += w[k] * px
            my += w[k] * py
        }
        mx /= sumW
        my /= sumW

        var vxx = 0.0
        var vxy = 0.0
        var vyy = 0.0
        for (k in logL.indices) {
            val dx = anchorPos.x - half + (k % n) * step - mx
            val dy = anchorPos.y - half + (k / n) * step - my
            vxx += w[k] * dx * dx
            vxy += w[k] * dx * dy
            vyy += w[k] * dy * dy
        }
        vxx /= sumW
        vxy /= sumW
        vyy /= sumW

        val accuracy = sqrt(vxx + vyy)
        if (!accuracy.isFinite() || accuracy > maxAccuracyM) return null

        return CellularEstimate(
            center = Enu(mx, my),
            cov = Cov2(vxx, vxy, vyy),
            accuracyM = accuracy,
            usedObservations = observations.size + acceptedWifi.size,
        )
    }

    /** With no cells, every access point is kept. With cells, the catalog point must sit inside a coverage disk. */
    private fun insideCellCoverage(cells: List<CellObservation>, ap: WifiObservation): Boolean {
        if (cells.isEmpty()) return true
        val p = frame.toEnu(LatLon(ap.latitude, ap.longitude))
        return cells.any { cell ->
            val c = frame.toEnu(LatLon(cell.station.latitude, cell.station.longitude))
            hypot(p.x - c.x, p.y - c.y) <= (cell.station.rangeM ?: DEFAULT_RANGE_M)
        }
    }

    private class Model(
        val center: Enu,
        val rangeM: Double,
        val distanceM: Double?,
        val sigmaDistanceM: Double,
        val weight: Double,
        val azimuthDeg: Double?,
        val halfSectorDeg: Double?,
    ) {
        val extentM: Double
            get() = max(rangeM, (distanceM ?: 0.0) + 3 * sigmaDistanceM)

        fun logLikelihood(p: Enu): Double {
            val dx = p.x - center.x
            val dy = p.y - center.y
            val r = hypot(dx, dy)

            var l = if (distanceM != null) {
                val e = (r - distanceM) / sigmaDistanceM
                -0.5 * e * e
            } else {
                val over = max(0.0, r - rangeM) / (0.25 * rangeM)
                -0.5 * over * over
            }

            if (azimuthDeg != null && halfSectorDeg != null && r > 1.0) {
                val bearingDeg = Math.toDegrees(atan2(dx, dy)) // 0 = north, clockwise
                val diff = wrapDeg(bearingDeg - azimuthDeg)
                val outside = max(0.0, abs(diff) - halfSectorDeg)
                l += -0.5 * (outside / 10.0).pow(2)
            }
            return weight * l
        }

        companion object {
            fun of(frame: LocalFrame, obs: CellObservation): Model {
                val center = frame.toEnu(LatLon(obs.station.latitude, obs.station.longitude))
                val range = obs.station.rangeM ?: DEFAULT_RANGE_M
                val ta = obs.timingAdvance?.takeIf { it in 1..MAX_LTE_TA }
                val distance = ta?.let { (it + 0.5) * LTE_TA_STEP_M }
                val sigma = distance?.let { 0.5 * LTE_TA_STEP_M + 0.15 * it } ?: 0.0
                val rsrpWeight = obs.rsrpDbm?.let { ((it + 120.0) / 40.0).coerceIn(0.2, 1.0) } ?: 0.6
                val weight = rsrpWeight * if (obs.registered) 1.0 else 0.7
                return Model(
                    center = center,
                    rangeM = range,
                    distanceM = distance,
                    sigmaDistanceM = sigma,
                    weight = weight,
                    azimuthDeg = obs.station.azimuthDeg,
                    halfSectorDeg = obs.station.sectorWidthDeg?.let { it / 2 },
                )
            }

            /** Gaussian at the catalog coordinate. Sigma is the catalog accuracy, not an RSSI range. */
            fun wifi(frame: LocalFrame, ap: WifiObservation): Model {
                val sigma = ap.accuracyM.coerceAtLeast(MIN_WIFI_SIGMA_M)
                val weight = ((ap.rssiDbm + 100.0) / 60.0).coerceIn(0.2, 1.0)
                return Model(
                    center = frame.toEnu(LatLon(ap.latitude, ap.longitude)),
                    rangeM = sigma,
                    distanceM = 0.0,
                    sigmaDistanceM = sigma,
                    weight = weight,
                    azimuthDeg = null,
                    halfSectorDeg = null,
                )
            }
        }
    }
}

/** Wraps an angle in degrees to [-180, 180). */
internal fun wrapDeg(a: Double): Double {
    var x = (a + 180.0) % 360.0
    if (x < 0) x += 360.0
    return x - 180.0
}

private const val DEFAULT_RANGE_M = 1_000.0
private const val LTE_TA_STEP_M = 78.12
private const val MAX_LTE_TA = 1282
private const val MIN_HALF_M = 300.0
private const val MAX_HALF_M = 6_000.0
private const val MIN_STEP_M = 10.0
private const val MIN_WIFI_SIGMA_M = 25.0
