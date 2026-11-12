// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.playbackselect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.sites.SearchFilterField
import com.nianticspatial.nsdk.playback.PlaybackFileInfo
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
object PlaybackSelectRoute

internal val Background = Color(0xFF1A1A2E)
private val Accent = Color(0xFF4A90D9)

/** Picks a validation scan to download and play in place of the camera. */
@Composable
fun PlaybackSelectView(
    nsdkSession: NSDKSession,
    library: PlaybackLibrary,
    helpContentState: MutableState<HelpContent?>,
    onShowDownloaded: () -> Unit,
) {
    val entry = LocalLifecycleOwner.current as ViewModelStoreOwner
    val manager = remember(entry) {
        val holders = ViewModelProvider(entry, ManagerHolder.factory(nsdkSession))
        holders[ManagerHolder::class.java].manager
    }
    DisposableEffect(Unit) {
        onDispose { library.dropPendingSource() }
    }
    LaunchedEffect(Unit) { manager.start() }
    LaunchedEffect(Unit) { library.refreshCached() }
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Playback Select\n\n" +
                    "Browse an organization's validation scans by site, or all at once. Tapping " +
                    "a scan downloads it; several can download at once. Tapping a downloaded " +
                    "scan plays it in place of the camera in every sample. The trash " +
                    "button removes a downloaded scan from the cache, stopping playback first " +
                    "if it is playing.\n\n" +
                    "Listing scans needs a token that can read them; a token scoped to " +
                    "site-assets:read gets HTTP_FORBIDDEN.",
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
        when (val state = manager.state) {
            is BrowseState.Loading -> CircularProgressIndicator(color = Color.White)
            is BrowseState.Error -> {
                Text(state.message, color = Color(0xFFFF6B6B))
                NavButton("Retry") { manager.loadOrganizations() }
            }
            is BrowseState.Organizations -> {
                Title("Select an Organization")
                val filterText = manager.filterText
                val filteredOrgs = remember(state.orgs, filterText) {
                    if (filterText.isBlank()) state.orgs
                    else state.orgs.filter { it.name.contains(filterText, ignoreCase = true) }
                }
                if (state.orgs.isNotEmpty()) {
                    SearchFilterField(
                        value = filterText,
                        onValueChange = { manager.onFilterChange(it) },
                        placeholder = "Filter organizations..."
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredOrgs, key = { it.id }) { org ->
                        NavButton(org.name) { manager.onOrganizationClick(org) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                CacheSettings(library, onShowDownloaded)
            }
            is BrowseState.Sites -> {
                NavButton("← Organizations", Color(0xFF6B6B6B)) { manager.onBackToOrganizations() }
                ModeToggle(allSelected = false, onBySite = {}, onAll = { manager.onAllClick(state.org) })
                Title(state.org.name)
                val filterText = manager.filterText
                val filteredSites = remember(state.sites, filterText) {
                    if (filterText.isBlank()) state.sites
                    else state.sites.filter { it.name.contains(filterText, ignoreCase = true) }
                }
                if (state.sites.isNotEmpty()) {
                    SearchFilterField(
                        value = filterText,
                        onValueChange = { manager.onFilterChange(it) },
                        placeholder = "Filter sites..."
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredSites, key = { it.id }) { site ->
                        NavButton(site.name) { manager.onSiteClick(state.org, site) }
                    }
                }
            }
            is BrowseState.Scans -> {
                if (state.site != null) {
                    NavButton("← Sites", Color(0xFF6B6B6B)) { manager.onBySiteClick(state.org) }
                } else {
                    NavButton("← Organizations", Color(0xFF6B6B6B)) { manager.onBackToOrganizations() }
                }
                ModeToggle(
                    allSelected = state.site == null,
                    onBySite = { manager.onBySiteClick(state.org) },
                    onAll = { if (state.site != null) manager.onAllClick(state.org) },
                )
                Title(state.site?.name ?: "All playbacks in ${state.org.name}")
                if (state.sitesLoaded != null) {
                    Text(
                        "Loaded ${state.sitesLoaded} / ${state.sitesTotal} sites...",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                } else if (state.entries.isEmpty()) {
                    Text("No validation scans", color = Color.Gray)
                }
                val filterText = manager.filterText
                val filteredEntries = remember(state.entries, filterText) {
                    if (filterText.isBlank()) state.entries
                    else state.entries.filter { it.name.contains(filterText, ignoreCase = true) }
                }
                if (state.entries.isNotEmpty()) {
                    SearchFilterField(
                        value = filterText,
                        onValueChange = { manager.onFilterChange(it) },
                        placeholder = "Filter playbacks..."
                    )
                }
                LaunchedEffect(state.entries) {
                    library.seedCached(state.entries.map { it.id })
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { scan ->
                        ScanEntryButton(
                            scan = scan,
                            showSite = state.site == null,
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
    }
}

/**
 * Keeps Playback Select's manager in its back stack entry, so browsing state survives the
 * Downloaded Playbacks screen; the manager closes when the entry leaves the back stack.
 */
private class ManagerHolder(val manager: PlaybackSelectManager) : ViewModel() {
    override fun onCleared() = manager.close()

    companion object {
        fun factory(nsdkSession: NSDKSession) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ManagerHolder(PlaybackSelectManager(nsdkSession)) as T
        }
    }
}

/** A scan button with its download progress as a fill and a delete button at its end. */
@Composable
internal fun ScanEntryButton(
    scan: PlaybackScan,
    showSite: Boolean,
    download: ScanDownload?,
    isActive: Boolean,
    onClick: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    fileInfo: PlaybackFileInfo?,
    loadFileInfo: () -> Unit,
) {
    LaunchedEffect(scan.id) { loadFileInfo() }
    val progress = when (download) {
        is ScanDownload.Downloading -> download.progress.toFloat()
        ScanDownload.Cached, ScanDownload.Opening -> 1f
        else -> 0f
    }
    val status = when {
        isActive -> "Playing"
        download is ScanDownload.Downloading -> "Downloading ${(progress * 100).toInt()}%"
        download is ScanDownload.Cached -> "Downloaded"
        download is ScanDownload.Opening -> "Opening…"
        download is ScanDownload.Deleting -> "Deleting…"
        download is ScanDownload.Incomplete -> "Incomplete"
        download is ScanDownload.Failed -> "Failed: ${download.reason}"
        else -> null
    }
    val duration = (fileInfo?.durationSeconds?.takeIf { it > 0 } ?: scan.durationSeconds)
        ?.roundToInt()?.let { " (${it / 60}:${"%02d".format(it % 60)})" }.orEmpty()
    val title = "${scan.name.ifBlank { scan.id }}$duration"
    val frames = fileInfo?.frameCount?.takeIf { it > 0 }?.let { "$it frames" }
    val subtitle = listOfNotNull(scan.siteName.takeIf { showSite }, frames, status)
        .joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFF2E7D32) else Color(0xFF2D2D44))
            .drawBehind {
                drawRect(Accent.copy(alpha = 0.5f), size = Size(size.width * progress, size.height))
            }
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = 12.dp, end = if (canDelete) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, color = Color(0xFFCCCCCC), fontSize = 12.sp)
            }
            // Spinner until the download reports progress, or while a cached scan opens.
            if (download == ScanDownload.Opening ||
                download is ScanDownload.Downloading && download.progress == 0.0
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete from cache",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/** Opens the downloaded scans, and clears the playback cache when it holds anything. */
@Composable
private fun CacheSettings(library: PlaybackLibrary, onShowDownloaded: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Gray)
        Text(
            "Cache",
            color = Color.Gray,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    NavButton("Downloaded playbacks", onClick = onShowDownloaded)
    var confirmingClear by remember { mutableStateOf(false) }
    if (library.clearing) {
        Text("Clearing…", color = Color.Gray, fontSize = 14.sp)
    } else if (library.hasCachedEntries) {
        NavButton("Clear all cache", Color(0xFF8B2E2E)) {
            if (library.downloadingCount > 0) confirmingClear = true else library.clearAll()
        }
    }
    library.clearError?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp) }
    if (confirmingClear) {
        val count = library.downloadingCount
        val downloads = if (count == 1) "1 playback download" else "$count playback downloads"
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            text = { Text("$downloads will be cancelled. Clear the cache anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    library.clearAll()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModeToggle(allSelected: Boolean, onBySite: () -> Unit, onAll: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleButton("By site", selected = !allSelected, onClick = onBySite)
        ToggleButton("All playbacks", selected = allSelected, onClick = onAll)
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Accent else Color(0xFF4A4A6A)
        )
    ) { Text(label) }
}

@Composable
internal fun Title(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

@Composable
private fun NavButton(text: String, color: Color = Accent, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(8.dp)
    ) { Text(text, color = Color.White) }
}

/** Names the scan playing in place of the camera; [showStop] adds a button that stops playback. */
@Composable
fun PlaybackBanner(library: PlaybackLibrary, showStop: Boolean) {
    val scan = library.activeScan ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE61A1A2E))
            .statusBarsPadding()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶ Playback: ${scan.name.ifBlank { scan.id }}",
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (showStop) {
            IconButton(onClick = library::useLiveCamera) {
                Icon(Icons.Default.Close, contentDescription = "Stop playback", tint = Color.White)
            }
        }
    }
}
