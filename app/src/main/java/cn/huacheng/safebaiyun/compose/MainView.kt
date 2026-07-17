package cn.huacheng.safebaiyun.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import cn.huacheng.safebaiyun.R
import cn.huacheng.safebaiyun.UnlockCoordinator
import cn.huacheng.safebaiyun.shizuku.ShizukuBridge
import cn.huacheng.safebaiyun.shizuku.ShizukuState
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.UnlockMode
import cn.huacheng.safebaiyun.unlock.UnlockModeRepo
import cn.huacheng.safebaiyun.util.showToast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(navController: NavHostController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mode by UnlockModeRepo.modeFlow.collectAsState()
    val shizukuState by ShizukuBridge.stateFlow.collectAsState()
    var unlockPreparing by remember { mutableStateOf(false) }

    val hasPermission = remember {
        mutableStateOf(false)
    }
    val showEditDialog = remember {
        mutableStateOf(false)
    }

    SideEffect {
        hasPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    Column {
        MainTopBar(
            onEditClick = { showEditDialog.value = true },
            onHelperClick = { navController.navigate("helper") },
        )
        UnlockModeSelector(
            selectedMode = mode,
            shizukuState = shizukuState,
            onModeSelected = { selectedMode ->
                UnlockModeRepo.setMode(selectedMode)
                if (selectedMode == UnlockMode.SHIZUKU) {
                    coroutineScope.launch {
                        val state = ShizukuBridge.prepare(context, launchIfStopped = true)
                        showShizukuSelectionResult(state)
                    }
                }
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission.value) {
                UnlockView(
                    enabled = !unlockPreparing,
                    onUnlock = {
                        coroutineScope.launch {
                            unlockPreparing = true
                            try {
                                UnlockCoordinator.start(
                                    context = context,
                                    waitForBluetoothInDefaultMode = false,
                                )
                            } finally {
                                unlockPreparing = false
                            }
                        }
                    },
                )
            } else {
                PermissionView(hasPermission)
            }
        }
    }

    if (showEditDialog.value) {
        EditDialog(state = showEditDialog) {
            DataRepo.readData()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockModeSelector(
    selectedMode: UnlockMode,
    shizukuState: ShizukuState,
    onModeSelected: (UnlockMode) -> Unit,
) {
    val modes = listOf(
        UnlockMode.DEFAULT to stringResource(R.string.default_mode),
        UnlockMode.SHIZUKU to stringResource(R.string.shizuku_mode),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.unlock_mode),
            style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    onClick = { onModeSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label)
                }
            }
        }
        if (selectedMode == UnlockMode.SHIZUKU) {
            Text(
                text = shizukuStatusText(shizukuState),
                color = when (shizukuState) {
                    ShizukuState.READY -> MaterialTheme.colorScheme.primary
                    ShizukuState.NOT_RUNNING,
                    ShizukuState.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun shizukuStatusText(state: ShizukuState): String = when (state) {
    ShizukuState.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
    ShizukuState.PERMISSION_REQUIRED -> stringResource(R.string.shizuku_permission_required)
    ShizukuState.PERMISSION_DENIED -> stringResource(R.string.shizuku_permission_denied)
    ShizukuState.READY -> stringResource(R.string.shizuku_ready)
    ShizukuState.UNSUPPORTED -> stringResource(R.string.shizuku_unsupported)
    ShizukuState.ERROR -> stringResource(R.string.shizuku_error)
}

private fun showShizukuSelectionResult(state: ShizukuState) {
    val message = when (state) {
        ShizukuState.NOT_RUNNING -> "请先启动 Shizuku 或 Sui 服务"
        ShizukuState.PERMISSION_DENIED -> "未授予 Shizuku/Sui 权限"
        ShizukuState.UNSUPPORTED -> "Shizuku/Sui API 版本过低"
        ShizukuState.ERROR -> "Shizuku/Sui 状态异常"
        ShizukuState.READY -> "Shizuku/Sui 模式已启用"
        ShizukuState.PERMISSION_REQUIRED -> return
    }
    showToast(message)
}

@Composable
private fun UnlockView(enabled: Boolean, onUnlock: () -> Unit) {
    Button(
        onClick = onUnlock,
        enabled = enabled,
        modifier = Modifier.size(144.dp, 56.dp),
    ) {
        Text(text = stringResource(id = R.string.unlock_door), fontSize = 18.sp)
    }
}

@Composable
private fun PermissionView(hasPermission: MutableState<Boolean>) {
    val requestPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasPermission.value = isGranted
        }

    Button(
        modifier = Modifier.size(144.dp, 56.dp),
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        },
    ) {
        Text(text = stringResource(id = R.string.request_permission), fontSize = 18.sp)
    }
}
