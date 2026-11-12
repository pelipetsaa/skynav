package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.positioning.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetPhotoGuideTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    @Test
    fun aPhotoBesideTheRoadMovesTheMarkOntoIt() {
        val guide = guideOnTheRoad()
        val beside = frame.toLatLon(Enu(80.0, 12.0))
        assertTrue(guide.offerStreetPhoto(beside, nowMs = 1_000))
        val at = frame.toEnu(guide.reckoning.position())
        assertEquals(80.0, at.x, 15.0)
        assertEquals(0.0, at.y, 8.0)
        assertTrue(guide.fix(1_000)!!.streetPhoto)
        assertFalse(guide.fix(20_000)!!.streetPhoto)
    }

    @Test
    fun aPhotoFarFromTheRoadDoesNotMoveTheMark() {
        val guide = guideOnTheRoad()
        val held = guide.reckoning.position()
        val far = frame.toLatLon(Enu(80.0, 200.0))
        assertFalse(guide.offerStreetPhoto(far, nowMs = 1_000))
        assertEquals(held.lat, guide.reckoning.position().lat, 1e-9)
        assertEquals(held.lon, guide.reckoning.position().lon, 1e-9)
    }

    private fun guideOnTheRoad(): InertialGuide {
        val guide = InertialGuide()
        guide.setRoads(straight(), frame, south = 49.9, west = 18.9, north = 50.1, east = 19.1)
        assertTrue(guide.onManual(frame.toLatLon(Enu(40.0, 0.0)), nowMs = 0))
        return guide
    }

    private fun straight(): RoadGraph {
        val nodes = listOf(1 to Enu(0.0, 0.0), 2 to Enu(200.0, 0.0))
        val nodeXml = nodes.joinToString("") { (id, enu) ->
            val ll = frame.toLatLon(enu)
            "<node id=\"$id\" lat=\"${ll.lat}\" lon=\"${ll.lon}\"/>"
        }
        val way = "<way id=\"10\"><nd ref=\"1\"/><nd ref=\"2\"/><tag k=\"highway\" v=\"residential\"/></way>"
        return RoadGraph.fromOsmXml("<osm>$nodeXml$way</osm>", frame)
    }
}
