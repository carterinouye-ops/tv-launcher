package com.carter.glasslauncher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

/**
 * @param ssid the network name, or null when connected but the name can't be read.
 * @param ssidBlockedReason non-null when the SSID was withheld rather than absent, so the
 *        caller can tell "not on Wi-Fi" apart from "on Wi-Fi but not allowed to say which".
 */
data class WifiStatus(
    val connected: Boolean,
    val ssid: String?,
    val ssidBlockedReason: String? = null
)

/**
 * Reads the active Wi-Fi network name.
 *
 * Since Android 10 the SSID is treated as location-adjacent data: WifiInfo.getSSID()
 * returns the literal string "<unknown ssid>" unless the caller holds ACCESS_FINE_LOCATION
 * *and* system location services are switched on. Both conditions are reported back so the
 * UI can fall back to a plain "Wi-Fi" label instead of showing a placeholder.
 */
object WifiInfoUtil {

    private const val UNKNOWN_SSID = "<unknown ssid>"

    fun current(context: Context): WifiStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connected = cm?.let {
            val caps = it.activeNetwork?.let(it::getNetworkCapabilities)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: false

        if (!connected) return WifiStatus(false, null)

        if (!hasLocationPermission(context)) {
            return WifiStatus(true, null, "missing ACCESS_FINE_LOCATION")
        }
        if (!isLocationEnabled(context)) {
            return WifiStatus(true, null, "system location services are off")
        }

        val ssid = try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo?.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
        } catch (e: Exception) {
            null
        }

        return WifiStatus(true, ssid, if (ssid == null) "SSID withheld by platform" else null)
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            lm.isLocationEnabled
        } catch (e: Exception) {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }
}
