package cn.huacheng.safebaiyun.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlin.system.exitProcess

@Keep
class ShizukuBluetoothUserService() : IShizukuBluetoothService.Stub() {

    @Keep
    constructor(context: Context) : this() {
        Log.d(TAG, "Created for ${context.packageName}")
    }

    override fun setBluetoothEnabled(enabled: Boolean): Boolean {
        val action = if (enabled) "enable" else "disable"
        return try {
            val process = ProcessBuilder("/system/bin/svc", "bluetooth", action)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            Log.d(TAG, "svc bluetooth $action exited $exitCode: ${output.take(500)}")
            exitCode == 0
        } catch (error: Exception) {
            Log.e(TAG, "Unable to $action Bluetooth", error)
            false
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        private const val TAG = "ShizukuBluetoothService"
    }
}
