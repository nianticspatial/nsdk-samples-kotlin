package com.nianticspatial.nsdk.externalsamples.scenesegmentation

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.common.OverlayContent
import kotlinx.serialization.Serializable

@Serializable
object SceneSegmentationRoute

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneSegmentationView(
    nsdkSessionManager: NSDKSessionManager,
    helpContentState: MutableState<HelpContent?>,
    overlayContentState: MutableState<OverlayContent?>
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val sceneSegmentationManager = remember { SceneSegmentationManager(nsdkSessionManager) }

    var overlayOpacity by remember { mutableFloatStateOf(0.5f) }
    val overlayContent = remember { SceneSegmentationOverlayContent(sceneSegmentationManager, nsdkSessionManager, overlayOpacity) }

    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Semantics Sample Help\n\nThis sample runs semantic segmentation and " +
                    "visualizes the confidence map with a pink/blue gradient.\nTO USE:\n" +
                    "Pick a semantic class from the dropdown, adjust the transparency slider, " +
                    "and toggle the overlay visibility as needed.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    DisposableEffect(overlayContent) {
        overlayContentState.value = overlayContent
        overlayContent.show()
        onDispose {
            overlayContent.hide()
            overlayContent.reset()
            overlayContentState.value = null
        }
    }

    DisposableEffect(lifecycleOwner, sceneSegmentationManager) {
        lifecycleOwner.lifecycle.addObserver(sceneSegmentationManager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(sceneSegmentationManager)
            sceneSegmentationManager.stop()
        }
    }

    LaunchedEffect(sceneSegmentationManager) {
        sceneSegmentationManager.start()
    }

    var latestWarning by remember { mutableStateOf<SemanticsWarningEvent?>(null) }
    LaunchedEffect(sceneSegmentationManager) {
        sceneSegmentationManager.warnings.collect { event ->
            latestWarning =
                if (event == SemanticsWarningEvent.Cleared) null else event
        }
    }

    val channels by sceneSegmentationManager.channels.collectAsState()
    val selectedIndex by sceneSegmentationManager.selectedChannelIndex.collectAsState()
    val sessionState by sceneSegmentationManager.sessionState.collectAsState()

    var channelMenuExpanded by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }

    fun toggleOverlayVisibility() {
        overlayVisible = !overlayVisible
        if (overlayVisible) {
            overlayContent.show()
        } else {
            overlayContent.hide()
            overlayContent.reset()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatusCard(sessionState, latestWarning)

                ExposedDropdownMenuBox(
                    expanded = channelMenuExpanded,
                    onExpandedChange = {
                        if (channels.isNotEmpty()) {
                            channelMenuExpanded = !channelMenuExpanded
                        }
                    }
                ) {
                    val selectedChannel = channels.getOrNull(selectedIndex)?.toString()
                    OutlinedTextField(
                        value = selectedChannel ?: "Loading channels...",
                        onValueChange = {},
                        readOnly = true,
                        enabled = channels.isNotEmpty(),
                        label = { Text("Semantic Channel") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = channelMenuExpanded,
                        onDismissRequest = { channelMenuExpanded = false }
                    ) {
                        channels.forEachIndexed { index, channel ->
                            DropdownMenuItem(
                                text = { Text(channel.toString()) },
                                onClick = {
                                    sceneSegmentationManager.selectChannel(index)
                                    overlayContent.reset()
                                    channelMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Transparency")
                    Text(text = "${(overlayOpacity * 100).toInt()}%")
                }

                Slider(
                    value = overlayOpacity,
                    onValueChange = { value ->
                        overlayOpacity = value
                        overlayContent.setOpacity(value)
                    },
                    colors = SliderDefaults.colors(activeTickColor = Color.Magenta)
                )

                Button(onClick = { toggleOverlayVisibility() }) {
                    Text(text = if (overlayVisible) "Hide Overlay" else "Show Overlay")
                }
            }
        }
    }
}


@Composable
private fun StatusCard(
    sessionState: SemanticsSessionState,
    warningEvent: SemanticsWarningEvent?
) {
    val statusText = when (sessionState) {
        SemanticsSessionState.Idle -> "Tap Start to load semantics"
        SemanticsSessionState.LoadingChannels -> "Starting semantics..."
        is SemanticsSessionState.Streaming -> {
            val channelName = sessionState.channel?.toString()
            channelName?.let { "Streaming $it" } ?: "Streaming semantics"
        }
        SemanticsSessionState.Stopping -> "Stopping semantics..."
        is SemanticsSessionState.Failed -> {
            val reason =
                sessionState.cause?.message?.takeIf { it.isNotBlank() }
                    ?: sessionState.cause?.javaClass?.simpleName
            reason?.let { "Semantics session failed: $it" } ?: "Semantics session failed"
        }
    }

    val warningText = when (warningEvent) {
        is SemanticsWarningEvent.ChannelQueryFailed ->
            "Channel query failed: ${warningEvent.cause.userFacingMessage()}"

        is SemanticsWarningEvent.ChannelQueryError ->
            "Channel query error: ${warningEvent.status.name}"

        SemanticsWarningEvent.ChannelsUnavailable ->
            "Semantic channels unavailable"

        is SemanticsWarningEvent.LatestConfidenceResultError ->
            "Latest confidence error (channel ${warningEvent.channelIndex}): " +
                warningEvent.status.name

        is SemanticsWarningEvent.StopFailed ->
            "Failed to stop semantics: ${warningEvent.cause.userFacingMessage()}"

        SemanticsWarningEvent.Cleared -> null
        null -> null
    }

    if (statusText.isEmpty() && warningText.isNullOrEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (statusText.isNotEmpty()) {
                Text(text = statusText, color = Color.White)
            }
            if (!warningText.isNullOrEmpty()) {
                Text(text = warningText, color = Color(0xFFFFC107))
            }
        }
    }
}

private fun Throwable.userFacingMessage(): String =
    localizedMessage?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

