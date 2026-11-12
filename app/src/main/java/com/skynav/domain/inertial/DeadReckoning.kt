package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Phone dead reckoning using the same limits as NoNGPS `DeadReckoning`.
 * Heading is degrees clockwise from north. A road more than 30° off the heading is ignored.
 * Heading is pulled toward the road only while they differ by less than 10°, with weight 0.1.
 */
class DeadReckoning {
    var hasPosition: Boolean = false
        private set
    var hasHeading: Boolean = false
        private set
    var headingDeg: Double = 0.0
        private set
    var speedMps: Double = 0.0
        private set
    var distanceSinceFixM: Double = 0.0
        private set
    var fixCount: Int = 0
        private set

    private var frame = LocalFrame(LatLon(0.0, 0.0))
    private var enu = Enu(0.0, 0.0)
    private var odometerM = 0.0
    private var aside = false
    private var asideSinceM = 0.0
    private var trustedUntilM = -1.0
    private var trustedRotationDeg = 0.0

    val isReady: Boolean get() = hasPosition && hasHeading

    fun position(): LatLon = frame.toLatLon(enu)

    fun accuracyM(): Double = BASE_ACCURACY_M + ACCURACY_PER_METER * distanceSinceFixM

    fun alongErrorM(): Double = BASE_ACCURACY_M + ALONG_ERROR_PER_METER * distanceSinceFixM

    /** A new reference. The distance that accuracy grows from starts over. */
    fun setPosition(position: LatLon) {
        frame = LocalFrame(position)
        enu = Enu(0.0, 0.0)
        hasPosition = true
        distanceSinceFixM = 0.0
        odometerM = 0.0
        aside = false
        trustedUntilM = -1.0
        trustedRotationDeg = 0.0
        fixCount++
    }

    /** Moves the mark without starting a new reference. */
    fun moveTo(position: LatLon) {
        if (!hasPosition) {
            setPosition(position)
            return
        }
        enu = frame.toEnu(position)
    }

    fun setHeading(headingDeg: Double) {
        this.headingDeg = wrap360(headingDeg)
        hasHeading = true
    }

    fun setSpeed(speedMps: Double) {
        this.speedMps = speedMps.coerceAtLeast(0.0)
    }

    /** The road just chosen is trusted for the next 50 m, until the car turns more than 10°. */
    fun trustRoad() {
        trustedUntilM = odometerM + MAX_ASIDE_ON_ROAD_M
        trustedRotationDeg = 0.0
    }

    /** [deltaDeg] is clockwise. */
    fun rotate(deltaDeg: Double) {
        if (!hasHeading) return
        headingDeg = wrap360(headingDeg + deltaDeg)
        trustedRotationDeg += abs(deltaDeg)
        if (trustedRotationDeg > MAX_TRUSTED_ROTATION_DEG) trustedUntilM = -1.0
    }

    fun advance(dtSec: Double) {
        if (dtSec <= 0.0) return
        advanceBy(speedMps * dtSec)
    }

    fun advanceBy(distanceM: Double) {
        if (!isReady || distanceM == 0.0) return
        val rad = Math.toRadians(headingDeg)
        enu += Enu(distanceM * sin(rad), distanceM * cos(rad))
        val travelled = abs(distanceM)
        distanceSinceFixM += travelled
        odometerM += travelled
    }

    fun reverseBy(distanceM: Double) = advanceBy(-abs(distanceM))

    /** Drops a step that left the road, so a rejected move does not grow the accuracy. */
    fun undoStep(distanceM: Double, back: LatLon) {
        if (!hasPosition) return
        moveTo(back)
        val d = abs(distanceM)
        distanceSinceFixM = (distanceSinceFixM - d).coerceAtLeast(0.0)
        odometerM = (odometerM - d).coerceAtLeast(0.0)
    }

    /** Counts distance already applied by the road walker. */
    fun noteTravel(distanceM: Double) {
        val d = abs(distanceM)
        distanceSinceFixM += d
        odometerM += d
    }

    /**
     * Pulls the mark onto [road]. Returns false when the road heads another way.
     * [roadBearingDeg] is undirected: the opposite direction is the same road.
     */
    fun snapToRoad(road: LatLon, roadBearingDeg: Double, pullHeading: Boolean = true): Boolean {
        if (!isReady) return false
        val target = nearerHeading(headingDeg, roadBearingDeg)
        val diff = abs(angleDiff(headingDeg, target))
        if (diff > MAX_SNAP_HEADING_DIFF_DEG) return false
        if (diff > MAX_HEADING_PULL_DIFF_DEG) {
            if (!aside) {
                aside = true
                asideSinceM = odometerM
            } else if (odometerM - asideSinceM > MAX_ASIDE_ON_ROAD_M) {
                return false
            }
        } else {
            aside = false
        }
        moveTo(road)
        val trusted = odometerM <= trustedUntilM
        if (pullHeading && (trusted || diff < MAX_HEADING_PULL_DIFF_DEG)) {
            val weight = if (trusted) 1.0 else SNAP_HEADING_WEIGHT
            headingDeg = wrap360(headingDeg + angleDiff(headingDeg, target) * weight)
        }
        return true
    }

    companion object {
        const val BASE_ACCURACY_M = 15.0
        const val ACCURACY_PER_METER = 0.02
        const val ALONG_ERROR_PER_METER = 0.007
        const val MAX_SNAP_HEADING_DIFF_DEG = 30.0
        const val MAX_HEADING_PULL_DIFF_DEG = 10.0
        const val SNAP_HEADING_WEIGHT = 0.1
        const val MAX_ASIDE_ON_ROAD_M = 50.0
        const val MAX_TRUSTED_ROTATION_DEG = 10.0
    }
}

fun wrap360(deg: Double): Double {
    val x = deg % 360.0
    return if (x < 0) x + 360.0 else x
}

/** Signed smallest difference [to] - [from], in (-180, 180]. */
fun angleDiff(from: Double, to: Double): Double {
    var d = wrap360(to) - wrap360(from)
    if (d > 180) d -= 360
    if (d <= -180) d += 360
    return d
}

fun undirectedDiff(a: Double, b: Double): Double {
    val d = abs(angleDiff(a, b))
    return min(d, 180.0 - d)
}

/** Road bearings are undirected. Returns the direction of [roadDeg] closest to [headingDeg]. */
fun nearerHeading(headingDeg: Double, roadDeg: Double): Double {
    val forward = wrap360(roadDeg)
    val back = wrap360(roadDeg + 180.0)
    return if (abs(angleDiff(headingDeg, forward)) <= abs(angleDiff(headingDeg, back))) forward else back
}
