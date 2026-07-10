package cn.huacheng.safebaiyun

import kotlinx.coroutines.delay

internal suspend fun awaitBluetoothEnabled(
    isEnabled: () -> Boolean,
    timeoutMillis: Long = 3_000L,
    pollIntervalMillis: Long = 100L,
    elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    wait: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    require(timeoutMillis >= 0L)
    require(pollIntervalMillis > 0L)

    val startedAt = elapsedRealtimeMillis()
    while (true) {
        if (isEnabled()) {
            return true
        }

        val remainingMillis = timeoutMillis - (elapsedRealtimeMillis() - startedAt)
        if (remainingMillis <= 0L) {
            return false
        }
        wait(minOf(pollIntervalMillis, remainingMillis))
    }
}
