package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extended Kalman filter over state `[px, py, v, psi, bGyro]`:
 * - `px, py`: position in local ENU (meters);
 * - `v`: forward speed (m/s);
 * - `psi`: heading, counter-clockwise from East (rad);
 * - `bGyro`: yaw-rate bias (rad/s).
 *
 * Prediction uses longitudinal acceleration and measured yaw rate.
 * Updates come from cellular position fixes (gated by Mahalanobis distance),
 * OBD speed, and stationary pseudo-measurements (zero velocity, gyro = bias).
 *
 * Heading is not observable from cellular fixes alone. Initialize it with [setHeading],
 * e.g. from the displacement between consecutive fixes after the vehicle has moved.
 */
class ExtendedKalmanFilter(private val config: Config = Config()) {

    data class Config(
        /** Longitudinal acceleration noise density, m/s^2 per sqrt(s). */
        val accelNoise: Double = 0.5,
        /** Yaw-rate noise density, rad/s per sqrt(s). */
        val gyroNoise: Double = 0.01,
        /** Gyro-bias random walk, rad/s per sqrt(s). */
        val biasWalk: Double = 1e-4,
        /** Per-second position process noise, m^2 per second. */
        val positionNoise: Double = 0.05,
        /** Chi-square gate for 2 DOF at 99%. */
        val gateChi2: Double = 9.21,
        /** After this many consecutive rejected fixes, the filter restarts its position from the fix. */
        val maxRejectStreak: Int = 5,
        val obdSpeedSigma: Double = 0.2,
        val stationarySpeedSigma: Double = 0.1,
        val stationaryBiasSigma: Double = 0.005,
    )

    var initialized: Boolean = false
        private set
    var timestampMs: Long = 0L
        private set

    private val n = 5
    private var x = DoubleArray(n)
    private var p = Array(n) { DoubleArray(n) }
    private var rejectStreak = 0

    fun initialize(pos: Enu, cov: Cov2, timestampMs: Long) {
        x = doubleArrayOf(pos.x, pos.y, 0.0, 0.0, 0.0)
        p = Array(n) { DoubleArray(n) }
        setPositionCov(cov)
        p[2][2] = 15.0 * 15.0
        p[3][3] = Math.PI * Math.PI
        p[4][4] = 0.05 * 0.05
        this.timestampMs = timestampMs
        rejectStreak = 0
        initialized = true
    }

    /** Prediction step. [yawRate] is the measured gyro value; null means no heading integration. */
    fun predict(timestampMs: Long, longitudinalAccel: Double?, yawRate: Double?) {
        if (!initialized) return
        val dt = (timestampMs - this.timestampMs) / 1000.0
        if (dt <= 0.0) return
        this.timestampMs = timestampMs

        val px = x[0]
        val py = x[1]
        val v = x[2]
        val psi = x[3]
        val b = x[4]
        val a = longitudinalAccel ?: 0.0
        val cosP = cos(psi)
        val sinP = sin(psi)

        val next = DoubleArray(n)
        next[0] = px + v * cosP * dt
        next[1] = py + v * sinP * dt
        next[2] = max(0.0, v + a * dt)
        next[3] = wrapRad(psi + ((yawRate ?: 0.0) - b) * dt)
        next[4] = b
        x = next

        val f = identity()
        f[0][2] = cosP * dt
        f[0][3] = -v * sinP * dt
        f[1][2] = sinP * dt
        f[1][3] = v * cosP * dt
        f[3][4] = if (yawRate != null) -dt else 0.0

        val q = Array(n) { DoubleArray(n) }
        q[0][0] = config.positionNoise * dt
        q[1][1] = config.positionNoise * dt
        q[2][2] = config.accelNoise * config.accelNoise * dt
        q[3][3] = config.gyroNoise * config.gyroNoise * dt
        q[4][4] = config.biasWalk * config.biasWalk * dt

        p = add(f.mul(p).mul(f.transpose()), q)
    }

    /** Position update from a cellular fix. Returns false if the fix was gated out. */
    fun updatePosition(fix: Enu, cov: Cov2): Boolean {
        if (!initialized) return false
        val h = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0),
        )
        val r = positionR(cov)
        val accepted = update(h, doubleArrayOf(fix.x, fix.y), r, config.gateChi2)
        if (accepted) {
            rejectStreak = 0
            return true
        }
        rejectStreak++
        if (rejectStreak >= config.maxRejectStreak) {
            // The filter is likely drifting. Re-anchor position on the latest fix.
            x[0] = fix.x
            x[1] = fix.y
            setPositionCov(cov)
            rejectStreak = 0
            return true
        }
        return false
    }

    /** Speed measurement. [sigma] defaults to the OBD figure; a route-derived speed passes a wider one. */
    fun updateSpeed(speedMps: Double, sigma: Double = config.obdSpeedSigma) {
        if (!initialized) return
        val h = arrayOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0))
        val r = arrayOf(doubleArrayOf(sigma * sigma))
        update(h, doubleArrayOf(speedMps), r, gateChi2 = null)
    }

    /**
     * Zero-velocity pseudo-measurement when the vehicle is stopped.
     * The gyro then reads only the bias, so it also refines [bGyro].
     */
    fun updateStationary(yawRateMeasured: Double?) {
        if (!initialized) return
        val vH = arrayOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0))
        val vR = arrayOf(doubleArrayOf(config.stationarySpeedSigma * config.stationarySpeedSigma))
        update(vH, doubleArrayOf(0.0), vR, gateChi2 = null)

        if (yawRateMeasured != null) {
            val bH = arrayOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0))
            val bR = arrayOf(doubleArrayOf(config.stationaryBiasSigma * config.stationaryBiasSigma))
            update(bH, doubleArrayOf(yawRateMeasured), bR, gateChi2 = null)
        }
    }

    /** Sets heading directly, e.g. from track displacement. Clears correlations with heading. */
    fun setHeading(psi: Double, variance: Double) {
        if (!initialized) return
        x[3] = wrapRad(psi)
        for (j in 0 until n) {
            p[3][j] = 0.0
            p[j][3] = 0.0
        }
        p[3][3] = max(variance, 1e-4)
    }

    fun position(): Enu = Enu(x[0], x[1])
    fun speedMps(): Double = x[2]
    fun headingRad(): Double = x[3]
    fun gyroBias(): Double = x[4]
    fun headingSigmaRad(): Double = sqrt(p[3][3])
    fun accuracyM(): Double = sqrt(p[0][0] + p[1][1])

    private fun setPositionCov(cov: Cov2) {
        p[0][0] = max(cov.xx, 1.0)
        p[0][1] = cov.xy
        p[1][0] = cov.xy
        p[1][1] = max(cov.yy, 1.0)
    }

    private fun positionR(cov: Cov2): Array<DoubleArray> = arrayOf(
        doubleArrayOf(max(cov.xx, 1.0), cov.xy),
        doubleArrayOf(cov.xy, max(cov.yy, 1.0)),
    )

    /** Generic Kalman update with Joseph covariance form. Returns false when gated out or singular. */
    private fun update(h: Array<DoubleArray>, z: DoubleArray, r: Array<DoubleArray>, gateChi2: Double?): Boolean {
        val m = z.size
        val hx = DoubleArray(m) { i -> dotRow(h[i], x) }
        val y = DoubleArray(m) { z[it] - hx[it] }

        val ph = p.mul(h.transpose())         // n x m
        val s = add(h.mul(ph), r)             // m x m
        val sInv = invertSmall(s) ?: return false

        if (gateChi2 != null) {
            var d2 = 0.0
            for (i in 0 until m) for (j in 0 until m) d2 += y[i] * sInv[i][j] * y[j]
            if (d2 > gateChi2) return false
        }

        val k = ph.mul(sInv)                  // n x m
        for (i in 0 until n) {
            var s2 = 0.0
            for (j in 0 until m) s2 += k[i][j] * y[j]
            x[i] += s2
        }

        val ikh = identity().also { id ->
            val kh = k.mul(h)
            for (i in 0 until n) for (j in 0 until n) id[i][j] -= kh[i][j]
        }
        p = add(ikh.mul(p).mul(ikh.transpose()), k.mul(r).mul(k.transpose()))
        return true
    }

    private fun identity(): Array<DoubleArray> = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

    private companion object {
        fun wrapRad(a: Double): Double = atan2(sin(a), cos(a))

        fun dotRow(row: DoubleArray, v: DoubleArray): Double {
            var s = 0.0
            for (j in row.indices) s += row[j] * v[j]
            return s
        }

        fun Array<DoubleArray>.mul(o: Array<DoubleArray>): Array<DoubleArray> {
            val cols = o[0].size
            return Array(size) { i ->
                DoubleArray(cols) { j ->
                    var s = 0.0
                    for (k in o.indices) s += this[i][k] * o[k][j]
                    s
                }
            }
        }

        fun Array<DoubleArray>.transpose(): Array<DoubleArray> =
            Array(this[0].size) { j -> DoubleArray(size) { i -> this[i][j] } }

        fun add(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
            Array(a.size) { i -> DoubleArray(a[i].size) { j -> a[i][j] + b[i][j] } }

        /** Inverse for 1x1 and 2x2 matrices (the only sizes used by measurement updates). */
        fun invertSmall(a: Array<DoubleArray>): Array<DoubleArray>? = when (a.size) {
            1 -> {
                val d = a[0][0]
                if (kotlin.math.abs(d) < 1e-12) null else arrayOf(doubleArrayOf(1.0 / d))
            }
            2 -> {
                val det = a[0][0] * a[1][1] - a[0][1] * a[1][0]
                if (kotlin.math.abs(det) < 1e-12) {
                    null
                } else {
                    arrayOf(
                        doubleArrayOf(a[1][1] / det, -a[0][1] / det),
                        doubleArrayOf(-a[1][0] / det, a[0][0] / det),
                    )
                }
            }
            else -> throw IllegalArgumentException("invertSmall supports up to 2x2")
        }
    }
}
