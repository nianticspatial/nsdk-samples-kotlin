// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Handles opening the web-page for login and receiving the results.
 *
 * When login is requested, opens a URL in the browser which points to the login web portal.
 * On success, a "deep link" is returned with tokens generated for the user session.
 *
 * If reusing this code outside the sample, it is recommended to change the redirectType in the URL
 * to "nsdk-external"
 */
class LoginManager(private val activity: Activity) {

    companion object {
        private const val TAG = "LoginManager"
        // Request code for identifying the auth result
        private const val AUTH_REQUEST_CODE = 1001
    }

    /**
     * Start the authentication flow.
     * Opens a Chrome Custom Tab (or browser) for web-based OAuth authentication.
     *
     * This is the Android equivalent of ASWebAuthenticationSession.
     * The Activity context automatically provides the presentation context
     * (no need for ASWebAuthenticationPresentationContextProviding).
     */
    fun startAuth() {
        // 1. The URL for the login page
        // On success, the "nsdk-samples" redirectType returns a deep link with "nsdk-samples://..." scheme.
        // NOTE: This can be replaced with "nsdk-external" if reusing this code (so as not to conflict).
        // (AndroidManifest.xml will need to be updated to handle "nsdk-external" scheme).
        val authURL = "${AuthConstants.EndPointUrls.SIGN_IN}?redirectType=nsdk-samples".toUri()

        // 2. Create CustomTabsIntent
        val customTabsIntent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(ContextCompat.getColor(activity, android.R.color.white))
                    .build()
            )
            .setShowTitle(true)
            .build()

        // 3. Launch the custom tab
        // The callback will be handled via deep link in AndroidManifest.xml
        customTabsIntent.launchUrl(activity, authURL)
    }

    /**
     * Handle the callback URL from the authentication flow.
     * This should be called from MainActivity.onNewIntent() or onCreate()
     * when the app receives a deep link intent.
     */
    fun handleCallback(intent: Intent) {
        val data = intent.data ?: run {
            Log.w(TAG, "Received callback with null data")
            return
        }

        // Verify it's our callback scheme
        if (data.scheme != AuthConstants.CALLBACK_SCHEME) {
            Log.w(TAG, "Received callback with unexpected scheme: ${data.scheme}")
            return
        }

        val urlString = data.toString()
        val tokens = AuthUtils.extractTokens(urlString)

        if (tokens.first == null && tokens.second == null) {
            Log.e(TAG, "Failed to extract tokens from callback URL: $urlString")
            return
        }

        NSSampleSessionManager.setNSSampleSession(sessionToken = tokens.second)
    }
}
