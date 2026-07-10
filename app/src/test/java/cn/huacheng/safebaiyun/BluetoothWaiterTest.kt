package cn.huacheng.safebaiyun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothWaiterTest {

    @Test
    fun returnsImmediatelyWhenBluetoothIsAlreadyEnabled() = runBlocking {
        var nowMillis = 0L
        var waitCount = 0

        val enabled = awaitBluetoothEnabled(
            isEnabled = { true },
            elapsedRealtimeMillis = { nowMillis },
            wait = {
                waitCount++
                nowMillis += it
            },
        )

        assertTrue(enabled)
        assertEquals(0, waitCount)
        assertEquals(0L, nowMillis)
    }

    @Test
    fun returnsAsSoonAsBluetoothBecomesEnabled() = runBlocking {
        var nowMillis = 0L

        val enabled = awaitBluetoothEnabled(
            isEnabled = { nowMillis >= 700L },
            elapsedRealtimeMillis = { nowMillis },
            wait = { nowMillis += it },
        )

        assertTrue(enabled)
        assertEquals(700L, nowMillis)
    }

    @Test
    fun returnsAfterThreeSecondsWhenBluetoothStaysDisabled() = runBlocking {
        var nowMillis = 0L

        val enabled = awaitBluetoothEnabled(
            isEnabled = { false },
            elapsedRealtimeMillis = { nowMillis },
            wait = { nowMillis += it },
        )

        assertFalse(enabled)
        assertEquals(3_000L, nowMillis)
    }
}
