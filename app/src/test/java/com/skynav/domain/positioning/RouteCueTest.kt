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
import kotlin.math.PI

class RouteCueTest {

    private val frame = LocalFrame(LatLon(50.1, 19.95))

    private fun engine(): NavigationEngine {
        val ll = frame.toLatLon(Enu(0.0, 0.0))
        val station = BaseStation(
            key = CellKey(260, 2, RadioTechnology.LTE, 1L),
            latitude = ll.lat,
            longitude = ll.lon,
            rangeM = 1_000.0,
            verified = false,
        )
        val eng = NavigationEngine(frame, { key -> if (key == station.key) station else null })
        eng.onCells(
            nowMs = 1_000L,
            measurements = listOf(
                CellMeasurement(1_000L, station.key, 1, 1, -100, null, null, null, true),
            ),
        )
        return eng
    }

    @Test
    fun routeHeadingAppliesOnlyWhileTheFilterIsLessCertain() {
        val eng = engine()
        val before = eng.current()!!
        eng.onRouteCue(bearingRad = PI / 2, bearingVariance = 0.04, speedMps = null)
        val seeded = eng.current()!!
        assertEquals(PI / 2, seeded.headingRad, 1e-6)
        assertEquals(before.position.accuracyM, seeded.position.accuracyM, 1e-6)

        eng.onRouteCue(bearingRad = 0.0, bearingVariance = 1.0, speedMps = null)
        assertEquals(PI / 2, eng.current()!!.headingRad, 1e-6)
    }

    @Test
    fun routeSpeedMovesTheFilterWithoutClaimingObdPrecision() {
        val eng = engine()
        eng.onRouteCue(bearingRad = 0.0, bearingVariance = 0.04, speedMps = 10.0)
        assertTrue(eng.current()!!.speedMps > 5.0)
    }
}
