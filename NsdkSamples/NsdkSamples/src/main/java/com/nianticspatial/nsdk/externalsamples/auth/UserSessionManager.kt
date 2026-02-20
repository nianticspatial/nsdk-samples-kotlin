package com.nianticspatial.nsdk.externalsamples.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import androidx.core.content.edit

/**
 * Maintains a user session with the sample backend and refreshes the access
 * token while the app is running. Refresh and access tokens are persisted in
 * SharedPreferences so the session can survive app restarts.
 */
object UserSessionManager {
    private const val TAG = "UserSessionManager"
    private const val UPDATE_INTERVAL_MS = 10_000L // 10 seconds
    private const val MIN_UNEXPIRED_TIME_LEFT_SECONDS = 60L // 60 seconds

    private const val PREFS_NAME = "UserSessionPrefs"
    private const val REFRESH_TOKEN_KEY = "UserSessionRefreshToken"
    private const val ACCESS_TOKEN_KEY = "UserSessionAccessToken"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var userSessionRefreshToken: String? = null
    private var userSessionAccessToken: String? = null

    private val _refreshTokenAvailable = MutableSharedFlow<String>(replay = 1)
    /**
     * Flow that emits when a valid user session refresh token becomes available.
     * Replays the last value if one exists, so new subscribers get the current token immediately.
     */
    val refreshTokenAvailable: SharedFlow<String> = _refreshTokenAvailable.asSharedFlow()

    /**
     * Gets the current refresh token.
     */
    val refreshToken: String?
        get() = userSessionRefreshToken

    /**
     * Gets the current access token.
     */
    val accessToken: String?
        get() = userSessionAccessToken

    /**
     * Checks if a session is in progress.
     */
    val isSessionInProgress: Boolean
        get() = !AuthUtils.isTokenEmptyOrExpiring(
            userSessionRefreshToken,
            minUnexpiredTimeLeftSeconds = 0
        )

    /**
     * Starts the user session refresh loop.
     *
     * @param context The application context (use context.applicationContext to avoid leaks)
     */
    fun start(context: Context) {
        if (userSessionRefreshToken != null) {
            return
        }

        loadUserSessionData(context)

        if (AuthUtils.isTokenEmptyOrExpiring(
                userSessionRefreshToken,
                minUnexpiredTimeLeftSeconds = 0
            )
        ) {
            clearSession()
            saveUserSessionData(context)
            return
        }

        // Emit the refresh token if it's valid (loaded from storage)
        userSessionRefreshToken?.let { token ->
            if (!AuthUtils.isTokenEmptyOrExpiring(token, minUnexpiredTimeLeftSeconds = 0)) {
                scope.launch {
                    _refreshTokenAvailable.emit(token)
                }
            }
        }

        updateUserSession(context)
    }

    /**
     * Sets the user session with the given tokens.
     *
     * @param context The application context (use context.applicationContext to avoid leaks)
     * @param refreshToken The refresh token
     * @param accessToken The access token
     */
    fun setUserSession(context: Context, refreshToken: String?, accessToken: String?) {
        val wasTokenValid = !AuthUtils.isTokenEmptyOrExpiring(userSessionRefreshToken, minUnexpiredTimeLeftSeconds = 0)

        userSessionRefreshToken = refreshToken
        userSessionAccessToken = accessToken
        saveUserSessionData(context)

        // Emit the refresh token if it's valid and either:
        // 1. We didn't have a valid token before, or
        // 2. This is a new valid token
        refreshToken?.let { token ->
            if (!AuthUtils.isTokenEmptyOrExpiring(token, minUnexpiredTimeLeftSeconds = 0)) {
                if (!wasTokenValid) {
                    scope.launch {
                        _refreshTokenAvailable.emit(token)
                    }
                }
            }
        }

        updateUserSession(context)
    }

    /**
     * Stops the user session refresh loop.
     *
     * @param context The application context (use context.applicationContext to avoid leaks)
     */
    fun stopUserSession(context: Context) {
        refreshJob?.cancel()
        refreshJob = null
        clearSession()
        saveUserSessionData(context)
    }

    private fun clearSession() {
        userSessionRefreshToken = null
        userSessionAccessToken = null
    }

    private fun saveUserSessionData(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (userSessionRefreshToken != null) {
                putString(REFRESH_TOKEN_KEY, userSessionRefreshToken)
            } else {
                remove(REFRESH_TOKEN_KEY)
            }

            if (userSessionAccessToken != null) {
                putString(ACCESS_TOKEN_KEY, userSessionAccessToken)
            } else {
                remove(ACCESS_TOKEN_KEY)
            }
        }
    }

    private fun loadUserSessionData(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        userSessionRefreshToken = prefs.getString(REFRESH_TOKEN_KEY, null)
        userSessionAccessToken = prefs.getString(ACCESS_TOKEN_KEY, null)
    }

    private fun updateUserSession(context: Context) {
        refreshJob?.cancel()

        val refreshToken = userSessionRefreshToken
        if (refreshToken == null ||
            AuthUtils.isTokenEmptyOrExpiring(
                refreshToken,
                minUnexpiredTimeLeftSeconds = 0
            )
        ) {
            return
        }

        val appContext = context.applicationContext
        refreshJob = scope.launch {
            runUserSessionLoop(appContext)
        }
    }

    private suspend fun executeSessionRefresh(context: Context): Boolean {
        val refreshToken = userSessionRefreshToken
        if (refreshToken == null ||
            AuthUtils.isTokenEmptyOrExpiring(
                refreshToken,
                minUnexpiredTimeLeftSeconds = 0
            )
        ) {
            Log.w(TAG, "User Session Refresh token has expired")
            return false
        }

        return try {
            val result = RequestUserSessionAccess.execute(refreshToken = refreshToken)

            userSessionRefreshToken = result.refreshToken
            userSessionAccessToken = result.accessToken

            saveUserSessionData(context)
            AuthAccessManager.setUserSessionAccessToken(userSessionAccessToken)
            true
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            Log.e(TAG, "Failed to refresh user session", e)
            // Clear session on persistent failure
            clearSession()
            saveUserSessionData(context)
            false
        }
    }

    private suspend fun runUserSessionLoop(context: Context) {
        try {
            while (true) {

                if (AuthUtils.isTokenEmptyOrExpiring(
                        userSessionAccessToken,
                        minUnexpiredTimeLeftSeconds = MIN_UNEXPIRED_TIME_LEFT_SECONDS
                    )
                ) {
                    if (!executeSessionRefresh(context)) {
                        break
                    }
                }

                delay(UPDATE_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // Nothing to do.
        } catch (e: Exception) {
            Log.e(TAG, "Refresh loop error", e)
        }
    }
}
