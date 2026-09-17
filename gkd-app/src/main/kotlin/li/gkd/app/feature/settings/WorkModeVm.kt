package li.gkd.app.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import li.gkd.app.app
import li.gkd.app.permission.PermissionStates
import li.gkd.app.service.WatchdogAlarm
import li.gkd.app.store.AppStore
import li.gkd.app.ui.share.BaseViewModel
import kotlin.time.Duration.Companion.milliseconds

class WorkModeVm : BaseViewModel() {
    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                PermissionStates.refreshAll()
                delay(1000.milliseconds)
            }
        }
    }

    fun setA11yWatchdogEnabled(enabled: Boolean) {
        AppStore.updateSettings { it.copy(enableA11yWatchdog = enabled) }
        // 闹钟兜底: 进程被系统杀死后仍能触发自愈, 需随开关启停
        if (enabled) {
            WatchdogAlarm.schedule(app)
        } else {
            WatchdogAlarm.cancel(app)
        }
    }
}
