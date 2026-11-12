package com.skynav.domain.replay

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.RadioTechnology
import com.skynav.domain.model.Vec3
import com.skynav.domain.validation.ErrorEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveLogTest {

    @Test
    fun cellLineRoundTrips() {
        val cell = CellMeasurement(
            timestampMs = 1234L,
            key = CellKey(260, 3, RadioTechnology.LTE, 42L),
            tac = 7,
            pci = 101,
            rsrpDbm = -100,
            rsrqDb = -11,
            sinrDb = 8,
            timingAdvance = 6,
            registered = true,
        )
        val parsed = DriveLog.parseLine(DriveLog.formatCell(cell)) as CellEvent
        assertEquals(cell, parsed.cell)
    }

    @Test
    fun wifiLineRoundTripsWithAndWithoutACoordinate() {
        val located = DriveLog.parseLine(
            DriveLog.formatWifi(10L, "aa:bb:cc:dd:ee:ff", -60, 2437, 50.1, 19.9, 200.0),
        ) as WifiEvent
        assertEquals("aa:bb:cc:dd:ee:ff", located.bssid)
        assertEquals(50.1, located.latitude!!, 1e-9)
        assertEquals(200.0, located.accuracyM!!, 1e-9)

        val bare = DriveLog.parseLine("WIFI,10,aa:bb:cc:dd:ee:ff,-60,2437,,,") as WifiEvent
        assertEquals(null, bare.latitude)
        assertEquals(-60, bare.rssiDbm)
    }

    @Test
    fun cellLineWithMissingIdentityKeepsNullKey() {
        val cell = CellMeasurement(5L, null, null, null, -90, null, null, null, false)
        val parsed = DriveLog.parseLine(DriveLog.formatCell(cell)) as CellEvent
        assertEquals(null, parsed.cell.key)
        assertEquals(false, parsed.cell.registered)
    }

    @Test
    fun imuLineRoundTripsAndEventsAreSortedByTime() {
        val s = ImuSample(20L, Vec3(0.1, 0.2, 0.3), Vec3(0.0, 0.0, 0.05), Vec3(0.0, 0.0, 9.8))
        val lines = sequenceOf(
            DriveLog.formatObd(30L, 12.5),
            DriveLog.formatImu(s),
            "# comment",
            "garbage,line",
        )
        val events = DriveLog.parse(lines)
        assertEquals(listOf(20L, 30L), events.map { it.timestampMs })
        assertEquals(s, (events[0] as ImuEvent).sample)
    }

    @Test
    fun errorEvaluatorReportsMedianP95AndAvailability() {
        val frame = LocalFrame(LatLon(50.0, 19.0))
        // Estimates are 10 m north of the truth, every second for 10 s.
        val truth = (0..10).map { i ->
            GpsTruthEvent(i * 1000L, 50.0 + 0.0, 19.0 + i * 0.0001, accuracyM = 5.0)
        }
        val estimates = truth.map { g ->
            val p = frame.toEnu(LatLon(g.latitude, g.longitude)) + Enu(0.0, 10.0)
            (g.timestampMs to frame.toLatLon(p))
        }
        // Add one truth point far in time from any estimate; it should count as unavailable.
        val extended = truth + GpsTruthEvent(60_000L, 50.0, 19.0, accuracyM = 5.0)

        val stats = ErrorEvaluator.evaluate(estimates, extended)
        assertEquals(12, stats.truthCount)
        assertEquals(11.0 / 12.0, stats.availability, 1e-9)
        assertEquals(10.0, stats.medianM, 0.1)
        assertEquals(10.0, stats.p95M, 0.1)
        assertTrue(stats.maxM >= stats.medianM)
    }
}
