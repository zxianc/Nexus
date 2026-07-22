package com.nexus.phone.nexus.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * During cellular voice calls, the default route often has no general Internet.
 * Bind the process to Wi‑Fi (preferred) or any validated Internet network for LLM.
 */
object NetworkBinder {
    private const val TAG = "NetworkBinder"

    fun bindBestInternet(context: Context): Boolean {
        val cm =
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?: return false
        val wifi = find(cm, preferWifi = true)
        val any = wifi ?: find(cm, preferWifi = false)
        if (any == null) {
            Log.w(TAG, "no validated Internet network")
            return false
        }
        val ok = cm.bindProcessToNetwork(any)
        val caps = cm.getNetworkCapabilities(any)
        Log.i(
            TAG,
            "bindProcessToNetwork=$ok wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                "cell=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}",
        )
        return ok
    }

    fun clear(context: Context) {
        val cm =
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?: return
        cm.bindProcessToNetwork(null)
        Log.i(TAG, "bindProcessToNetwork cleared")
    }

    private fun find(cm: ConnectivityManager, preferWifi: Boolean): Network? {
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@firstOrNull false
            // VALIDATED is ideal; some OEMs skip it — still accept INTERNET.
            if (preferWifi) {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                true
            }
        }
    }
}
