package com.skynav.domain.positioning

import com.skynav.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMathTest {

    @Test
    fun yawRateIsGyroComponentAlongGravity() {
        // Phone flat-ish with a roll component on X; only the vertical projection counts.
        val yaw = MotionMath.yawRateAboutVertical(
            gyro = Vec3(0.3, 0.0, 0.4),
            gravity = Vec3(0.0, 0.0, 9.81),
        )
        assertEquals(0.4, yaw!!, 1e-9)
    }

    @Test
    fun yawRateIsNullWithoutGravity() {
        assertNull(MotionMath.yawRateAboutVertical(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 0.0)))
    }

    @Test
    fun longitudinalAccelProjectsOntoForwardAxis() {
        val a = MotionMath.longitudinalAccel(Vec3(0.0, 1.5, 0.2), Vec3(0.0, 1.0, 0.0))
        assertEquals(1.5, a, 1e-9)
    }

    @Test
    fun stopDetectorNeedsHoldTime() {
        val d = StopDetector(speedThresholdMps = 0.3, holdMs = 1_500)
        assertFalse(d.update(0, 0.0))
        assertFalse(d.update(1_000, 0.1))
        assertTrue(d.update(1_500, 0.1))
    }

    @Test
    fun stopDetectorResetsWhenMoving() {
        val d = StopDetector()
        d.update(0, 0.0)
        assertFalse(d.update(1_000, 5.0))
        assertFalse(d.update(2_000, 0.0))
    }

    @Test
    fun stopDetectorNeverReportsStopWithoutSpeed() {
        val d = StopDetector(holdMs = 0)
        assertFalse(d.update(0, null))
        assertFalse(d.update(10_000, null))
    }
}
