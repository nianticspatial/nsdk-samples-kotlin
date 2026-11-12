// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.playbackselect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.HelpContent
import kotlinx.serialization.Serializable

@Serializable
object DownloadedPlaybacksRoute

/** Lists every scan in the playback cache, from any organization or site, to play or delete. */
@Composable
fun DownloadedPlaybacksView(
    nsdkSession: NSDKSession,
    library: PlaybackLibrary,
    helpContentState: MutableState<HelpContent?>,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember { DownloadedPlaybacksManager(nsdkSession, library) }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(manager)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(manager)
            manager.onDestroy(lifecycleOwner)
            library.dropPendingSource()
        }
    }
    LaunchedEffect(Unit) { manager.load() }
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Downloaded Playbacks\n\n" +
                    "Every scan in this device's playback cache. Tapping one plays it in place " +
                    "of the camera in every sample. A scan whose details cannot be looked up " +
                    "shows its ID. A download in progress shows its progress, and tapping an " +
                    "incomplete one, left when the app stopped mid-download, downloads it " +
                    "again. The trash button removes a scan from the cache, stopping " +
                    "playback first if it is playing.",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Title("Downloaded Playbacks")
        manager.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
        val scans = manager.scans?.filter { it.id in library.downloads }
        when {
            scans == null -> if (manager.error == null) CircularProgressIndicator(color = Color.White)
            scans.isEmpty() -> Text("No downloaded playbacks", color = Color.Gray)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(scans.orEmpty(), key = { it.id }) { scan ->
                ScanEntryButton(
                    scan = scan,
                    showSite = false,
                    download = library.downloads[scan.id],
                    isActive = library.activeScan?.id == scan.id,
                    onClick = { library.select(scan) },
                    canDelete = library.canDelete(scan.id),
                    onDelete = { library.delete(scan.id) },
                    fileInfo = library.fileInfos[scan.id],
                    loadFileInfo = { library.loadFileInfo(scan.id) },
                )
            }
        }
    }
}
