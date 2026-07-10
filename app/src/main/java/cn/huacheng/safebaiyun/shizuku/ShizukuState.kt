package cn.huacheng.safebaiyun.shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    READY,
    UNSUPPORTED,
    ERROR,
}

internal fun resolveShizukuState(
    installed: Boolean,
    binderAlive: Boolean,
    preV11: Boolean = false,
    permissionGranted: Boolean = false,
    permissionDenied: Boolean = false,
): ShizukuState = when {
    !installed -> ShizukuState.NOT_INSTALLED
    !binderAlive -> ShizukuState.NOT_RUNNING
    preV11 -> ShizukuState.UNSUPPORTED
    permissionGranted -> ShizukuState.READY
    permissionDenied -> ShizukuState.PERMISSION_DENIED
    else -> ShizukuState.PERMISSION_REQUIRED
}
