// Copyright 2025 Niantic.

package com.nianticspatial.nsdk.externalsamples

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationHelper(private val context: Context, private val listener: OnUpdateListener) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null

    interface OnUpdateListener {
        fun onLocationUpdate(location: Location)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (Utils.hasLocationPermissions(context)) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    listener.onLocationUpdate(location)
                }
            }

            locationRequest =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .build()
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { listener.onLocationUpdate(it) }
                }
            }

            Log.i("NSDK", "Subscribing to location updates")
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            Log.i("NSDK", "Unsubscribing to location updates")
            fusedLocationClient.removeLocationUpdates(it)
        }
    }
}
