package com.skynav.data.session

import com.skynav.domain.geo.LatLon
import com.skynav.domain.inertial.GuideFix
import com.skynav.domain.positioning.NavigationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One published navigation state. [sequence] changes on every publish so equal positions still update the map. */
data class PublishedFix(
    val snapshot: NavigationSnapshot?,
    val radio: Boolean,
    val sequence: Long,
    val guide: GuideFix? = null,
)

data class GpsOffer(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val speedMps: Double,
    val timeMs: Long,
)

/**
 * The foreground service writes fixes and reads the route cue. The map screen does the opposite.
 * A new [setRouteCue] bumps [cueGeneration] so the service applies each cue once.
 */
object NavigationHub {
    private val _fixes = MutableStateFlow<PublishedFix?>(null)
    val fixes: StateFlow<PublishedFix?> = _fixes

    @Volatile
    var routeCue: RouteCue? = null
        private set

    @Volatile
    var cueGeneration: Int = 0
        private set

    @Volatile
    var manualRequest: LatLon? = null
        private set

    @Volatile
    var gpsOffer: GpsOffer? = null
        private set

    @Volatile
    private var reverseRequested = false

    @Volatile
    private var pauseToggle = false

    private val _gpsEnabled = MutableStateFlow(true)
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled

    fun publish(snapshot: NavigationSnapshot?, radio: Boolean, guide: GuideFix? = null) {
        val next = (_fixes.value?.sequence ?: 0L) + 1L
        _fixes.value = PublishedFix(snapshot, radio, next, guide)
    }

    fun requestManual(position: LatLon) {
        manualRequest = position
    }

    fun takeManual(): LatLon? {
        val mark = manualRequest
        manualRequest = null
        return mark
    }

    fun offerGps(latitude: Double, longitude: Double, accuracyM: Double, speedMps: Double, timeMs: Long) {
        gpsOffer = GpsOffer(latitude, longitude, accuracyM, speedMps, timeMs)
    }

    fun requestReverse() {
        reverseRequested = true
    }

    fun requestPauseToggle() {
        pauseToggle = true
    }

    fun setGpsEnabled(enabled: Boolean) {
        _gpsEnabled.value = enabled
    }

    fun takeDriveCommands(): Pair<Boolean, Boolean> {
        val reverse = reverseRequested
        val pause = pauseToggle
        reverseRequested = false
        pauseToggle = false
        return reverse to pause
    }

    fun takeGps(): GpsOffer? {
        val offer = gpsOffer
        gpsOffer = null
        return offer
    }

    fun setRouteCue(bearingRad: Double, bearingVariance: Double, speedMps: Double?) {
        routeCue = RouteCue(bearingRad, bearingVariance, speedMps)
        cueGeneration++
    }

    fun clearRouteCue() {
        routeCue = null
        cueGeneration++
    }
}

data class RouteCue(
    val bearingRad: Double,
    val bearingVariance: Double,
    val speedMps: Double?,
)
