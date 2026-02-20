package com.nianticspatial.nsdk.externalsamples.wps

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.LocalSceneEngine
import com.nianticspatial.nsdk.externalsamples.LocalSceneMaterialLoader
import com.nianticspatial.nsdk.externalsamples.arChildNodes
import com.nianticspatial.nsdk.externalsamples.common.ErrorDisplay
import com.nianticspatial.nsdk.externalsamples.createUnlitColorMaterial
import com.nianticspatial.nsdk.externalsamples.destroyRecursively
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.node.CubeNode
import kotlinx.serialization.Serializable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

@Serializable object WpsRoute

@Composable
fun WpsView(
    context: Context,
    nsdkSessionManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    modifier: Modifier = Modifier
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val engine = LocalSceneEngine.current
  val materialLoader = LocalSceneMaterialLoader.current
  val cubeMaterial = remember {
      createUnlitColorMaterial(context, materialLoader, Color.Red)
  }
  var cubePoseNode by remember { mutableStateOf<PoseNode?>(null) }

  // Release the Filament material instances before the engine is torn down
  // (e.g., during an orientation change) to avoid Filament aborting because
  // “UnlitColor” still has a live instance.
  DisposableEffect(engine) {
    onDispose {
      // All cube nodes need to be destroyed before destroy the material instance
      // because they have the reference to the material instance.
      cubePoseNode?.destroyRecursively(arChildNodes)
      cubePoseNode = null
      engine.destroyMaterialInstance(cubeMaterial)
    }
  }

  // Set Help Contents
  DisposableEffect(Unit) {
    helpContentState.value = {
      Text(
        text = "WPS Sample Help\n\nThis sample demonstrates the differences in accuracy and precision" +
          " between your device's GPS signal and the optimized WPS coordinates.\nTO USE:\nOnce tracking is automatically" +
          " established, the map presents two indicators: the reported GPS coordinates and heading in blue, and the" +
          " WPS coordinates and heading in red.\nThe values of the coordinates are also listed below the map.\n\n" +
          "You can drag the map to move the view around, and tap on it to place a cube in the world. The map will" +
          " display the distance to the cube, and can be used to help understand the difference in reported heading between the GPS and WPS systems.",
        color = Color.White
      )
    }
    onDispose { helpContentState.value = null }
  }

  val wpsManager = remember(context, nsdkSessionManager) {
    WpsManager(nsdkSessionManager)
  }
  val gpsManager = remember(context, nsdkSessionManager) {
    GpsManager(nsdkSessionManager)
  }

  // Let the manager handle cleaning itself up
  DisposableEffect(lifecycleOwner, wpsManager) {
    lifecycleOwner.lifecycle.addObserver(wpsManager)
    lifecycleOwner.lifecycle.addObserver(gpsManager)
    wpsManager.startWps()
    onDispose {
      wpsManager.onDestroy(lifecycleOwner)
      gpsManager.onDestroy(lifecycleOwner)
      lifecycleOwner.lifecycle.removeObserver(wpsManager)
      lifecycleOwner.lifecycle.removeObserver(gpsManager)
    }
  }

  val gpsLatitude by gpsManager.gpsLatitude.collectAsState()
  val gpsLongitude by gpsManager.gpsLongitude.collectAsState()
  val gpsHeadingDeg by gpsManager.gpsHeadingDeg.collectAsState()

  val wpsLatitude by wpsManager.wpsLatitude.collectAsState()
  val wpsLongitude by wpsManager.wpsLongitude.collectAsState()
  val wpsHeadingDeg by wpsManager.wpsHeadingDeg.collectAsState()
  val wpsOriginLatitude by wpsManager.wpsOriginLatitude.collectAsState()
  val wpsOriginLongitude by wpsManager.wpsOriginLongitude.collectAsState()
  val trackingToEdn by wpsManager.trackingToEdn.collectAsState()

  var geoInfoVisibleState by remember { mutableStateOf(false) }
  val geoInfoText by remember(gpsLatitude, gpsLongitude, wpsLatitude, wpsLongitude, trackingToEdn) {
    derivedStateOf {
      buildString {
        appendLine("Reference")
        appendLine(
          "GPS (Blue): (${String.format("%.6f", gpsLatitude)}, " +
            "${String.format("%.6f", gpsLongitude)})"
        )
        appendLine()
        appendLine(
          "WPS (Red): (${String.format("%.6f", wpsLatitude)}, " +
            "${String.format("%.6f", wpsLongitude)})"
        )
        appendLine()
        appendLine("Tracking to Edn:")
        appendLine(trackingToEdn?.formatMatrix())
      }.trim()
    }
  }

  var errorText by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(wpsManager) {
    wpsManager.events.collect { event ->
      errorText = when (event) {
        WpsEvent.SessionStarted,
        WpsEvent.PoseUpdated,
        WpsEvent.SessionStopped -> null
        is WpsEvent.SessionStartFailed -> "Failed to start session."
        is WpsEvent.SessionStopFailed -> "Failed to stop session."
        WpsEvent.NeedsMoreMotion -> "Please walk around more to initialize WPS estimate!"
        is WpsEvent.Error -> "WPS error: ${event.code}"
      }
    }
  }

  var hasCentered by remember { mutableStateOf(false) }

  val camera = rememberCameraState(
    firstPosition =
      CameraPosition(target = Position(latitude = 0.0, longitude = 0.0))
  )

  LaunchedEffect(gpsLatitude, gpsLongitude) {
    // Move map to the user position only once
    if (!hasCentered && gpsLatitude != null && gpsLongitude != null) {
      gpsLatitude?.let { gpsLat ->
        gpsLongitude?.let { gpsLng ->
          camera.animateTo(
            finalPosition = camera.position.copy(
              target = Position(
                latitude = gpsLat,
                longitude = gpsLng
              ),
              zoom = 17.0
            ),
            duration = 3.seconds
          )
          hasCentered = true
        }
      }
    }
  }

  var tappedPosition by remember { mutableStateOf<Position?>(null) }

  var distanceToTappedPosition by remember { mutableStateOf<Float?>(null) }

  // Update Cube position
  LaunchedEffect(cubePoseNode, tappedPosition, trackingToEdn, wpsOriginLatitude, wpsOriginLongitude, wpsLongitude, wpsLatitude, wpsHeadingDeg) {
    val node = cubePoseNode ?: return@LaunchedEffect
    val tap = tappedPosition ?: return@LaunchedEffect
    val trackingPose = trackingToEdn ?: return@LaunchedEffect
    val originLat = wpsOriginLatitude ?: return@LaunchedEffect
    val originLng = wpsOriginLongitude ?: return@LaunchedEffect

    val world = latLngToWorld(
      latitude = tap.latitude,
      longitude = tap.longitude,
      wpsOriginLatitude = originLat,
      wpsOriginLongitude = originLng,
      trackingToEdn = trackingPose
    )

    val cameraPose = nsdkSessionManager.currentFrame?.camera?.pose
    cameraPose?.let {
      world.y = it.ty()
      node.position = world
      distanceToTappedPosition = length(it.position - world)
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      distanceToTappedPosition?.let {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color.Black.copy(alpha = 0.7f),
        ) {
          Text(
            text = "Distance: ${"%.1f".format(it)} m",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp)
          )
        }
      }

      MaplibreMap(
        cameraState = camera,
        baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
        modifier = Modifier
          .fillMaxWidth()
          .fillMaxHeight(0.33f),
        onMapClick = { position, dpOffset ->
          tappedPosition = position
          if (cubePoseNode == null) {
            cubePoseNode = PoseNode(engine = engine).apply {
              val cube = CubeNode(
                engine = engine,
                size = Float3(1f, 1f, 1f),
                center = Float3(0.0f, 0.0f, 0.0f),
                materialInstance = cubeMaterial
              )
              addChildNode(cube)
              // Add to scene
              arChildNodes.add(this)
            }
          }
          ClickResult.Consume
        }
      ) {

        // Draw GPS point and heading cone
        gpsLatitude?.let { gpsLat ->
          gpsLongitude?.let { gpsLng ->
            val gpsPosition = Position(latitude = gpsLat, longitude = gpsLng)
            val userGpsLocationSource = rememberGeoJsonSource(data = GeoJsonData.Features(Point(gpsPosition)))
            CircleLayer(
              id = "user-gps-location",
              source = userGpsLocationSource, color = const(Color.Blue),
            )

            gpsHeadingDeg?.let { gpsHeading ->
              val gpsConeSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(
                  createHeadingCone(
                    center = gpsPosition,
                    headingDeg = gpsHeading,
                    zoom = camera.position.zoom
                  )
                )
              )

              FillLayer(
                id = "user-gps-heading",
                source = gpsConeSource,
                color = const(Color.Blue.copy(alpha = 0.2f))
              )
            }
          }
        }

        // Draw WPS point and heading cone
        wpsLatitude?.let { wpsLat ->
          wpsLongitude?.let { wpsLng ->
            val wpsPosition = Position(latitude = wpsLat, longitude = wpsLng)
            val userWpsLocationSource = rememberGeoJsonSource(data = GeoJsonData.Features(Point(wpsPosition)))

            CircleLayer(
              id = "user-wps-location",
              source = userWpsLocationSource, color = const(Color.Red),
            )

            wpsHeadingDeg?.let { wpsHeading ->
              val wpsConeSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(
                  createHeadingCone(
                    center = wpsPosition,
                    headingDeg = wpsHeading,
                    zoom = camera.position.zoom
                  )
                )
              )

              FillLayer(
                id = "user-wps-heading",
                source = wpsConeSource,
                color = const(Color.Red.copy(alpha = 0.2f))
              )
            }
          }
        }

        tappedPosition?.let { tap ->
          val tappedSource = rememberGeoJsonSource(
            data = GeoJsonData.Features(Point(tap))
          )
          CircleLayer(
            id = "user-tap",
            source = tappedSource,
            color = const(Color.Green),
          )
        }
      }

      Button(
        onClick = { geoInfoVisibleState = !geoInfoVisibleState }
      ) {
        Text(text = if (geoInfoVisibleState) "Hide Info" else "Show Info")
      }

      AnimatedVisibility(
        geoInfoVisibleState,
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = Color.Black.copy(alpha = 0.7f)
        ) {
          Text(
            text = geoInfoText,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp)
          )
        }
      }
    }

    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      errorText?.let {
          ErrorDisplay(errorMessage = it)
      }
    }
  }
}

private fun headingConeRadiusMeters(
  baseRadius: Double,
  zoom: Double,
  anchorZoom: Double = 17.0
): Double {
  val zoomDelta = anchorZoom - zoom
  val zoomScale = 2.0.pow(zoomDelta)
  return baseRadius * zoomScale
}

private fun createHeadingCone(
  center: Position,
  headingDeg: Double,
  zoom: Double,
  halfAngleDeg: Double = 25.0,
  baseRadiusMeters: Double = 20.0
): Polygon {
  val radiusMeters = headingConeRadiusMeters(baseRadiusMeters, zoom)
  val normalizedHeading = ((headingDeg % 360.0) + 360.0) % 360.0
  val left = offsetPosition(center, normalizedHeading - halfAngleDeg, radiusMeters)
  val right = offsetPosition(center, normalizedHeading + halfAngleDeg, radiusMeters)

  val ring = listOf(
    Position(longitude = center.longitude, latitude = center.latitude),
    left,
    right,
    Position(longitude = center.longitude, latitude = center.latitude)
  )

  return Polygon(listOf(ring))
}

private fun offsetPosition(
  origin: Position,
  bearingDeg: Double,
  distanceMeters: Double
): Position {
  val radiusEarth = 6_378_137.0
  val bearingRad = bearingDeg * PI / 180.0
  val angularDistance = distanceMeters / radiusEarth

  val lat1 = origin.latitude * PI / 180.0
  val lon1 = origin.longitude * PI / 180.0

  val lat2 = asin(
    sin(lat1) * cos(angularDistance) +
      cos(lat1) * sin(angularDistance) * cos(bearingRad)
  )
  val lon2 = lon1 + atan2(
    sin(bearingRad) * sin(angularDistance) * cos(lat1),
    cos(angularDistance) - sin(lat1) * sin(lat2)
  )

  return Position(
    latitude = lat2 * 180.0 / PI,
    longitude = lon2 * 180.0 / PI
  )
}

private fun haversineDistanceMeters(start: Position, end: Position): Double {
  val earthRadius = 6_378_137.0
  val lat1 = Math.toRadians(start.latitude)
  val lat2 = Math.toRadians(end.latitude)
  val deltaLat = lat2 - lat1
  val deltaLon = Math.toRadians(end.longitude - start.longitude)
  val a = sin(deltaLat / 2).pow(2) +
    cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
  val c = 2 * atan2(sqrt(a), sqrt(1 - a))
  return earthRadius * c
}

private fun latLngToWorld(
  latitude: Double,
  longitude: Double,
  wpsOriginLatitude: Double,
  wpsOriginLongitude: Double,
  trackingToEdn: Pose
): Float3 {
  val DEGREES_TO_METERS = 111_319.9
  // Compute tangential EDN offsets (east/north on the local plane, down assumed 0)
  val eastMeters = cos(latitude * PI / 180.0) * (longitude - wpsOriginLongitude) * DEGREES_TO_METERS
  val northMeters = (latitude - wpsOriginLatitude) * DEGREES_TO_METERS

  val ednToTracking = trackingToEdn.inverse()
  val trackingPoint = ednToTracking.transformPoint(floatArrayOf(eastMeters.toFloat(), 0f, northMeters.toFloat()))
  return Float3(trackingPoint[0], trackingPoint[1], trackingPoint[2])
}

private fun Pose.formatMatrix(): String {
  val MATRIX_INDENT = "  "
  val matrix = FloatArray(16)
  toMatrix(matrix, 0)
  return buildString {
    for (row in 0 until 4) {
      append(MATRIX_INDENT)
      for (col in 0 until 4) {
        append("% .5f".format(matrix[row * 4 + col]))
        if (col < 3) append(' ')
      }
      if (row < 3) appendLine()
    }
  }
}
