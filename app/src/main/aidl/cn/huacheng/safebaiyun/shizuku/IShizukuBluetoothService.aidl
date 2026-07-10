package cn.huacheng.safebaiyun.shizuku;

interface IShizukuBluetoothService {
    boolean setBluetoothEnabled(boolean enabled) = 1;
    void destroy() = 16777114;
}
