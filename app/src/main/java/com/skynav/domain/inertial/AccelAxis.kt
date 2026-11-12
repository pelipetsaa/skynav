package com.skynav.domain.inertial

import com.skynav.domain.model.Vec3
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The forward axis of the phone is unknown. It is the accelerometer direction that
 * follows the change of speed between two radio fixes projected onto the road.
 * Until the correlation is at least 0.75 on 40 comparisons, this adds no distance.
 */
class AccelAxis {
    private val samples = ArrayDeque<Pair<Vec3, Double>>()
    private var direction = Vec3(1.0, 0.0, 0.0)
    var correlation: Double = 0.0
        private set
    var usable: Boolean = false
        private set
    private var speedMps = 0.0

    fun resetSpeed(speedMps: Double) {
        this.speedMps = speedMps.coerceAtLeast(0.0)
    }

    fun currentSpeedMps(): Double = speedMps

    fun onComparison(accel: Vec3, carAccel: Double) {
        if (abs(carAccel) < MIN_CAR_ACCEL) return
        samples.addLast(accel to carAccel)
        while (samples.size > MEMORY) samples.removeFirst()
        refit()
    }

    /**
     * Distance the learned axis would add over [dtSec].
     * Null while the axis is not trusted, so a guess cannot move the car.
     */
    fun distanceM(accel: Vec3, dtSec: Double): Double? {
        if (!usable || dtSec <= 0.0) return null
        val along = accel.dot(direction).coerceIn(-MAX_ACCEL, MAX_ACCEL)
        speedMps = (speedMps + along * dtSec).coerceAtLeast(0.0)
        return speedMps * dtSec
    }

    private fun refit() {
        if (samples.isEmpty()) {
            usable = false
            return
        }
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for ((a, y) in samples) {
            sx += a.x * y
            sy += a.y * y
            sz += a.z * y
        }
        val dir = Vec3(sx, sy, sz)
        val n = dir.norm()
        if (n < 1e-6) {
            usable = false
            return
        }
        direction = Vec3(dir.x / n, dir.y / n, dir.z / n)
        var meanA = 0.0
        var meanY = 0.0
        val projected = DoubleArray(samples.size)
        val targets = DoubleArray(samples.size)
        samples.forEachIndexed { i, (a, y) ->
            projected[i] = a.dot(direction)
            targets[i] = y
            meanA += projected[i]
            meanY += y
        }
        meanA /= samples.size
        meanY /= samples.size
        var num = 0.0
        var denA = 0.0
        var denY = 0.0
        for (i in projected.indices) {
            val da = projected[i] - meanA
            val dy = targets[i] - meanY
            num += da * dy
            denA += da * da
            denY += dy * dy
        }
        correlation = if (denA <= 0.0 || denY <= 0.0) 0.0 else num / sqrt(denA * denY)
        usable = samples.size >= MIN_SAMPLES && correlation >= MIN_CORRELATION
    }

    companion object {
        const val MIN_CAR_ACCEL = 0.5
        const val MEMORY = 240
        const val MIN_SAMPLES = 40
        const val MIN_CORRELATION = 0.75
        const val MAX_ACCEL = 4.5
    }
}

/** Speed along the road between two radio fixes. This is the teacher, in place of an OBD speed. */
fun teacherSpeedMps(distanceAlongRoadM: Double, dtSec: Double): Double? {
    if (dtSec < 1.0) return null
    return (distanceAlongRoadM / dtSec).coerceAtLeast(0.0)
}

fun teacherAccel(previousSpeed: Double, speed: Double, dtSec: Double): Double? {
    if (dtSec < 1.0) return null
    return (speed - previousSpeed) / dtSec
}
