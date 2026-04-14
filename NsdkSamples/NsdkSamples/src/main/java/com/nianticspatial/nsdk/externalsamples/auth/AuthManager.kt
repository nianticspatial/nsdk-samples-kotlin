// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.nianticspatial.nsdk.NSDKSession
import com.nianticspatial.nsdk.externalsamples.FeatureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns all auth business logic. Observes [NSSampleSessionManager] for token availability,
 * forwards refresh tokens to [NSDKSession], and exposes UI state as Compose state.
 *
 * [AuthView] reads this state and delegates user actions back here, so the view
 * contains no token logic and requires no Android [Context] or [Activity] of its own.
 */
class AuthManager(
    private val nsdkSession: NSDKSession,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : FeatureManager() {

    private val hasBuiltInToken = AuthUtils.isValidJwt(AuthConstants.accessToken)

    var isLoggedIn by mutableStateOf(hasBuiltInToken || NSSampleSessionManager.isSessionInProgress)
        private set

    var statusMessage by mutableStateOf(
        when {
            hasBuiltInToken -> "Logged In (Access Token)"
            NSSampleSessionManager.isSessionInProgress -> "Logged In"
            else -> "Not Logged In"
        }
    )
        private set

    /** False when using a built-in access token — login/logout buttons should be hidden. */
    val showLoginControls = !hasBuiltInToken

    var emailMessage by mutableStateOf(
        if (NSSampleSessionManager.isSessionInProgress)
            AuthUtils.jwtEmail(NSSampleSessionManager.sessionToken) ?: ""
        else ""
    )
        private set

    init {
        setupSessionAccess()
    }

    /**
     * Collects the sample token flow and forwards refresh tokens to [NSDKSession].
     * Called once from [init]; runs for the lifetime of this manager.
     */
    private fun setupSessionAccess() {
        if (hasBuiltInToken) return
        coroutineScope.launch {
            NSSampleSessionManager.setupSessionAccess(
                session = nsdkSession,
                coroutineScope = coroutineScope,
                onSuccess = {
                    val email = AuthUtils.jwtEmail(NSSampleSessionManager.sessionToken)
                    isLoggedIn = true
                    statusMessage = "Logged In"
                    emailMessage = email ?: ""
                },
                onFailure = {
                    statusMessage = "Login failed"
                }
            )
        }
    }

    /**
     * Starts the browser-based OAuth login flow. Requires an [Activity] because
     * Chrome Custom Tabs must be launched from one.
     */
    fun login(activity: Activity) {
        LoginManager(activity).startAuth()
        statusMessage = "Login in progress..."
    }

    /**
     * Clears the session and resets NSDK tokens.
     */
    fun logout() {
        NSSampleSessionManager.stopNSSampleSession()
        nsdkSession.setAccessToken("")
        isLoggedIn = false
        statusMessage = "Not Logged In"
        emailMessage = ""
    }

    override fun onDestroy(owner: LifecycleOwner) {
        coroutineScope.cancel()
    }
}
