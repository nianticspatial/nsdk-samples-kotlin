package com.nianticspatial.nsdk.externalsamples.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Display for error messages
 */
@Composable
fun ErrorDisplay(errorMessage: String?) {
    errorMessage?.let { message ->
        Text(
            text = "Error: $message",
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Red.copy(alpha = 0.1f))
                .padding(8.dp),
            color = Color.Red,
            textAlign = TextAlign.Center
        )
    }
}
