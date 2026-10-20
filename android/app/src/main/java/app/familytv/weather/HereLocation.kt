package app.familytv.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import app.familytv.user.WeatherPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * "Use my location" for the weather: the platform's approximate location only (coarse permission,
 * no IP lookup), rounded to 2 decimals (about 1 km) before it leaves the TV, and named by the
 * device's own geocoder when it has one, else "Your location". Many Android TV boxes have no
 * location at all; then this returns null.
 */
object HereLocation {
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun find(context: Context): WeatherPlace? = withContext(Dispatchers.IO) {
        if (!allowed(context)) return@withContext null
        val lm = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val loc = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?: current(lm, providers)
            ?: return@withContext null
        val lat = round2(loc.latitude)
        val lon = round2(loc.longitude)
        val address = address(context, lat, lon)
        WeatherPlace(
            address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: "Your location",
            address?.countryName.orEmpty(),
            lat, lon,
        )
    }

    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    /** A fresh fix from the network provider (Android 11+), waiting at most 10 s. */
    @SuppressLint("MissingPermission")
    private suspend fun current(lm: LocationManager, providers: List<String>): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = LocationManager.NETWORK_PROVIDER.takeIf { it in providers } ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching { lm.getCurrentLocation(provider, cancel, Runnable::run) { if (cont.isActive) cont.resume(it) } }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /** The place's name from the device's geocoder; null when it has none or it doesn't answer in 5 s. */
    private suspend fun address(context: Context, lat: Double, lon: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return withTimeoutOrNull(5_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                            override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}
