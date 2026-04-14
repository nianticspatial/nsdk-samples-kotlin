// Copyright 2026 Niantic Spatial.
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
     * Returns true if [token] looks like a structurally valid JWT: three dot-separated parts
     * where the header and payload are valid base64url-encoded JSON objects. Does not verify
     * the signature. Rejects placeholder strings like "set_your_token_here".
     */
    fun isValidJwt(token: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 3 || parts.any { it.isEmpty() }) return false
        return parts[0].decodeBase64UrlJson() != null && parts[1].decodeBase64UrlJson() != null
    }

    private fun String.decodeBase64UrlJson(): JSONObject? {
        return try {
            var s = replace("-", "+").replace("_", "/")
            val padding = s.length % 4
            if (padding > 0) s += "=".repeat(4 - padding)
            JSONObject(String(Base64.decode(s, Base64.DEFAULT)))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the email (or sub as fallback) from a JWT payload.
     */
    fun jwtEmail(token: String?): String? {
        if (token == null) return null

        val parts = token.split(".")
        if (parts.size < 2) return null

        var payload = parts[1]
        payload = payload.replace("-", "+")
        payload = payload.replace("_", "/")

        val padding = payload.length % 4
        if (padding > 0) {
            payload += "=".repeat(4 - padding)
        }

        return try {
            val decodedBytes = Base64.decode(payload, Base64.DEFAULT)
            val jsonString = String(decodedBytes)
            val jsonObject = JSONObject(jsonString)
            jsonObject.optString("email").takeIf { it.isNotEmpty() }
                ?: jsonObject.optString("sub").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
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
