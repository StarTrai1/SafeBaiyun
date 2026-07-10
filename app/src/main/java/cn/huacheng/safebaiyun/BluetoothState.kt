package cn.huacheng.safebaiyun

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

internal fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
internal fun isBluetoothEnabled(context: Context): Boolean {
    if (!hasBluetoothConnectPermission(context)) {
        return false
    }
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        ?: return false
    return try {
        manager.adapter?.isEnabled == true
    } catch (error: RuntimeException) {
        Log.w("BluetoothState", "Unable to read Bluetooth state", error)
        false
    }
}
