// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.media.Image
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.CameraIntrinsicsFromARCore
import com.nianticspatial.nsdk.FrameData
import com.nianticspatial.nsdk.InputDataFlags

class NSDKSessionManager(
    context: Context,
    val session: NSDKSession,
    val arManager: ARSessionManager
) : DefaultLifecycleObserver, OnFrameUpdateListener {
    val sensorHelper: SensorHelper by lazy { SensorHelper(context) }

    var currentFrame: Frame? = null
        private set

    init {
        arManager.setFrameUpdateListener(this)
    }

    private fun sendFrame(frame: Frame, requested: Int) {
        val data = FrameData(frame.timestamp / 1_000_000, frame.timestamp)

        if (InputDataFlags.GPS_LOCATION.Within(requested)) {
            data.location = arManager.lastLocation
        }

        var image: Image? = null
        if (InputDataFlags.CAMERA_IMAGE.Within(requested)) {
            // Camera Image might not be ready yet, skip if so
            try {
                image = frame.acquireCameraImage()
                data.cameraImagePlanes = image.planes
                data.cameraImageWidth = image.width
                data.cameraImageHeight = image.height
                data.cameraImageFormat = image.format
                data.cameraIntrinsics = CameraIntrinsicsFromARCore(frame.camera.imageIntrinsics)
            } catch (ignored: NotYetAvailableException) {
                // Continue execution if the camera image is not yet available
            }
        }

        if (InputDataFlags.POSE.Within(requested)) {
            // Use camera pose provided by ARCore (OpenGL coordinate system)
            data.cameraPose = frame.camera.pose
        }

        if (InputDataFlags.DEVICE_ORIENTATION.Within(requested)) {
            data.screenOrientation = arManager.lastImageOrientation
        }

        if (InputDataFlags.TRACKING_STATE.Within(requested)) {
            data.trackingState = frame.camera.trackingState
        }

        if (InputDataFlags.COMPASS.Within(requested)) {
            data.compass = sensorHelper.compass()
        }

        session.sendFrame(data)
        image?.close()
    }

    override fun onFrameUpdate(frame: Frame) {
        currentFrame = frame
        val requested = session.getRequestedDataFormats()

        if (!InputDataFlags.NONE.Is(requested)) {
            sendFrame(frame, requested)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        sensorHelper.resume()
    }

    override fun onPause(owner: LifecycleOwner) {
        sensorHelper.pause()
    }
}
