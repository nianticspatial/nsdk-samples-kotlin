// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nianticspatial.nsdk.NSDKSession

/**
 * Maintains a sample session with the sample backend and refreshes the session
 * token while the app is running. The token is persisted via [TokenStorage]
 * so the session can survive app restarts.
 *
 * Call [configure] once from [android.app.Application.onCreate] before any other use.
 */
object NSSampleSessionManager {
    private const val TAG = "NSSampleSessionManager"
    private const val UPDATE_INTERVAL_MS = 10_000L // 10 seconds
    private const val MIN_UNEXPIRED_TIME_LEFT_SECONDS = 60L // 60 seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var nsSampleSessionToken: String? = null

    private lateinit var storage: TokenStorage

    private val _sampleTokenAvailable = MutableSharedFlow<String>(replay = 1)
    /**
     * Flow that emits when a valid sample session token becomes available.
     * Replays the last value if one exists, so new subscribers get the current token immediately.
     */
    val sampleTokenAvailable: SharedFlow<String> = _sampleTokenAvailable.asSharedFlow()

    /**
     * Gets the current sample session token.
     */
    val sessionToken: String?
        get() = nsSampleSessionToken

    /**
     * Checks if a session is in progress.
     */
    val isSessionInProgress: Boolean
        get() = !AuthUtils.isTokenEmptyOrExpiring(
            nsSampleSessionToken,
            minUnexpiredTimeLeftSeconds = 0
        )

    /**
     * Must be called once before any other method, typically from [android.app.Application.onCreate].
     * Provides the [TokenStorage] implementation and immediately starts the session refresh loop,
     * restoring any persisted session.
     */
    fun configure(tokenStorage: TokenStorage) {
        storage = tokenStorage
        start()
    }

    /**
     * Sets the sample session with the given token.
     */
    fun setNSSampleSession(sessionToken: String?) {
        val wasTokenValid = !AuthUtils.isTokenEmptyOrExpiring(
            nsSampleSessionToken,
            minUnexpiredTimeLeftSeconds = 0
        )

        nsSampleSessionToken = sessionToken
        storage.save(sessionToken)

        sessionToken?.let { token ->
            if (!AuthUtils.isTokenEmptyOrExpiring(token, minUnexpiredTimeLeftSeconds = 0)) {
                if (!wasTokenValid) {
                    scope.launch {
                        _sampleTokenAvailable.emit(token)
                    }
                }
            }
        }

        updateSession()
    }

    /**
     * Stops the session refresh loop and clears all stored tokens.
     */
    fun stopNSSampleSession() {
        refreshJob?.cancel()
        refreshJob = null
        clearSession()
        storage.save(null)
        _sampleTokenAvailable.resetReplayCache()
    }

    /**
     * Sets up NSDK session access by collecting the sample token flow and forwarding
     * NSDK access tokens to the NSDK session. Returns a Job that can be cancelled to stop
     * the collection.
     *
     * @param session The NSDK session to forward tokens to
     * @param coroutineScope The scope in which to launch the collection coroutine
     * @return A Job representing the running collection, or null if not started
     */
    fun setupSessionAccess(
        session: NSDKSession,
        coroutineScope: CoroutineScope,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ): Job? {
        return coroutineScope.launch {
            sampleTokenAvailable.collect { sampleToken ->
                val nsdkRefreshToken = try {
                    requestNsdkRefreshToken(sessionToken = sampleToken)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to get NSDK refresh token", e)
                    withContext(Dispatchers.Main) { onFailure?.invoke() }
                    return@collect
                }
                val nsdkAccessToken = try {
                    requestNsdkAccessToken(nsdkRefreshToken = nsdkRefreshToken)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to get NSDK access token", e)
                    withContext(Dispatchers.Main) { onFailure?.invoke() }
                    return@collect
                }
                withContext(Dispatchers.Main) {
                    session.setAccessToken(nsdkAccessToken)
                    onSuccess?.invoke()
                }
            }
        }
    }

    private fun start() {
        if (nsSampleSessionToken != null) return

        nsSampleSessionToken = storage.load()

        if (AuthUtils.isTokenEmptyOrExpiring(nsSampleSessionToken, minUnexpiredTimeLeftSeconds = 0)) {
            clearSession()
            storage.save(null)
            return
        }

        nsSampleSessionToken?.let { token ->
            scope.launch { _sampleTokenAvailable.emit(token) }
        }

        updateSession()
    }

    private fun clearSession() {
        nsSampleSessionToken = null
    }

    private fun updateSession() {
        refreshJob?.cancel()

        val token = nsSampleSessionToken
        if (token == null || AuthUtils.isTokenEmptyOrExpiring(token, minUnexpiredTimeLeftSeconds = 0)) {
            return
        }

        refreshJob = scope.launch { runSessionLoop() }
    }

    private suspend fun executeSessionRefresh(): Boolean {
        val token = nsSampleSessionToken
        if (token == null || AuthUtils.isTokenEmptyOrExpiring(token, minUnexpiredTimeLeftSeconds = 0)) {
            Log.w(TAG, "Sample session token has expired")
            return false
        }

        return try {
            val newToken = requestSampleSessionAccess(sessionToken = token)
            nsSampleSessionToken = newToken
            storage.save(newToken)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to refresh sample session", e)
            clearSession()
            storage.save(null)
            false
        }
    }

    private suspend fun runSessionLoop() {
        try {
            while (true) {
                if (AuthUtils.isTokenEmptyOrExpiring(
                        nsSampleSessionToken,
                        minUnexpiredTimeLeftSeconds = MIN_UNEXPIRED_TIME_LEFT_SECONDS
                    )
                ) {
                    if (!executeSessionRefresh()) break
                }
                delay(UPDATE_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            // Nothing to do.
        } catch (e: Exception) {
            Log.e(TAG, "Session refresh loop error", e)
        }
    }
}
