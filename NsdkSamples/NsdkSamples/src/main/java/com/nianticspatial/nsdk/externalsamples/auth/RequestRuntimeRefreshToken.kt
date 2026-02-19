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

private const val TAG = "RequestRuntimeRefreshToken"

/**
 * Request a runtime refresh token by exchanging a user session refresh token.
 * Provides a unique runtime refresh token for every build or play mode session.
 *
 * This is a first-time request that creates a new refresh token (not refreshing an existing one).
 */
object RequestRuntimeRefreshToken {
    /**
     * The final output containing the runtime refresh token credentials.
     */
    data class RuntimeRefreshTokenResult(
        val refreshToken: String,
        val expiresAt: Int?
    )

    /**
     * Executes an asynchronous network request to exchange a user session refresh token for a runtime refresh token.
     *
     * @param userSessionRefreshToken A valid user session refresh token used to authenticate the request.
     * @return A RuntimeRefreshTokenResult containing the new runtime refresh token and optional expiry.
     * @throws RuntimeRefreshTokenError if the server returns an error or if the response is invalid.
     */
    suspend fun execute(userSessionRefreshToken: String): RuntimeRefreshTokenResult = withContext(Dispatchers.IO) {
        // Convert the end-point string to a URL
        val identityEndpoint = try {
            URL(AuthConstants.EndPointUrls.IDENTITY)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid endpoint URL: ${AuthConstants.EndPointUrls.IDENTITY}", e)
            throw RuntimeRefreshTokenError.InvalidEndpointUrl
        }

        // Prepare the URLRequest with standard JSON headers
        val connection = identityEndpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        // Pass the user session refresh token via the Cookie header as required by the Auth spec
        connection.setRequestProperty("Cookie", "refresh_token=$userSessionRefreshToken")
        connection.doOutput = true

        try {
            // Send the request body
            val requestBody = JSONObject().apply {
                put("grantType", "exchange_build_refresh_token")
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
                        Log.e(TAG, "Error response body: $response")
                        val jsonObject = JSONObject(response)
                        jsonObject.optString("error", response)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read error response", e)
                    null
                }
                Log.e(TAG, "Server error: statusCode=$responseCode, message=$errorMessage")
                throw RuntimeRefreshTokenError.ServerError(responseCode, errorMessage)
            }

            // Read the response
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val jsonObject = JSONObject(response)
            if (!jsonObject.has("buildRefreshToken")) {
                Log.e(TAG, "Response missing buildRefreshToken field. Response: $response")
                throw RuntimeRefreshTokenError.InvalidResponse
            }
            val buildRefreshToken = jsonObject.getString("buildRefreshToken")
            if (buildRefreshToken.isEmpty()) {
                Log.e(TAG, "buildRefreshToken is empty")
                throw RuntimeRefreshTokenError.InvalidResponse
            }

            val expiresAt = if (jsonObject.has("expiresAt") && !jsonObject.isNull("expiresAt")) {
                jsonObject.optInt("expiresAt")
            } else {
                null
            }

            RuntimeRefreshTokenResult(refreshToken = buildRefreshToken, expiresAt = expiresAt)
        } catch (e: RuntimeRefreshTokenError) {
            Log.e(TAG, "Runtime refresh token request failed: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during runtime refresh token request", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Potential errors encountered during the runtime refresh token request process.
 */
sealed class RuntimeRefreshTokenError(message: String) : Exception(message) {
    data object InvalidEndpointUrl : RuntimeRefreshTokenError("Endpoint is not a valid URL") {
        private fun readResolve(): Any = InvalidEndpointUrl
    }

    data object InvalidResponse : RuntimeRefreshTokenError("Invalid response from identity endpoint.") {
        private fun readResolve(): Any = InvalidResponse
    }

    data class ServerError(val statusCode: Int, val errorMessage: String?) :
        RuntimeRefreshTokenError(
            if (!errorMessage.isNullOrEmpty()) {
                "Identity endpoint returned $statusCode: $errorMessage"
            } else {
                "Identity endpoint returned $statusCode."
            }
        )
}
