package cn.huacheng.safebaiyun

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.UnlockRepo
import cn.huacheng.safebaiyun.util.showToast
import kotlinx.coroutines.launch

/**
 *
 *@description:
 *@author: guangzhou
 *@create: 2024-05-06
 */
class ShortcutActivity : ComponentActivity() {

    private var unlockStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate action=${intent?.action}")
        setContent {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
        }
        when (intent?.action) {
            Intent.ACTION_CREATE_SHORTCUT -> createShortcut()
            ACTION_WIDGET_UNLOCK -> waitForBluetoothThenUnlock()
            else -> unlock()
        }
    }

    private fun createShortcut() {
        val intent = Intent()
        val icon = Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher)
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.unlock_door))
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, icon)
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent(this, ShortcutActivity::class.java))
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun waitForBluetoothThenUnlock() {
        if (!hasBluetoothPermission()) {
            unlock()
            return
        }

        lifecycleScope.launch {
            val enabled = awaitBluetoothEnabled(isEnabled = ::isBluetoothEnabled)
            Log.d(TAG, "Bluetooth wait finished, enabled=$enabled")
            unlock()
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return try {
            bluetoothManager.adapter?.isEnabled == true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to read Bluetooth state", error)
            false
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun unlock() {
        if (unlockStarted) {
            return
        }
        unlockStarted = true

        if (!hasBluetoothPermission() || DataRepo.readData().first.isEmpty()) {
            showToast("请先初始化")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        showToast("开始解锁门禁")
        UnlockRepo.unlock()
        finish()
    }

    companion object {
        const val ACTION_WIDGET_UNLOCK =
            "cn.huacheng.safebaiyun.action.WIDGET_UNLOCK"

        private const val TAG = "ShortcutActivity"
    }
}
