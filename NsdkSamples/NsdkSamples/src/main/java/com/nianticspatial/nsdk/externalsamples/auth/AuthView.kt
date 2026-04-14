// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
object AuthRoute

@Composable
fun AuthView(
    manager: AuthManager,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val isLoggedIn = manager.isLoggedIn
    val statusMessage = manager.statusMessage
    val emailMessage = manager.emailMessage
    val showLoginControls = manager.showLoginControls

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp)
    ) {
        Text(
            text = statusMessage,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isLoggedIn) Color(0xFF4CAF50) else Color(0xFFFF9800),
            textAlign = TextAlign.Center
        )

        if (emailMessage.isNotEmpty()) {
            Text(
                text = emailMessage,
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (showLoginControls && isLoggedIn) {
            Button(
                onClick = { manager.logout() },
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                )
            ) {
                Text(
                    text = "Logout",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        } else if (showLoginControls) {
            Button(
                onClick = { manager.login(activity) },
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Text(
                    text = "Login",
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}
