package com.skynav.domain.inertial

import kotlin.math.abs

/**
 * While GPS is reliable, its turns are compared with the gyroscope.
 * Three turns the other way flip the yaw sign, which is how a phone mounted either way is learned.
 * Thresholds match NoNGPS `TurnSignChecker`.
 */
class TurnSignChecker {
    var reversed: Boolean = false
        private set

    private var lastBearing: Double? = null
    private var lastMs = 0L
    private var gpsTurn = 0.0
    private var gyroTurn = 0.0
    private var sameTurns = 0
    private var oppositeTurns = 0

    fun onGyro(deltaDeg: Double) {
        gyroTurn += deltaDeg
    }

    fun onGpsBearing(bearingDeg: Double, timeMs: Long) {
        val previous = lastBearing
        if (previous == null || timeMs - lastMs > MAX_GPS_GAP_MS) {
            restart(bearingDeg, timeMs)
            return
        }
        val step = angleDiff(previous, bearingDeg)
        if (abs(step) <= MAX_STRAIGHT_STEP_DEG && abs(gpsTurn) >= MIN_TURN_DEG) {
            finish()
            restart(bearingDeg, timeMs)
            return
        }
        gpsTurn += step
        lastBearing = bearingDeg
        lastMs = timeMs
    }

    private fun finish() {
        if (abs(gpsTurn) < MIN_TURN_DEG || abs(gyroTurn) < abs(gpsTurn) / 2.0) {
            gpsTurn = 0.0
            gyroTurn = 0.0
            return
        }
        if (gpsTurn * gyroTurn > 0.0) sameTurns++ else oppositeTurns++
        reversed = oppositeTurns >= TURNS_TO_DECIDE && oppositeTurns > sameTurns
        gpsTurn = 0.0
        gyroTurn = 0.0
    }

    private fun restart(bearingDeg: Double, timeMs: Long) {
        lastBearing = bearingDeg
        lastMs = timeMs
        gpsTurn = 0.0
        gyroTurn = 0.0
    }

    companion object {
        const val MIN_TURN_DEG = 30.0
        const val MAX_STRAIGHT_STEP_DEG = 3.0
        const val MAX_GPS_GAP_MS = 3_000L
        const val TURNS_TO_DECIDE = 3
    }
}

/** Automatic gyro calibration starts only after the phone has been still for 2 s. */
class StillGate {
    private var sinceMs: Long? = null

    fun ready(still: Boolean, timeMs: Long): Boolean {
        if (!still) {
            sinceMs = null
            return false
        }
        val started = sinceMs ?: timeMs.also { sinceMs = it }
        return timeMs - started >= DELAY_MS
    }

    companion object {
        const val DELAY_MS = 2_000L
    }
}
