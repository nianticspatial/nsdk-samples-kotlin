package com.nianticspatial.nsdk.externalsamples.auth

import com.nianticspatial.nsdk.externalsamples.BuildConfig

/**
 * Shared constants used for authentication
 */
object AuthConstants {
    // Developer tokens can be pasted here to enable samples that require server access
    // (this will skip Enterprise Auth login):
    var accessToken: String = BuildConfig.ACCESS_TOKEN
    var refreshToken: String = BuildConfig.REFRESH_TOKEN

    // List of all end-points used by authentication
    // TODO: production URLs here (when working):
    object EndPointUrls {
        const val SIGN_IN: String = "https://sample-app-frontend-internal.nianticspatial.com/signin"
        const val IDENTITY: String = "https://spatial-identity.nianticspatial.com/oauth/token"
        const val ACCESS: String = "https://sample-app-backend-internal.nianticspatial.com/api/access-token"
    }

    // The callback URL scheme for deep linking (equivalent to iOS's callbackURLScheme)
    const val CALLBACK_SCHEME: String = "nsdk-samples"
}
