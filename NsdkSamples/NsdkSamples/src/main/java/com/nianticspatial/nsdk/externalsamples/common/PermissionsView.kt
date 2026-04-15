// Copyright 2026 Niantic Spatial.

package com.nianticspatial.nsdk.externalsamples.common

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nianticspatial.nsdk.externalsamples.Utils

@Composable
fun PermissionsView(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionState = remember { mutableStateOf(false) }
    val requestPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            permissionState.value = permissions[Manifest.permission.CAMERA] ?: false
                && permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        }

    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            if (!Utils.hasCameraPermissions(context)) add(Manifest.permission.CAMERA)
            if (!Utils.hasLocationPermissions(context)) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(Unit) {
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    if (Utils.hasLocationPermissions(context) && Utils.hasCameraPermissions(context)) {
        permissionState.value = true
    }

    // When user comes back from settings after setting activities, check again before moving on
    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Utils.hasLocationPermissions(context) && Utils.hasCameraPermissions(context)) {
                    permissionState.value = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (permissionState.value) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (context is Activity) {
                Button(onClick = {
                    Utils.shouldShowRequestPermissionRationale(context)
                }) {
                    Text("Request Permissions")
                }
            } else {
                Text("Unable to get permissions")
            }
        }
    }
}
