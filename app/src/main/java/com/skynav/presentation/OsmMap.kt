package com.skynav.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.skynav.data.routing.OsmEndpoints
import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** MapLibre view. [onGesture] fires when the user drags the map, so following can pause. */
@Composable
fun OsmMap(
    position: LatLon?,
    accuracyM: Double?,
    headingRad: Double?,
    headingSigmaRad: Double?,
    route: List<LatLon>,
    follow: Boolean,
    onGesture: () -> Unit,
    onLongPress: (LatLon) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val binder = remember { MapBinder(context, onGesture, onLongPress) }
    DisposableEffect(binder) {
        binder.start()
        onDispose { binder.stop() }
    }
    AndroidView(
        factory = { binder.view },
        modifier = modifier,
        update = {
            binder.onGesture = onGesture
            binder.onLongPress = onLongPress
            binder.render(position, accuracyM, headingRad, headingSigmaRad, route, follow)
        },
    )
}

private class MapBinder(
    context: Context,
    var onGesture: () -> Unit,
    var onLongPress: (LatLon) -> Unit,
) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var aimed = false
    private var pending: Pending? = null

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isAttributionEnabled = true
            ready.uiSettings.isLogoEnabled = true
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onGesture()
            }
            ready.addOnMapLongClickListener { point ->
                onLongPress(LatLon(point.latitude, point.longitude))
                true
            }
            ready.setStyle(Style.Builder().fromUri(OsmEndpoints.MAP_STYLE)) { loaded ->
                style = loaded
                loaded.addSource(GeoJsonSource(SRC_CIRCLE))
                loaded.addSource(GeoJsonSource(SRC_ROUTE))
                loaded.addSource(GeoJsonSource(SRC_HEADING))
                loaded.addSource(GeoJsonSource(SRC_PUCK))
                loaded.addLayer(
                    FillLayer(LAYER_CIRCLE, SRC_CIRCLE).withProperties(
                        PropertyFactory.fillColor("#1565C0"),
                        PropertyFactory.fillOpacity(0.18f),
                    ),
                )
                loaded.addLayer(
                    LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                        PropertyFactory.lineColor("#1565C0"),
                        PropertyFactory.lineWidth(5f),
                    ),
                )
                loaded.addLayer(
                    LineLayer(LAYER_HEADING, SRC_HEADING).withProperties(
                        PropertyFactory.lineColor("#0D47A1"),
                        PropertyFactory.lineWidth(3f),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(LAYER_PUCK, SRC_PUCK).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#1565C0"),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                    ),
                )
                pending?.let { draw(it) }
            }
        }
    }

    fun start() {
        view.onStart()
        view.onResume()
    }

    fun stop() {
        view.onPause()
        view.onStop()
        view.onDestroy()
    }

    fun render(
        position: LatLon?,
        accuracyM: Double?,
        headingRad: Double?,
        headingSigmaRad: Double?,
        route: List<LatLon>,
        follow: Boolean,
    ) {
        val next = Pending(position, accuracyM, headingRad, headingSigmaRad, route, follow)
        pending = next
        if (style == null || map == null) return
        draw(next)
    }

    private fun draw(frame: Pending) {
        val loaded = style ?: return
        val mapView = map ?: return
        val position = frame.position
        val route = frame.route
        val follow = frame.follow
        val accuracyM = frame.accuracyM
        val headingRad = frame.headingRad
        val headingSigmaRad = frame.headingSigmaRad
        if (route.size >= 2) {
            loaded.geo(SRC_ROUTE, lineFeature(route.map { Point.fromLngLat(it.lon, it.lat) }))
        } else {
            loaded.geo(SRC_ROUTE, emptyCollection())
        }
        if (position == null) {
            loaded.geo(SRC_CIRCLE, emptyCollection())
            loaded.geo(SRC_HEADING, emptyCollection())
            loaded.geo(SRC_PUCK, emptyCollection())
            return
        }
        val radius = (accuracyM ?: 30.0).coerceIn(20.0, 3_000.0)
        loaded.geo(SRC_CIRCLE, polygonFeature(circle(position, radius)))
        val showHeading = headingRad != null && (headingSigmaRad ?: Double.MAX_VALUE) < 1.0
        if (showHeading) loaded.geo(SRC_HEADING, lineFeature(headingSegment(position, headingRad!!)))
        else loaded.geo(SRC_HEADING, emptyCollection())
        loaded.geo(SRC_PUCK, Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
        if (follow) {
            val zoom = if (aimed) mapView.cameraPosition.zoom else 15.0
            aimed = true
            mapView.cameraPosition = CameraPosition.Builder()
                .target(LatLng(position.lat, position.lon))
                .zoom(zoom)
                .build()
        }
    }

    private fun Style.geo(id: String, feature: Feature) {
        getSourceAs<GeoJsonSource>(id)?.setGeoJson(feature)
    }

    private fun Style.geo(id: String, collection: FeatureCollection) {
        getSourceAs<GeoJsonSource>(id)?.setGeoJson(collection)
    }

    private data class Pending(
        val position: LatLon?,
        val accuracyM: Double?,
        val headingRad: Double?,
        val headingSigmaRad: Double?,
        val route: List<LatLon>,
        val follow: Boolean,
    )

    private companion object {
        const val SRC_ROUTE = "route"
        const val SRC_CIRCLE = "circle"
        const val SRC_HEADING = "heading"
        const val SRC_PUCK = "puck"
        const val LAYER_ROUTE = "route-line"
        const val LAYER_CIRCLE = "circle-fill"
        const val LAYER_HEADING = "heading-line"
        const val LAYER_PUCK = "puck-circle"
    }
}

private fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(arrayOf())

private fun lineFeature(points: List<Point>): Feature =
    Feature.fromGeometry(LineString.fromLngLats(points))

private fun polygonFeature(ring: List<LatLon>): Feature {
    val points = ring.map { Point.fromLngLat(it.lon, it.lat) } + Point.fromLngLat(ring.first().lon, ring.first().lat)
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
}

private fun circle(center: LatLon, radiusM: Double): List<LatLon> {
    val frame = LocalFrame(center)
    return List(36) { i ->
        val a = 2.0 * PI * i / 36.0
        frame.toLatLon(Enu(radiusM * cos(a), radiusM * sin(a)))
    }
}

private fun headingSegment(origin: LatLon, bearingRad: Double): List<Point> {
    val tip = LocalFrame(origin).toLatLon(Enu(40.0 * cos(bearingRad), 40.0 * sin(bearingRad)))
    return listOf(Point.fromLngLat(origin.lon, origin.lat), Point.fromLngLat(tip.lon, tip.lat))
}
