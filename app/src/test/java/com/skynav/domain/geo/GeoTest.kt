package com.skynav.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun localFrameRoundTripsAcrossKrakow() {
        val frame = LocalFrame(LatLon(50.0614, 19.9366))
        val p = LatLon(50.0700, 19.9500)
        val back = frame.toLatLon(frame.toEnu(p))
        assertEquals(p.lat, back.lat, 1e-9)
        assertEquals(p.lon, back.lon, 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111km() {
        val d = Geo.haversineM(LatLon(50.0, 19.0), LatLon(51.0, 19.0))
        assertEquals(111_195.0, d, 50.0)
    }

    @Test
    fun localFrameAgreesWithHaversineForKilometreScaleOffsets() {
        val origin = LatLon(50.0, 19.0)
        val frame = LocalFrame(origin)
        val enu = Enu(1_000.0, -700.0)
        val ll = frame.toLatLon(enu)
        assertEquals(enu.norm(), Geo.haversineM(origin, ll), 1.0)
    }
}
