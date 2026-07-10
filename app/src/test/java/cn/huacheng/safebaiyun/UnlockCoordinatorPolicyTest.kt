package cn.huacheng.safebaiyun

import cn.huacheng.safebaiyun.unlock.UnlockMode
import cn.huacheng.safebaiyun.unlock.UnlockResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockCoordinatorPolicyTest {

    @Test
    fun onlySuccessfulShizukuUnlockDisablesBluetooth() {
        assertTrue(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.SHIZUKU,
                UnlockResult.SUCCESS,
            ),
        )
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.DEFAULT,
                UnlockResult.SUCCESS,
            ),
        )
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.SHIZUKU,
                UnlockResult.FAILURE,
            ),
        )
        assertFalse(
            shouldDisableBluetoothAfterUnlock(
                UnlockMode.SHIZUKU,
                UnlockResult.TIMEOUT,
            ),
        )
    }
}
