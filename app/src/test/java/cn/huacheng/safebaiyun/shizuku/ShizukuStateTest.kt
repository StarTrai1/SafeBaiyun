package cn.huacheng.safebaiyun.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {

    @Test
    fun unavailableBinderReportsServiceNotRunning() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            resolveShizukuState(binderAlive = false),
        )
    }

    @Test
    fun runningServiceReportsPermissionAndVersionStates() {
        assertEquals(
            ShizukuState.UNSUPPORTED,
            resolveShizukuState(binderAlive = true, preV11 = true),
        )
        assertEquals(
            ShizukuState.PERMISSION_REQUIRED,
            resolveShizukuState(binderAlive = true),
        )
        assertEquals(
            ShizukuState.PERMISSION_DENIED,
            resolveShizukuState(
                binderAlive = true,
                permissionDenied = true,
            ),
        )
        assertEquals(
            ShizukuState.READY,
            resolveShizukuState(
                binderAlive = true,
                permissionGranted = true,
            ),
        )
    }
}
