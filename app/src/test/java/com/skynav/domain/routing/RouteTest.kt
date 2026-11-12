package com.skynav.domain.routing

import com.skynav.domain.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {

    private val route: Route by lazy {
        val json = javaClass.classLoader!!.getResourceAsStream("mock-osrm.json")!!.bufferedReader().readText()
        parseOsrmRoute(json)!!
    }

    @Test
    fun parsesMockOsrmGeoJson() {
        assertEquals(3, route.points.size)
        assertEquals(LatLon(50.10, 19.95), route.points.first())
        assertEquals(3, route.steps.size)
        assertEquals("depart", route.steps[0].maneuver)
        assertEquals("turn", route.steps[1].maneuver)
        assertTrue(route.steps[1].instruction.contains("налево"))
        assertEquals(1271.5, route.distanceM, 0.1)
    }

    @Test
    fun missingRouteIsNull() {
        assertEquals(null, parseOsrmRoute("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun pointOnTheSecondLegSelectsTheTurn() {
        val progress = RouteFollower(route).progress(LatLon(50.102, 19.96), accuracyM = 80.0)
        assertEquals(1, progress.stepIndex)
        assertTrue("cross ${progress.crossTrackM}", progress.crossTrackM < 30.0)
        assertFalse(progress.offRoute)
        assertTrue(guidanceText(route, progress).startsWith("Через"))
    }

    @Test
    fun pointBesideTheRoadIsOffRouteOnlyOutsideTheAccuracyCircle() {
        val follower = RouteFollower(route)
        val beside = LatLon(50.104, 19.955)
        val tight = follower.progress(beside, accuracyM = 50.0)
        val wide = follower.progress(beside, accuracyM = 2_000.0)
        assertTrue(tight.offRoute)
        assertEquals("Позиция вне маршрута", guidanceText(route, tight))
        assertFalse(wide.offRoute)
    }

    @Test
    fun alongTrackSpeedIgnoresAShortIntervalAndABackwardHop() {
        assertEquals(null, alongTrackSpeedMps(0.0, 100.0, dtSec = 0.2))
        assertEquals(10.0, alongTrackSpeedMps(100.0, 150.0, dtSec = 5.0)!!, 1e-9)
        assertEquals(0.0, alongTrackSpeedMps(150.0, 140.0, dtSec = 5.0)!!, 1e-9)
    }

    @Test
    fun parsesNominatimPlaces() {
        val json = """[{"lat":"50.06","lon":"19.94","display_name":"Rynek, Krakow"}]"""
        val place = parseNominatim(json).single()
        assertEquals(50.06, place.latitude, 1e-9)
        assertEquals(19.94, place.longitude, 1e-9)
        assertEquals("Rynek, Krakow", place.name)
    }
}
