package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.Position
import com.skynav.domain.model.PositionSource
import com.skynav.domain.model.Vec3
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/** Snapshot of the navigation state, enough for map matching and evaluation. */
data class NavigationSnapshot(
    val timestampMs: Long,
    val position: Position,
    val headingRad: Double,
    val headingSigmaRad: Double,
    val speedMps: Double,
)

/**
 * Pure (Android-free) pipeline used both live and in offline replay:
 * IMU -> EKF prediction, OBD speed -> EKF update, cellular -> posterior fix -> EKF update.
 *
 * Heading is initialized from track displacement once the vehicle has moved far enough
 * between two accepted cellular fixes. Without GPS or Wi-Fi, nothing else anchors heading.
 */
class NavigationEngine(
    private val frame: LocalFrame,
    private val stationLookup: (CellKey) -> BaseStation?,
    private val forwardAxis: Vec3 = Vec3(0.0, 1.0, 0.0),
    private val ekf: ExtendedKalmanFilter = ExtendedKalmanFilter(),
    private val cellEstimator: CellularPositionEstimator = CellularPositionEstimator(frame),
) {
    private val stopDetector = StopDetector()
    private var speedFromObd: Double? = null
    private var speedFromObdMs = Long.MIN_VALUE
    private var headingObserved = false
    private val fixHistory = ArrayDeque<FixRecord>()
    /** Radio timestamp of the last scan that was allowed to update the filter. */
    private var lastAppliedRadioMs: Long? = null

    fun appliedRadioMs(): Long? = lastAppliedRadioMs

    private class FixRecord(val timestampMs: Long, val position: Enu, val accuracyM: Double)

    fun onObdSpeed(timestampMs: Long, speedMps: Double) {
        speedFromObd = speedMps
        speedFromObdMs = timestampMs
        ekf.updateSpeed(speedMps)
    }

    /**
     * Heading and speed taken from the active route.
     * The heading is applied only while the filter's own heading variance is larger, so a
     * later track-based heading is left alone. The speed uses a wide sigma and does not shrink
     * the position covariance on its own.
     */
    fun onRouteCue(bearingRad: Double, bearingVariance: Double, speedMps: Double?) {
        if (!ekf.initialized) return
        val own = ekf.headingSigmaRad()
        if (own * own > bearingVariance) ekf.setHeading(bearingRad, bearingVariance)
        if (speedMps != null) ekf.updateSpeed(speedMps, ROUTE_SPEED_SIGMA)
    }

    fun onImu(sample: ImuSample) {
        val yaw = MotionMath.yawRateAboutVertical(sample.gyro, sample.gravity)
        val acc = MotionMath.longitudinalAccel(sample.linearAcc, forwardAxis)
        val recentSpeed = speedFromObd?.takeIf { sample.timestampMs - speedFromObdMs < OBD_STALE_MS }
        val stopped = stopDetector.update(sample.timestampMs, recentSpeed)
        if (!ekf.initialized) return
        ekf.predict(sample.timestampMs, acc, yaw)
        if (stopped) ekf.updateStationary(yaw)
    }

    /**
     * Feeds one batch of cell measurements taken at the same time.
     * [nowMs] is the clock of the caller (elapsed realtime), not the radio timestamp.
     *
     * A scan is ignored when its radio timestamp was already applied, or when it is older
     * than [MAX_RADIO_AGE_MS]. `TelephonyManager.getAllCellInfo` returns a cache, and feeding
     * that cache again would treat one measurement as many and collapse the covariance.
     */
    fun onCells(
        nowMs: Long,
        measurements: List<CellMeasurement>,
        wifi: List<WifiObservation> = emptyList(),
    ): NavigationSnapshot? {
        val radioMs = measurements.maxOfOrNull { it.timestampMs } ?: return current()
        if (radioMs == lastAppliedRadioMs || nowMs - radioMs > MAX_RADIO_AGE_MS) return current()
        lastAppliedRadioMs = radioMs

        val observations = measurements.mapNotNull { m ->
            val key = m.key ?: return@mapNotNull null
            val station = stationLookup(key) ?: return@mapNotNull null
            CellObservation(station, m.timingAdvance, m.rsrpDbm, m.registered)
        }
        val estimate = cellEstimator.estimate(observations, wifi)
        if (estimate != null) {
            if (!ekf.initialized) {
                ekf.initialize(estimate.center, estimate.cov, nowMs)
            } else {
                ekf.updatePosition(estimate.center, estimate.cov)
            }
            // Record the raw cellular fix (not the filtered state) for track-based heading.
            recordFix(nowMs, estimate.center, estimate.accuracyM)
            maybeInitHeadingFromTrack()
        }
        return current()
    }

    fun current(): NavigationSnapshot? {
        if (!ekf.initialized) return null
        val ll: LatLon = frame.toLatLon(ekf.position())
        return NavigationSnapshot(
            timestampMs = ekf.timestampMs,
            position = Position(
                latitude = ll.lat,
                longitude = ll.lon,
                accuracyM = ekf.accuracyM(),
                timestampMs = ekf.timestampMs,
                source = PositionSource.FUSED,
            ),
            headingRad = ekf.headingRad(),
            headingSigmaRad = ekf.headingSigmaRad(),
            speedMps = ekf.speedMps(),
        )
    }

    private fun recordFix(timestampMs: Long, pos: Enu, accuracyM: Double) {
        fixHistory.addLast(FixRecord(timestampMs, pos, accuracyM))
        while (fixHistory.isNotEmpty() && timestampMs - fixHistory.first().timestampMs > HISTORY_MS) {
            fixHistory.removeFirst()
        }
    }

    /**
     * Sets heading from the displacement between the latest fix and an earlier one at least
     * [MIN_TRACK_BASELINE_M] away. Variance follows the two fixes' accuracy over the baseline.
     */
    private fun maybeInitHeadingFromTrack() {
        if (headingObserved || fixHistory.size < 2) return
        val latest = fixHistory.last()
        // Walk back from the newest fix; take the newest earlier fix whose baseline is long enough
        // relative to the fixes' uncertainty (baseline >= 4 * sigma gives heading sigma <= ~0.25 rad).
        for (i in fixHistory.size - 2 downTo 0) {
            val earlier = fixHistory[i]
            if (latest.timestampMs - earlier.timestampMs > TRACK_WINDOW_MS) break
            val d = latest.position - earlier.position
            val dist = d.norm()
            val sigmaPos = sqrt(latest.accuracyM * latest.accuracyM + earlier.accuracyM * earlier.accuracyM)
            if (dist < max(MIN_TRACK_BASELINE_M, MIN_BASELINE_TO_SIGMA * sigmaPos)) continue
            val sigmaHeading = sigmaPos / dist
            ekf.setHeading(atan2(d.y, d.x), sigmaHeading * sigmaHeading)
            headingObserved = true
            return
        }
    }

    private companion object {
        const val OBD_STALE_MS = 2_000L
        const val HISTORY_MS = 120_000L
        const val TRACK_WINDOW_MS = 120_000L
        const val MIN_TRACK_BASELINE_M = 150.0
        const val MIN_BASELINE_TO_SIGMA = 4.0
        const val MAX_RADIO_AGE_MS = 10_000L
        const val ROUTE_SPEED_SIGMA = 3.0
    }
}
