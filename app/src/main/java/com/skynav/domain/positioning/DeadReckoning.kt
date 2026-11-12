package com.skynav.domain.positioning

import com.skynav.domain.model.Vec3

/**
 * Turns raw device-frame sensor vectors into vehicle-frame quantities.
 *
 * The phone's mounting orientation is unknown, so:
 * - yaw rate is the gyroscope projected onto the gravity direction (vertical axis);
 * - longitudinal acceleration uses a calibrated forward axis in the device frame.
 */
object MotionMath {

    /** Yaw rate about the vertical axis in rad/s, positive counter-clockwise seen from above. */
    fun yawRateAboutVertical(gyro: Vec3, gravity: Vec3): Double? {
        val g = gravity.norm()
        if (g < 1e-3) return null
        return gyro.dot(gravity) / g
    }

    /** Longitudinal acceleration in m/s^2. [forwardAxis] must be a unit vector in the device frame. */
    fun longitudinalAccel(linearAcc: Vec3, forwardAxis: Vec3): Double = linearAcc.dot(forwardAxis)
}

/**
 * Declares the vehicle stopped after speed has stayed below [speedThresholdMps] for [holdMs].
 * Requires a speed source (OBD). Without speed the detector never reports a stop, because
 * a constant-speed drive also looks quiet to the accelerometer.
 */
class StopDetector(
    private val speedThresholdMps: Double = 0.3,
    private val holdMs: Long = 1_500,
) {
    private var quietSinceMs: Long? = null

    fun update(timestampMs: Long, speedMps: Double?): Boolean {
        if (speedMps == null) {
            quietSinceMs = null
            return false
        }
        if (speedMps >= speedThresholdMps) {
            quietSinceMs = null
            return false
        }
        val start = quietSinceMs ?: timestampMs.also { quietSinceMs = it }
        return timestampMs - start >= holdMs
    }
}
