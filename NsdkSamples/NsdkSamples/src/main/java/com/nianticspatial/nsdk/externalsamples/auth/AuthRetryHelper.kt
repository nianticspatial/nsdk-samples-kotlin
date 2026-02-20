// Copyright Niantic Spatial.

package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Log
import com.nianticspatial.nsdk.NSDKSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A helper class that provides automatic retry logic for NSDK operations that may fail.
 *
 * Flow:
 * 1. Before running: if not authorized, wait for authorization.
 * 2. Run the operation.
 * 3. If the operation fails: wait 1 second, then retry once.
 * 4. If the retry fails, the exception is thrown (no further retries).
 *
 * Usage:
 * ```kotlin
 * val retryHelper = AuthRetryHelper(nsdkSession)
 * val result = retryHelper.withRetry {
 *     sitesSession.requestSelfUserInfo()
 * }
 * ```
 */
class AuthRetryHelper(private val nsdkSession: NSDKSession) {
    companion object {
        private const val TAG = "AuthRetryHelper"
        private const val INITIAL_AUTH_WAIT_MS = 30_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val AUTH_POLL_INTERVAL_MS = 1000L
    }

    /**
     * Executes an async operation with automatic retry.
     * Pre-flight: waits for authorization if not already authorized. Then runs the operation; on failure waits 1s and retries once. If the retry fails, the exception is thrown.
     *
     * @param operation The suspend function to execute
     * @return The result of the operation
     * @throws The original exception if both attempt and retry fail, or timeout if initial auth wait times out
     */
    suspend fun <T> withRetry(operation: suspend () -> T): T {
        waitForAuthorizationIfNeeded(INITIAL_AUTH_WAIT_MS)
        return withContext(Dispatchers.Main) {
            try {
                operation()
            } catch (e: Exception) {
                Log.d(TAG, "Operation failed: ${e.message}, waiting 1 second before retry...")
                delay(RETRY_DELAY_MS)
                operation()
            }
        }
    }

    /**
     * If not authorized, waits for the session to become authorized (pre-flight before running the operation).
     * NSDK session APIs (e.g. isAuthorized) must be called on the main thread; the native layer crashes
     * (ComponentManager::GetOrCreateComponent) when invoked from a background thread.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @throws AuthRetryException if not authorized within timeout
     */
    private suspend fun waitForAuthorizationIfNeeded(timeoutMs: Long) {
        withContext(Dispatchers.Main) {
            if (nsdkSession.isAuthorized) return@withContext
            val startTime = System.currentTimeMillis()
            while (!nsdkSession.isAuthorized) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    Log.w(TAG, "Authorization timeout after ${timeoutMs}ms")
                    throw AuthRetryException("Timeout waiting for authorization")
                }
                delay(AUTH_POLL_INTERVAL_MS)
            }
        }
    }
}

/**
 * Exception thrown when retry operations fail
 */
class AuthRetryException(message: String) : Exception(message)
