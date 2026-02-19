// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples.auth

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.nianticspatial.nsdk.NSDKSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object AuthRoute

@Composable
fun AuthView(
    activity: Activity,
    nsdkSession: NSDKSession,
    helpContentState: MutableState<(@Composable () -> Unit)?>,
    modifier: Modifier = Modifier
) {
    // Track login status and auth flow type
    var isLoggedIn by remember { mutableStateOf(UserSessionManager.isSessionInProgress) }
    var statusMessage by remember { 
        mutableStateOf(
            if (UserSessionManager.isSessionInProgress) "Logged In" else "Not Logged In"
        )
    }
    var pendingAuthFlow by remember { mutableStateOf<String?>(null) }
    var activeAuthFlow by remember { mutableStateOf<String?>(null) }
    var detectedFlowType by remember { mutableStateOf<String?>(null) }
    
    // Coroutine scope for handling async operations
    val scope = rememberCoroutineScope()
    
    // Function to detect flow type by checking if access token has userId
    fun detectFlowType(): String? {
        // Use native NSDK function to get access token info
        val accessAuthInfo = nsdkSession.getAccessAuthInfo()
        return if (accessAuthInfo != null && accessAuthInfo.userId.isNotEmpty()) {
            // Developer tokens have userId (user-scoped)
            "DEVELOPER"
        } else if (nsdkSession.getAccessAuthInfo() != null) {
            // Enterprise tokens don't have userId (service-scoped)
            "ENTERPRISE"
        } else {
            null
        }
    }

    // Helper function to handle logout
    fun onLogoutPressed() {
        // Stop user session manager
        UserSessionManager.stopUserSession(activity.applicationContext)
        
        // Stop auth access manager (enterprise flow)
        AuthAccessManager.stopAuthAccess()
        
        // Clear NSDK session tokens
        nsdkSession.setAccessToken("")
        nsdkSession.setRefreshToken("")
        
        // Update UI state
        isLoggedIn = false
        statusMessage = "Not Logged In"
        pendingAuthFlow = null
        activeAuthFlow = null
        detectedFlowType = null
    }

    // Helper function to handle developer login
    fun onDeveloperLoginPressed() {
        val loginManager = LoginManager(activity = activity)
        pendingAuthFlow = "DEVELOPER"
        loginManager.startAuth()
        statusMessage = "Developer login in progress..."
    }

    // Helper function to handle enterprise login
    fun onEnterpriseLoginPressed() {
        val loginManager = LoginManager(activity = activity)
        pendingAuthFlow = "ENTERPRISE"
        loginManager.startAuth()
        statusMessage = "Enterprise login in progress..."
    }
    
    /**
     * Developer flow: Exchange user session refresh token for a build refresh token (user-scoped)
     * Goes to IDENTITY endpoint with grant type "exchange_build_refresh_token"
     */
    fun handleDeveloperFlow(userSessionRefreshToken: String?) {
        if (userSessionRefreshToken.isNullOrEmpty()) {
            Log.e("AuthView", "Developer flow: No user session refresh token available")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val result = RequestRuntimeRefreshToken.execute(userSessionRefreshToken)
                Log.i("AuthView", "Developer flow: Successfully obtained build refresh token")
                
                // Switch to Main dispatcher for native NSDK call and UI updates
                withContext(Dispatchers.Main) {
                    // Set the build refresh token on the NSDK session
                    nsdkSession.setRefreshToken(result.refreshToken)
                    Log.i("AuthView", "Developer flow: Set build refresh token on NSDK session")
                    
                    // Update UI state
                    isLoggedIn = true
                    statusMessage = "Logged In"
                }
            } catch (e: Exception) {
                Log.e("AuthView", "Developer flow: Failed to get build refresh token", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Developer login failed"
                }
            }
        }
    }

    /**
     * Enterprise flow: Exchange user session access token for NS API access token (service-scoped)
     * Goes to sample backend ACCESS endpoint
     */
    fun handleEnterpriseFlow(userSessionAccessToken: String?) {
        if (userSessionAccessToken.isNullOrEmpty()) {
            Log.e("AuthView", "Enterprise flow: No user session access token available")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // Start the AuthAccessManager which will continuously refresh the access token
                AuthAccessManager.startAuthAccess(userSessionAccessToken)
                Log.i("AuthView", "Enterprise flow: Started auth access manager with user session token")
                
                // Update UI state
                withContext(Dispatchers.Main) {
                    isLoggedIn = true
                    statusMessage = "Logged In"
                }
            } catch (e: Exception) {
                Log.e("AuthView", "Enterprise flow: Failed to start auth access", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Enterprise login failed"
                }
            }
        }
    }

    // Set up help content
    LaunchedEffect(Unit) {
        helpContentState.value = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Authentication Management",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "This screen allows you to manage your authentication session.\n\n" +
                            "• Developer Login: Uses the standard developer authentication flow\n" +
                            "• Sample Enterprise Login: Uses sample enterprise authentication endpoints\n" +
                            "• Logout: Clears your current session tokens",
                    fontSize = 14.sp,
                    color = Color.White,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
    
    // Subscribe to UserSessionManager refresh token updates to detect login completion
    LaunchedEffect(Unit) {
        // On startup, detect and restore the flow type
        val flowType = detectFlowType()
        if (flowType != null) {
            detectedFlowType = flowType
            activeAuthFlow = flowType
            Log.i("AuthView", "Detected flow type on startup: $flowType")
            
            // Restart the appropriate flow
            if (flowType == "ENTERPRISE") {
                val userSessionAccessToken = UserSessionManager.accessToken
                if (userSessionAccessToken != null) {
                    handleEnterpriseFlow(userSessionAccessToken)
                }
            } else if (flowType == "DEVELOPER") {
                val userSessionRefreshToken = UserSessionManager.refreshToken
                if (userSessionRefreshToken != null) {
                    handleDeveloperFlow(userSessionRefreshToken)
                }
            }
        }
        
        // Listen for new logins
        UserSessionManager.refreshTokenAvailable.collect { userSessionRefreshToken ->
            Log.i("AuthView", "Received user session refresh token update")
            
            // Check which flow we need to handle
            when (pendingAuthFlow) {
                "DEVELOPER" -> {
                    handleDeveloperFlow(userSessionRefreshToken)
                    activeAuthFlow = "DEVELOPER"
                    detectedFlowType = "DEVELOPER"
                    pendingAuthFlow = null
                }
                "ENTERPRISE" -> {
                    // For enterprise flow, we need the access token
                    val userSessionAccessToken = UserSessionManager.accessToken
                    handleEnterpriseFlow(userSessionAccessToken)
                    activeAuthFlow = "ENTERPRISE"
                    detectedFlowType = "ENTERPRISE"
                    pendingAuthFlow = null
                }
            }
        }
    }
    
    // Subscribe to AuthAccessManager access token updates for enterprise flow
    // This runs continuously to handle token refreshes
    LaunchedEffect(Unit) {
        // Apply current token immediately if one exists
        val currentToken = AuthAccessManager.accessToken
        if (!currentToken.isNullOrEmpty()) {
            nsdkSession.setAccessToken(currentToken)
            Log.i("AuthView", "Set initial enterprise access token on NSDK session")
        }

        // Subscribe to future token updates from AuthAccessManager
        AuthAccessManager.accessTokenUpdated.collectLatest { accessToken ->
            nsdkSession.setAccessToken(accessToken)
            Log.i("AuthView", "Enterprise flow: Updated NSDK session with new access token")
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // Status Text
            Text(
                text = statusMessage,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (isLoggedIn) Color(0xFF4CAF50) else Color(0xFFFF9800),
                textAlign = TextAlign.Center
            )
            
            // Flow Type Indicator
            if (isLoggedIn && detectedFlowType != null) {
                Text(
                    text = when (detectedFlowType) {
                        "DEVELOPER" -> "Developer Flow"
                        "ENTERPRISE" -> "Sample Enterprise Flow"
                        else -> ""
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoggedIn) {
                // Show Logout Button
                Button(
                    onClick = { onLogoutPressed() },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text(
                        text = "Logout",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else {
                // Show Login Buttons
                Button(
                    onClick = { onDeveloperLoginPressed() },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text(
                        text = "Developer Login",
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                Button(
                    onClick = { onEnterpriseLoginPressed() },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF673AB7)
                    )
                ) {
                    Text(
                        text = "Sample Enterprise Login",
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }

            // Additional Info
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isLoggedIn) {
                    "Session tokens are stored securely"
                } else {
                    "Choose a login method to authenticate"
                },
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}
