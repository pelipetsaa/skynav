package com.skynav.domain.inertial

import com.skynav.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bias of a still phone, matching NoNGPS `GyroCalibrator`.
 * The accelerometer (or gravity) vector points up. Yaw is rotation about that axis.
 * A calibration is kept only when the next still measurement at the same stop agrees with it.
 */
class GyroCalibrator {
    private var gyroSum = Vec3(0.0, 0.0, 0.0)
    private var gyroSq = Vec3(0.0, 0.0, 0.0)
    private var accelSum = Vec3(0.0, 0.0, 0.0)
    private var accelSq = Vec3(0.0, 0.0, 0.0)
    private var count = 0

    fun reset() {
        gyroSum = Vec3(0.0, 0.0, 0.0)
        gyroSq = Vec3(0.0, 0.0, 0.0)
        accelSum = Vec3(0.0, 0.0, 0.0)
        accelSq = Vec3(0.0, 0.0, 0.0)
        count = 0
    }

    fun add(gyro: Vec3, accel: Vec3) {
        gyroSum += gyro
        gyroSq += Vec3(gyro.x * gyro.x, gyro.y * gyro.y, gyro.z * gyro.z)
        accelSum += accel
        accelSq += Vec3(accel.x * accel.x, accel.y * accel.y, accel.z * accel.z)
        count++
    }

    fun isComplete(): Boolean = count >= REQUIRED_SAMPLES

    fun isStill(): Boolean {
        if (count < 2) return false
        return std(gyroSum, gyroSq, count).max() <= MAX_STILL_GYRO_STD &&
            std(accelSum, accelSq, count).max() <= MAX_STILL_ACCEL_STD
    }

    fun bias(): Vec3 = gyroSum / count.toDouble()

    fun up(): Vec3 {
        val mean = accelSum / count.toDouble()
        val n = mean.norm()
        return if (n < 1e-6) Vec3(0.0, 0.0, 1.0) else mean / n
    }

    companion object {
        const val REQUIRED_SAMPLES = 150
        const val MAX_STILL_GYRO_STD = 0.03
        const val MAX_STILL_ACCEL_STD = 0.5
        const val MAX_BIAS_CHANGE_DEG_S = 0.5
        const val LONG_STOP_MS = 15_000L
        const val MAX_CALIBRATIONS_DIFF_DEG_S = 0.3

        /** Clockwise yaw in degrees per second, the direction a compass heading grows. */
        fun yawRateDeg(gyro: Vec3, bias: Vec3, up: Vec3): Double {
            val axis = up.unit()
            val rad = (gyro - bias).dot(axis)
            return -Math.toDegrees(rad)
        }

        fun biasChangeAllowed(oldBias: Vec3?, newBias: Vec3, up: Vec3, stoppedMs: Long): Boolean {
            if (oldBias == null) return true
            val change = abs(yawRateDeg(newBias, Vec3(0.0, 0.0, 0.0), up) - yawRateDeg(oldBias, Vec3(0.0, 0.0, 0.0), up))
            return change <= MAX_BIAS_CHANGE_DEG_S || stoppedMs >= LONG_STOP_MS
        }

        /** Mean of two still calibrations, or null when they disagree. */
        fun confirm(previous: Vec3, next: Vec3, up: Vec3): Vec3? {
            val diff = abs(
                yawRateDeg(previous, Vec3(0.0, 0.0, 0.0), up) - yawRateDeg(next, Vec3(0.0, 0.0, 0.0), up),
            )
            if (diff > MAX_CALIBRATIONS_DIFF_DEG_S) return null
            return (previous + next) / 2.0
        }
    }
}

private fun Vec3.max(): Double = maxOf(abs(x), abs(y), abs(z))

private operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
private operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
private operator fun Vec3.div(k: Double) = Vec3(x / k, y / k, z / k)
private fun Vec3.unit(): Vec3 {
    val n = norm()
    return if (n < 1e-9) Vec3(0.0, 0.0, 1.0) else this / n
}

private fun std(sum: Vec3, sq: Vec3, n: Int): Vec3 {
    val k = n.toDouble()
    fun one(s: Double, q: Double): Double {
        val mean = s / k
        val variance = (q / k) - mean * mean
        return sqrt(variance.coerceAtLeast(0.0))
    }
    return Vec3(one(sum.x, sq.x), one(sum.y, sq.y), one(sum.z, sq.z))
}
