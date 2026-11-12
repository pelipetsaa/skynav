package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.Vec3
import com.skynav.domain.positioning.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoGpsGapsTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    @Test
    fun oneRepeatedNetworkPointDoesNotDropGps() {
        val detector = GpsSpoofingDetector()
        val origin = frame.origin
        assertTrue(detector.onNetwork(origin, accuracyM = 400.0, timeMs = 0))
        assertTrue(detector.checkSatellite(origin, timeMs = 0))
        val ghost = frame.toLatLon(Enu(5_000.0, 0.0))
        assertFalse(detector.onNetwork(ghost, accuracyM = 400.0, timeMs = 1_000))
        assertFalse(detector.onNetwork(ghost, accuracyM = 400.0, timeMs = 2_000))
        assertTrue(detector.checkSatellite(origin, timeMs = 3_000))
        assertFalse(detector.spoofed)
    }

    @Test
    fun gpsReturnsAfterAStreakThatAgreesWithTheMark() {
        val guide = InertialGuide()
        val origin = frame.origin
        guide.onRadio(origin, accuracyM = 500.0, nowMs = 0)
        var accepted = false
        for (second in 0..15) {
            accepted = guide.offerGps(origin, accuracyM = 10.0, timeMs = second * 1_000L, speedMps = 0.0)
        }
        assertTrue(accepted)
        assertEquals(GuideSource.GPS, guide.source)
        assertEquals(10.0, guide.fix(15_000)!!.accuracyM, 0.1)
    }

    @Test
    fun turningGpsOffIgnoresTheNextFix() {
        val guide = InertialGuide()
        val origin = frame.origin
        guide.onRadio(origin, accuracyM = 500.0, nowMs = 0)
        var accepted = false
        for (second in 0..15) {
            accepted = guide.offerGps(origin, accuracyM = 10.0, timeMs = second * 1_000L, speedMps = 0.0)
        }
        assertTrue(accepted)
        guide.setGpsEnabled(false)
        assertEquals(GuideSource.INERTIAL, guide.source)
        val jumped = frame.toLatLon(Enu(2_000.0, 0.0))
        assertFalse(guide.offerGps(jumped, accuracyM = 10.0, timeMs = 20_000, speedMps = 10.0))
        assertEquals(origin.lat, guide.reckoning.position().lat, 1e-6)
        assertEquals(origin.lon, guide.reckoning.position().lon, 1e-6)
    }

    @Test
    fun theGpsScaleIsAppliedToTheNextPhoneSpeed() {
        val table = SpeedTable()
        repeat(30) { table.onSample(carSpeedKmh = 36.0, gpsSpeedMps = 11.0, dtSec = 1.0, accelerationMps2 = 0.0) }
        val scale = table.scale(36.0)
        assertEquals(1.1, scale, 0.05)
        assertEquals(11.0, 10.0 * scale, 0.5)
        val restored = SpeedTable()
        restored.restore(table.serialize())
        assertEquals(scale, restored.scale(36.0), 0.001)
    }

    @Test
    fun threeOppositeTurnsFlipTheGyroSign() {
        val checker = TurnSignChecker()
        repeat(3) { index ->
            val start = index * 10_000L
            checker.onGpsBearing(0.0, start)
            checker.onGyro(-40.0)
            checker.onGpsBearing(40.0, start + 1_000)
            checker.onGpsBearing(40.0, start + 2_000)
        }
        assertTrue(checker.reversed)
    }

    @Test
    fun calibrationWaitsTwoSecondsOfStillness() {
        val gate = StillGate()
        assertFalse(gate.ready(still = true, timeMs = 0))
        assertFalse(gate.ready(still = true, timeMs = 1_999))
        assertTrue(gate.ready(still = true, timeMs = 2_000))
        val moving = StillGate()
        assertFalse(moving.ready(still = false, timeMs = 5_000))
    }

    @Test
    fun aBendMovesTheMarkForwardAlongTheRoad() {
        val roads = bend()
        val matcher = BendMatcher()
        matcher.onMotion(0.0, 100.0)
        matcher.onMotion(45.0, 40.0)
        val corner = frame.toLatLon(Enu(120.0, 0.0))
        val shift = matcher.match(roads, frame, corner, bearingDeg = 90.0, maxShiftM = 60.0)
        assertTrue(shift != null && shift < -20.0)
        val moved = placeAlongRoad(roads, frame, corner, -shift!!)
        assertTrue(moved != null)
        val at = frame.toEnu(moved!!)
        assertTrue(at.x > 130.0)
        assertTrue(at.y < -10.0)
    }

    @Test
    fun aTraceBesideTheRoadIsMovedOntoIt() {
        val roads = straight()
        val matcher = PathMatcher()
        matcher.onMotion(0.0, 120.0, headingDeg = 90.0)
        val beside = frame.toLatLon(Enu(120.0, 25.0))
        val placed = matcher.match(roads, frame, beside)
        assertTrue(placed != null)
        val at = frame.toEnu(placed!!)
        assertEquals(120.0, at.x, 20.0)
        assertEquals(0.0, at.y, 8.0)
    }

    @Test
    fun theSecondMarkSetsTheHeadingAndPauseHoldsTheMark() {
        val guide = InertialGuide()
        guide.setRoads(cross(), frame, south = 49.9, west = 18.9, north = 50.1, east = 19.1)
        assertTrue(guide.onManual(frame.toLatLon(Enu(50.0, 12.0)), nowMs = 0))
        assertTrue(guide.onManual(frame.toLatLon(Enu(100.0, 40.0)), nowMs = 5_000))
        assertEquals(0.0, guide.reckoning.headingDeg, 25.0)
        guide.reverse()
        assertEquals(180.0, guide.reckoning.headingDeg, 25.0)
        val held = guide.reckoning.position()
        guide.setPaused(true)
        guide.onImu(
            ImuSample(
                timestampMs = 6_000,
                linearAcc = Vec3(2.0, 0.0, 0.0),
                gyro = Vec3(0.0, 0.0, 0.4),
                gravity = Vec3(0.0, 0.0, 9.8),
            ),
        )
        assertEquals(held.lat, guide.reckoning.position().lat, 1e-9)
        assertEquals(held.lon, guide.reckoning.position().lon, 1e-9)
        assertTrue(guide.paused)
    }

    private fun straight(): RoadGraph = graph(
        listOf(1 to Enu(0.0, 0.0), 2 to Enu(250.0, 0.0)),
        listOf(10L to listOf(1L, 2L)),
    )

    private fun bend(): RoadGraph {
        val nodes = ArrayList<Pair<Int, Enu>>()
        nodes.add(1 to Enu(0.0, 0.0))
        nodes.add(2 to Enu(120.0, 0.0))
        var x = 120.0
        var y = 0.0
        var id = 3
        for (step in 1..4) {
            val rad = Math.toRadians(90.0 + 11.25 * step)
            x += 10.0 * kotlin.math.sin(rad)
            y += 10.0 * kotlin.math.cos(rad)
            nodes.add(id to Enu(x, y))
            id++
        }
        val tail = Math.toRadians(135.0)
        nodes.add(id to Enu(x + 10.0 * kotlin.math.sin(tail), y + 10.0 * kotlin.math.cos(tail)))
        return graph(nodes, listOf(10L to nodes.map { it.first.toLong() }))
    }

    private fun cross(): RoadGraph = graph(
        listOf(
            1 to Enu(0.0, 0.0),
            2 to Enu(100.0, 0.0),
            3 to Enu(200.0, 0.0),
            4 to Enu(100.0, -80.0),
            5 to Enu(100.0, 80.0),
        ),
        listOf(10L to listOf(1L, 2L, 3L), 11L to listOf(4L, 2L, 5L)),
    )

    private fun graph(nodes: List<Pair<Int, Enu>>, ways: List<Pair<Long, List<Long>>>): RoadGraph {
        val nodeXml = nodes.joinToString("") { (id, enu) ->
            val ll = frame.toLatLon(enu)
            "<node id=\"$id\" lat=\"${ll.lat}\" lon=\"${ll.lon}\"/>"
        }
        val wayXml = ways.joinToString("") { (id, refs) ->
            val nd = refs.joinToString("") { "<nd ref=\"$it\"/>" }
            "<way id=\"$id\">$nd<tag k=\"highway\" v=\"residential\"/></way>"
        }
        return RoadGraph.fromOsmXml("<osm>$nodeXml$wayXml</osm>", frame)
    }
}
