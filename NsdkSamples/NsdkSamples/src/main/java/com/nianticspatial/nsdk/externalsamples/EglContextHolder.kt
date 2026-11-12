// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * Owns a retained GLES3 EGL context + pbuffer surface so the context can be made current again
 * after WebView/Chromium clears the thread's current EGL context.
 *
 * ARCore [com.google.ar.core.Session.update] requires a current GL context; SceneView updates
 * ARCore *before* Filament [beginFrame] rebinds the SurfaceView swapchain.
 */
class EglContextHolder private constructor(
    val display: EGLDisplay,
    val context: EGLContext,
    private val surface: EGLSurface,
) {
    fun makeCurrent(): Boolean {
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun destroy() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        fun create(): EglContextHolder {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            check(EGL14.eglInitialize(display, null, 0, null, 0)) { "eglInitialize failed" }

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = intArrayOf(0)
            val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE)
            check(
                EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0)
            ) { "eglChooseConfig failed" }
            val config = configs[0] ?: error("No EGLConfig")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttribs,
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Error making GL context current"
            }
            return EglContextHolder(display, context, surface)
        }
    }
}
