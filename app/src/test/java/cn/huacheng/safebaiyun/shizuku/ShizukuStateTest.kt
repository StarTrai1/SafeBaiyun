package cn.huacheng.safebaiyun.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {

    @Test
    fun packageAndBinderStateTakePriority() {
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            resolveShizukuState(installed = false, binderAlive = false),
        )
        assertEquals(
            ShizukuState.NOT_RUNNING,
            resolveShizukuState(installed = true, binderAlive = false),
        )
    }

    @Test
    fun runningServiceReportsPermissionAndVersionStates() {
        assertEquals(
            ShizukuState.UNSUPPORTED,
            resolveShizukuState(installed = true, binderAlive = true, preV11 = true),
        )
        assertEquals(
            ShizukuState.PERMISSION_REQUIRED,
            resolveShizukuState(installed = true, binderAlive = true),
        )
        assertEquals(
            ShizukuState.PERMISSION_DENIED,
            resolveShizukuState(
                installed = true,
                binderAlive = true,
                permissionDenied = true,
            ),
        )
        assertEquals(
            ShizukuState.READY,
            resolveShizukuState(
                installed = true,
                binderAlive = true,
                permissionGranted = true,
            ),
        )
    }
}
