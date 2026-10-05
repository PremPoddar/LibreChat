package com.prempoddar.librechat.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Which runtime permissions each part of the mesh needs on this Android version. */
object Permissions {
    val bluetooth: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Android 10–11 only let apps scan for Bluetooth devices with location permission.
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val wifi: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val notifications: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun granted(context: Context, perms: List<String>) =
        perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun has(context: Context, perm: String) = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
