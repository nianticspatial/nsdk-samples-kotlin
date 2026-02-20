package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import androidx.core.net.toUri

/**
 * Miscellaneous utility functions related to authentication.
 */
object AuthUtils {
    /**
     * Extract refresh and access tokens from a URL string.
     */
    fun extractTokens(urlString: String): Pair<String?, String?> {
        return try {
            val uri = urlString.toUri()
            val accessToken = uri.getQueryParameter("accessToken")
            val refreshToken = uri.getQueryParameter("refreshToken")
            Pair(accessToken, refreshToken)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }
    /**
     * Print details of a token
     */
    fun printTokenDetails(tag: String, token: String?, context: String) {
        val abbreviatedToken = token?.takeLast(4) ?: "<none>"
        val expiration = jwtExpiration(token)

        val timeLeft = if (expiration != null) {
            val secondsLeft = (expiration - System.currentTimeMillis() / 1000).toInt()
            secondsLeft.toString()
        } else {
            "<unknown>"
        }

        Log.d(tag, "$context: $abbreviatedToken, time left = $timeLeft")
    }

    /**
     * Is the given token either empty, expired, or about to expire (in the given time left):
     */
    fun isTokenEmptyOrExpiring(
        token: String?,
        minUnexpiredTimeLeftSeconds: Long
    ): Boolean {
        if (token.isNullOrEmpty()) return true

        val expiration = jwtExpiration(token) ?: return true
        val timeLeft = expiration - (System.currentTimeMillis() / 1000)

        return timeLeft <= minUnexpiredTimeLeftSeconds
    }

    /**
     * Extracts an expiration timestamp from a JWT payload.
     */
    private fun jwtExpiration(token: String?): Long? {
        if (token == null) return null

        val parts = token.split(".")
        if (parts.size < 2) return null

        var payload = parts[1]
        // Base64 URL decode
        payload = payload.replace("-", "+")
        payload = payload.replace("_", "/")

        // Add padding if needed
        val padding = payload.length % 4
        if (padding > 0) {
            payload += "=".repeat(4 - padding)
        }

        return try {
            val decodedBytes = Base64.decode(payload, Base64.DEFAULT)
            val jsonString = String(decodedBytes)
            val jsonObject = JSONObject(jsonString)

            when (val exp = jsonObject.opt("exp")) {
                is Number -> exp.toLong()
                is String -> exp.toLongOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
