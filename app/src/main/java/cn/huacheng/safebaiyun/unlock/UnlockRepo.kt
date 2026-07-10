package cn.huacheng.safebaiyun.unlock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import cn.huacheng.safebaiyun.util.ContextHolder
import cn.huacheng.safebaiyun.util.LockBiz
import cn.huacheng.safebaiyun.util.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

enum class UnlockResult {
    SUCCESS,
    FAILURE,
    TIMEOUT,
}

@SuppressLint("MissingPermission")
object UnlockRepo {

    private const val TAG = "UnlockRepo"
    private const val MAGIC_SERVICE = "14839ac4-7d7e-415c-9a42-167340cf2339"
    private const val UNLOCK_TIMEOUT_MILLIS = 10_000L
    private const val READ_RETRY_DELAY_MILLIS = 200L
    private const val MAX_READ_ATTEMPTS = 3
    private val CLIENT_CHARACTERISTIC_CONFIGURATION =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val operationLock = Any()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var unlockInProgress = false
    private var activeGatt: BluetoothGatt? = null
    private var readableCharacteristic: BluetoothGattCharacteristic? = null
    private var writeableCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var config: Pair<String, String>
    private var completion: ((UnlockResult) -> Unit)? = null
    private var autoDisconnectJob: Job? = null
    private var readRetryJob: Job? = null
    private var readAttemptCount = 0
    private var readCompleted = false
    private val pendingDescriptorWrites = ArrayDeque<DescriptorWriteRequest>()

    private data class DescriptorWriteRequest(
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray,
    )

    private val logList = mutableListOf("Hello World")
    val logFlow: MutableStateFlow<List<String>> = MutableStateFlow(logList.toList())

    fun unlock(onComplete: (UnlockResult) -> Unit = {}): Boolean {
        synchronized(operationLock) {
            if (unlockInProgress) {
                showToast("正在解锁，请稍候")
                return false
            }
            unlockInProgress = true
            completion = onComplete
            activeGatt = null
            readableCharacteristic = null
            writeableCharacteristic = null
            pendingDescriptorWrites.clear()
            readAttemptCount = 0
            readCompleted = false
            readRetryJob?.cancel()
            readRetryJob = null
        }

        val bluetoothManager = ContextHolder.get()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = try {
            bluetoothManager?.adapter
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to obtain Bluetooth adapter", error)
            null
        }

        if (bluetoothAdapter == null) {
            log("蓝牙适配器不可用")
            showToast("蓝牙服务暂不可用")
            finishUnlock(UnlockResult.FAILURE)
            return true
        }

        config = DataRepo.readData()
        if (!BluetoothAdapter.checkBluetoothAddress(config.first)) {
            showToast("Mac地址格式错误")
            finishUnlock(UnlockResult.FAILURE)
            return true
        }

        val newGatt = connect(bluetoothAdapter)
        if (newGatt == null) {
            finishUnlock(UnlockResult.FAILURE)
            return true
        }
        val operationActive = synchronized(operationLock) {
            if (unlockInProgress) {
                if (activeGatt == null) {
                    activeGatt = newGatt
                }
                activeGatt === newGatt
            } else {
                false
            }
        }
        if (!operationActive) {
            runCatching { newGatt.close() }
            return true
        }

        val timeoutJob = operationScope.launch {
            delay(UNLOCK_TIMEOUT_MILLIS)
            if (isActive && unlockInProgress) {
                log("10s超时，自动断开连接")
                showToast("解锁超时")
                finishUnlock(UnlockResult.TIMEOUT)
            }
        }
        synchronized(operationLock) {
            if (unlockInProgress && activeGatt === newGatt) {
                autoDisconnectJob?.cancel()
                autoDisconnectJob = timeoutJob
            } else {
                timeoutJob.cancel()
            }
        }
        return true
    }

    fun isUnlockInProgress(): Boolean = unlockInProgress

    private fun connect(bluetoothAdapter: BluetoothAdapter): BluetoothGatt? {
        val newGatt = try {
            val remoteDevice = bluetoothAdapter.getRemoteDevice(config.first)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                remoteDevice.connectGatt(
                    ContextHolder.get(),
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } else {
                remoteDevice.connectGatt(
                    ContextHolder.get(),
                    false,
                    callback,
                )
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start GATT connection", error)
            null
        }

        if (newGatt == null) {
            log("创建蓝牙连接失败")
            showToast("蓝牙连接失败，请稍后重试")
            return null
        }
        log("尝试连接蓝牙")
        return newGatt
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            log("连接状态改变 status$status,newState$newState")
            if (!isActiveGatt(gatt)) {
                runCatching { gatt?.close() }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                showToast("蓝牙连接失败")
                finishUnlock(UnlockResult.FAILURE, gatt)
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    log("开始搜索服务")
                    val started = runCatching { gatt?.discoverServices() == true }
                        .getOrDefault(false)
                    if (!started) {
                        showToast("门禁服务搜索失败")
                        finishUnlock(UnlockResult.FAILURE, gatt)
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    showToast("蓝牙连接已断开")
                    finishUnlock(UnlockResult.FAILURE, gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (!isActiveGatt(gatt)) {
                return
            }
            log("搜索服务完成 $status")
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                showToast("门禁服务搜索失败")
                finishUnlock(UnlockResult.FAILURE, gatt)
                return
            }
            log("搜索到以下服务：${gatt.services?.map { it.uuid }?.joinToString(",")}")
            handleService(gatt, gatt.services?.find { it.uuid.toString() == MAGIC_SERVICE })
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicReadResult(gatt, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            handleCharacteristicReadResult(gatt, characteristic?.value, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int,
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (!isActiveGatt(gatt) || gatt == null) {
                return
            }
            log("通知描述符写入回调 ${descriptor?.uuid},status:$status")
            startNextSetupOperation(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (!isActiveGatt(gatt)) {
                return
            }
            log("特征码写入回调 $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                showToast("开门成功")
                finishUnlock(UnlockResult.SUCCESS, gatt)
            } else {
                showToast("密钥写入失败")
                finishUnlock(UnlockResult.FAILURE, gatt)
            }
        }
    }

    private fun handleService(gatt: BluetoothGatt, service: BluetoothGattService?) {
        if (service == null) {
            showToast("未找到门禁服务")
            finishUnlock(UnlockResult.FAILURE, gatt)
            return
        }

        log("开始处理服务，共${service.characteristics.size}个特征")
        val notificationCharacteristics = mutableListOf<BluetoothGattCharacteristic>()
        var readable: BluetoothGattCharacteristic? = null
        var writeable: BluetoothGattCharacteristic? = null
        service.characteristics.forEach { characteristic ->
            log("特征${characteristic.uuid},prop:${characteristic.properties}")
            val properties = characteristic.properties
            if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                readable = characteristic
            }
            if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                writeable = characteristic
            }
            if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            ) {
                notificationCharacteristics.add(characteristic)
            }
        }

        val readableCharacteristic = readable
        val writableCharacteristic = writeable
        if (readableCharacteristic == null || writableCharacteristic == null) {
            showToast("门禁蓝牙特征不可用")
            finishUnlock(UnlockResult.FAILURE, gatt)
            return
        }
        val descriptorWrites = prepareNotificationDescriptorWrites(
            gatt,
            notificationCharacteristics,
        )
        synchronized(operationLock) {
            if (!unlockInProgress || activeGatt !== gatt) {
                return
            }
            this.readableCharacteristic = readableCharacteristic
            writeableCharacteristic = writableCharacteristic
            pendingDescriptorWrites.clear()
            pendingDescriptorWrites.addAll(descriptorWrites)
            readAttemptCount = 0
            readCompleted = false
        }
        startNextSetupOperation(gatt)
    }

    private fun prepareNotificationDescriptorWrites(
        gatt: BluetoothGatt,
        notificationCharacteristics: List<BluetoothGattCharacteristic>,
    ): List<DescriptorWriteRequest> {
        val writes = mutableListOf<DescriptorWriteRequest>()
        notificationCharacteristics.forEach { characteristic ->
            val notificationEnabled = runCatching {
                gatt.setCharacteristicNotification(characteristic, true)
            }.getOrDefault(false)
            log("启用特征通知 ${characteristic.uuid}:$notificationEnabled")
            if (!notificationEnabled) {
                return@forEach
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION)
            if (descriptor == null) {
                log("特征 ${characteristic.uuid} 未提供通知描述符")
                return@forEach
            }
            val value = if (
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            ) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writes.add(DescriptorWriteRequest(descriptor, value))
        }
        return writes
    }

    private fun startNextSetupOperation(gatt: BluetoothGatt) {
        while (isActiveGatt(gatt)) {
            val request = synchronized(operationLock) {
                if (!unlockInProgress || activeGatt !== gatt) {
                    return
                }
                pendingDescriptorWrites.pollFirst()
            }
            if (request == null) {
                startCharacteristicRead(gatt)
                return
            }

            val started = writeDescriptor(gatt, request)
            log("通知描述符写入 ${request.descriptor.uuid}:$started")
            if (started) {
                return
            }
            log("通知描述符写入未启动，继续后续操作")
        }
    }

    private fun writeDescriptor(
        gatt: BluetoothGatt,
        request: DescriptorWriteRequest,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(request.descriptor, request.value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            request.descriptor.value = request.value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(request.descriptor)
        }
    }.onFailure { error ->
        Log.e(TAG, "Unable to write notification descriptor", error)
    }.getOrDefault(false)

    private fun startCharacteristicRead(gatt: BluetoothGatt) {
        val characteristic = synchronized(operationLock) {
            if (!unlockInProgress || activeGatt !== gatt || readCompleted) {
                return
            }
            readAttemptCount++
            readableCharacteristic
        }
        if (characteristic == null) {
            showToast("门禁蓝牙特征不可用")
            finishUnlock(UnlockResult.FAILURE, gatt)
            return
        }

        val started = runCatching { gatt.readCharacteristic(characteristic) }
            .onFailure { error -> Log.e(TAG, "Unable to read access-control data", error) }
            .getOrDefault(false)
        log("特征读取启动 第${readAttemptCount}次:$started")
        if (!started) {
            retryCharacteristicRead(gatt, "读取请求未启动")
        }
    }

    private fun handleCharacteristicReadResult(
        gatt: BluetoothGatt?,
        value: ByteArray?,
        status: Int,
    ) {
        if (!isActiveGatt(gatt) || gatt == null) {
            return
        }
        log("特征码读取回调 status:$status,size:${value?.size ?: -1}")
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            val retryJob = synchronized(operationLock) {
                if (!unlockInProgress || activeGatt !== gatt || readCompleted) {
                    return
                }
                readCompleted = true
                readRetryJob.also { readRetryJob = null }
            }
            retryJob?.cancel()
            handleCharacteristicWrite(gatt, value)
            return
        }
        retryCharacteristicRead(gatt, "读取回调失败 status:$status")
    }

    private fun retryCharacteristicRead(gatt: BluetoothGatt, reason: String) {
        val canRetry = synchronized(operationLock) {
            if (!unlockInProgress || activeGatt !== gatt || readCompleted) {
                return
            }
            readAttemptCount < MAX_READ_ATTEMPTS
        }
        if (!canRetry) {
            log("$reason，已达到最大重试次数")
            showToast("门禁数据读取失败")
            finishUnlock(UnlockResult.FAILURE, gatt)
            return
        }

        log("$reason，${READ_RETRY_DELAY_MILLIS}ms后重试")
        val retryJob = operationScope.launch {
            delay(READ_RETRY_DELAY_MILLIS)
            if (isActive && isActiveGatt(gatt)) {
                startCharacteristicRead(gatt)
            }
        }
        synchronized(operationLock) {
            if (unlockInProgress && activeGatt === gatt && !readCompleted) {
                readRetryJob?.cancel()
                readRetryJob = retryJob
            } else {
                retryJob.cancel()
            }
        }
    }

    private fun handleCharacteristicWrite(gatt: BluetoothGatt, value: ByteArray) {
        val characteristic = writeableCharacteristic
        if (characteristic == null) {
            showToast("门禁写入特征不可用")
            finishUnlock(UnlockResult.FAILURE, gatt)
            return
        }

        val result = runCatching {
            log("开始写入密钥")
            val key = LockBiz.encryptData(
                value,
                LockBiz.hexToByteArray(config.first),
                config.second,
            )
            log(key.joinToString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    key,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = key
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to write unlock key", error)
        }.getOrDefault(false)
        log("密钥写入结果 $result")
        if (!result) {
            showToast("密钥写入失败")
            finishUnlock(UnlockResult.FAILURE, gatt)
        }
    }

    private fun isActiveGatt(gatt: BluetoothGatt?): Boolean {
        if (gatt == null) {
            return false
        }
        return synchronized(operationLock) {
            unlockInProgress && gatt === activeGatt
        }
    }

    private fun finishUnlock(result: UnlockResult, callbackGatt: BluetoothGatt? = null) {
        val completionData = synchronized(operationLock) {
            if (!unlockInProgress) {
                return
            }
            if (callbackGatt != null && activeGatt != null && callbackGatt !== activeGatt) {
                return
            }

            val data = Triple(activeGatt ?: callbackGatt, completion, readRetryJob)
            unlockInProgress = false
            activeGatt = null
            readableCharacteristic = null
            writeableCharacteristic = null
            completion = null
            pendingDescriptorWrites.clear()
            readAttemptCount = 0
            readCompleted = false
            readRetryJob = null
            data
        }

        completionData.third?.cancel()
        autoDisconnectJob?.cancel()
        autoDisconnectJob = null
        completionData.first?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
                .onFailure { error -> Log.e(TAG, "Unable to close GATT", error) }
        }
        completionData.second?.invoke(result)
    }

    private fun log(msg: String) {
        println(msg)
    }
}
