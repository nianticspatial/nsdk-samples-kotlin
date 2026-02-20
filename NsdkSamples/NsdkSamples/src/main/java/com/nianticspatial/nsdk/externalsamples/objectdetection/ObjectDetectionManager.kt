package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.AwarenessImageParams
import com.nianticspatial.nsdk.AwarenessStatus
import com.nianticspatial.nsdk.Orientation
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import com.nianticspatial.nsdk.objectdetection.DetectedObject
import com.nianticspatial.nsdk.objectdetection.ObjectDetectionConfig
import com.nianticspatial.nsdk.objectdetection.ObjectDetectionResult
import com.nianticspatial.nsdk.objectdetection.toDetectedObjects
import com.nianticspatial.nsdk.objectdetection.transformed
import com.nianticspatial.nsdk.utils.ImageMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

class ObjectDetectionManager(
    private val nsdkManager: NSDKSessionManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : FeatureManager() {

    companion object {
        const val FRAME_RATE = 30 // fps
        const val MIN_PROBABILITY_= 0.5f // filter out lower probability detection than this
        val detectionUpdateDelayMs: Long = ((1000f / FRAME_RATE).toLong())
    }

    // NSDK Object Detection session instance
    val objectDetectionSession = nsdkManager.session.objectDetection.acquire()

    var detectionStarted by mutableStateOf(false)
        private set

    var viewTransformedDetectedObjects by mutableStateOf<List<DetectedObject>>(emptyList())
        private set

    var classNames by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        // Configure the object detection feature
        objectDetectionSession.configure(
            ObjectDetectionConfig(
                frameRate = FRAME_RATE,
                framesUntilSeen = 0,
                framesUntilDiscarded = 0
            )
        )
    }

    private fun processLatestDetections(viewportSize: Size): NSDKResult<ObjectDetectionResult, AwarenessStatus> {
        val result = objectDetectionSession.getLatestDetections()

        if (result is NSDKResult.Success) {

            // Extract detected objects
            val list = result.value.toDetectedObjects()

            // Cache the awareness params for reprojection
            val latestAwarenessImageParams = result.value.awarenessParams;

            // Calculate an affine mapping from the original coordinate frame to the viewport
            val displayMatrix = calculateDisplayMatrix(viewportSize)

            // Acquire the original coordinate frame of the bounding boxes
            val modelSize = when (val metadata = objectDetectionSession.getMetadata()) {
                is NSDKResult.Success -> metadata.value.imageParams.modelFrameSize
                else -> Size(256, 256)
            }

            val reprojectionMatrix =
                calculateReprojectionMatrix(latestAwarenessImageParams, nsdkManager.currentFrame)

            // Convert the detected objects to the viewport
            var transformedObjects: List<DetectedObject> = listOf()
            list.forEach { obj ->

                if (obj.probability >= MIN_PROBABILITY_) {
                    // Transform the bounding box to fit the viewport
                    val detectedObject = obj.transformed(
                        containerSize = modelSize,
                        viewportSize = viewportSize,
                        display = displayMatrix,
                        reprojection = reprojectionMatrix
                    )

                    transformedObjects = transformedObjects + detectedObject
                }
            }

            viewTransformedDetectedObjects = transformedObjects

            // Lazy-load class names
            if (classNames.isEmpty()) {
                when (val namesResult = objectDetectionSession.getClassNames()) {
                    is NSDKResult.Success -> classNames = namesResult.value.toList()
                    is NSDKResult.Error -> Log.i(
                        "NSDK ObjectDetection",
                        "Class names not available yet: ${namesResult.code}"
                    )
                }
            }
        }

        return result
    }

    fun getObjectName(obj: DetectedObject): String? {
        return classNames.getOrNull(obj.classId)
    }

    private fun calculateReprojectionMatrix(
        imageParams: AwarenessImageParams?,
        frame: Frame?,
    ): Matrix {
        if (frame == null || imageParams == null) return Matrix()

        // 1. Compute rotation angle around Z axis (in radians) based on viewport orientation
        val angle = when (nsdkManager.arManager.lastImageOrientation) {
            Orientation.PORTRAIT -> -PI.toFloat() / 2f
            Orientation.LANDSCAPE_LEFT -> -PI.toFloat()
            Orientation.PORTRAIT_UPSIDE_DOWN -> -PI.toFloat() * 1.5f
            else -> 0f
        }

        // 2. Create a rotation pose from the Z-axis quaternion
        val halfAngle = angle / 2f
        val rotation = Pose.makeRotation(0f, 0f, sin(halfAngle), cos(halfAngle))

        // 3. Rotate both poses and invert to get view matrices
        val referencePose = rotation.compose(imageParams!!.extrinsics.inverse())
        val targetPose = rotation.compose(frame.camera.pose.inverse())

        val referenceMatrix = FloatArray(16)
        val targetMatrix = FloatArray(16)
        referencePose.toMatrix(referenceMatrix, 0)
        targetPose.toMatrix(targetMatrix, 0)

        // 4. Projection parameters
        val imageSize = imageParams!!.intrinsics.getImageDimensions();
        val aspect = imageSize[0].toFloat() / imageSize[1].toFloat()
        val focalLengthY = imageParams!!.intrinsics.getFocalLength()[1] // fy
        val fovRadians = 2f * atan(imageSize[1].toFloat() / (2f * focalLengthY))

        // 5. Return the computed 3x3 homography
        return ImageMath.reprojection(
            aspect = aspect,
            fovRadians = fovRadians,
            zNear = 0.2f,
            zFar = 100f,
            referenceView = referenceMatrix,
            targetView = targetMatrix
        )
    }

    private fun calculateDisplayMatrix(size: Size): Matrix? {
        return objectDetectionSession.calculateViewportMapping(
            viewportSize = size,
            orientation = nsdkManager.arManager.lastImageOrientation
        )
    }

    fun startDetection(
        viewportSize: Size,
        processDetectionsCallback: (NSDKResult<ObjectDetectionResult, AwarenessStatus>) -> Unit
    ) {
        if (detectionStarted)
            return

        detectionStarted = true

        objectDetectionSession.start()

        coroutineScope.launch {
            while (detectionStarted) {
                delay(detectionUpdateDelayMs)

                val result = processLatestDetections(viewportSize)
                processDetectionsCallback(result)
            }
        }
    }

    fun stopDetection() {
        if (!detectionStarted)
            return

        detectionStarted = false

        objectDetectionSession.stop()
        viewTransformedDetectedObjects = emptyList()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        objectDetectionSession.stop()
        objectDetectionSession.close()
    }
}
