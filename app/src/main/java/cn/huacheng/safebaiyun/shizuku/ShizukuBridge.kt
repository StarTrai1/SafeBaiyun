package cn.huacheng.safebaiyun.shizuku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    // Sui has no standalone manager package; this is only used as a launch target.
    private const val MANAGER_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4301
    private const val PERMISSION_TIMEOUT_MILLIS = 60_000L

    private val mutableState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    private val permissionMutex = Mutex()
    private var applicationContext: Context? = null

    val stateFlow: StateFlow<ShizukuState>
        get() = mutableState

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        ShizukuBluetoothController.invalidate()
        mutableState.value = ShizukuState.NOT_RUNNING
    }

    fun initialize(context: Context) {
        if (applicationContext != null) {
            return
        }
        applicationContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh(context)
    }

    fun refresh(context: Context? = applicationContext): ShizukuState {
        if (context == null) {
            mutableState.value = ShizukuState.ERROR
            return mutableState.value
        }

        val binderAlive = isBinderAlive()
        val state = resolveShizukuState(
            binderAlive = binderAlive,
            preV11 = binderAlive &&
                runCatching { Shizuku.isPreV11() }.getOrDefault(true),
            permissionGranted = binderAlive && runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
            permissionDenied = binderAlive && runCatching {
                Shizuku.shouldShowRequestPermissionRationale()
            }.getOrDefault(false),
        )
        mutableState.value = state
        return state
    }

    suspend fun prepare(context: Context, launchIfStopped: Boolean): ShizukuState {
        var state = refresh(context)
        if (state == ShizukuState.NOT_RUNNING) {
            if (launchIfStopped) {
                openManager(context)
            }
            return state
        }

        if (state == ShizukuState.PERMISSION_REQUIRED) {
            val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) {
                requestPermission()
            } ?: false
            state = refresh(context)
            if (!granted && state == ShizukuState.PERMISSION_REQUIRED) {
                mutableState.value = ShizukuState.PERMISSION_DENIED
                state = ShizukuState.PERMISSION_DENIED
            }
        }
        return state
    }

    private fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun openManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE_NAME)
            ?: return false
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.e(TAG, "Unable to open Shizuku", error)
        }.getOrDefault(false)
    }

    private suspend fun requestPermission(): Boolean = permissionMutex.withLock {
        if (!isBinderAlive()) {
            return@withLock false
        }
        if (runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        ) {
            return@withLock true
        }
        if (runCatching {
                Shizuku.shouldShowRequestPermissionRationale()
            }.getOrDefault(true)
        ) {
            return@withLock false
        }

        suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == PERMISSION_REQUEST_CODE && continuation.isActive) {
                    removePermissionResultListener(listener)
                    continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            continuation.invokeOnCancellation {
                removePermissionResultListener(listener)
            }

            try {
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (error: RuntimeException) {
                removePermissionResultListener(listener)
                Log.e(TAG, "Unable to request Shizuku permission", error)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    private fun removePermissionResultListener(
        listener: Shizuku.OnRequestPermissionResultListener,
    ) {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(listener)
        }.onFailure { error ->
            Log.w(TAG, "Unable to remove Shizuku permission listener", error)
        }
    }
}
