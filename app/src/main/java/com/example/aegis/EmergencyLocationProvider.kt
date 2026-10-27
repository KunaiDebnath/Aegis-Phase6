package com.example.aegis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface LocationResult {
    data class Success(val location: EmergencyLocation, val isFresh: Boolean = true) : LocationResult
    data class Unavailable(val reason: String) : LocationResult
    object Timeout : LocationResult
    object PermissionDenied : LocationResult
}

class EmergencyLocationProvider(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    fun isLocationServiceEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false

        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Attempts a one-time fresh location acquisition with a strict timeout.
     * If fresh high-accuracy location cannot be obtained within the timeout,
     * attempts fallback to recent lastLocation (only if less than 5 minutes old),
     * otherwise returns Timeout or Unavailable.
     */
    suspend fun getCurrentLocation(timeoutMillis: Long = 7000L): LocationResult {
        return withContext(Dispatchers.IO) {
            if (!hasLocationPermission()) {
                return@withContext LocationResult.PermissionDenied
            }

            if (!isLocationServiceEnabled()) {
                return@withContext LocationResult.Unavailable("Location services (GPS) are disabled on device.")
            }

            val result = withTimeoutOrNull(timeoutMillis) {
                requestFreshLocation()
            }

            if (result != null) {
                return@withContext result
            }

            // If fresh fix timed out, check if a recent last known location is available
            val fallback = getRecentLastLocation()
            if (fallback != null) {
                return@withContext LocationResult.Success(fallback, isFresh = false)
            }

            LocationResult.Timeout
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(): LocationResult {
        val cancellationTokenSource = CancellationTokenSource()

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null && EmergencyUtils.isValidCoordinate(location.latitude, location.longitude)) {
                    val emergencyLoc = EmergencyLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        timestamp = location.time
                    )
                    continuation.resume(LocationResult.Success(emergencyLoc, isFresh = true))
                } else {
                    continuation.resume(LocationResult.Unavailable("GPS fix returned null or invalid coordinates."))
                }
            }.addOnFailureListener { exception ->
                continuation.resume(LocationResult.Unavailable(exception.message ?: "Failed to acquire location fix."))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getRecentLastLocation(): EmergencyLocation? {
        return suspendCancellableCoroutine { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null && EmergencyUtils.isValidCoordinate(location.latitude, location.longitude)) {
                        // Accept lastLocation only if reasonably recent (less than 5 minutes old)
                        val elapsedMillis = SystemClock.elapsedRealtime() - (location.elapsedRealtimeNanos / 1_000_000)
                        if (elapsedMillis in 0..300_000) {
                            val emergencyLoc = EmergencyLocation(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                                timestamp = location.time
                            )
                            continuation.resume(emergencyLoc)
                            return@addOnSuccessListener
                        }
                    }
                    continuation.resume(null)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}
