package com.nianticspatial.nsdk.externalsamples

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.nianticspatial.nsdk.externalsamples.auth.LoginManager
import com.nianticspatial.nsdk.externalsamples.auth.UserSessionManager
import com.nianticspatial.nsdk.externalsamples.common.PermissionsView

class MainActivity : ComponentActivity() {
    private lateinit var loginManager: LoginManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UserSessionManager.start(applicationContext)

        loginManager = LoginManager(this)

        // Handle deep link callback if app was opened via OAuth redirect
        handleAuthCallback(intent)

        enableEdgeToEdge()
        setContent {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                PermissionsView {
                    NSDKDemoView(modifier = Modifier.padding(innerPadding), this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link callback if app was already running
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent) {
        if (intent.data != null && intent.data?.scheme == "nsdk-samples") {
            loginManager.handleCallback(intent)
        }
    }
}
