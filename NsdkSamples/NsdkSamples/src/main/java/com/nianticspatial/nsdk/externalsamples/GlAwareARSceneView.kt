// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.MissingGlContextException
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ViewNode2.WindowManager

/**
 * [ARSceneView] that rebinds Filament's EGL context before each ARCore update.
 *
 * WebView/Chromium can clear the main-thread current EGL context; ARCore then throws
 * [MissingGlContextException] because SceneView calls [Session.update] before Filament
 * [Renderer.beginFrame] restores the SurfaceView context.
 */
class GlAwareARSceneView @JvmOverloads constructor(
    context: Context,
    private val eglHolder: EglContextHolder,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    sharedActivity: ComponentActivity? = null,
    sharedLifecycle: Lifecycle? = null,
    sharedEngine: Engine? = null,
    sharedModelLoader: ModelLoader? = null,
    sharedMaterialLoader: MaterialLoader? = null,
    sharedEnvironmentLoader: EnvironmentLoader? = null,
    sharedScene: Scene? = null,
    sharedView: View? = null,
    sharedRenderer: Renderer? = null,
    sharedCameraNode: ARCameraNode? = null,
    sharedMainLightNode: LightNode? = null,
    sharedEnvironment: Environment? = null,
    isOpaque: Boolean = true,
    sharedCollisionSystem: CollisionSystem? = null,
    sharedCameraStream: ARCameraStream? = null,
    sessionFeatures: Set<Session.Feature> = setOf(),
    sessionCameraConfig: ((Session) -> CameraConfig)? = null,
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    viewNodeWindowManager: WindowManager? = null,
    onSessionCreated: ((session: Session) -> Unit)? = null,
    onSessionResumed: ((session: Session) -> Unit)? = null,
    onSessionPaused: ((session: Session) -> Unit)? = null,
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    onGestureListener: GestureDetector.OnGestureListener? = null,
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
) : ARSceneView(
    context = context,
    attrs = attrs,
    defStyleAttr = defStyleAttr,
    defStyleRes = defStyleRes,
    sharedActivity = sharedActivity,
    sharedLifecycle = sharedLifecycle,
    sharedEngine = sharedEngine,
    sharedModelLoader = sharedModelLoader,
    sharedMaterialLoader = sharedMaterialLoader,
    sharedEnvironmentLoader = sharedEnvironmentLoader,
    sharedScene = sharedScene,
    sharedView = sharedView,
    sharedRenderer = sharedRenderer,
    sharedCameraNode = sharedCameraNode,
    sharedMainLightNode = sharedMainLightNode,
    sharedEnvironment = sharedEnvironment,
    isOpaque = isOpaque,
    sharedCollisionSystem = sharedCollisionSystem,
    sharedCameraStream = sharedCameraStream,
    sessionFeatures = sessionFeatures,
    sessionCameraConfig = sessionCameraConfig,
    sessionConfiguration = sessionConfiguration,
    viewNodeWindowManager = viewNodeWindowManager,
    onSessionCreated = onSessionCreated,
    onSessionResumed = onSessionResumed,
    onSessionPaused = onSessionPaused,
    onSessionFailed = onSessionFailed,
    onTrackingFailureChanged = onTrackingFailureChanged,
    onGestureListener = onGestureListener,
    onTouchEvent = onTouchEvent,
    onSessionUpdated = onSessionUpdated,
) {
    override fun onFrame(frameTimeNanos: Long) {
        // Always rebind before Session.update — WebView may clear the thread's current context
        // between frames, and EGLContext Java wrappers are not reliably comparable with ==.
        eglHolder.makeCurrent()
        try {
            super.onFrame(frameTimeNanos)
        } catch (e: MissingGlContextException) {
            if (eglHolder.makeCurrent()) {
                try {
                    super.onFrame(frameTimeNanos)
                } catch (retry: MissingGlContextException) {
                    Log.e(TAG, "ARCore update still missing GL context after rebind", retry)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GlAwareARSceneView"
    }
}
