package com.nexus.phone.nexus.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * During cellular voice calls, the default route often has no general Internet.
 * Bind the process to Wi‑Fi (preferred) or any Internet network for LLM.
 *
 * Never throws — missing permission / OEM quirks must not break the PCM session.
 */
object NetworkBinder {
    private const val TAG = "NetworkBinder"

    fun bindBestInternet(context: Context): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "ACCESS_NETWORK_STATE missing; skip bind")
                return false
            }
            val cm =
                context.applicationContext.getSystemService(ConnectivityManager::class.java)
                    ?: return false
            val wifi = find(cm, preferWifi = true)
            val any = wifi ?: find(cm, preferWifi = false)
            if (any == null) {
                Log.w(TAG, "no Internet network to bind")
                return false
            }
            val ok = cm.bindProcessToNetwork(any)
            val caps = cm.getNetworkCapabilities(any)
            Log.i(
                TAG,
                "bindProcessToNetwork=$ok wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                    "cell=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}",
            )
            ok
        } catch (e: Exception) {
            Log.w(TAG, "bindBestInternet failed: ${e.message}")
            false
        }
    }

    fun clear(context: Context) {
        try {
            val cm =
                context.applicationContext.getSystemService(ConnectivityManager::class.java)
                    ?: return
            cm.bindProcessToNetwork(null)
            Log.i(TAG, "bindProcessToNetwork cleared")
        } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    private fun find(cm: ConnectivityManager, preferWifi: Boolean): Network? {
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@firstOrNull false
            if (preferWifi) {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                true
            }
        }
    }
}
