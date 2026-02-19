// Copyright Niantic Spatial.

package com.nianticspatial.nsdk.externalsamples.sites

import android.app.Activity
import android.util.Log
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.nianticspatial.nsdk.LatLng
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.NsdkStatusException
import com.nianticspatial.nsdk.externalsamples.HelpContent
import com.nianticspatial.nsdk.externalsamples.NSDKSessionManager
import com.nianticspatial.nsdk.externalsamples.vps2.VPS2Route
import com.nianticspatial.nsdk.externalsamples.auth.AuthRetryHelper
import com.nianticspatial.nsdk.sites.AssetDeploymentType
import com.nianticspatial.nsdk.sites.AssetInfo
import com.nianticspatial.nsdk.sites.AssetPipelineJobStatus
import com.nianticspatial.nsdk.sites.OrganizationInfo
import com.nianticspatial.nsdk.sites.SiteInfo
import com.nianticspatial.nsdk.sites.SitesError
import com.nianticspatial.nsdk.sites.SitesException
import com.nianticspatial.nsdk.sites.SitesSession
import com.nianticspatial.nsdk.sites.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
object SitesRoute

private const val TAG = "SitesView"
private const val AUTH_TIMEOUT_MS = 30000L
private const val AUTH_POLL_INTERVAL_MS = 1000L

/** A site with its chosen Production VPS asset (for localization). */
private data class SiteWithVpsAsset(val site: SiteInfo, val asset: AssetInfo)

/**
 * Navigation state for the Sites demo.
 * Each state (except Loading and initial Error) supports showing a warning message
 * while still allowing navigation back.
 * Sites in OrganizationView are filtered to those with at least one Production VPS asset.
 */
private sealed class SitesNavState {
    object Loading : SitesNavState()
    data class UserView(
        val user: UserInfo,
        val organizations: List<OrganizationInfo>,
        val warning: String? = null
    ) : SitesNavState()
    data class OrganizationView(
        val org: OrganizationInfo,
        val sitesWithAssets: List<SiteWithVpsAsset>,
        val warning: String? = null
    ) : SitesNavState()
    data class Error(val message: String) : SitesNavState()
}

/**
 * Main Sites View composable that demonstrates Sites Manager functionality
 */
@Composable
fun SitesView(
  nsdkSessionManager: NSDKSessionManager,
  navHostController: NavHostController,
  helpContentState: MutableState<HelpContent?>
) {
    val nsdkSession: NSDKSession = nsdkSessionManager.session
    val coroutineScope = rememberCoroutineScope()
    val sitesSession = remember { nsdkSession.sites.acquire() }
    val retryHelper = remember { AuthRetryHelper(nsdkSession) }

    // Navigation state
    var navState by remember { mutableStateOf<SitesNavState>(SitesNavState.Loading) }

    // Keep track for back navigation
    var currentUser by remember { mutableStateOf<UserInfo?>(null) }
    var currentOrganizations by remember { mutableStateOf<List<OrganizationInfo>>(emptyList()) }

    // Search filter state
    var filterText by remember { mutableStateOf("") }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }

    // Set Help contents
    DisposableEffect(Unit) {
        helpContentState.value = {
            Text(
                text = "Sites Sample Help\n\n" +
                    "This sample demonstrates the Sites Manager API for browsing organizations and sites.\n\n" +
                    "TO USE:\n" +
                    "1. Select an organization from your user's organizations\n" +
                    "2. Select a site (only sites with a Production VPS asset are shown)\n" +
                    "3. VPS2 localization starts immediately for the selected site\n\n" +
                    "You can filter items by name using the search field. " ,
                color = Color.White
            )
        }
        onDispose { helpContentState.value = null }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            sitesSession.close()
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        loadInitialData(
            retryHelper = retryHelper,
            sitesSession = sitesSession,
            onStateChange = { navState = it },
            onUserLoaded = { user ->
                currentUser = user
            },
            onOrganizationsLoaded = { orgs ->
                currentOrganizations = orgs
            }
        )
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
        // Info display area (fixed height)
        InfoBox(navState = navState)

        // Button area (fills remaining space)
        when (val state = navState) {
            is SitesNavState.Loading -> {
                LoadingView()
            }
            is SitesNavState.Error -> {
                ErrorView(message = state.message)
            }
            is SitesNavState.UserView -> {
                OrganizationButtons(
                    organizations = state.organizations,
                    warning = state.warning,
                    filterText = filterText,
                    onFilterChange = { filterText = it },
                    onOrganizationClick = { org ->
                        filterText = ""
                        currentLocation = nsdkSessionManager.arManager.lastLocation?.let { location ->
                            LatLng(location.latitude, location.longitude)
                        }
                        coroutineScope.launch {
                            loadSitesForOrganization(
                                retryHelper = retryHelper,
                                sitesSession = sitesSession,
                                org = org,
                                user = currentUser,
                                organizations = currentOrganizations,
                                onStateChange = { navState = it }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            is SitesNavState.OrganizationView -> {
                SiteButtons(
                    sitesWithAssets = state.sitesWithAssets,
                    warning = state.warning,
                    currentLocation = currentLocation,
                    filterText = filterText,
                    onFilterChange = { filterText = it },
                    onSiteClick = { anchorPayload ->
                        filterText = ""
                        navHostController.navigate(VPS2Route(anchorPayload))
                    },
                    onBackClick = {
                        filterText = ""
                        currentUser?.let { user ->
                            navState = SitesNavState.UserView(user, currentOrganizations)
                        }
                    },
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
            },
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.verticalScroll(scrollState)
        )
    }
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
        user.organizationId?.let { appendLine("Organization ID: $it") }
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

// ============================================================================
// Data Loading Functions
// ============================================================================

private suspend fun loadInitialData(
  retryHelper: AuthRetryHelper,
  sitesSession: SitesSession,
  onStateChange: (SitesNavState) -> Unit,
  onUserLoaded: (UserInfo) -> Unit,
  onOrganizationsLoaded: (List<OrganizationInfo>) -> Unit
) {
    try {
        onStateChange(SitesNavState.Loading)

        // Request user info with retry
        val userResult = retryHelper.withRetry {
            sitesSession.requestSelfUserInfo()
        }

        val user = userResult.user
        if (user == null) {
            onStateChange(SitesNavState.Error("Failed to retrieve user information"))
            return
        }

        onUserLoaded(user)

        // Request organizations for user with retry
        val orgsResult = retryHelper.withRetry {
            sitesSession.requestOrganizationsForUser(user.id)
        }
        val organizations = orgsResult.organizations

        onOrganizationsLoaded(organizations)

        // Show user view with warning if no organizations (still allows seeing user info)
        val warning = if (organizations.isEmpty()) "No organizations found" else null
        onStateChange(SitesNavState.UserView(user, organizations, warning))

    } catch (e: CancellationException) {
        throw e
    } catch (e: SitesException) {
        Log.e(TAG, "Sites error: ${e.error}", e)
        onStateChange(SitesNavState.Error("Error: ${e.error}"))
    } catch (e: Exception) {
        Log.e(TAG, "Error loading data", e)
        onStateChange(SitesNavState.Error("Error: ${e.message}"))
    }
}

private suspend fun loadSitesForOrganization(
  retryHelper: AuthRetryHelper,
  sitesSession: SitesSession,
  org: OrganizationInfo,
  user: UserInfo?,
  organizations: List<OrganizationInfo>,
  onStateChange: (SitesNavState) -> Unit
) {
    try {
        onStateChange(SitesNavState.Loading)

        val result = retryHelper.withRetry {
            sitesSession.requestSitesForOrganization(org.id)
        }
        val allSites = result.sites

        if (allSites.isEmpty()) {
            val warning = "No sites found for ${org.name}"
            onStateChange(SitesNavState.OrganizationView(org, emptyList(), warning))
            return
        }

        // Fetch assets for all sites in parallel; keep only sites that have a Production VPS asset.
        val sitesWithAssets = coroutineScope {
            allSites.map { site ->
                async {
                    try {
                        val assetsResult = retryHelper.withRetry {
                            sitesSession.requestAssetsForSite(site.id)
                        }
                        val vpsAsset = assetsResult.assets.firstOrNull { asset ->
                            val vps = asset.vpsData
                            vps != null &&
                                vps.anchorPayload.isNotBlank() &&
                                asset.deployment == AssetDeploymentType.PRODUCTION
                        }
                        if (vpsAsset != null) SiteWithVpsAsset(site, vpsAsset) else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load assets for site ${site.name}: ${e.message}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }

        val warning = when {
            sitesWithAssets.isEmpty() -> "No sites with Production VPS asset found for ${org.name}"
            else -> null
        }
        onStateChange(SitesNavState.OrganizationView(org, sitesWithAssets, warning))

    } catch (e: CancellationException) {
        throw e
    } catch (e: SitesException) {
        Log.e(TAG, "Sites error: ${e.error}", e)
        if (user != null) {
            onStateChange(SitesNavState.UserView(user, organizations, "Error loading sites: ${e.error}"))
        } else {
            onStateChange(SitesNavState.Error("Error loading sites: ${e.error}"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading sites", e)
        if (user != null) {
            onStateChange(SitesNavState.UserView(user, organizations, "Error: ${e.message}"))
        } else {
            onStateChange(SitesNavState.Error("Error: ${e.message}"))
        }
    }
}

