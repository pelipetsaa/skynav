package com.skynav.domain.positioning

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMatchingTest {

    private val frame = LocalFrame(LatLon(50.0, 19.0))

    // Two parallel east-west primary roads about 15 m apart (0.000135 deg of latitude).
    private val osm = """
        <?xml version="1.0" encoding="UTF-8"?>
        <osm version="0.6">
          <node id="1" lat="50.0"      lon="19.000"/>
          <node id="2" lat="50.0"      lon="19.010"/>
          <node id="3" lat="50.000135" lon="19.000"/>
          <node id="4" lat="50.000135" lon="19.010"/>
          <node id="5" lat="50.0"      lon="19.020"/>
          <way id="10">
            <nd ref="1"/><nd ref="2"/><nd ref="5"/>
            <tag k="highway" v="primary"/>
          </way>
          <way id="11">
            <nd ref="3"/><nd ref="4"/>
            <tag k="highway" v="primary"/>
          </way>
          <way id="12">
            <nd ref="1"/><nd ref="3"/>
            <tag k="building" v="yes"/>
          </way>
        </osm>
    """.trimIndent()

    private val graph = RoadGraph.fromOsmXml(osm, frame)

    private fun input(t: Long, lon: Double, latOffsetM: Double, sigma: Double) = MatchInput(
        timestampMs = t,
        position = frame.toEnu(LatLon(50.0, lon)) + Enu(0.0, latOffsetM),
        sigmaM = sigma,
        headingRad = 0.0,
        speedMps = 10.0,
    )

    @Test
    fun parsesOnlyDrivableHighwayWays() {
        assertEquals(setOf(10L, 11L), graph.ways.map { it.id }.toSet())
    }

    @Test
    fun followsTheRoadTheCarIsOn() {
        val inputs = listOf(
            input(0, 19.002, latOffsetM = 2.0, sigma = 3.0),
            input(1_000, 19.004, latOffsetM = 2.0, sigma = 3.0),
            input(2_000, 19.006, latOffsetM = 2.0, sigma = 3.0),
        )
        val matched = MapMatcher(graph).match(inputs)
        assertTrue(matched.all { it.wayId == 10L })
        assertTrue("well-separated roads should not be ambiguous", matched.none { it.ambiguous })
    }

    @Test
    fun coarseFixOnParallelRoadsIsFlaggedAmbiguous() {
        val inputs = listOf(input(0, 19.004, latOffsetM = 7.5, sigma = 20.0))
        val matched = MapMatcher(graph).match(inputs)
        assertTrue(matched.single().ambiguous)
    }

    @Test
    fun pointOffRoadNetworkKeepsNullWay() {
        val far = MatchInput(0, frame.toEnu(LatLon(50.5, 19.0)), sigmaM = 10.0)
        val matched = MapMatcher(graph).match(listOf(far))
        assertEquals(null, matched.single().wayId)
        assertFalse(matched.single().ambiguous)
    }
}
