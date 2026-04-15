// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AuthRequests"

/**
 * Refreshes the sample session token. Returns the rotated token from Set-Cookie,
 * or null if the server did not rotate it.
 */
suspend fun requestSampleSessionAccess(sessionToken: String): String? = withContext(Dispatchers.IO) {
    val connection = openIdentityConnection()
    connection.setRequestProperty("Cookie", "refresh_token=$sessionToken")
    connection.postJson(JSONObject().apply {
        put("grantType", "refresh_user_session_access_token")
    })
    // Discard body — rotated token is in Set-Cookie
    connection.inputStream.close()
    connection.extractRefreshTokenCookie()
}

/**
 * Exchanges a sample session token for an NSDK refresh token.
 */
suspend fun requestNsdkRefreshToken(sessionToken: String): String = withContext(Dispatchers.IO) {
    val connection = openIdentityConnection()
    connection.setRequestProperty("Cookie", "refresh_token=$sessionToken")
    val response = connection.postJson(JSONObject().apply {
        put("grantType", "exchange_build_refresh_token")
    })
    response.getString("buildRefreshToken").also {
        if (it.isEmpty()) error("buildRefreshToken is empty")
    }
}

/**
 * Exchanges an NSDK refresh token for an NSDK access token.
 */
suspend fun requestNsdkAccessToken(nsdkRefreshToken: String): String = withContext(Dispatchers.IO) {
    val connection = openIdentityConnection()
    val response = connection.postJson(JSONObject().apply {
        put("grantType", "refresh_build_access_token")
        put("buildRefreshToken", nsdkRefreshToken)
    })
    response.getString("buildAccessToken").also {
        if (it.isEmpty()) error("buildAccessToken is empty")
    }
}

// --- helpers ---

private fun openIdentityConnection(): HttpURLConnection {
    val connection = URL(AuthConstants.EndPointUrls.IDENTITY).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true
    return connection
}

/** Posts [body], checks for a 2xx response, and returns the parsed JSON body. Disconnects on return. */
private fun HttpURLConnection.postJson(body: JSONObject): JSONObject {
    try {
        OutputStreamWriter(outputStream).use { it.write(body.toString()) }

        val code = responseCode
        if (code !in 200..299) {
            val msg = errorStream?.let {
                runCatching { JSONObject(BufferedReader(InputStreamReader(it)).readText()).optString("error") }.getOrNull()
            }
            Log.e(TAG, "Server error $code: $msg")
            error("Identity endpoint returned $code${if (!msg.isNullOrEmpty()) ": $msg" else ""}")
        }

        val text = BufferedReader(InputStreamReader(inputStream)).readText()
        return JSONObject(text)
    } finally {
        disconnect()
    }
}

private fun HttpURLConnection.extractRefreshTokenCookie(): String? {
    val headers = headerFields["Set-Cookie"] ?: headerFields["set-cookie"] ?: return null
    for (header in headers) {
        for (part in header.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("refresh_token=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim()
            }
        }
    }
    return null
}
