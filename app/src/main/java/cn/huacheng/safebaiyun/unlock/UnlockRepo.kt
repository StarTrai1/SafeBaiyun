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

    private val operationLock = Any()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var unlockInProgress = false
    private var activeGatt: BluetoothGatt? = null
    private var writeableCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var config: Pair<String, String>
    private var completion: ((UnlockResult) -> Unit)? = null
    private var autoDisconnectJob: Job? = null

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
            writeableCharacteristic = null
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
            if (!isActiveGatt(gatt)) {
                return
            }
            log("特征码读取回调 $status,${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicWrite(gatt, value)
            } else {
                showToast("门禁数据读取失败")
                finishUnlock(UnlockResult.FAILURE, gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            if (!isActiveGatt(gatt)) {
                return
            }
            val value = characteristic?.value
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || gatt == null) {
                showToast("门禁数据读取失败")
                finishUnlock(UnlockResult.FAILURE, gatt)
                return
            }
            log("特征码读取回调 $status,${value.size}")
            handleCharacteristicWrite(gatt, value)
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
        writeableCharacteristic = writableCharacteristic
        handleCharacteristics(gatt, notificationCharacteristics, readableCharacteristic)
    }

    private fun handleCharacteristics(
        gatt: BluetoothGatt,
        notificationCharacteristics: List<BluetoothGattCharacteristic>,
        readable: BluetoothGattCharacteristic,
    ) {
        log("开始处理特征,读取门禁数据")
        notificationCharacteristics.forEach { characteristic ->
            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.descriptors.forEach { descriptor ->
                if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                gatt.writeDescriptor(descriptor)
            }
        }

        val result = runCatching { gatt.readCharacteristic(readable) }.getOrDefault(false)
        log("特征读取结果 $result")
        if (!result) {
            showToast("门禁数据读取失败")
            finishUnlock(UnlockResult.FAILURE, gatt)
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
            characteristic.value = key
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
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

            val data = (activeGatt ?: callbackGatt) to completion
            unlockInProgress = false
            activeGatt = null
            writeableCharacteristic = null
            completion = null
            data
        }

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
