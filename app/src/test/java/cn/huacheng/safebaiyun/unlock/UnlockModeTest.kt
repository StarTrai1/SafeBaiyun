package cn.huacheng.safebaiyun.unlock

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockModeTest {

    @Test
    fun missingOrUnknownStoredValueUsesDefaultMode() {
        assertEquals(UnlockMode.DEFAULT, UnlockMode.fromStoredValue(null))
        assertEquals(UnlockMode.DEFAULT, UnlockMode.fromStoredValue(""))
        assertEquals(UnlockMode.DEFAULT, UnlockMode.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun storedShizukuValueRestoresShizukuMode() {
        assertEquals(UnlockMode.SHIZUKU, UnlockMode.fromStoredValue("SHIZUKU"))
    }
}
