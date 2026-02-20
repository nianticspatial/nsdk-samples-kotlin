package com.nianticspatial.nsdk.externalsamples.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Request access to the user session as part of Enterprise Authentication
 */
object RequestUserSessionAccess {
    /**
     * The final output containing the updated session credentials.
     */
    data class UserSessionResult(
        val refreshToken: String?,
        val accessToken: String?
    )

    /**
     * Executes an asynchronous network request to refresh the user session.
     *
     * @param refreshToken The current refresh token used to authenticate the request.
     * @return A UserSessionResult containing the new access token and an optional new refresh token.
     * @throws UserSessionError if the server returns an error or if the response is invalid.
     */
    suspend fun execute(refreshToken: String): UserSessionResult = withContext(Dispatchers.IO) {
        // Convert the end-point string to a URL
        val identityEndpoint = try {
            URL(AuthConstants.EndPointUrls.IDENTITY)
        } catch (e: Exception) {
            throw UserSessionError.InvalidEndpointUrl
        }

        // Prepare the URLRequest with standard JSON headers
        val connection = identityEndpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Cookie", "refresh_token=$refreshToken")
        connection.doOutput = true

        try {
            // Send the request body
            val requestBody = JSONObject().apply {
                put("grantType", "refresh_user_session_access_token")
            }
            val outputStream = OutputStreamWriter(connection.outputStream)
            outputStream.write(requestBody.toString())
            outputStream.flush()
            outputStream.close()

            // Perform the network call
            val responseCode = connection.responseCode

            // Check for successful HTTP status codes (2xx)
            if (responseCode !in 200..299) {
                val errorMessage = try {
                    val errorStream = connection.errorStream
                    if (errorStream != null) {
                        val reader = BufferedReader(InputStreamReader(errorStream))
                        val response = reader.readText()
                        reader.close()
                        val jsonObject = JSONObject(response)
                        jsonObject.optString("error", response)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
                throw UserSessionError.ServerError(responseCode, errorMessage)
            }

            // Read the response
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val jsonObject = JSONObject(response)
            if (!jsonObject.has("token")) {
                throw UserSessionError.InvalidResponse
            }
            val token = jsonObject.getString("token")
            if (token.isEmpty()) {
                throw UserSessionError.InvalidResponse
            }

            // Refresh tokens are always rotated and sent back via Set-Cookie headers
            val newRefreshToken = extractRefreshToken(connection, identityEndpoint)

            UserSessionResult(refreshToken = newRefreshToken, accessToken = token)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Helper method to extract the 'refresh_token' from the HTTP response headers.
     *
     * @param connection The HttpURLConnection containing the headers.
     * @param url The URL associated with the cookies.
     * @return The string value of the refresh token if found.
     */
    private fun extractRefreshToken(connection: HttpURLConnection, url: URL): String? {
        // Get Set-Cookie headers
        val setCookieHeaders = connection.headerFields["Set-Cookie"]
            ?: connection.headerFields["set-cookie"]
            ?: return null

        // Find the refresh_token cookie
        for (cookieHeader in setCookieHeaders) {
            val cookieParts = cookieHeader.split(";")
            for (part in cookieParts) {
                val trimmed = part.trim()
                if (trimmed.startsWith("refresh_token=", ignoreCase = true)) {
                    return trimmed.substringAfter("=").substringBefore(";").trim()
                }
            }
        }

        return null
    }
}

/**
 * Potential errors encountered during the user session request process.
 */
sealed class UserSessionError(message: String) : Exception(message) {
    data object InvalidEndpointUrl : UserSessionError("Endpoint is not a valid URL") {
        private fun readResolve(): Any = InvalidEndpointUrl
    }

    data object InvalidResponse : UserSessionError("Invalid response from identity endpoint.") {
        private fun readResolve(): Any = InvalidResponse
    }

    data class ServerError(val statusCode: Int, val errorMessage: String?) :
        UserSessionError(
            if (!errorMessage.isNullOrEmpty()) {
                "Identity endpoint returned $statusCode: $errorMessage"
            } else {
                "Identity endpoint returned $statusCode."
            }
        )
}
