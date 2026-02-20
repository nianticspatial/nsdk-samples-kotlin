package com.nianticspatial.nsdk.externalsamples.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Request access to the NS API, using the sample user session access token.
 */
object RequestAuthAccess {
    private const val TAG = "RequestAuthAccess"
    /**
     * Executes an asynchronous network request to get an NS API access token.
     *
     * @param userSessionAccessToken The user session access token used to authenticate the request.
     * @return The access token string if successful, or null if the request failed.
     * @throws AuthAccessError if the server returns an error or if the response is invalid.
     */
    suspend fun execute(userSessionAccessToken: String): String? = withContext(Dispatchers.IO) {
        // Convert the end-point string to a URL
        val accessEndpoint = try {
            URL(AuthConstants.EndPointUrls.ACCESS)
        } catch (e: MalformedURLException) {
            throw AuthAccessError.InvalidEndpointUrl
        }

        // Prepare the URLRequest with Authorization header
        val connection = accessEndpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $userSessionAccessToken")
        connection.setRequestProperty("Content-Type", "application/json")

        try {
            // Perform the network call
            val responseCode = connection.responseCode

            // Check for successful HTTP status codes (2xx)
            if (responseCode !in 200..299) {
                val errorMessage = try {
                    connection.errorStream?.use { errorStream ->
                        BufferedReader(InputStreamReader(errorStream)).use { reader ->
                            val response = reader.readText()
                            try {
                                val jsonObject = JSONObject(response)
                                jsonObject.optString("error", response)
                            } catch (e: Exception) {
                                response
                            }
                        }
                    }
                } catch (e: Exception) {
                    null
                }
                throw AuthAccessError.ServerError(responseCode, errorMessage)
            }

            // Read the response
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            val jsonObject = JSONObject(response)

            // Check if the response contains an error field
            if (jsonObject.has("error")) {
                val error = jsonObject.optString("error", "")
                if (error.isNotEmpty()) {
                    throw AuthAccessError.ServerError(responseCode, error)
                }
            }

            // Check if access token is present
            if (!jsonObject.has("accessToken")) {
                throw AuthAccessError.InvalidResponse
            }
            val accessToken = jsonObject.getString("accessToken")
            if (accessToken.isEmpty()) {
                throw AuthAccessError.InvalidResponse
            }

            accessToken
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Potential errors encountered during the auth access request process.
 */
sealed class AuthAccessError(message: String) : Exception(message) {
    data object InvalidEndpointUrl : AuthAccessError("Endpoint is not a valid URL") {
        private fun readResolve(): Any = InvalidEndpointUrl
    }

    data object InvalidResponse : AuthAccessError("Failed to parse access token response") {
        private fun readResolve(): Any = InvalidResponse
    }

    data class ServerError(val statusCode: Int, val errorMessage: String?) :
        AuthAccessError(
            if (!errorMessage.isNullOrEmpty()) {
                "Error in request for NS auth access token: $statusCode : $errorMessage"
            } else {
                "Error in request for NS auth access token: $statusCode"
            }
        )
}
