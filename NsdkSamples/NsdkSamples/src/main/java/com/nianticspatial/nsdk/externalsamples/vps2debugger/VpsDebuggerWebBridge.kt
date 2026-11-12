// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.vps2debugger

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Streams buffered VPS debugger events into the hosted Firebase viewer via
 * `window.__vpsBridge` (Argus / [VpsDebuggerLiveOverlay] protocol).
 */
class VpsDebuggerWebBridge(
    private val eventBuffer: VpsDebuggerEventBuffer,
    private val scope: CoroutineScope,
    private val viewerUrl: String = DEFAULT_VIEWER_URL,
) {
    private var webView: WebView? = null
    private var visible = false
    private var bridgeReady = false
    private var isFlushing = false
    private var nextSendIndex = 0
    private var flushJob: Job? = null

    fun attach(webView: WebView) {
        this.webView = webView
        // Use default (hardware) compositing. LAYER_TYPE_SOFTWARE desyncs Leaflet tile
        // CSS transforms from SVG/Canvas overlays during pan/zoom. GlAwareARSceneView
        // already rebinds EGL if WebView clears the GL current.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        @SuppressLint("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Allow Leaflet/Mapbox tiles over HTTPS while the viewer may be loaded via HTTP.
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (visible) {
                    scope.launch { prepareBridge(catchUp = true) }
                }
            }
        }
    }

    fun detach() {
        hide()
        webView = null
    }

    fun show() {
        val view = webView ?: return
        if (visible) return
        visible = true
        bridgeReady = false
        nextSendIndex = 0
        view.loadUrl(viewerUrl)
        startFlushLoop()
    }

    fun hide() {
        if (!visible) return
        visible = false
        val shouldReset = bridgeReady
        bridgeReady = false
        flushJob?.cancel()
        flushJob = null
        val view = webView ?: return
        // Await resetViewer before blanking; otherwise about:blank wins the race and reset never runs.
        // Skip blank if show() resumed this WebView before the coroutine finished.
        scope.launch {
            if (shouldReset) {
                evalJs(
                    "if (window.__vpsBridge && window.__vpsBridge.resetViewer) { " +
                        "window.__vpsBridge.resetViewer(); }"
                )
            }
            withContext(Dispatchers.Main) {
                if (!visible && webView === view) {
                    view.loadUrl("about:blank")
                }
            }
        }
    }

    fun onDebuggerEvent() {
        // Flush loop polls the buffer.
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive && visible) {
                if (bridgeReady && !isFlushing && nextSendIndex < eventBuffer.count) {
                    flush(catchUp = false)
                }
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    private suspend fun prepareBridge(catchUp: Boolean) {
        if (!visible) return
        val ready = waitForBridgeFunction()
        if (!ready) {
            Log.e(TAG, "window.__vpsBridge.onEvents was not ready. Deploy latest viewer to Firebase.")
            return
        }
        val configJson = """{"bridgeVersion":1,"embedded":true}"""
        evalJs(
            "if (window.__vpsBridge.applyViewerConfig) { " +
                "window.__vpsBridge.applyViewerConfig($configJson); " +
                "} else { window.__vpsViewerConfig = $configJson; }"
        )
        evalJs("if (window.__vpsBridge.beginLive) { window.__vpsBridge.beginLive('live'); }")
        bridgeReady = true
        nextSendIndex = 0
        Log.i(TAG, "Bridge ready. Session events: ${eventBuffer.count}")
        flush(catchUp = catchUp)
    }

    private suspend fun waitForBridgeFunction(): Boolean {
        repeat(50) {
            if (!visible) return false
            val ok = evalJsBool(
                "Boolean(window.__vpsBridge && typeof window.__vpsBridge.onEvents === 'function')"
            )
            if (ok) return true
            delay(100)
        }
        return false
    }

    private suspend fun flush(catchUp: Boolean) {
        if (!visible || !bridgeReady || isFlushing) return
        if (nextSendIndex >= eventBuffer.count) return

        isFlushing = true
        try {
            val batchSize = if (catchUp) CATCH_UP_BATCH_SIZE else MAX_BATCH_SIZE
            while (nextSendIndex < eventBuffer.count && visible) {
                val take = minOf(eventBuffer.count - nextSendIndex, batchSize)
                val batchJson = buildString {
                    append('[')
                    for (i in 0 until take) {
                        if (i > 0) append(',')
                        append(eventBuffer.eventJsonAt(nextSendIndex + i))
                    }
                    append(']')
                }
                val ok = evalJsBool("window.__vpsBridge.onEvents($batchJson); true")
                if (!ok) {
                    Log.w(TAG, "onEvents JavaScript evaluation failed.")
                    break
                }
                nextSendIndex += take
                if (!catchUp) break
                delay(1)
            }
        } finally {
            isFlushing = false
        }
    }

    private suspend fun evalJs(script: String): String? = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { result ->
                cont.resume(result)
            }
        }
    }

    private suspend fun evalJsBool(script: String): Boolean {
        val result = evalJs(script) ?: return false
        return result == "true"
    }

    companion object {
        private const val TAG = "VpsDebuggerWebBridge"
        const val DEFAULT_VIEWER_URL = "https://vpsdebugger.nianticspatial.com/?embedded=true"
        private const val MAX_BATCH_SIZE = 25
        private const val CATCH_UP_BATCH_SIZE = 50
        private const val FLUSH_INTERVAL_MS = 200L
    }
}
