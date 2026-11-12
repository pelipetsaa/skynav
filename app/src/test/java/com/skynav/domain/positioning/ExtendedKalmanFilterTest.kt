package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedKalmanFilterTest {

    private fun filterAt(origin: Enu = Enu(0.0, 0.0), heading: Double = 0.0): ExtendedKalmanFilter {
        val f = ExtendedKalmanFilter()
        f.initialize(origin, Cov2(xx = 100.0, xy = 0.0, yy = 100.0), timestampMs = 0)
        f.setHeading(heading, variance = 1e-4)
        f.updateSpeed(10.0)
        return f
    }

    @Test
    fun predictOnlyStraightLineDriftsAlongHeadingAtSpeed() {
        val f = filterAt()
        var t = 0L
        while (t < 60_000L) {
            t += 100
            f.predict(t, longitudinalAccel = 0.0, yawRate = 0.0)
        }
        assertEquals(600.0, f.position().x, 1.0)
        assertEquals(0.0, f.position().y, 1.0)
    }

    @Test
    fun yawRateIntegratesToHeadingChange() {
        val f = filterAt()
        var t = 0L
        while (t < 10_000L) {
            t += 20
            f.predict(t, longitudinalAccel = 0.0, yawRate = 0.1)
        }
        assertEquals(1.0, f.headingRad(), 0.02)
    }

    @Test
    fun accuracyGrowsWhenNoCorrectionsArrive() {
        val f = filterAt()
        val before = f.accuracyM()
        var t = 0L
        while (t < 60_000L) {
            t += 100
            f.predict(t, longitudinalAccel = null, yawRate = 0.0)
        }
        assertTrue(f.accuracyM() > before)
    }

    @Test
    fun positionFixPullsStateTowardMeasurement() {
        val f = filterAt()
        assertTrue(f.updatePosition(Enu(30.0, 0.0), Cov2(100.0, 0.0, 100.0)))
        assertTrue("x=${f.position().x}", f.position().x in 10.0..20.0)
    }

    @Test
    fun outlierFixIsGatedOut() {
        val f = ExtendedKalmanFilter()
        f.initialize(Enu(0.0, 0.0), Cov2(10.0 * 10.0, 0.0, 10.0 * 10.0), timestampMs = 0)
        assertFalse(f.updatePosition(Enu(1_000.0, 0.0), Cov2(100.0, 0.0, 100.0)))
    }

    @Test
    fun persistentRejectionsReanchorOnFix() {
        val f = ExtendedKalmanFilter(ExtendedKalmanFilter.Config(maxRejectStreak = 3))
        f.initialize(Enu(0.0, 0.0), Cov2(10.0 * 10.0, 0.0, 10.0 * 10.0), timestampMs = 0)
        val far = Enu(1_000.0, 0.0)
        val cov = Cov2(100.0, 0.0, 100.0)
        assertFalse(f.updatePosition(far, cov))
        assertFalse(f.updatePosition(far, cov))
        assertTrue(f.updatePosition(far, cov))
        assertEquals(1_000.0, f.position().x, 1e-9)
    }

    @Test
    fun stationaryGyroReadingsConvergeBiasEstimate() {
        val f = ExtendedKalmanFilter()
        f.initialize(Enu(0.0, 0.0), Cov2(25.0, 0.0, 25.0), timestampMs = 0)
        // Stopped car: true yaw rate is zero, so the gyro reads only its bias (0.02 rad/s).
        repeat(20) { f.updateStationary(yawRateMeasured = 0.02) }
        assertEquals(0.02, f.gyroBias(), 0.002)
        assertEquals(0.0, f.speedMps(), 0.05)
    }
}
