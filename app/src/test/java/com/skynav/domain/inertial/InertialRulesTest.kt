package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.Vec3
import com.skynav.domain.positioning.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InertialRulesTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    @Test
    fun accuracyGrowsByTwoPercentOfThePath() {
        val dead = DeadReckoning()
        dead.setPosition(frame.origin)
        dead.setHeading(90.0)
        dead.setSpeed(10.0)
        dead.advance(10.0)
        assertEquals(100.0, dead.distanceSinceFixM, 0.01)
        assertEquals(17.0, dead.accuracyM(), 0.01)
        assertEquals(15.7, dead.alongErrorM(), 0.01)
        val moved = frame.toEnu(dead.position())
        assertEquals(100.0, moved.x, 1.0)
        assertEquals(0.0, moved.y, 1.0)
    }

    @Test
    fun aRoadFortyDegreesOffTheHeadingIsRejected() {
        val dead = DeadReckoning()
        dead.setPosition(frame.origin)
        dead.setHeading(90.0)
        dead.setSpeed(5.0)
        dead.advance(2.0)
        val before = dead.position()
        val road = frame.toLatLon(Enu(10.0, 0.0))
        assertFalse(dead.snapToRoad(road, roadBearingDeg = 130.0))
        assertEquals(before.lat, dead.position().lat, 1e-9)
        assertEquals(before.lon, dead.position().lon, 1e-9)
        assertEquals(90.0, dead.headingDeg, 0.01)
    }

    @Test
    fun aRoadWithinTenDegreesPullsTheHeading() {
        val dead = DeadReckoning()
        dead.setPosition(frame.origin)
        dead.setHeading(90.0)
        assertTrue(dead.snapToRoad(frame.toLatLon(Enu(0.0, 0.0)), roadBearingDeg = 95.0))
        assertEquals(90.5, dead.headingDeg, 0.01)
    }

    @Test
    fun theSecondStillCalibrationConfirmsTheFirst() {
        val up = Vec3(0.0, 0.0, 1.0)
        val bias = Vec3(0.0, 0.0, 0.01)
        val first = stillBias(bias)
        val second = stillBias(bias)
        val confirmed = GyroCalibrator.confirm(first, second, up)
        assertNotNull(confirmed)
        assertEquals(0.01, confirmed!!.z, 1e-9)

        val disagree = stillBias(Vec3(0.0, 0.0, 0.01 + Math.toRadians(1.0)))
        assertNull(GyroCalibrator.confirm(first, disagree, up))
    }

    @Test
    fun yawIsRotationAboutTheUpVector() {
        val up = Vec3(0.0, 0.0, 1.0)
        val zero = Vec3(0.0, 0.0, 0.0)
        val aboutX = GyroCalibrator.yawRateDeg(Vec3(0.1, 0.0, 0.0), zero, up)
        val aboutUp = GyroCalibrator.yawRateDeg(Vec3(0.0, 0.0, 0.1), zero, up)
        assertEquals(0.0, aboutX, 1e-6)
        assertEquals(-Math.toDegrees(0.1), aboutUp, 1e-6)
    }

    @Test
    fun aBendUnder35DegreesDoesNotMoveTheCarToACrossing() {
        val roads = cross()
        val corner = frame.toLatLon(Enu(60.0, 0.0))
        assertNull(TurnMatcher.findCrossing(roads, frame, corner, fromDeg = 90.0, toDeg = 110.0))
        assertFalse(TurnMatcher.isRealTurn(90.0, 110.0))
    }

    @Test
    fun aRightAngleTurnLandsOnTheCrossing() {
        val roads = cross()
        val corner = frame.toLatLon(Enu(60.0, 0.0))
        val crossing = TurnMatcher.findCrossing(roads, frame, corner, fromDeg = 90.0, toDeg = 0.0)
        assertNotNull(crossing)
        val at = frame.toEnu(crossing!!)
        assertEquals(100.0, at.x, 5.0)
        assertEquals(0.0, at.y, 5.0)
    }

    @Test
    fun theWalkerStopsAtTheCrossing() {
        val roads = cross()
        val start = frame.toLatLon(Enu(70.0, 0.0))
        val walked = RoadWalker.walk(roads, frame, start, bearingDeg = 90.0, distanceM = 80.0)
        assertNotNull(walked)
        assertTrue(walked!!.atCrossing)
        assertTrue(walked.distanceM < 50.0)
        val at = frame.toEnu(walked.position)
        assertEquals(100.0, at.x, 8.0)
        assertEquals(0.0, at.y, 8.0)
    }

    @Test
    fun theAccelerometerAddsNoDistanceUntilTheAxisIsLearned() {
        val early = AccelAxis()
        repeat(10) {
            early.onComparison(Vec3(2.0, 0.0, 0.0), 2.0)
            early.onComparison(Vec3(-1.0, 0.0, 0.0), -1.0)
        }
        assertFalse(early.usable)
        assertNull(early.distanceM(Vec3(1.0, 0.0, 0.0), 1.0))

        val learned = AccelAxis()
        repeat(20) {
            learned.onComparison(Vec3(2.0, 0.0, 0.0), 2.0)
            learned.onComparison(Vec3(-2.0, 0.0, 0.0), -2.0)
        }
        assertTrue(learned.usable)
        assertTrue(learned.correlation >= AccelAxis.MIN_CORRELATION)
        learned.resetSpeed(10.0)
        val distance = learned.distanceM(Vec3(0.0, 0.0, 0.0), 1.0)
        assertNotNull(distance)
        assertEquals(10.0, distance!!, 0.01)
    }

    @Test
    fun aManualMarkSitsOnTheNearestRoad() {
        val guide = InertialGuide()
        guide.setRoads(cross(), frame, south = 49.9, west = 18.9, north = 50.1, east = 19.1)
        val tap = frame.toLatLon(Enu(50.0, 12.0))
        assertTrue(guide.onManual(tap))
        val at = frame.toEnu(guide.reckoning.position())
        assertEquals(50.0, at.x, 2.0)
        assertEquals(0.0, at.y, 2.0)
        assertEquals(GuideSource.MANUAL, guide.source)
        assertFalse(guide.needsMark)
    }

    @Test
    fun aTwoKilometreGpsJumpIsLeftWhereTheMarkAlreadyIs() {
        val guide = InertialGuide()
        val cell = LatLon(50.0, 19.0)
        guide.onRadio(cell, accuracyM = 500.0, nowMs = 0)
        assertFalse(guide.offerGps(cell, accuracyM = 10.0, timeMs = 0, speedMps = 0.0))
        val jumped = frame.toLatLon(Enu(2_000.0, 0.0))
        assertFalse(guide.offerGps(jumped, accuracyM = 10.0, timeMs = 1_000, speedMps = 0.0))
        assertEquals(cell.lat, guide.reckoning.position().lat, 1e-6)
        assertEquals(cell.lon, guide.reckoning.position().lon, 1e-6)
        assertEquals(GuideSource.INERTIAL, guide.source)
    }

    private fun stillBias(gyro: Vec3): Vec3 {
        val calibrator = GyroCalibrator()
        repeat(GyroCalibrator.REQUIRED_SAMPLES) {
            calibrator.add(gyro, Vec3(0.0, 0.0, 9.8))
        }
        assertTrue(calibrator.isComplete())
        assertTrue(calibrator.isStill())
        return calibrator.bias()
    }

    private fun cross(): RoadGraph {
        fun node(id: Int, east: Double, north: Double): String {
            val ll = frame.toLatLon(Enu(east, north))
            return """<node id="$id" lat="${ll.lat}" lon="${ll.lon}"/>"""
        }
        val xml = """
            <?xml version="1.0"?>
            <osm>
              ${node(1, 0.0, 0.0)}
              ${node(2, 100.0, 0.0)}
              ${node(3, 200.0, 0.0)}
              ${node(4, 100.0, -80.0)}
              ${node(5, 100.0, 80.0)}
              <way id="10">
                <nd ref="1"/><nd ref="2"/><nd ref="3"/>
                <tag k="highway" v="residential"/>
              </way>
              <way id="11">
                <nd ref="4"/><nd ref="2"/><nd ref="5"/>
                <tag k="highway" v="residential"/>
              </way>
            </osm>
        """.trimIndent()
        return RoadGraph.fromOsmXml(xml, frame)
    }
}
