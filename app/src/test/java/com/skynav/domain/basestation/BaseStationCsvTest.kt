package com.skynav.domain.basestation

import com.skynav.domain.model.RadioTechnology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseStationCsvTest {

    @Test
    fun parsesSectorsAndSkipsHeaderAndComments() {
        val csv = """
            ${BaseStationCsv.HEADER}
            # comment line
            260,3,LTE,12345,50.0614,19.9366,120,65,800,30,operator,1700000000000
            260,3,NR,987654321,50.0700,19.9500,,,,,crowdsourced,1700000000000
        """.trimIndent()

        val result = BaseStationCsv.parse(csv)
        assertEquals(2, result.stations.size)
        assertTrue(result.rejected.isEmpty())

        val lte = result.stations[0]
        assertEquals(RadioTechnology.LTE, lte.key.technology)
        assertEquals(120.0, lte.azimuthDeg!!, 1e-9)
        assertEquals(30.0, lte.accuracyM, 1e-9)
        assertTrue(lte.verified)
    }

    @Test
    fun crowdsourcedOrUnmeasuredRowsAreUnverified() {
        val csv = "260,3,NR,987654321,50.07,19.95,,,,,crowdsourced,0"
        val s = BaseStationCsv.parse(csv).stations.single()
        assertFalse(s.verified)
    }

    @Test
    fun invalidRowsAreRejectedNotImported() {
        val csv = """
            260,3,LTE,1,95.0,19.0,,,,,operator,0
            260,3,XYZ,2,50.0,19.0,,,,,operator,0
            not,a,row
        """.trimIndent()
        val result = BaseStationCsv.parse(csv)
        assertTrue(result.stations.isEmpty())
        assertEquals(3, result.rejected.size)
    }
}
