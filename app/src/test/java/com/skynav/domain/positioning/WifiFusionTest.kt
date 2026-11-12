package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.RadioTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Cell and Wi-Fi observations share one grid. The access points come from a mock catalog,
 * not from a live lookup.
 */
class WifiFusionTest {

    private val frame = LocalFrame(LatLon(50.1, 19.95))
    private val truth = Enu(300.0, 0.0)

    private fun tower(): BaseStation {
        val ll = frame.toLatLon(Enu(0.0, 0.0))
        return BaseStation(
            key = CellKey(260, 2, RadioTechnology.LTE, 1L),
            latitude = ll.lat,
            longitude = ll.lon,
            rangeM = 1_000.0,
            verified = false,
        )
    }

    private fun cell() = CellObservation(tower(), timingAdvance = null, rsrpDbm = -100, registered = true)

    private fun wifiAt(x: Double, y: Double, rssi: Int, accuracyM: Double): WifiObservation {
        val ll = frame.toLatLon(Enu(x, y))
        return WifiObservation(ll.lat, ll.lon, rssi, accuracyM)
    }

    private fun mockCatalog(): List<WifiObservation> =
        javaClass.classLoader!!.getResourceAsStream("mock-wifi.csv")!!.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("name,") }
                .map { line ->
                    val f = line.split(",")
                    wifiAt(f[1].toDouble(), f[2].toDouble(), f[3].toInt(), f[4].toDouble())
                }
                .toList()
        }

    @Test
    fun agreeingAccessPointsInsideTheCellPullTheFixOffTheTower() {
        val estimator = CellularPositionEstimator(frame)
        val cellOnly = estimator.estimate(listOf(cell()))!!
        val near = mockCatalog().take(3)
        val fused = estimator.estimate(listOf(cell()), near)!!

        val cellError = hypot(cellOnly.center.x - truth.x, cellOnly.center.y - truth.y)
        val fusedError = hypot(fused.center.x - truth.x, fused.center.y - truth.y)
        assertTrue("cell-only error $cellError should be the distance to the tower", cellError > 200.0)
        assertTrue("fused error $fusedError m", fusedError < 80.0)
        assertTrue("fusion should beat the tower point", fusedError < cellError / 2)
        assertEquals(4, fused.usedObservations)
    }

    @Test
    fun accessPointOutsideTheCellCoverageIsIgnored() {
        val estimator = CellularPositionEstimator(frame)
        val outlier = mockCatalog().last()
        val fused = estimator.estimate(listOf(cell()), listOf(outlier))!!
        assertTrue(
            "outlier must not move the fix, center ${fused.center}",
            hypot(fused.center.x, fused.center.y) < 40.0,
        )
        assertEquals(1, fused.usedObservations)
    }

    @Test
    fun oneCrowdsourcedAccessPointDoesNotClaimATightFix() {
        val estimator = CellularPositionEstimator(frame)
        val single = wifiAt(200.0, 0.0, rssi = -60, accuracyM = 250.0)
        val fused = estimator.estimate(listOf(cell()), listOf(single))!!
        assertTrue("reported accuracy ${fused.accuracyM}", fused.accuracyM > 150.0)
        assertEquals(2, fused.usedObservations)
    }

    @Test
    fun strongerRssiPullsMoreThanAWeakAccessPoint() {
        val estimator = CellularPositionEstimator(frame)
        val strong = wifiAt(200.0, 0.0, rssi = -40, accuracyM = 40.0)
        val weak = wifiAt(600.0, 0.0, rssi = -90, accuracyM = 40.0)
        val fused = estimator.estimate(listOf(cell()), listOf(strong, weak))!!
        assertTrue("center ${fused.center} should stay closer to the strong AP", fused.center.x < 350.0)
    }

    @Test
    fun engineAppliesTheMockCatalogWithTheCell() {
        val station = tower()
        val engine = NavigationEngine(frame, { key -> if (key == station.key) station else null })
        val measurement = CellMeasurement(
            timestampMs = 5_000L,
            key = station.key,
            tac = 1,
            pci = 1,
            rsrpDbm = -100,
            rsrqDb = null,
            sinrDb = null,
            timingAdvance = null,
            registered = true,
        )
        val snap = engine.onCells(5_000L, listOf(measurement), mockCatalog().take(3))!!
        val p = frame.toEnu(LatLon(snap.position.latitude, snap.position.longitude))
        val err = hypot(p.x - truth.x, p.y - truth.y)
        assertTrue("engine error $err m", err < 80.0)
    }
}
