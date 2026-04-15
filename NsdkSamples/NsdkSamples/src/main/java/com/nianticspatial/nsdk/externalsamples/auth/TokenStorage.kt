// Copyright 2026 Niantic Spatial.
package com.nianticspatial.nsdk.externalsamples.auth

import android.content.Context
import androidx.core.content.edit

/**
 * Abstraction over persistent token storage. Decouples [NSSampleSessionManager]
 * from Android [Context] so the manager's public API requires no platform types.
 */
interface TokenStorage {
    fun load(): String?
    fun save(token: String?)
}

/**
 * [TokenStorage] backed by [android.content.SharedPreferences].
 * Capture [context] once at construction; always uses [Context.applicationContext]
 * internally to avoid activity leaks.
 */
class SharedPrefsTokenStorage(context: Context) : TokenStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(SESSION_TOKEN_KEY, null)

    override fun save(token: String?) = prefs.edit {
        if (token != null) putString(SESSION_TOKEN_KEY, token) else remove(SESSION_TOKEN_KEY)
    }

    private companion object {
        const val PREFS_NAME = "NSSampleSessionPrefs"
        const val SESSION_TOKEN_KEY = "NSSampleSessionToken"
    }
}
