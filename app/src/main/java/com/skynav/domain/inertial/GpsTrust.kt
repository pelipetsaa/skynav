package com.skynav.domain.inertial

import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import java.util.ArrayDeque
import kotlin.math.max

/**
 * A satellite position is dropped when it contradicts the cell and Wi-Fi position,
 * or when it jumps farther than a car could drive. One repeated network point does not
 * replace the accepted network position, so it cannot by itself reject GPS.
 * Thresholds match NoNGPS `GpsSpoofingDetector`.
 */
class GpsSpoofingDetector {
    var spoofed: Boolean = false
        private set

    private var network: Fix? = null
    private var trusted: Fix? = null
    private var pendingJump: Fix? = null
    private var consistent = 0
    private val phantoms = ArrayDeque<LatLon>()

    /** Returns false when the network point jumped away and is not accepted yet. */
    fun onNetwork(position: LatLon, accuracyM: Double, timeMs: Long): Boolean {
        if (phantoms.any { Geo.haversineM(it, position) <= SAME_POINT_M }) return false
        val current = network
        if (current != null && Geo.haversineM(current.position, position) > tolerance(current.accuracyM)) {
            val pending = pendingJump
            if (pending == null) {
                pendingJump = Fix(position, accuracyM, timeMs)
                return false
            }
            val samePoint = Geo.haversineM(pending.position, position) <= SAME_POINT_M
            val confirmed = !samePoint && Geo.haversineM(pending.position, position) <= tolerance(accuracyM)
            val aged = samePoint && timeMs - pending.timeMs >= JUMP_CONFIRM_MS
            if (confirmed || aged) {
                if (aged) rememberPhantom(current.position)
                acceptNetwork(position, accuracyM, timeMs)
                return true
            }
            if (!samePoint) pendingJump = Fix(position, accuracyM, timeMs)
            return false
        }
        if (current != null && accuracyM * MORE_ACCURATE_FACTOR < current.accuracyM) {
            acceptNetwork(position, accuracyM, timeMs)
            return true
        }
        acceptNetwork(position, accuracyM, timeMs)
        return true
    }

    /** Returns true when this satellite position may be trusted. */
    fun checkSatellite(position: LatLon, timeMs: Long): Boolean {
        val known = trusted
        val net = freshNetwork(timeMs)
        val contradicts = net != null && Geo.haversineM(net.position, position) > tolerance(net.accuracyM)
        if (known == null) {
            if (contradicts) {
                spoofed = true
                consistent = 0
                return false
            }
            trusted = Fix(position, 0.0, timeMs)
            spoofed = false
            consistent = 0
            return true
        }
        val jumpM = Geo.haversineM(known.position, position)
        val dtSec = (timeMs - known.timeMs).coerceAtLeast(0L) / 1000.0
        val reachable = jumpM < MIN_JUMP_M || jumpM <= MAX_SPEED_MPS * dtSec
        if (!reachable || contradicts) {
            spoofed = true
            consistent = 0
            return false
        }
        if (spoofed) {
            consistent++
            val need = if (net != null) CONSISTENT else CONSISTENT_WITHOUT_NETWORK
            if (consistent < need) return false
            spoofed = false
        }
        trusted = Fix(position, 0.0, timeMs)
        consistent = 0
        return true
    }

    private fun acceptNetwork(position: LatLon, accuracyM: Double, timeMs: Long) {
        network = Fix(position, accuracyM, timeMs)
        pendingJump = null
    }

    private fun freshNetwork(timeMs: Long): Fix? {
        val net = network ?: return null
        return if (timeMs - net.timeMs <= NETWORK_MAX_AGE_MS) net else null
    }

    private fun rememberPhantom(position: LatLon) {
        phantoms.addLast(position)
        while (phantoms.size > MAX_PHANTOMS) phantoms.removeFirst()
    }

    private data class Fix(val position: LatLon, val accuracyM: Double, val timeMs: Long)

    companion object {
        const val NETWORK_MAX_AGE_MS = 3 * 60 * 1000L
        const val NETWORK_MIN_TOLERANCE_M = 3_000.0
        const val NETWORK_ACCURACY_FACTOR = 3.0
        const val MAX_SPEED_MPS = 300.0 / 3.6
        const val MIN_JUMP_M = 1_000.0
        const val CONSISTENT = 5
        const val CONSISTENT_WITHOUT_NETWORK = 10
        const val JUMP_CONFIRM_MS = NETWORK_MAX_AGE_MS
        const val SAME_POINT_M = 10.0
        const val MAX_PHANTOMS = 16
        const val MORE_ACCURATE_FACTOR = 2.0

        fun tolerance(accuracyM: Double): Double =
            max(NETWORK_MIN_TOLERANCE_M, accuracyM * NETWORK_ACCURACY_FACTOR)
    }
}

/**
 * GPS is used again only after a stretch of accurate positions that agree with the mark,
 * or after it has followed the car along the roads at the same speed.
 * Thresholds match NoNGPS `GpsReturnDetector`.
 */
class GpsReturnDetector {
    private var streakStartMs: Long? = null
    private var followStartMs: Long? = null
    private var followDistanceM = 0.0
    private var last: Fix? = null

    fun reset() {
        streakStartMs = null
        followStartMs = null
        followDistanceM = 0.0
        last = null
    }

    fun onGps(
        position: LatLon,
        accuracyM: Double,
        timeMs: Long,
        own: LatLon?,
        ownErrorM: Double,
        gpsSpeedMps: Double?,
        carSpeedMps: Double?,
        onRoad: Boolean,
    ): Boolean {
        if (accuracyM > MAX_ACCURACY_M) {
            reset()
            return false
        }
        val previous = last
        if (previous != null) {
            val gap = timeMs - previous.timeMs
            val step = Geo.haversineM(previous.position, position)
            val jumped = gap > MAX_GAP_MS || step > MAX_SPEED_MPS * (gap.coerceAtLeast(0L) / 1000.0)
            if (jumped && step >= MIN_TOLERANCE_M) {
                restart(timeMs)
            } else if (onRoad && speedsAgree(gpsSpeedMps, carSpeedMps)) {
                if (followStartMs == null) followStartMs = previous.timeMs
                followDistanceM += step
            } else {
                followStartMs = timeMs
                followDistanceM = 0.0
            }
        } else {
            restart(timeMs)
        }
        last = Fix(position, timeMs)
        val streak = streakStartMs?.let { timeMs - it } ?: 0L
        val agrees = own != null &&
            Geo.haversineM(own, position) <= ownErrorM + accuracyM + MIN_TOLERANCE_M
        val followed = followStartMs != null &&
            timeMs - followStartMs!! >= MIN_FOLLOW_MS &&
            followDistanceM >= MIN_FOLLOW_DISTANCE_M
        return streak >= MIN_STREAK_MS && (agrees || followed)
    }

    private fun restart(timeMs: Long) {
        streakStartMs = timeMs
        followStartMs = timeMs
        followDistanceM = 0.0
    }

    private fun speedsAgree(gps: Double?, car: Double?): Boolean {
        if (gps == null || car == null) return false
        val diff = kotlin.math.abs(gps - car)
        return diff <= MAX_SPEED_DIFF_MPS || diff <= kotlin.math.abs(car) * MAX_SPEED_DIFF_RATIO
    }

    private data class Fix(val position: LatLon, val timeMs: Long)

    companion object {
        const val MIN_STREAK_MS = 15_000L
        const val MAX_GAP_MS = 3_000L
        const val MAX_ACCURACY_M = 25.0
        const val MAX_SPEED_MPS = 180.0 / 3.6
        const val MIN_TOLERANCE_M = 100.0
        const val MIN_FOLLOW_MS = 30_000L
        const val MIN_FOLLOW_DISTANCE_M = 100.0
        const val MAX_SPEED_DIFF_MPS = 2.0
        const val MAX_SPEED_DIFF_RATIO = 0.2
    }
}
