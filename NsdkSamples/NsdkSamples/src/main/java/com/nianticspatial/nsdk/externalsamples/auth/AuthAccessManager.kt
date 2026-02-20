package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Maintains auth access to the NS API, refreshing it periodically.
 * Access is requested using the sample user session access token.
 */
object AuthAccessManager {
    private const val TAG = "AuthAccessManager"
    private const val UPDATE_INTERVAL_MS = 10_000L // 10 seconds
    private const val MIN_UNEXPIRED_TIME_LEFT_SECONDS = 60L // 60 seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var userSessionAccessToken: String? = null
    private var authAccessToken: String? = null

    private val _accessTokenUpdated = MutableSharedFlow<String>(replay = 1)
    val accessTokenUpdated: SharedFlow<String> = _accessTokenUpdated.asSharedFlow()

    /**
     * Gets the current access token.
     */
    val accessToken: String?
        get() = authAccessToken

    /**
     * Starts the auth access refresh loop with the given user session access token.
     *
     * @param userSessionAccessToken The user session access token used to authenticate requests.
     */
    fun startAuthAccess(userSessionAccessToken: String) {
        // Don't interrupt the current auth access loop if we already have a good user session access token
        if (!AuthUtils.isTokenEmptyOrExpiring(
                this.userSessionAccessToken,
                minUnexpiredTimeLeftSeconds = 0
            )
        ) {
            return
        }

        // Don't start the auth access loop if we don't have an access token
        if (userSessionAccessToken.isEmpty()) {
            Log.w(TAG, "Cannot start auth access: user session access token is empty")
            return
        }

        refreshJob?.cancel()
        this.userSessionAccessToken = userSessionAccessToken

        refreshJob = scope.launch {
            startAuthAccessAsync()
        }
    }

    /**
     * Stops the auth access refresh loop.
     */
    fun stopAuthAccess() {
        refreshJob?.cancel()
        refreshJob = null
        userSessionAccessToken = null
        authAccessToken = null
        
        // Clear the SharedFlow replay buffer to prevent old tokens from being replayed
        // We do this by resetting the MutableSharedFlow
        _accessTokenUpdated.resetReplayCache()
    }

    /**
     * Sets the user session access token.
     */
    fun setUserSessionAccessToken(token: String?) {
        userSessionAccessToken = token
    }

    /**
     * Checks if the current access token is expired or about to expire.
     */
    private fun accessIsExpiredOrAboutToExpire(): Boolean {
        return AuthUtils.isTokenEmptyOrExpiring(
            authAccessToken,
            minUnexpiredTimeLeftSeconds = MIN_UNEXPIRED_TIME_LEFT_SECONDS
        )
    }

    /**
     * The main async loop that periodically refreshes the access token.
     */
    private suspend fun startAuthAccessAsync() {
        // Loop that runs forever during runtime, periodically refreshing the access token
        // (if we have a valid user session access token)
        try {
            while (true) {

                if (accessIsExpiredOrAboutToExpire()) {
                    val token = userSessionAccessToken
                    if (token.isNullOrEmpty()) {
                        Log.e(TAG, "Failed to request NS access token - no user session access token available")
                        break
                    }

                    try {
                        val accessToken = RequestAuthAccess.execute(userSessionAccessToken = token)
                        if (!accessToken.isNullOrEmpty()) {
                            authAccessToken = accessToken
                            _accessTokenUpdated.emit(accessToken)
                        } else {
                            Log.e(TAG, "Failed to request NS access token - empty response")
                            break
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            throw e
                        }
                        Log.e(TAG, "Failed to request NS access token", e)
                        break
                    }
                }

                delay(UPDATE_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // Exiting the application, so we don't need to do anything here.
        } catch (e: Exception) {
            Log.e(TAG, "Error in auth access refresh loop", e)
        }
    }
}
