package com.nianticspatial.nsdk.externalsamples.wps

import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class GpsManager(
    private val nsdkSessionManager: NSDKSessionManager,
    parentScope: CoroutineScope? = null,
) : FeatureManager() {

    companion object {
        private const val SAMPLE_INTERVAL_MS = 100L
    }

    private val scope: CoroutineScope = if (parentScope != null) {
        // Create a child scope so we follow the parent’s lifetime but keep
        // cancellations/errors isolated to this manager.
        val context: CoroutineContext = parentScope.coroutineContext
        CoroutineScope(context + SupervisorJob(context[Job]))
    } else {
        // No external scope was provided, so own a dedicated supervisor scope.
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }
    private var pollingJob: Job? = null

    // MutableStateFlow is used so the data can be observed from both Compose
    // and non-UI consumers (ViewModels, tests, etc.) while still exposing a
    // reactive stream that supports multiple collectors.
    private val _gpsLatitude = MutableStateFlow<Double?>(null)
    val gpsLatitude: StateFlow<Double?> = _gpsLatitude

    private val _gpsLongitude = MutableStateFlow<Double?>(null)
    val gpsLongitude: StateFlow<Double?> = _gpsLongitude

    private val _gpsHeadingDeg = MutableStateFlow<Double?>(null)
    val gpsHeadingDeg: StateFlow<Double?> = _gpsHeadingDeg

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        startGps()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        stopGps()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        stopGps()
        scope.cancel()
    }

    private fun startGps() {
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            while (isActive) {
                // Fetch sensor data off the main thread to avoid blocking UI rendering.
                val location = withContext(Dispatchers.Default) { nsdkSessionManager.arManager.lastLocation }
                val heading = withContext(Dispatchers.Default) {
                    nsdkSessionManager.sensorHelper.compass().trueHeading.toDouble()
                }

                // Publish the latest readings on the main thread so UI state stays in sync.
                withContext(Dispatchers.Main.immediate) {
                    location?.let {
                        if (_gpsLatitude.value != it.latitude || _gpsLongitude.value != it.longitude) {
                            _gpsLatitude.value = it.latitude
                            _gpsLongitude.value = it.longitude
                        }
                    }
                    if (_gpsHeadingDeg.value != heading) {
                        _gpsHeadingDeg.value = heading
                    }
                }

                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopGps() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
