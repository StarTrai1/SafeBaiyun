package cn.huacheng.safebaiyun.unlock

import android.content.Context
import androidx.core.content.edit
import cn.huacheng.safebaiyun.util.ContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UnlockMode {
    DEFAULT,
    SHIZUKU;

    companion object {
        internal fun fromStoredValue(value: String?): UnlockMode =
            values().firstOrNull { it.name == value } ?: DEFAULT
    }
}

object UnlockModeRepo {

    private const val PREFERENCES_NAME = "unlock_mode"
    private const val KEY_MODE = "mode"

    private val preferences by lazy {
        ContextHolder.get().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val mutableModeFlow by lazy {
        MutableStateFlow(readStoredMode())
    }

    val modeFlow: StateFlow<UnlockMode>
        get() = mutableModeFlow

    fun currentMode(): UnlockMode = mutableModeFlow.value

    fun setMode(mode: UnlockMode) {
        preferences.edit {
            putString(KEY_MODE, mode.name)
        }
        mutableModeFlow.value = mode
    }

    private fun readStoredMode(): UnlockMode =
        UnlockMode.fromStoredValue(preferences.getString(KEY_MODE, null))
}
