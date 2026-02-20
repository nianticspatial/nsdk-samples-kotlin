// Copyright 2025 Niantic.
package com.nianticspatial.nsdk.externalsamples

import androidx.lifecycle.DefaultLifecycleObserver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

open class FeatureManager : DefaultLifecycleObserver {
    protected val _toasts = MutableSharedFlow<String>()
    val toasts: SharedFlow<String> = _toasts
}
