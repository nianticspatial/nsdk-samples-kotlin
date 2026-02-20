// Copyright 2025 Niantic.

package com.nianticspatial.nsdk.externalsamples

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.UnavailableException

class Utils {
    companion object {
        private fun hasPermissions(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(
                context, permission
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasCameraPermissions(context: Context): Boolean {
            return hasPermissions(context, Manifest.permission.CAMERA)
        }

        fun hasLocationPermissions(context: Context): Boolean {
            return hasPermissions(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun shouldShowRequestPermissionRationale(
            activity: Activity,
        ) {
            if (!hasCameraPermissions(activity) || !hasLocationPermissions(activity)) {
                Toast.makeText(
                    activity,
                    "Camera, storage and location permissions are needed to run this application",
                    Toast.LENGTH_SHORT
                ).show()
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.CAMERA
                    ) || ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) || ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.fromParts("package", activity.packageName, null)
                    activity.startActivity(intent)
                }
            }
        }

        fun isARCoreSupportedAndUpToDate(activity: Activity, askUserToInstall: Boolean): Boolean {
            return when (ArCoreApk.getInstance().checkAvailability(activity)) {
                Availability.SUPPORTED_INSTALLED -> true
                Availability.SUPPORTED_APK_TOO_OLD, Availability.SUPPORTED_NOT_INSTALLED -> {
                    try {
                        // Request ARCore installation or update if needed.
                        when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                            InstallStatus.INSTALL_REQUESTED -> {
                                Log.i("NSDK", "ARCore installation requested.")
                                false
                            }

                            InstallStatus.INSTALLED -> true
                        }
                    } catch (e: UnavailableException) {
                        Log.e("NSDK", "ARCore not installed", e)
                        false
                    }
                }

                Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    // This device is not supported for AR.
                    Toast.makeText(
                        activity,
                        "This device does not support ARCore",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }

                Availability.UNKNOWN_CHECKING -> {
                    Toast.makeText(
                        activity,
                        "Unable to check for ARCore installation, try again later",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }

                Availability.UNKNOWN_ERROR, Availability.UNKNOWN_TIMED_OUT -> {
                    Toast.makeText(
                        activity,
                        "Try again when connected to internet",
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }
    }
}
