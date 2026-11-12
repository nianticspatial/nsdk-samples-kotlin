// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

private const val TAG = "VPS2MapView"

// Keyless public vector tiles (OpenFreeMap), so the sample needs no map account or API token.
//
// This is a free community service with no uptime guarantee, chosen to keep the sample runnable
// with zero setup — it is not a recommendation for production. Point this at your own style URL
// (MapTiler, Stadia, a self-hosted style, …) before shipping anything that depends on it. The map
// needs network access; if the style cannot be fetched the card reports it instead of sitting blank.
private const val MAP_STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

private val USER_DOT_COLOR = VPS2ReservedColors.UserDot

private const val RECENTER_THRESHOLD_METERS = 1.0

// Markers get dropped within a few metres of the user, so the map opens zoomed well in: at zoom 18
// a DOT_RADIUS-wide dot covers ~3m of ground, which merges markers tapped a couple of metres apart
// into a single blob. At 20 the same dot is under a metre across, so they stay tellable apart.
private const val DEFAULT_ZOOM = 20.0

// maplibre-compose defaults its zoom range to 0..20, which leaves no room to pinch in past the
// default above. Vector tiles overzoom cleanly, so allow a couple more levels for inspecting
// markers placed very close together.
private val ZOOM_RANGE = 0f..22f

private val DOT_RADIUS = 7.dp

// The heading cone is drawn as GeoJSON, so its size is in metres on the ground rather than on
// screen. Sizing it from the camera's current scale keeps it visually constant instead of swelling
// to fill the card as you zoom in (at zoom 20 a fixed 14m cone is ~240dp long).
private const val CONE_LENGTH_DP = 60.0
private const val CONE_FALLBACK_LENGTH_METERS = 14.0

/** A colored dot to draw on the map (user, a VPS2 anchor, or a placed marker). */
data class VPS2MapDot(
    val latitude: Double,
    val longitude: Double,
    val color: Color,
)

/**
 * Miniature map card pinned to the bottom of the VPS2 screen, mirroring the Swift `VPS2MapView`.
 * It shows the user, every VPS2-tracked anchor, and every map-placed marker as colored dots, and —
 * when [placementEnabled] — turns a tap on the map into a placement via [onTapCoordinate].
 *
 * Built on maplibre-compose (org.maplibre.compose:maplibre-compose). A translucent cone at the user
 * location points along [headingDeg] to show which way the device is facing, since the map and the
 * compass are toggled and only one is visible at a time.
 *
 * Unlike the Swift version (which is zoom-only), panning stays on here: it's needed to place a
 * marker anywhere other than the user's immediate surroundings, and — now that recentring is
 * threshold-gated instead of running every frame — it no longer fights a manual pan the way it did
 * before that fix.
 */
@Composable
fun VPS2MapView(
    userLatitude: Double,
    userLongitude: Double,
    headingDeg: Double,
    placementEnabled: Boolean,
    anchorDots: List<VPS2MapDot>,
    placedMarkers: List<VPS2PlacedMarker>,
    onTapCoordinate: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(latitude = userLatitude, longitude = userLongitude),
            zoom = DEFAULT_ZOOM,
        ),
    )

    // How far the device must move before the map recentres (mirrors Swift's
    // recentreThresholdMetres). The geolocation updates every frame, so without this the camera
    // restarts its 0.5 s animateTo on every frame of sensor/pose noise and never finishes.
    var centeredOn by remember { mutableStateOf<Position?>(null) }

    var styleLoadFailed by remember { mutableStateOf(false) }

    // Recenter on the user as VPS2 refines the device geolocation, preserving any manual zoom.
    LaunchedEffect(userLatitude, userLongitude) {
        val previous = centeredOn
        if (previous != null &&
            distanceMeters(previous.latitude, previous.longitude, userLatitude, userLongitude) < RECENTER_THRESHOLD_METERS
        ) {
            return@LaunchedEffect
        }
        val target = Position(latitude = userLatitude, longitude = userLongitude)
        centeredOn = target
        camera.animateTo(
            finalPosition = camera.position.copy(target = target),
            duration = 0.5.seconds,
        )
    }

    // One CircleLayer per distinct color keeps the layer count bounded (palette + user + anchor
    // states) no matter how many markers are placed.
    val dotsByColor = remember(userLatitude, userLongitude, anchorDots, placedMarkers) {
        buildList {
            add(VPS2MapDot(userLatitude, userLongitude, USER_DOT_COLOR))
            addAll(anchorDots)
            placedMarkers.forEach { add(VPS2MapDot(it.latitude, it.longitude, it.color)) }
        }.groupBy { it.color.toArgb() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.33f)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (placementEnabled) {
                    Modifier.border(3.dp, Color.Green, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            ),
    ) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(MAP_STYLE_URI),
            cameraState = camera,
            zoomRange = ZOOM_RANGE,
            // Panning stays on so the user can look away from their own position to place a marker
            // elsewhere — the recenter threshold above (not disabling the gesture) is what stops it
            // from fighting the user, since it now only recentres past 1m of real movement instead
            // of on every localization frame. Rotate/tilt are off: not useful on a small overview
            // map and one less way to lose your bearings on it. Double-tap and quick-zoom are also
            // off: MapLibre holds a single tap until the double-tap window elapses to disambiguate
            // the two, so with them enabled a placement tap is delayed and often swallowed by the
            // zoom detector. Pinch-to-zoom (isZoomEnabled) stays on.
            options = MapOptions(
                gestureOptions = GestureOptions(
                    isRotateEnabled = false,
                    isTiltEnabled = false,
                    isDoubleTapEnabled = false,
                    isQuickZoomEnabled = false,
                ),
            ),
            // Every tap is forwarded, including while placement is disabled: the caller owns the
            // decision and explains a rejection to the user. Gating it here instead would make a
            // tap on a non-placeable map silently do nothing.
            onMapClick = { pos, _ ->
                onTapCoordinate(pos.latitude, pos.longitude)
                ClickResult.Consume
            },
            onMapLoadFailed = { reason ->
                // The card renders blank when the style can't load (no network, tile host down), so
                // say so rather than leaving the user staring at an empty box.
                Log.w(TAG, "Map style failed to load from $MAP_STYLE_URI: $reason")
                styleLoadFailed = true
            },
            onMapLoadFinished = { styleLoadFailed = false },
        ) {
            // Heading cone under the user dot, opening toward the device facing direction. Its
            // GeoJSON is at the user's coordinate, so it stays put and rotates correctly even if
            // the map is panned. metersPerDpAtTarget is 0 until the map is initialized, so the
            // cone falls back to a fixed ground size until the real scale is known.
            val metersPerDp = camera.metersPerDpAtTarget
            val coneLengthMeters =
                if (metersPerDp > 0) CONE_LENGTH_DP * metersPerDp else CONE_FALLBACK_LENGTH_METERS
            val headingSource = rememberGeoJsonSource(
                GeoJsonData.JsonString(
                    headingConeJson(userLatitude, userLongitude, headingDeg, coneLengthMeters),
                ),
            )
            FillLayer(
                id = "vps2-heading-cone",
                source = headingSource,
                color = const(USER_DOT_COLOR),
                opacity = const(0.5f),
            )
            dotsByColor.forEach { (argb, dots) ->
                key(argb) {
                    val source = rememberGeoJsonSource(GeoJsonData.JsonString(featureCollectionJson(dots)))
                    CircleLayer(
                        id = "vps2-dots-$argb",
                        source = source,
                        radius = const(DOT_RADIUS),
                        color = const(Color(argb)),
                        // Outline so two markers dropped close together still read as two dots
                        // rather than one merged blob when they overlap.
                        strokeWidth = const(1.5.dp),
                        strokeColor = const(Color.White),
                    )
                }
            }
        }

        if (styleLoadFailed) {
            Text(
                text = "Map tiles unavailable — check your network connection",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp),
            )
        }
    }
}

/** Great-circle distance in meters between two lat/lng coordinates (haversine formula). */
private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusMeters * c
}

/**
 * Builds a GeoJSON FeatureCollection of points from [dots]. Kotlin's `Double.toString` is
 * locale-independent (always a `.` decimal), so the coordinates are safe to interpolate directly.
 */
private fun featureCollectionJson(dots: List<VPS2MapDot>): String =
    dots.joinToString(
        prefix = """{"type":"FeatureCollection","features":[""",
        postfix = "]}",
        separator = ",",
    ) { dot ->
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${dot.longitude},${dot.latitude}]},"properties":{}}"""
    }

/**
 * Builds a GeoJSON triangle "cone" with its apex at the user and opening toward [headingDeg]
 * (degrees clockwise from north), used to show the device's facing direction on the map. The two
 * far corners are offset [coneLengthMeters] from the user; the small local offsets make a
 * flat-earth lat/lng approximation more than accurate enough at this zoom.
 */
private fun headingConeJson(
    latitude: Double,
    longitude: Double,
    headingDeg: Double,
    coneLengthMeters: Double,
): String {
    val halfAngleDeg = 30.0
    val metersPerDegLat = 111_320.0
    val latRad = Math.toRadians(latitude)

    fun corner(bearingDeg: Double): Pair<Double, Double> {
        val bearing = Math.toRadians(bearingDeg)
        val dLat = coneLengthMeters * cos(bearing) / metersPerDegLat
        val dLng = coneLengthMeters * sin(bearing) / (metersPerDegLat * cos(latRad))
        return (latitude + dLat) to (longitude + dLng)
    }

    val (leftLat, leftLng) = corner(headingDeg - halfAngleDeg)
    val (rightLat, rightLng) = corner(headingDeg + halfAngleDeg)
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon",""" +
        """"coordinates":[[[$longitude,$latitude],[$leftLng,$leftLat],[$rightLng,$rightLat],[$longitude,$latitude]]]},""" +
        """"properties":{}}]}"""
}
