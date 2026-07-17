package cn.huacheng.safebaiyun.shizuku

enum class ShizukuState {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    READY,
    UNSUPPORTED,
    ERROR,
}

internal fun resolveShizukuState(
    binderAlive: Boolean,
    preV11: Boolean = false,
    permissionGranted: Boolean = false,
    permissionDenied: Boolean = false,
): ShizukuState = when {
    !binderAlive -> ShizukuState.NOT_RUNNING
    preV11 -> ShizukuState.UNSUPPORTED
    permissionGranted -> ShizukuState.READY
    permissionDenied -> ShizukuState.PERMISSION_DENIED
    else -> ShizukuState.PERMISSION_REQUIRED
}
