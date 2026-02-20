package com.nianticspatial.nsdk.externalsamples.objectdetection

import android.util.Log
import android.util.Size as AndroidSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.nianticspatial.nsdk.NSDKResult
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.HelpContent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.Serializable

@Serializable
object ObjectDetectionRoute

@OptIn(DelicateCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetectionView(
    nsdkManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>
) {

    val objectDetectionManager =
        remember { ObjectDetectionManager(nsdkManager) }

    // Aggregation manager is used to aggregate objects across frames to prevent jitter
    val aggregationManager =
        remember { AggregationManager(objectDetectionManager) }

    val continuousPresenter = remember { ContinuousPresenter() }
    val tapSelectPresenter = remember { TapSelectPresenter() }

    var useTapSelectPresenter by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    var viewportSize: AndroidSize by remember { mutableStateOf(AndroidSize(1, 1)) }

    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Object Detection Sample Help\n\nTThis sample identifies objects that fall under NSDK’s" +
                    " classification categories and then draws bounding boxes around the detected objects.\nTO USE:\nPress the \"Start\" button," +
                    " and move the camera around, pointing at the objects you want to identify. " +
                    " To switch between tap and continuous mode, press the button below the start detection button." +
                    "\n\n In Continuous mode, red boxes will " +
                    " appear around the objects detected along with their labels and confidence level." +
                    "\n\n In Tap mode, a yellow box will " +
                    " appear around the object you tap on, along with its label and confidence level.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    DisposableEffect(lifecycleOwner, objectDetectionManager) {
        lifecycleOwner.lifecycle.addObserver(objectDetectionManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(objectDetectionManager)
        }
    }

    DisposableEffect(aggregationManager, continuousPresenter, tapSelectPresenter) {
        onDispose {
            aggregationManager.clear()
            continuousPresenter.clear()
            tapSelectPresenter.clear()
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .onGloballyPositioned {
                layoutCoordinates ->
            viewportSize = AndroidSize(layoutCoordinates.size.width, layoutCoordinates.size.height)
        }
        .pointerInput(useTapSelectPresenter) {
            if (useTapSelectPresenter) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { change ->
                            val position = change.position


                            val touchX = position.x
                            val touchY = position.y

                            // Ignore touch if its on the general area of the buttons
                            val screenHeight = size.height
                            val buttonAreaHeight = screenHeight * 0.15f
                            val isInButtonArea = touchY > (screenHeight - buttonAreaHeight)

                            if (!isInButtonArea) {
                                when {
                                    change.pressed && !change.previousPressed -> {
                                        // Touch down
                                        tapSelectPresenter.handleTouchBegan(Offset(touchX, touchY))
                                    }
                                    !change.pressed && change.previousPressed -> {
                                        // Touch up
                                        tapSelectPresenter.handleTouchEnded()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }) {

        // Toggle button for presenter mode
        Button(
            onClick = {
                useTapSelectPresenter = !useTapSelectPresenter
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp)
        ) {
            Text(if (useTapSelectPresenter) "Tap Mode" else "Continuous Mode")
        }

        // Toggle button for Start/Stop Detection
        Button(
            onClick = {
                if (!objectDetectionManager.detectionStarted) {

                    objectDetectionManager.startDetection(viewportSize) { processedDetectionsResult ->
                        when (processedDetectionsResult) {
                            is NSDKResult.Success -> {
                                val aggregated = aggregationManager.update(objectDetectionManager.viewTransformedDetectedObjects)
                                continuousPresenter.update(aggregated)
                                tapSelectPresenter.update(aggregated)
                            }
                            is NSDKResult.Error -> {
                                Log.d("NSDK", "Detection error: ${processedDetectionsResult.code}")
                            }
                        }
                    }
                } else {
                    objectDetectionManager.stopDetection()
                    aggregationManager.clear()
                    continuousPresenter.clear()
                    tapSelectPresenter.clear()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(if (objectDetectionManager.detectionStarted) "Stop Detection" else "Start Detection")
        }

        // Use the correct presenter to draw detected objects based on mode
        if (useTapSelectPresenter) {
            tapSelectPresenter.Draw(modifier = Modifier.fillMaxSize())
        } else {
            continuousPresenter.Draw(modifier = Modifier.fillMaxSize())
        }
    }
}
