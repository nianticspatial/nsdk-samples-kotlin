// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2debugger

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VpsDebuggerWebView(
    bridge: VpsDebuggerWebBridge,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).also { bridge.attach(it) }
        },
        onRelease = { bridge.detach() },
    )
}
