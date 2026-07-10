package cn.huacheng.safebaiyun

import android.content.Intent
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
            ACTION_WIDGET_UNLOCK -> startUnlock(waitForBluetoothInDefaultMode = true)
            else -> startUnlock(waitForBluetoothInDefaultMode = false)
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

    private fun startUnlock(waitForBluetoothInDefaultMode: Boolean) {
        if (unlockStarted) {
            return
        }
        unlockStarted = true

        lifecycleScope.launch {
            val result = UnlockCoordinator.start(
                context = this@ShortcutActivity,
                waitForBluetoothInDefaultMode = waitForBluetoothInDefaultMode,
            )
            if (result == UnlockStartResult.NOT_INITIALIZED ||
                result == UnlockStartResult.MISSING_BLUETOOTH_PERMISSION
            ) {
                startActivity(Intent(this@ShortcutActivity, MainActivity::class.java))
            }
            finish()
        }
    }

    companion object {
        const val ACTION_WIDGET_UNLOCK =
            "cn.huacheng.safebaiyun.action.WIDGET_UNLOCK"

        private const val TAG = "ShortcutActivity"
    }
}
