// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.capture

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.DarkGray
import androidx.compose.ui.graphics.Color.Companion.LightGray
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class CaptureRoute(val dummy: Boolean = false)

@Composable
fun CaptureView(
    context: Activity,
    nsdkManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    overlayContentState: MutableState<OverlayContent?>
) {
    val captureManager = remember(nsdkManager) {
        CaptureManager(
            nsdkManager.session.scanning.acquire(),
            nsdkManager.session.recordingExporter.acquire()
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val captureOverlayContent = remember(captureManager) { CaptureOverlayContent(captureManager) }
    DisposableEffect(captureOverlayContent) {
        overlayContentState.value = captureOverlayContent
        onDispose {
            captureOverlayContent.hide()
            overlayContentState.value = null
        }
    }

    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Capture Sample Help\n\nThis sample creates and saves a recording that can later" +
        " be used for NSDK Playback\nTO USE:\nPress 'Start Capture' to start capturing." +
        " A simple visualization will indicate the parts covered by the capture, " +
        "with red stripes indicating the missed areas. After the capture stops, " +
        "the capture will automatically be exported to Niantic Spatial's " +
        "recorder format and you can use the result archive for NSDK Playback.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    DisposableEffect(captureManager) {
        lifecycleOwner.lifecycle.addObserver(captureManager)
        onDispose {
            captureManager.onDestroy(lifecycleOwner)
            lifecycleOwner.lifecycle.removeObserver(captureManager)
        }
    }

    LaunchedEffect(captureManager.isRecording) {
        if (captureManager.isRecording) {
            captureOverlayContent.show()
        } else {
            captureOverlayContent.hide()
        }
    }

    CaptureControls(
        captureManager = captureManager,
        context = context,
        coroutineScope = coroutineScope
    )
}

@Composable
private fun CaptureControls(
    captureManager: CaptureManager,
    context: Activity,
    coroutineScope: CoroutineScope
) {

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            enabled = !captureManager.isSaving && !captureManager.isExporting &&
                (!captureManager.isRecording || captureManager.frameCount > 0),
            onClick = {
                if (!captureManager.isRecording) {
                    coroutineScope.launch {
                        captureManager.startCapture()
                    }
                } else {
                    coroutineScope.launch {
                        captureManager.save()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = DarkGray,
                disabledContentColor = LightGray
            )
        ) {
            val buttonText = when {
                captureManager.isSaving -> "Saving..."
                captureManager.isRecording && captureManager.frameCount == 0 -> "Capturing..."
                captureManager.isRecording -> "Stop Capture"
                else -> "Start Capture"
            }
            Text(buttonText)
        }

        if (captureManager.isExporting) {
            LinearProgressIndicator(
                progress = captureManager.exportProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
        }

        if (captureManager.saveInfo != null && !captureManager.isExporting) {
            Button(
                enabled = !captureManager.isRecording && !captureManager.isSaving,
                onClick = {
                    coroutineScope.launch {
                        val result = captureManager.export()
                        // Run on main thread for file sharing
                        context.runOnUiThread {
                            result.onSuccess { exportedPath ->
                                try {
                                    val file = File(exportedPath)
                                    if (file.exists()) {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )

                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip" // or "application/octet-stream" for generic files
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }

                                        val chooser = Intent.createChooser(shareIntent, "Share Capture")
                                        context.startActivity(chooser)
                                    } else {
                                        Log.e("CaptureView", "Exported file not found at: $exportedPath")
                                    }
                                } catch (e: Exception) {
                                    Log.e("CaptureView", "Failed to share exported file", e)
                                }
                            }.onFailure { exception ->
                                AlertDialog.Builder(context)
                                    .setTitle("Export Failed")
                                    .setMessage(exception.message ?: "Unknown error")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
            ) {
                Text("Export Capture")
            }
        }
    }
}
