package com.skynav.domain.lookup

import com.skynav.domain.basestation.BaseStationCsv
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.wifi.WifiNetwork
import com.skynav.domain.wifi.wifiCentroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParsersTest {

    @Test
    fun parsesTheServingCellReturnedByOpenCellId() {
        val json = """
            {"lat":50.1012,"lon":19.9568,"mcc":260,"mnc":2,"lac":52812,"cellid":32823092,
            "range":1489,"samples":1,"radio":"LTE","message":null}
        """.trimIndent()
        val point = parseOpenCellId(json)!!
        assertEquals(50.1012, point.latitude, 1e-9)
        assertEquals(19.9568, point.longitude, 1e-9)
        assertEquals(1489, point.rangeM)
        assertEquals(1, point.samples)
    }

    @Test
    fun missingCellHasNoCoordinate() {
        assertNull(parseOpenCellId("""{"code":1,"message":"Cell not found"}"""))
    }

    @Test
    fun parsesTheFirstWigleResult() {
        val json = """
            {"success":true,"results":[{"ssid":"cafe","netid":"AA:BB:CC:DD:EE:FF","trilat":50.102,"trilong":19.957}]}
        """.trimIndent()
        val point = parseWigleSearch(json)!!
        assertEquals(50.102, point.latitude, 1e-9)
        assertEquals(19.957, point.longitude, 1e-9)
        assertEquals("cafe", point.label)
    }

    @Test
    fun bundledStationStaysUnverified() {
        val csv = """
            260,2,LTE,32823092,50.1012,19.9568,,,1489,,crowdsourced,1762980000000
        """.trimIndent()
        val station = BaseStationCsv.parse(csv).stations.single()
        assertEquals(50.1012, station.latitude, 1e-9)
        assertEquals(1489.0, station.rangeM!!, 1e-9)
        assertFalse(station.verified)
    }

    @Test
    fun centroidPullsTowardTheStrongerAccessPoint() {
        val frame = LocalFrame(LatLon(50.0, 19.0))
        val weak = WifiNetwork("aa", "a", rssiDbm = -90, frequencyMhz = 2412, latitude = 50.0, longitude = 19.0)
        val strong = WifiNetwork("bb", "b", rssiDbm = -40, frequencyMhz = 2412, latitude = 50.001, longitude = 19.0)
        val center = wifiCentroid(listOf(weak, strong), frame)!!
        val strongEnu = frame.toEnu(LatLon(50.001, 19.0))
        assertTrue(center.y > strongEnu.y / 2)
    }

    @Test
    fun centroidIsNullWithoutCoordinates() {
        val frame = LocalFrame(LatLon(50.0, 19.0))
        val network = WifiNetwork("aa", "a", rssiDbm = -50, frequencyMhz = 2412)
        assertNull(wifiCentroid(listOf(network), frame))
    }
}
