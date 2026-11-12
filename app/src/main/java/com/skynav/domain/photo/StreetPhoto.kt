package com.skynav.domain.photo

import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.inertial.angleDiff
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A street-level photo with a known position. Mapillary (CC BY-SA 4.0) is the source.
 * [compassDeg] is the camera heading in degrees clockwise from north. Null means the heading is unknown.
 */
data class StreetPhoto(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val compassDeg: Double?,
    val capturedMs: Long?,
    val thumbUrl: String?,
) {
    val position: LatLon get() = LatLon(latitude, longitude)
}

/** The best photo for one frame, with how much it beats the second best. */
data class PhotoMatch(val photo: StreetPhoto, val similarity: Double, val margin: Double)

/**
 * Decides whether one camera frame matches a nearby street photo.
 *
 * A match is accepted only when it is clearly better than every other candidate.
 * The thresholds are starting values and must be tuned on recorded drives.
 */
object PhotoMatcher {
    const val MIN_SIMILARITY = 0.85
    const val MIN_MARGIN = 0.03
    const val MIN_CANDIDATES = 2
    const val MAX_HEADING_DIFF_DEG = 40.0
    const val MAX_SHIFT_M = 150.0

    /** Keeps photos whose camera heading is close to the car heading. Photos without a heading are dropped. */
    fun sameDirection(photos: List<StreetPhoto>, carHeadingDeg: Double): List<StreetPhoto> =
        photos.filter { photo ->
            val heading = photo.compassDeg ?: return@filter false
            abs(angleDiff(carHeadingDeg, heading)) <= MAX_HEADING_DIFF_DEG
        }

    /** Cosine similarity of two embeddings. Returns 0 when either vector is empty or zero. */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "embeddings differ in size: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0.0 else dot / denom
    }

    /**
     * Returns the photo that matches [frame], or null when no photo is clearly better than the rest.
     * A single candidate is never accepted, because there is nothing to compare it with.
     */
    fun best(frame: FloatArray, candidates: List<Pair<StreetPhoto, FloatArray>>): PhotoMatch? {
        if (candidates.size < MIN_CANDIDATES) return null
        val scored = candidates
            .map { (photo, embedding) -> photo to similarity(frame, embedding) }
            .sortedByDescending { it.second }
        val (photo, top) = scored[0]
        val margin = top - scored[1].second
        if (top < MIN_SIMILARITY || margin < MIN_MARGIN) return null
        return PhotoMatch(photo, top, margin)
    }

    /** True when the matched photo is close enough to move the mark to it. */
    fun withinReach(from: LatLon, match: PhotoMatch): Boolean =
        Geo.haversineM(from, match.photo.position) <= MAX_SHIFT_M
}
