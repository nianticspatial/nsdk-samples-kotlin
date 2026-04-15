// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import com.nianticspatial.nsdk.externalsamples.BuildConfig

/**
 * Shared constants used for authentication
 */
object AuthConstants {
    // Developer access token can be pasted here to enable samples that require server access
    // (this will skip the login flow):
    var accessToken: String = BuildConfig.ACCESS_TOKEN

    // List of all end-points used by authentication
    object EndPointUrls {
        const val SIGN_IN: String = "https://sample-app-frontend-internal.nianticspatial.com/signin"
        const val IDENTITY: String = "https://spatial-identity.nianticspatial.com/oauth/token"
    }

    // The callback URL scheme for deep linking (equivalent to iOS's callbackURLScheme)
    const val CALLBACK_SCHEME: String = "nsdk-samples"
}
