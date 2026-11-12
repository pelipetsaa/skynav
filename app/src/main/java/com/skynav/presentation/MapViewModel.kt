package com.skynav.presentation

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skynav.data.routing.NominatimClient
import com.skynav.data.routing.OsrmClient
import com.skynav.data.session.NavigationHub
import com.skynav.data.session.PublishedFix
import com.skynav.domain.geo.LatLon
import com.skynav.domain.routing.Place
import com.skynav.domain.routing.Route
import com.skynav.domain.routing.RouteFollower
import com.skynav.domain.routing.alongTrackSpeedMps
import com.skynav.domain.routing.guidanceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Map screen state. The position itself is [NavigationHub.fixes]. */
data class MapUi(
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    val route: Route? = null,
    val guidance: String = "",
    val status: String = "",
)

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val nominatim = NominatimClient()
    private val osrm = OsrmClient()

    private val _ui = MutableStateFlow(MapUi())
    val ui: StateFlow<MapUi> = _ui

    private var destination: Place? = null
    private var follower: RouteFollower? = null
    private var previousAlongM: Double? = null
    private var previousMs: Long? = null
    private var offSinceMs: Long? = null
    private var routing = false
    private var lastMark = 0

    init {
        viewModelScope.launch {
            NavigationHub.fixes.collect { fix -> if (fix != null) onFix(fix) }
        }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty() || _ui.value.searching) return
        _ui.value = _ui.value.copy(searching = true, status = "")
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { nominatim.search(q) }.getOrDefault(emptyList())
            }
            _ui.value = _ui.value.copy(searching = false, places = found, status = if (found.isEmpty()) "Ничего не найдено" else "")
        }
    }

    fun select(place: Place, origin: LatLon?) {
        destination = place
        _ui.value = _ui.value.copy(places = emptyList())
        if (origin == null) {
            _ui.value = _ui.value.copy(status = "Нет оценки позиции")
            return
        }
        buildRoute(origin.lat, origin.lon, place, null)
    }

    fun clearRoute() {
        destination = null
        follower = null
        previousAlongM = null
        previousMs = null
        offSinceMs = null
        NavigationHub.clearRouteCue()
        _ui.value = _ui.value.copy(route = null, guidance = "", status = "")
    }

    private fun onFix(fix: PublishedFix) {
        val guide = fix.guide
        val place = destination
        if (guide != null && place != null && guide.markSequence != lastMark && guide.markSequence > 0) {
            lastMark = guide.markSequence
            buildRoute(guide.latitude, guide.longitude, place, guide.headingDeg)
        }
        val route = _ui.value.route ?: return
        val follow = follower ?: return
        if (fix.guide?.needsMark == true) {
            _ui.value = _ui.value.copy(guidance = "")
            return
        }
        val shown = shown(fix) ?: return
        val here = shown.first
        val progress = follow.progress(here, shown.second)
        _ui.value = _ui.value.copy(guidance = guidanceText(route, progress))
        if (progress.offRoute) {
            val now = SystemClock.elapsedRealtime()
            val since = offSinceMs ?: now.also { offSinceMs = it }
            val place = destination
            if (place != null && now - since >= REROUTE_AFTER_MS) {
                buildRoute(here.lat, here.lon, place, fix.guide?.headingDeg)
            }
            return
        }
        offSinceMs = null
        if (!fix.radio) return
        val whenMs = fix.guide?.timestampMs ?: fix.snapshot?.timestampMs ?: return
        val speed = previousAlongM?.let { earlier ->
            previousMs?.let { t -> alongTrackSpeedMps(earlier, progress.alongM, (whenMs - t) / 1000.0) }
        }
        previousAlongM = progress.alongM
        previousMs = whenMs
        NavigationHub.setRouteCue(progress.bearingRad, ROUTE_HEADING_VARIANCE, speed)
    }

    private fun shown(fix: PublishedFix): Pair<LatLon, Double>? {
        val guide = fix.guide
        if (guide != null) return LatLon(guide.latitude, guide.longitude) to guide.accuracyM
        val snap = fix.snapshot ?: return null
        return LatLon(snap.position.latitude, snap.position.longitude) to snap.position.accuracyM
    }

    private fun buildRoute(lat: Double, lon: Double, place: Place, headingDeg: Double?) {
        if (routing) return
        routing = true
        offSinceMs = null
        _ui.value = _ui.value.copy(status = "Строим маршрут")
        viewModelScope.launch {
            val route = withContext(Dispatchers.IO) {
                runCatching {
                    osrm.route(LatLon(lat, lon), LatLon(place.latitude, place.longitude), headingDeg)
                }.getOrNull()
            }
            routing = false
            if (route == null) {
                _ui.value = _ui.value.copy(status = "Маршрут не построен")
                return@launch
            }
            follower = RouteFollower(route)
            previousAlongM = null
            previousMs = null
            val progress = follower!!.progress(LatLon(lat, lon), accuracyM = route.distanceM.coerceAtLeast(1.0))
            _ui.value = _ui.value.copy(route = route, guidance = guidanceText(route, progress), status = "")
        }
    }

    private companion object {
        const val REROUTE_AFTER_MS = 8_000L
        /** About 30 degrees. Wider than a heading learned from a long track, so that one wins. */
        const val ROUTE_HEADING_VARIANCE = 0.25
    }
}
