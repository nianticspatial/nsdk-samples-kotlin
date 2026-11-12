// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.node.ARCameraNode
import io.github.sceneview.ar.rememberAREnvironment
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberView

/**
 * Same as SceneView [ARScene], but uses [GlAwareARSceneView] so ARCore keeps a current EGL
 * context when a WebView overlay is also on screen.
 */
@Composable
fun GlAwareARScene(
    eglHolder: EglContextHolder,
    modifier: Modifier = Modifier,
    engine: Engine,
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader,
    environmentLoader: EnvironmentLoader = rememberEnvironmentLoader(engine),
    scene: Scene = rememberScene(engine),
    view: View = rememberView(engine),
    renderer: Renderer = rememberRenderer(engine),
    cameraNode: ARCameraNode,
    mainLightNode: LightNode? = rememberMainLightNode(engine),
    environment: Environment = rememberAREnvironment(engine),
    isOpaque: Boolean = true,
    collisionSystem: CollisionSystem = rememberCollisionSystem(view),
    planeRenderer: Boolean = false,
    cameraStream: ARCameraStream,
    sessionFeatures: Set<Session.Feature> = setOf(),
    sessionCameraConfig: ((Session) -> CameraConfig)? = null,
    sessionConfiguration: ((session: Session, Config) -> Unit)? = null,
    childNodes: List<Node> = rememberNodes(),
    onSessionCreated: ((session: Session) -> Unit)? = null,
    onSessionResumed: ((session: Session) -> Unit)? = null,
    onSessionPaused: ((session: Session) -> Unit)? = null,
    onSessionFailed: ((exception: Exception) -> Unit)? = null,
    onSessionUpdated: ((session: Session, frame: Frame) -> Unit)? = null,
    onTrackingFailureChanged: ((trackingFailureReason: TrackingFailureReason?) -> Unit)? = null,
    onGestureListener: GestureDetector.OnGestureListener? = rememberOnGestureListener(),
    onTouchEvent: ((e: MotionEvent, hitResult: HitResult?) -> Boolean)? = null,
    activity: ComponentActivity? = LocalContext.current as? ComponentActivity,
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle,
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GlAwareARSceneView(
                context = context,
                eglHolder = eglHolder,
                sharedActivity = activity,
                sharedLifecycle = lifecycle,
                sharedEngine = engine,
                sharedModelLoader = modelLoader,
                sharedMaterialLoader = materialLoader,
                sharedEnvironmentLoader = environmentLoader,
                sharedScene = scene,
                sharedView = view,
                sharedRenderer = renderer,
                sharedCameraNode = cameraNode,
                sharedMainLightNode = mainLightNode,
                sharedEnvironment = environment,
                isOpaque = isOpaque,
                sharedCollisionSystem = collisionSystem,
                sharedCameraStream = cameraStream,
                sessionFeatures = sessionFeatures,
                sessionCameraConfig = sessionCameraConfig,
                sessionConfiguration = sessionConfiguration,
                onSessionCreated = onSessionCreated,
                onSessionResumed = onSessionResumed,
                onSessionPaused = onSessionPaused,
                onSessionFailed = onSessionFailed,
                onTrackingFailureChanged = onTrackingFailureChanged,
                onGestureListener = onGestureListener,
                onTouchEvent = onTouchEvent,
                onSessionUpdated = onSessionUpdated,
            )
        },
        update = { sceneView ->
            sceneView.childNodes = childNodes
            sceneView.scene = scene
            sceneView.setCameraNode(cameraNode)
            sceneView.mainLightNode = mainLightNode
            sceneView.environment = environment
            sceneView.onGestureListener = onGestureListener
            sceneView.onTouchEvent = onTouchEvent
            sceneView.planeRenderer.isEnabled = planeRenderer
            sceneView.onSessionCreated = onSessionCreated
            sceneView.onSessionResumed = onSessionResumed
            sceneView.onSessionPaused = onSessionPaused
            sceneView.onSessionFailed = onSessionFailed
            sceneView.onTrackingFailureChanged = onTrackingFailureChanged
            sceneView.onSessionUpdated = onSessionUpdated
        },
        onReset = {},
        onRelease = { sceneView -> sceneView.destroy() },
    )
}

@Composable
fun rememberEglContextHolder(): EglContextHolder {
    val holder = remember { EglContextHolder.create() }
    DisposableEffect(holder) {
        onDispose { holder.destroy() }
    }
    return holder
}
