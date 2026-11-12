package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.RadioTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

class CellularPositionEstimatorTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    private fun station(id: Long, x: Double, y: Double, range: Double? = null): BaseStation {
        val ll = frame.toLatLon(Enu(x, y))
        return BaseStation(
            key = CellKey(260, 3, RadioTechnology.LTE, id),
            latitude = ll.lat,
            longitude = ll.lon,
            rangeM = range,
            verified = true,
        )
    }

    private fun obs(s: BaseStation, ta: Int?, registered: Boolean = true) =
        CellObservation(station = s, timingAdvance = ta, rsrpDbm = -95, registered = registered)

    /** TA index that a phone at [p] would report for a tower at [s]. */
    private fun taFor(s: Enu, p: Enu): Int {
        val d = hypot(p.x - s.x, p.y - s.y)
        return (d / 78.12 - 0.5).roundToInt().coerceIn(0, 1282)
    }

    @Test
    fun noObservationsGivesNoEstimate() {
        assertNull(CellularPositionEstimator(frame).estimate(emptyList()))
    }

    @Test
    fun singleCellWithoutTimingAdvanceCentersOnTowerWithRadiusScaleError() {
        val s = station(1, 0.0, 0.0, range = 500.0)
        val est = CellularPositionEstimator(frame).estimate(listOf(obs(s, ta = null)))!!

        assertTrue("center should be near tower, was ${est.center}", hypot(est.center.x, est.center.y) < 30.0)
        // Uniform disk of radius R has RMS radius ~ 0.7 R.
        assertTrue("accuracy ${est.accuracyM}", est.accuracyM in 250.0..450.0)
    }

    @Test
    fun threeTimingAdvanceRingsTrilaterateWithinAFewTensOfMeters() {
        val truth = Enu(400.0, 300.0)
        val towers = listOf(Enu(0.0, 0.0), Enu(1000.0, 0.0), Enu(0.0, 1000.0))
        val observations = towers.mapIndexed { i, t ->
            obs(station(i + 1L, t.x, t.y, range = 1500.0), ta = taFor(t, truth), registered = i == 0)
        }

        val est = CellularPositionEstimator(frame).estimate(observations)!!
        val err = hypot(est.center.x - truth.x, est.center.y - truth.y)
        assertTrue("trilateration error $err m", err < 80.0)
        assertTrue("TA should tighten accuracy vs single cell", est.accuracyM < 300.0)
    }

    @Test
    fun zeroTimingAdvanceIsTreatedAsNotMeasured() {
        val s = station(1, 0.0, 0.0, range = 500.0)
        val estimator = CellularPositionEstimator(frame)
        val zero = estimator.estimate(listOf(obs(s, ta = 0)))!!
        val none = estimator.estimate(listOf(obs(s, ta = null)))!!
        assertEquals(none.accuracyM, zero.accuracyM, 1e-6)
        assertEquals(none.center.x, zero.center.x, 1.0)
    }

    @Test
    fun outOfRangeTimingAdvanceIsIgnoredAndFallsBackToCoverage() {
        val s = station(1, 0.0, 0.0, range = 500.0)
        val withBogusTa = CellularPositionEstimator(frame).estimate(listOf(obs(s, ta = 5000)))!!
        val withoutTa = CellularPositionEstimator(frame).estimate(listOf(obs(s, ta = null)))!!
        assertEquals(withoutTa.accuracyM, withBogusTa.accuracyM, 1e-6)
    }
}
