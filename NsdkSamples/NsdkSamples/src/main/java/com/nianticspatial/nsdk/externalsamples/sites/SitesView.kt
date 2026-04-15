// Copyright 2026 Niantic Spatial.

package com.nianticspatial.nsdk.externalsamples.sites

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import androidx.navigation.NavHostController
import com.nianticspatial.nsdk.LatLng
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.sites.AssetDeploymentType
import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.AssetPipelineJobStatus
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SiteInfo
import com.nianticspatial.nsdk.sites.UserInfo
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Default coordinates for the "Near Me" search (Palo Alto - update for your location)
private const val DEFAULT_LAT = 0.0
private const val DEFAULT_LNG = 0.0
private const val DEFAULT_RADIUS_METERS = 1000.0

@Serializable
object SitesRoute


/**
 * Main Sites View composable that demonstrates Sites Manager functionality.
 */
@Composable
fun SitesView(
  nsdkSessionManager: NSDKSessionManager,
  navHostController: NavHostController,
  helpContentState: MutableState<HelpContent?>
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val sitesManager = remember { SitesManager(nsdkSessionManager.session) }

    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(sitesManager)
        onDispose { lifecycleOwner.lifecycle.removeObserver(sitesManager) }
    }

    val navState by sitesManager::navState
    val filterText by sitesManager::filterText

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Sites Sample Help\n\n" +
                    "This sample demonstrates the Sites Manager API for browsing organizations and sites.\n\n" +
                    "TO USE:\n" +
                    "Choose 'Display Sites Near Me' to find VPS sites near a coordinate, or\n" +
                    "'Display Sites From Orgs' to browse your organization's sites.\n" +
                    "Tapping a site starts VPS2 localization immediately.\n\n" +
                    "You can filter items by name using the search field. ",
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Info display area — hidden on mode selection screen
        if (navState !is SitesNavState.ModeSelection) {
            InfoBox(navState = navState)
        }

        // Button area (fills remaining space)
        when (val state = navState) {
            is SitesNavState.ModeSelection -> {
                ModeSelectionView(
                    onFromOrgsClick = { sitesManager.onStartFromOrgsFlow() },
                    onNearMeClick = { sitesManager.onStartNearMeFlow() }
                )
            }
            is SitesNavState.Loading -> {
                LoadingView()
            }
            is SitesNavState.Error -> {
                ErrorView(message = state.message)
                Spacer(modifier = Modifier.height(8.dp))
                BackButton(label = "← Back to Mode Selection", onClick = {
                    sitesManager.onBackToModeSelection()
                })
            }
            is SitesNavState.UserView -> {
                OrganizationButtons(
                    organizations = state.organizations,
                    warning = state.warning,
                    filterText = filterText,
                    onFilterChange = { sitesManager.onFilterChange(it) },
                    onOrganizationClick = { org ->
                        val currentLocation = nsdkSessionManager.dataSource?.latestGpsSample()?.let {
                            LatLng(it.latitude, it.longitude)
                        }
                        sitesManager.onOrganizationClick(org, currentLocation)
                    },
                    onBackClick = { sitesManager.onBackToModeSelection() },
                    modifier = Modifier.weight(1f)
                )
            }
            is SitesNavState.OrganizationView -> {
                SiteButtons(
                    sitesWithAssets = state.sitesWithAssets,
                    warning = state.warning,
                    currentLocation = sitesManager.currentLocation,
                    filterText = filterText,
                    onFilterChange = { sitesManager.onFilterChange(it) },
                    onSiteClick = { anchorPayload ->
                        navHostController.navigate(VPS2Route(anchorPayload))
                    },
                    onBackClick = { sitesManager.onBackToUserView() },
                    modifier = Modifier.weight(1f)
                )
            }
            is SitesNavState.NearMeResults -> {
                NearMePanel(
                    sitesWithAssets = state.sitesWithAssets,
                    warning = state.warning,
                    filterText = filterText,
                    onFilterChange = { sitesManager.onFilterChange(it) },
                    onSearch = { lat, lng, radius ->
                        sitesManager.onFilterChange("")
                        val currentLocation = nsdkSessionManager.dataSource?.latestGpsSample()?.let {
                            LatLng(it.latitude, it.longitude)
                        }
                        sitesManager.onNearMeSearch(lat, lng, radius, currentLocation)
                    },
                    onSiteClick = { anchorPayload ->
                        navHostController.navigate(VPS2Route(anchorPayload))
                    },
                    onBackClick = { sitesManager.onBackToModeSelection() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ============================================================================
// Info Box
// ============================================================================

@Composable
private fun InfoBox(navState: SitesNavState) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 200.dp)
            .background(Color(0xFF2D2D44), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = when (navState) {
                is SitesNavState.Loading -> "⏳ Loading..."
                is SitesNavState.Error -> "❌ ${navState.message}"
                is SitesNavState.UserView -> formatUserInfo(navState.user)
                is SitesNavState.OrganizationView -> formatOrganizationInfo(navState.org)
                is SitesNavState.NearMeResults -> "📍 Sites Near Me\nSearch for VPS sites near a GPS coordinate."
                is SitesNavState.ModeSelection -> ""
            },
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.verticalScroll(scrollState)
        )
    }
}

// ============================================================================
// Mode Selection
// ============================================================================

@Composable
private fun ModeSelectionView(
    onFromOrgsClick: () -> Unit,
    onNearMeClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sites Manager",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "How would you like to find VPS sites?",
            color = Color(0xFFAAAAAA),
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        StyledButton(
            text = "🌍  Display Sites Near Me",
            onClick = onNearMeClick,
            color = Color(0xFF2E7D32)
        )
        StyledButton(
            text = "🏢  Display Sites From Orgs",
            onClick = onFromOrgsClick,
            color = Color(0xFF4A90D9)
        )
    }
}

// ============================================================================
// Near Me Panel
// ============================================================================

@Composable
private fun NearMePanel(
    sitesWithAssets: List<SiteWithVpsAsset>,
    warning: String?,
    filterText: String,
    onFilterChange: (String) -> Unit,
    onSearch: (lat: Double, lng: Double, radius: Double) -> Unit,
    onSiteClick: (anchorPayload: String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var latText by remember { mutableStateOf(DEFAULT_LAT.toString()) }
    var lngText by remember { mutableStateOf(DEFAULT_LNG.toString()) }
    var radiusText by remember { mutableStateOf(DEFAULT_RADIUS_METERS.toString()) }

    val filteredSites = remember(sitesWithAssets, filterText) {
        if (filterText.isBlank()) sitesWithAssets
        else sitesWithAssets.filter { it.site.name.contains(filterText, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BackButton(label = "← Back to Mode Selection", onClick = onBackClick)

        CoordinateInputField(label = "Latitude", value = latText, onValueChange = { latText = it })
        CoordinateInputField(label = "Longitude", value = lngText, onValueChange = { lngText = it })
        CoordinateInputField(label = "Radius (meters)", value = radiusText, onValueChange = { radiusText = it })

        StyledButton(
            text = "📍 Use My Location",
            onClick = {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    LocationServices.getFusedLocationProviderClient(context)
                        .lastLocation
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                latText = String.format("%.6f", location.latitude)
                                lngText = String.format("%.6f", location.longitude)
                            }
                        }
                }
            },
            color = Color(0xFF1565C0)
        )

        StyledButton(
            text = "Search Nearby Sites",
            onClick = {
                val lat = latText.toDoubleOrNull() ?: DEFAULT_LAT
                val lng = lngText.toDoubleOrNull() ?: DEFAULT_LNG
                val radius = radiusText.toDoubleOrNull() ?: DEFAULT_RADIUS_METERS
                onSearch(lat, lng, radius)
            },
            color = Color(0xFF2E7D32)
        )

        warning?.let { msg -> WarningText(msg) }

        if (sitesWithAssets.isNotEmpty()) {
            Text(
                text = "VPS Sites Near You (${sitesWithAssets.size}):",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            SearchFilterField(
                value = filterText,
                onValueChange = onFilterChange,
                placeholder = "Filter sites..."
            )
            filteredSites.forEach { item ->
                val payload = item.asset.vpsData?.anchorPayload
                if (!payload.isNullOrBlank()) {
                    StyledButton(
                        text = item.site.name,
                        onClick = { onSiteClick(payload) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CoordinateInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label, color = Color.Gray) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF2D2D44),
            unfocusedContainerColor = Color(0xFF2D2D44),
            focusedBorderColor = Color(0xFF4A90D9),
            unfocusedBorderColor = Color(0xFF4A4A6A),
            focusedLabelColor = Color(0xFF4A90D9),
            unfocusedLabelColor = Color.Gray
        ),
        shape = RoundedCornerShape(8.dp)
    )
}

private fun formatUserInfo(user: UserInfo): String {
    return buildString {
        appendLine("👤 USER INFORMATION")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Name: ${user.firstName} ${user.lastName}")
        appendLine("ID: ${user.id}")
        appendLine("Email: ${user.email}")
        appendLine("Status: ${user.status}")
        appendLine("Created: ${formatTimestamp(user.createdTimestamp)}")
    }
}

private fun formatOrganizationInfo(org: OrganizationInfo): String {
    return buildString {
        appendLine("🏢 ORGANIZATION INFORMATION")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Name: ${org.name}")
        appendLine("ID: ${org.id}")
        appendLine("Status: ${org.status}")
        appendLine("Created: ${formatTimestamp(org.createdTimestamp)}")
    }
}

private fun formatSiteInfo(site: SiteInfo): String {
    return buildString {
        appendLine("📍 SITE INFORMATION")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Name: ${site.name}")
        appendLine("ID: ${site.id}")
        appendLine("Status: ${site.status}")
        appendLine("Organization ID: ${site.organizationId}")
        if (site.hasLocation) {
            appendLine("Location: (${String.format("%.6f", site.latitude)}, ${String.format("%.6f", site.longitude)})")
        } else {
            appendLine("Location: Not available")
        }
        site.parentSiteId?.let { appendLine("Parent Site ID: $it") }
    }
}

private fun formatAssetInfo(asset: AssetInfo): String {
    return buildString {
        appendLine("📦 ASSET INFORMATION")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Name: ${asset.name}")
        appendLine("ID: ${asset.id}")
        appendLine("Type: ${asset.assetType}")
        appendLine("Status: ${asset.assetStatus}")
        appendLine("Site ID: ${asset.siteId}")
        asset.description?.let { appendLine("Description: $it") }
        if (asset.deployment != AssetDeploymentType.UNSPECIFIED) {
            appendLine("Deployment: ${asset.deployment}")
        }
        asset.pipelineJobId?.let { appendLine("Pipeline Job ID: $it") }
        if (asset.pipelineJobStatus != AssetPipelineJobStatus.UNSPECIFIED) {
            appendLine("Pipeline Status: ${asset.pipelineJobStatus}")
        }
        // Typed asset data
        asset.meshData?.let { mesh ->
            appendLine("Mesh Root Node ID: ${mesh.rootNodeId}")
            appendLine("Mesh Coverage: ${mesh.meshCoverage} m²")
            if (mesh.nodeIds.isNotEmpty()) {
                appendLine("Node IDs (${mesh.nodeIds.size}): ${mesh.nodeIds.joinToString(", ")}")
            }
        }
        asset.splatData?.let { splat ->
            appendLine("Splat Root Node ID: ${splat.rootNodeId}")
        }
        asset.vpsData?.let { vps ->
            appendLine("VPS Anchor: ${vps.anchorPayload}")
        }
        if (asset.sourceScanIds.isNotEmpty()) {
            appendLine("Source Scan IDs (${asset.sourceScanIds.size}): ${asset.sourceScanIds.joinToString(", ")}")
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp * 1000)
    val formatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return formatter.format(date)
}

private fun haversineDistanceMeters(start: LatLng, end: LatLng): Double {
    val earthRadius = 6_378_137.0
    val lat1 = Math.toRadians(start.lat)
    val lat2 = Math.toRadians(end.lat)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(end.lng - start.lng)
    val a = sin(deltaLat / 2).pow(2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}

// ============================================================================
// Button Components
// ============================================================================

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun ErrorView(message: String) {
    Text(
        text = message,
        color = Color(0xFFFF6B6B),
        fontSize = 14.sp,
        modifier = Modifier.padding(8.dp)
    )
}

@Composable
private fun WarningText(message: String) {
    Text(
        text = "⚠️ $message",
        color = Color(0xFFFFD93D),
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3D3D00).copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(8.dp)
    )
}

@Composable
private fun OrganizationButtons(
    organizations: List<OrganizationInfo>,
    warning: String?,
    filterText: String,
    onFilterChange: (String) -> Unit,
    onOrganizationClick: (OrganizationInfo) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredOrganizations = remember(organizations, filterText) {
        if (filterText.isBlank()) organizations
        else organizations.filter { it.name.contains(filterText, ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BackButton(label = "← Back to Mode Selection", onClick = onBackClick)

        warning?.let { msg ->
            WarningText(msg)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (organizations.isNotEmpty()) {
            Text(
                text = "Select an Organization:",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            SearchFilterField(
                value = filterText,
                onValueChange = onFilterChange,
                placeholder = "Filter organizations..."
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredOrganizations) { org ->
                    StyledButton(
                        text = org.name,
                        onClick = { onOrganizationClick(org) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SiteButtons(
    sitesWithAssets: List<SiteWithVpsAsset>,
    warning: String?,
    currentLocation: LatLng?,
    filterText: String,
    onFilterChange: (String) -> Unit,
    onSiteClick: (anchorPayload: String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredSitesWithAssets = remember(sitesWithAssets, filterText, currentLocation) {
        val matching =
            if (filterText.isBlank()) sitesWithAssets
            else sitesWithAssets.filter { it.site.name.contains(filterText, ignoreCase = true) }

        if (currentLocation == null) {
            matching
        } else {
            matching.sortedWith(
                compareBy<SiteWithVpsAsset> { (site) ->
                    if (site.hasLocation) {
                        haversineDistanceMeters(
                            currentLocation,
                            LatLng(site.latitude, site.longitude)
                        )
                    } else {
                        Double.POSITIVE_INFINITY
                    }
                }.thenBy { it.site.name }
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BackButton(label = "← Back to Organizations", onClick = onBackClick)

        warning?.let { msg ->
            WarningText(msg)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (sitesWithAssets.isNotEmpty()) {
            Text(
                text = "Select a Site (Localize):",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            SearchFilterField(
                value = filterText,
                onValueChange = onFilterChange,
                placeholder = "Filter sites..."
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSitesWithAssets, key = { it.site.id }) { item ->
                    val payload = item.asset.vpsData?.anchorPayload
                    if (!payload.isNullOrBlank()) {
                        StyledButton(
                            text = item.site.name,
                            onClick = { onSiteClick(payload) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                color = Color.Gray
            )
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF2D2D44),
            unfocusedContainerColor = Color(0xFF2D2D44),
            focusedBorderColor = Color(0xFF4A90D9),
            unfocusedBorderColor = Color(0xFF4A4A6A)
        ),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun StyledButton(
    text: String,
    onClick: () -> Unit,
    color: Color = Color(0xFF4A90D9)
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    StyledButton(
        text = label,
        onClick = onClick,
        color = Color(0xFF6B6B6B)
    )
}


