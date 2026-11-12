package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.RadioTechnology
import com.skynav.domain.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Synthetic end-to-end run: a car drives due east at 10 m/s for 90 s past three towers.
 * Cell scans every 2 s, IMU at 50 Hz (straight line, so gyro and accel are zero), OBD at 1 Hz.
 */
class NavigationEngineTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    private val towers = listOf(
        Triple(1L, -200.0, 400.0),
        Triple(2L, 600.0, -400.0),
        Triple(3L, 1400.0, 400.0),
    )

    private val stationsByKey: Map<CellKey, BaseStation> = towers.associate { (id, x, y) ->
        val ll = frame.toLatLon(Enu(x, y))
        val key = CellKey(260, 3, RadioTechnology.LTE, id)
        key to BaseStation(key, ll.lat, ll.lon, rangeM = 1500.0, verified = true)
    }

    private fun scan(xTrue: Double, t: Long): List<CellMeasurement> = towers.mapIndexed { i, (id, x, y) ->
        val d = hypot(xTrue - x, 0.0 - y)
        val ta = (d / 78.12 - 0.5).roundToInt().coerceIn(0, 1282)
        CellMeasurement(
            timestampMs = t,
            key = CellKey(260, 3, RadioTechnology.LTE, id),
            tac = null,
            pci = null,
            rsrpDbm = -95,
            rsrqDb = -10,
            sinrDb = 10,
            timingAdvance = ta,
            registered = i == 0,
        )
    }

    @Test
    fun straightDriveStaysWithinAFewHundredMetres() {
        val engine = NavigationEngine(frame, { stationsByKey[it] })
        val gravity = Vec3(0.0, 0.0, 9.81)
        val speed = 10.0
        val errors = ArrayList<Double>()
        var nextScan = 0L
        var t = 0L

        while (t <= 90_000L) {
            val xTrue = speed * t / 1000.0
            if (t % 1000L == 0L) engine.onObdSpeed(t, speed)
            engine.onImu(ImuSample(t, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), gravity))
            if (t >= nextScan) {
                val snap = engine.onCells(t, scan(xTrue, t))
                nextScan += 2_000L
                if (snap != null && t >= 20_000L) {
                    val p = frame.toEnu(LatLon(snap.position.latitude, snap.position.longitude))
                    errors.add(hypot(p.x - xTrue, p.y))
                }
            }
            t += 20L
        }

        assertTrue("expected scans after warm-up", errors.isNotEmpty())
        val sorted = errors.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        println("NavigationEngineTest synthetic straight drive: n=${sorted.size} median=${"%.1f".format(median)} m p95=${"%.1f".format(p95)} m")
        assertTrue("median error $median m", median < 150.0)
        assertNotNull(engine.current())
    }

    @Test
    fun repeatedScanDoesNotTightenTheEstimate() {
        val engine = NavigationEngine(frame, { stationsByKey[it] })
        val scan = scan(xTrue = 0.0, t = 1_000L)
        val first = engine.onCells(nowMs = 1_500L, scan)!!
        val again = engine.onCells(nowMs = 3_000L, scan)!!
        assertEquals(first.position.accuracyM, again.position.accuracyM, 1e-6)
    }

    @Test
    fun staleScanIsNotApplied() {
        val engine = NavigationEngine(frame, { stationsByKey[it] })
        val scan = scan(xTrue = 0.0, t = 1_000L)
        assertNull(engine.onCells(nowMs = 30_000L, scan))
    }
}
