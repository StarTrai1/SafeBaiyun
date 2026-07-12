package cn.huacheng.safebaiyun

import cn.huacheng.safebaiyun.unlock.UnlockMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockCoordinatorPolicyTest {

    @Test
    fun appOpenedBluetoothIsDisabledAfterShizukuUnlock() {
        assertTrue(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.SHIZUKU,
                bluetoothEnabledByApp = true,
            ),
        )
    }

    @Test
    fun manuallyEnabledBluetoothStaysEnabledAfterShizukuUnlock() {
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.SHIZUKU,
                bluetoothEnabledByApp = false,
            ),
        )
    }

    @Test
    fun defaultModeNeverDisablesBluetooth() {
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.DEFAULT,
                bluetoothEnabledByApp = true,
            ),
        )
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.DEFAULT,
                bluetoothEnabledByApp = false,
            ),
        )
    }
}
