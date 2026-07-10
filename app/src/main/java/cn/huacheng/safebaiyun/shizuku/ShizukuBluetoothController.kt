package cn.huacheng.safebaiyun.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import cn.huacheng.safebaiyun.util.ContextHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

object ShizukuBluetoothController {

    private const val TAG = "ShizukuBluetooth"
    private const val USER_SERVICE_VERSION = 1
    private const val BIND_TIMEOUT_MILLIS = 5_000L

    private val lock = Any()
    private val commandMutex = Mutex()

    @Volatile
    private var service: IShizukuBluetoothService? = null
    private var pendingBinding: CompletableDeferred<IShizukuBluetoothService>? = null

    private val userServiceArgs by lazy {
        val context = ContextHolder.get()
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuBluetoothUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("bluetooth")
            .version(USER_SERVICE_VERSION)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binder.pingBinder()) {
                failPendingBinding(IllegalStateException("Invalid Shizuku UserService binder"))
                return
            }
            val connectedService = IShizukuBluetoothService.Stub.asInterface(binder)
            synchronized(lock) {
                service = connectedService
                pendingBinding?.complete(connectedService)
                pendingBinding = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            invalidate()
        }
    }

    suspend fun setBluetoothEnabled(enabled: Boolean): Boolean = commandMutex.withLock {
        if (ShizukuBridge.refresh() != ShizukuState.READY) {
            return@withLock false
        }
        try {
            val connectedService = getService()
            withContext(Dispatchers.IO) {
                connectedService.setBluetoothEnabled(enabled)
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            Log.e(TAG, "Unable to change Bluetooth state", error)
            invalidate()
            false
        }
    }

    fun invalidate() {
        val binding = synchronized(lock) {
            service = null
            pendingBinding.also { pendingBinding = null }
        }
        binding?.completeExceptionally(
            IllegalStateException("Shizuku UserService disconnected"),
        )
    }

    private suspend fun getService(): IShizukuBluetoothService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }

        var shouldBind = false
        val binding = synchronized(lock) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            pendingBinding ?: CompletableDeferred<IShizukuBluetoothService>().also {
                pendingBinding = it
                shouldBind = true
            }
        }

        if (shouldBind) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (error: RuntimeException) {
                failPendingBinding(error)
            }
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) {
            binding.await()
        } ?: throw IllegalStateException("Timed out binding Shizuku UserService")
    }

    private fun failPendingBinding(error: Throwable) {
        val binding = synchronized(lock) {
            pendingBinding.also { pendingBinding = null }
        }
        binding?.completeExceptionally(error)
    }
}
