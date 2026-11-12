package com.skynav.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skynav.data.lookup.MapillaryClient
import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.photo.PhotoMatcher
import com.skynav.domain.photo.StreetPhoto
import java.net.HttpURLConnection
import java.net.URL

/**
 * Compares one camera frame with nearby Mapillary photos and returns the photo position
 * when the match is clear. Thumbnails are kept only for this trip.
 */
class StreetPhotoCheck(
    private val mapillary: MapillaryClient,
    private val embedder: FrameEmbedder,
) : AutoCloseable {

    private val embeddings = HashMap<String, FloatArray>()
    private var lastAttempt: LatLon? = null

    fun check(frame: Bitmap, place: LatLon, headingDeg: Double): LatLon? {
        val previous = lastAttempt
        if (previous != null && Geo.haversineM(previous, place) < MIN_MOVE_M) return null
        val photos = mapillary.photosAround(place.lat, place.lon)
            .let { PhotoMatcher.sameDirection(it, headingDeg) }
            .sortedBy { Geo.haversineM(place, it.position) }
            .take(MAX_COMPARE)
        if (photos.size < PhotoMatcher.MIN_CANDIDATES) return null
        lastAttempt = place
        val seen = embedder.embed(frame)
        val candidates = photos.mapNotNull { photo -> embedding(photo)?.let { photo to it } }
        val match = PhotoMatcher.best(seen, candidates) ?: return null
        if (!PhotoMatcher.withinReach(place, match)) return null
        return match.photo.position
    }

    override fun close() {
        embedder.close()
    }

    private fun embedding(photo: StreetPhoto): FloatArray? {
        embeddings[photo.id]?.let { return it }
        val url = photo.thumbUrl ?: return null
        val thumb = download(url) ?: return null
        return embedder.embed(thumb).also {
            thumb.recycle()
            embeddings[photo.id] = it
        }
    }

    private fun download(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val MIN_MOVE_M = 20.0
        const val MAX_COMPARE = 8
        const val USER_AGENT = "SkyNav/0.1 (com.skynav)"
    }
}
