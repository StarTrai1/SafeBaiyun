package cn.huacheng.safebaiyun

import android.content.Context
import android.util.Log
import cn.huacheng.safebaiyun.shizuku.ShizukuBluetoothController
import cn.huacheng.safebaiyun.shizuku.ShizukuBridge
import cn.huacheng.safebaiyun.shizuku.ShizukuState
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.UnlockMode
import cn.huacheng.safebaiyun.unlock.UnlockModeRepo
import cn.huacheng.safebaiyun.unlock.UnlockRepo
import cn.huacheng.safebaiyun.util.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UnlockStartResult {
    STARTED,
    BUSY,
    NOT_INITIALIZED,
    MISSING_BLUETOOTH_PERMISSION,
    SHIZUKU_NOT_READY,
}

object UnlockCoordinator {

    private const val TAG = "UnlockCoordinator"
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()

    @Volatile
    private var appOpenedBluetooth = false

    @Volatile
    private var pendingDisableJob: Job? = null

    suspend fun start(
        context: Context,
        waitForBluetoothInDefaultMode: Boolean,
    ): UnlockStartResult = startMutex.withLock {
        startInternal(context, waitForBluetoothInDefaultMode)
    }

    private suspend fun startInternal(
        context: Context,
        waitForBluetoothInDefaultMode: Boolean,
    ): UnlockStartResult {
        val applicationContext = context.applicationContext
        if (!hasBluetoothConnectPermission(applicationContext)) {
            showToast("请先授予蓝牙权限")
            return UnlockStartResult.MISSING_BLUETOOTH_PERMISSION
        }
        if (DataRepo.readData().first.isEmpty()) {
            showToast("请先初始化")
            return UnlockStartResult.NOT_INITIALIZED
        }
        if (UnlockRepo.isUnlockInProgress()) {
            showToast("正在解锁，请稍候")
            return UnlockStartResult.BUSY
        }

        val mode = UnlockModeRepo.currentMode()
        var bluetoothEnabledByApp = false
        if (mode == UnlockMode.SHIZUKU) {
            val state = ShizukuBridge.prepare(context, launchIfStopped = true)
            if (state != ShizukuState.READY) {
                showShizukuState(state)
                return UnlockStartResult.SHIZUKU_NOT_READY
            }

            pendingDisableJob?.cancel()
            pendingDisableJob = null
            // 上次的自动关闭可能失败或仍在进行：蓝牙仍归应用所有时重新拉起，保证解锁时蓝牙可用
            if (!isBluetoothEnabled(applicationContext) || appOpenedBluetooth) {
                val requested = ShizukuBluetoothController.setBluetoothEnabled(true)
                if (!requested) {
                    showToast("Shizuku 打开蓝牙失败")
                    return UnlockStartResult.SHIZUKU_NOT_READY
                }
                appOpenedBluetooth = true
            }
            bluetoothEnabledByApp = appOpenedBluetooth
            val enabled = awaitBluetoothEnabled(
                isEnabled = { isBluetoothEnabled(applicationContext) },
            )
            Log.d(TAG, "Shizuku Bluetooth enable wait finished, enabled=$enabled")
            if (!enabled) {
                showToast("蓝牙开启超时，继续尝试解锁")
            }
        } else if (waitForBluetoothInDefaultMode) {
            val enabled = awaitBluetoothEnabled(
                isEnabled = { isBluetoothEnabled(applicationContext) },
            )
            Log.d(TAG, "Default Bluetooth wait finished, enabled=$enabled")
        }

        showToast("开始解锁门禁")
        val started = UnlockRepo.unlock { _ ->
            if (shouldDisableBluetoothAfterUnlock(mode, bluetoothEnabledByApp)) {
                pendingDisableJob = applicationScope.launch {
                    closeBluetoothAfterUnlock(applicationContext)
                }
            }
        }
        return if (started) {
            UnlockStartResult.STARTED
        } else {
            UnlockStartResult.BUSY
        }
    }

    private suspend fun closeBluetoothAfterUnlock(context: Context) {
        val requested = ShizukuBluetoothController.setBluetoothEnabled(false)
        if (!requested) {
            showToast("Shizuku 关闭蓝牙失败")
            return
        }
        val disabled = awaitBluetoothEnabled(
            isEnabled = { !isBluetoothEnabled(context) },
        )
        Log.d(TAG, "Shizuku Bluetooth disable wait finished, disabled=$disabled")
        if (!disabled) {
            showToast("蓝牙自动关闭失败")
            return
        }
        appOpenedBluetooth = false
    }

    private fun showShizukuState(state: ShizukuState) {
        val message = when (state) {
            ShizukuState.NOT_INSTALLED -> "未安装 Shizuku"
            ShizukuState.NOT_RUNNING -> "请先启动 Shizuku"
            ShizukuState.PERMISSION_REQUIRED -> "等待 Shizuku 授权"
            ShizukuState.PERMISSION_DENIED -> "未授予 Shizuku 权限"
            ShizukuState.UNSUPPORTED -> "Shizuku 版本过低"
            ShizukuState.ERROR -> "Shizuku 状态异常"
            ShizukuState.READY -> return
        }
        showToast(message)
    }
}

internal fun shouldDisableBluetoothAfterUnlock(
    mode: UnlockMode,
    bluetoothEnabledByApp: Boolean,
): Boolean = mode == UnlockMode.SHIZUKU && bluetoothEnabledByApp
