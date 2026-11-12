package com.skynav.data.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapillaryClientTest {

    @Test
    fun parsesPointsAndSkipsEntriesWithoutAValidPosition() {
        val json = """
            {"data":[
              {"id":"111","geometry":{"type":"Point","coordinates":[19.9383,50.0647]},
               "compass_angle":87.5,"captured_at":1700000000000,"thumb_256_url":"https://x/1.jpg"},
              {"id":"222","geometry":{"type":"Point","coordinates":[400.0,50.0]}},
              {"id":"333","compass_angle":12.0},
              {"id":"444","geometry":{"type":"Point","coordinates":[19.94,50.06]}}
            ]}
        """.trimIndent()
        val photos = parseMapillaryImages(json)
        assertEquals(listOf("111", "444"), photos.map { it.id })
        val first = photos[0]
        assertEquals(50.0647, first.latitude, 1e-9)
        assertEquals(19.9383, first.longitude, 1e-9)
        assertEquals(87.5, first.compassDeg!!, 1e-9)
        assertEquals(1700000000000L, first.capturedMs)
        assertNull(photos[1].compassDeg)
        assertNull(photos[1].thumbUrl)
    }

    @Test
    fun emptyOrMissingDataGivesNoPhotos() {
        assertTrue(parseMapillaryImages("""{"data":[]}""").isEmpty())
        assertTrue(parseMapillaryImages("""{}""").isEmpty())
    }

    @Test
    fun boundingBoxStaysUnderTheApiLimit() {
        val box = MapillaryClient.boundingBox(50.0, 19.0, 150.0)
        val (left, bottom, right, top) = box.split(",").map { it.toDouble() }
        assertTrue(right - left < MapillaryClient.MAX_BOX_DEG)
        assertTrue(top - bottom < MapillaryClient.MAX_BOX_DEG)
        assertEquals(19.0, (left + right) / 2, 1e-5)
        assertEquals(50.0, (bottom + top) / 2, 1e-5)
    }

    @Test
    fun blankTokenMakesNoRequest() {
        assertTrue(MapillaryClient("").photosAround(50.0, 19.0).isEmpty())
    }
}
