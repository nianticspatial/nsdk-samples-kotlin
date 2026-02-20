package com.nianticspatial.nsdk.externalsamples.wps

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.WPSError
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WpsEvent {
  data object SessionStarted : WpsEvent
  data class SessionStartFailed(val cause: Throwable) : WpsEvent
  data object PoseUpdated : WpsEvent
  data object SessionStopped : WpsEvent
  data class SessionStopFailed(val cause: Throwable) : WpsEvent
  data object NeedsMoreMotion : WpsEvent
  data class Error(val code: WPSError) : WpsEvent
}

class WpsManager(
    private val nsdkSessionManager: NSDKSessionManager,
    parentScope: CoroutineScope? = null,
) : FeatureManager() {

  companion object {
    private const val TAG = "WpsManager"
    private const val SAMPLE_INTERVAL_MS = 100L
  }

  private val scope: CoroutineScope = if (parentScope != null) {
    // Create a child scope so we follow the parent’s lifetime but keep
    // cancellations/errors isolated to this manager.
    val context = parentScope.coroutineContext
    CoroutineScope(context + SupervisorJob(context[Job]))
  } else {
    // No external scope was provided, so own a dedicated supervisor scope.
    CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
  }
  private var pollingJob: Job? = null
  private val wpsSession = nsdkSessionManager.session.wps.acquire()
  private var shouldSessionResume: Boolean = false

  // MutableStateFlow is used so the data can be observed from both Compose
  // and non-UI consumers (ViewModels, tests, etc.) while still exposing a
  // reactive stream that supports multiple collectors.
  private val _wpsLatitude = MutableStateFlow<Double?>(null)
  val wpsLatitude: StateFlow<Double?> = _wpsLatitude

  private val _wpsLongitude = MutableStateFlow<Double?>(null)
  val wpsLongitude: StateFlow<Double?> = _wpsLongitude

  private val _wpsHeadingDeg = MutableStateFlow<Double?>(null)
  val wpsHeadingDeg: StateFlow<Double?> = _wpsHeadingDeg

  private val _wpsOriginLatitude = MutableStateFlow<Double?>(null)
  val wpsOriginLatitude: StateFlow<Double?> = _wpsOriginLatitude

  private val _wpsOriginLongitude = MutableStateFlow<Double?>(null)
  val wpsOriginLongitude: StateFlow<Double?> = _wpsOriginLongitude

  private val _trackingToEdn = MutableStateFlow<Pose?>(null)
  val trackingToEdn: StateFlow<Pose?> = _trackingToEdn

  private val _events = MutableSharedFlow<WpsEvent>(replay = 0)
  val events: SharedFlow<WpsEvent> = _events

  private val _isRunning = MutableStateFlow(false)
  val isRunning: StateFlow<Boolean> = _isRunning

  fun startWps() {
    if (isRunning.value) return

    runCatching { wpsSession.start() }
      .onFailure { error ->
        Log.e(TAG, "Failed to start WPS session", error)
        scope.launch { _events.emit(WpsEvent.SessionStartFailed(error)) }
        return
      }

    _isRunning.value = true
    scope.launch { _events.emit(WpsEvent.SessionStarted) }

    pollingJob = scope.launch {
      while (isActive) {
        // Fetch data off the main thread to avoid blocking UI rendering.
        val pose = withContext(Dispatchers.Default) {
          nsdkSessionManager.currentFrame?.camera?.displayOrientedPose
        }

        // Publish the latest readings on the main thread so UI state stays in sync.
        withContext(Dispatchers.Main.immediate) {
          pose?.let { updateWps(it) }
        }
        delay(SAMPLE_INTERVAL_MS)
      }
    }
  }

  fun stopWps() {
    if (!isRunning.value) return

    pollingJob?.cancel()
    pollingJob = null

    runCatching { wpsSession.stop() }
      .onFailure { error ->
        Log.e(TAG, "Failed to stop WPS session", error)
        scope.launch { _events.emit(WpsEvent.SessionStopFailed(error)) }
      }
      .onSuccess {
        scope.launch { _events.emit(WpsEvent.SessionStopped) }
      }

    _isRunning.value = false
  }

  private fun updateWps(pose: Pose) {
    when (val result = wpsSession.getDevicePoseAsGeolocation(pose)) {
      is NSDKResult.Success -> {
        _wpsLatitude.value = result.value.latitude
        _wpsLongitude.value = result.value.longitude
        _wpsHeadingDeg.value = result.value.heading

        when (val locationResult = wpsSession.getLatestLocation()) {
          is NSDKResult.Success -> {
            val location = locationResult.value
            _wpsOriginLatitude.value = location.referenceLatitudeDegrees
            _wpsOriginLongitude.value = location.referenceLongitudeDegrees
            _trackingToEdn.value = location.trackingToRelativeEdn
          }
          is NSDKResult.Error -> {
            val event = if (locationResult.code == WPSError.NOT_INITIALIZED) {
              WpsEvent.NeedsMoreMotion
            } else {
              WpsEvent.Error(locationResult.code)
            }
            scope.launch { _events.emit(event) }
            return
          }
        }

        scope.launch { _events.emit(WpsEvent.PoseUpdated) }
      }
      is NSDKResult.Error -> {
        val event = if (result.code == WPSError.NOT_INITIALIZED) {
          WpsEvent.NeedsMoreMotion
        } else {
          WpsEvent.Error(result.code)
        }
        scope.launch { _events.emit(event) }
      }
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    super.onResume(owner)
    if (shouldSessionResume) {
      shouldSessionResume = false
      startWps()
    }
  }

  override fun onPause(owner: LifecycleOwner) {
    super.onPause(owner)
    shouldSessionResume = isRunning.value
    stopWps()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    super.onDestroy(owner)
    stopWps()
    scope.cancel()
    runCatching { wpsSession.close() }
      .onFailure { error -> Log.e(TAG, "Failed to close WPS session", error) }
  }
}
