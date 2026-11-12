package com.skynav.domain.photo

import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMatcherTest {

    private val here = LatLon(50.0, 19.0)

    private fun photo(id: String, compass: Double?, lat: Double = 50.0, lon: Double = 19.0) =
        StreetPhoto(id, lat, lon, compass, capturedMs = null, thumbUrl = null)

    @Test
    fun keepsPhotosFacingTheCarAndDropsTheRest() {
        val photos = listOf(
            photo("ahead", compass = 10.0),
            photo("opposite", compass = 190.0),
            photo("unknown", compass = null),
            photo("edge", compass = 35.0),
        )
        val kept = PhotoMatcher.sameDirection(photos, carHeadingDeg = 0.0).map { it.id }
        assertEquals(listOf("ahead", "edge"), kept)
    }

    @Test
    fun aClearWinnerIsAccepted() {
        val frame = floatArrayOf(1f, 0f, 0f)
        val match = PhotoMatcher.best(
            frame,
            listOf(photo("a", 0.0) to floatArrayOf(1f, 0f, 0f), photo("b", 0.0) to floatArrayOf(0f, 1f, 0f)),
        )
        assertNotNull(match)
        assertEquals("a", match!!.photo.id)
        assertEquals(1.0, match.similarity, 1e-6)
    }

    @Test
    fun aNearTieIsRejected() {
        val frame = floatArrayOf(1f, 0f, 0f)
        val match = PhotoMatcher.best(
            frame,
            listOf(photo("a", 0.0) to floatArrayOf(1f, 0f, 0f), photo("b", 0.0) to floatArrayOf(0.999f, 0.04f, 0f)),
        )
        assertNull(match)
    }

    @Test
    fun aWeakBestIsRejected() {
        val frame = floatArrayOf(1f, 1f, 1f)
        val match = PhotoMatcher.best(
            frame,
            listOf(photo("a", 0.0) to floatArrayOf(1f, 0f, 0f), photo("b", 0.0) to floatArrayOf(0f, 0f, 1f)),
        )
        assertNull(match)
    }

    @Test
    fun aSingleCandidateIsNeverAccepted() {
        val frame = floatArrayOf(1f, 0f, 0f)
        assertNull(PhotoMatcher.best(frame, listOf(photo("a", 0.0) to floatArrayOf(1f, 0f, 0f))))
    }

    @Test
    fun aMatchFarAwayIsOutOfReach() {
        val near = PhotoMatch(photo("near", 0.0, lat = 50.0005), similarity = 0.9, margin = 0.1)
        val far = PhotoMatch(photo("far", 0.0, lat = 50.003), similarity = 0.9, margin = 0.1)
        assertTrue(PhotoMatcher.withinReach(here, near))
        assertFalse(PhotoMatcher.withinReach(here, far))
        assertTrue(Geo.haversineM(here, far.photo.position) > PhotoMatcher.MAX_SHIFT_M)
    }
}
