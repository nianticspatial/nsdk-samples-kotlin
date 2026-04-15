// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * Composable content used to render contextual help within the scaffold.
 */
typealias HelpContent = @Composable () -> Unit

/**
 * Wraps screen content with back and help actions, injecting a contextual help overlay when supplied.
 *
 * @param content Provides the screen content and a state holder so it can register contextual help UI.
 */
@Composable
fun BackHelpScaffold(
    navController: NavController,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(MutableState<HelpContent?>) -> Unit
) {
    // Tracks whether the contextual help overlay should be shown over the screen content.
    var helpVisible by remember { mutableStateOf(false) }
    // Holds the lazily supplied help composable; null means no help is available.
    val helpContentState = remember { mutableStateOf<HelpContent?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        bottomBar = {
            BottomAppBar(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                // Navigates back to the previous screen.
                IconButton(
                    onClick = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        }
                      },
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(Modifier.weight(1f))

                // Toggle the help overlay only if contextual help content has been provided.
                IconButton(
                    onClick = { helpVisible = !helpVisible && helpContentState.value != null },
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Help,
                        contentDescription = "Help",
                        tint = Color.White
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Render the main screen content, allowing it to register contextual help UI.
            content(helpContentState)

            if (helpVisible) {
                helpContentState.value?.let { help ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            // Invoke the help composable supplied by the screen content.
                            help()
                        }
                    }
                }
            }
        }
    }
}
