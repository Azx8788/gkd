package li.gkd.app.service

import android.app.PendingIntent
import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import li.gkd.app.META
import li.gkd.app.a11y.useA11yServiceEnabledFlow
import li.gkd.app.app
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.notif.NotificationDispatcher
import li.gkd.app.notif.PostedNotificationKey
import li.gkd.app.permission.PermissionStates
import li.gkd.app.platform.overlay.KeepAliveOverlayCoordinator
import li.gkd.app.priv.PrivilegeServiceStatus
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.privilegeServiceStatusFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.store.AppStore.updateEnableAutomator
import li.gkd.app.domain.rule.RuleSummary
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.ui.share.statusText
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.LogUtils
import priv.kit.core.Privilege
import kotlin.time.Duration.Companion.milliseconds

class StatusService : LifecycleHookService() {

    private val a11yServiceEnabledFlow by lazy { useA11yServiceEnabledFlow(lifecycleScope) }
    private fun statusTriple(): Triple<String, String, String?> {
        val abRunning = A11yService.isRunning.value
        val automationRunning = uiAutomationFlow.value != null
        val store = storeFlow.value
        val ruleSummary = SubscriptionState.ruleSummaryFlow.value
        val count = actionCountFlow.value
        val privilegeServiceStatus = privilegeServiceStatusFlow.value
        val title = if (store.useCustomNotifText) {
            store.customNotifTitle.replaceTemplate(ruleSummary, count)
        } else {
            META.appName
        }
        return if (PermissionStates.appOpsRestrictedFlow.value) {
            Triple(title, "权限受限，请重新授权", "gkd://page/3")
        } else if (privilegeServiceStatus == PrivilegeServiceStatus.DisconnectedDesired) {
            Triple(title, "特权服务连接已中断，请检查", "gkd://page/4")
        } else if (!automationRunning && !abRunning) {
            if (currentAppUseA11y) {
                val text = if (a11yServiceEnabledFlow.value) {
                    "无障碍发生故障"
                } else if (PermissionStates.writeSecureSettings.updateAndGet()) {
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            AppInfoRepository.appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        "局部关闭 · $name"
                    } else {
                        "无障碍已关闭"
                    }
                } else {
                    "无障碍未授权"
                }
                Triple(title, text, defaultStatusNotification.uri)
            } else {
                val text =
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            AppInfoRepository.appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        "局部关闭 · $name"
                    } else {
                        "自动化已关闭"
                    }
                Triple(title, text, defaultStatusNotification.uri)
            }
        } else if (!store.enableMatch) {
            Triple(title, "暂停规则匹配", "gkd://page?tab=1")
        } else if (store.useCustomNotifText) {
            Triple(
                title,
                store.customNotifText.replaceTemplate(ruleSummary, count),
                defaultStatusNotification.uri
            )
        } else {
            Triple(title, ruleSummary.statusText(count), defaultStatusNotification.uri)
        }
    }

    init {
        useServicePresence(
            stateFlow = isRunning,
            name = "常驻通知",
            startToastDelayMillis = if (app.justStarted) 1000 else 0,
        )
        onCreated {
            if (!defaultStatusNotification.startForeground()) return@onCreated
            lifecycleScope.launch {
                combine(
                    A11yService.isRunning,
                    KeepAliveOverlayCoordinator.accessibilityAttached,
                ) { a11yRunning, a11yOverlayAttached ->
                    a11yRunning to a11yOverlayAttached
                }.distinctUntilChanged().collectLatest {
                    val (a11yRunning, a11yOverlayAttached) = it
                    if (a11yRunning && a11yOverlayAttached) {
                        KeepAliveOverlayCoordinator.releaseAfterHandoff(
                            source = KeepAliveOverlayCoordinator.Source.Status,
                            owner = this@StatusService,
                        )
                    } else {
                        KeepAliveOverlayCoordinator.acquire(
                            source = KeepAliveOverlayCoordinator.Source.Status,
                            owner = this@StatusService,
                            context = this@StatusService,
                            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        )
                    }
                }
            }
            lifecycleScope.launch {
                combine(
                    A11yService.isRunning,
                    uiAutomationFlow,
                    storeFlow,
                    SubscriptionState.ruleSummaryFlow,
                    privilegeServiceStatusFlow,
                    a11yServiceEnabledFlow,
                    PermissionStates.writeSecureSettings.stateFlow,
                    PermissionStates.appOpsRestrictedFlow,
                    topAppIdFlow,
                    actionCountFlow.debounce(1000L.milliseconds),
                ) {
                    statusTriple()
                }.collect {
                    NotificationCatalog.status(
                        title = it.first,
                        text = it.second,
                        uri = it.third,
                    ).startForeground()
                }
            }
            // 无障碍看门狗: 掉线后发通知询问, 10 秒内用户确认或超时后自动重启
            lifecycleScope.launch {
                storeFlow.map { it.enableA11yWatchdog }.distinctUntilChanged()
                    .collectLatest { enabled ->
                        if (!enabled) {
                            // 看门狗关闭时同步取消闹钟兜底(升级安装/多入口关闭场景)
                            WatchdogAlarm.cancel(app)
                            return@collectLatest
                        }
                        // 确保闹钟兜底已注册: 老版本升级上来时看门狗已开但闹钟不存在
                        WatchdogAlarm.schedule(app)
                        var consecutiveFailures = 0
                        while (true) {
                            delay(A11Y_WATCHDOG_CHECK_INTERVAL.milliseconds)
                            if (A11yService.isRunning.value) {
                                consecutiveFailures = 0
                                continue
                            }
                            // 清空残留命令, 只接受本次询问窗口内的用户操作
                            while (watchdogCommands.tryReceive().isSuccess) {
                            }
                            NotificationCatalog.watchdogAsk(
                                allowIntent = watchdogCommandIntent(ACTION_WATCHDOG_RESTART_NOW),
                                postponeIntent = watchdogCommandIntent(ACTION_WATCHDOG_POSTPONE),
                            ).post()
                            val command = withTimeoutOrNull(A11Y_WATCHDOG_CONFIRM_TIMEOUT.milliseconds) {
                                watchdogCommands.receive()
                            }
                            if (command == WatchdogCommand.Postpone) {
                                // 用户选择暂不重启, 本轮跳过并退避
                                NotificationDispatcher.cancel(PostedNotificationKey.Watchdog.id)
                                consecutiveFailures++
                                delay(watchdogBackoff(consecutiveFailures))
                                continue
                            }
                            // 用户确认或超时: 执行自动重启
                            watchdogRestart()
                            // 等待重启流程完成(内部含时序等待)后再判断结果
                            delay(A11Y_WATCHDOG_AWAIT_TIME.milliseconds)
                            if (A11yService.isRunning.value) {
                                NotificationDispatcher.cancel(PostedNotificationKey.Watchdog.id)
                                consecutiveFailures = 0
                            } else {
                                consecutiveFailures++
                                val hasWriteSecure =
                                    PermissionStates.writeSecureSettings.updateAndGet()
                                NotificationCatalog.watchdogFail(
                                    if (hasWriteSecure) {
                                        "自动重启无障碍失败，请尝试手动重启"
                                    } else {
                                        "缺少「写入安全设置权限」且 Shizuku 特权服务未连接，无法自动重启"
                                    }
                                ).post()
                                // 连续失败时指数退避, 减少无效重试与通知打扰
                                delay(watchdogBackoff(consecutiveFailures))
                            }
                        }
                    }
            }
        }
        onDestroyed {
            KeepAliveOverlayCoordinator.release(
                source = KeepAliveOverlayCoordinator.Source.Status,
                owner = this,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCHDOG_RESTART_NOW -> watchdogCommands.trySend(WatchdogCommand.RestartNow)
            ACTION_WATCHDOG_POSTPONE -> watchdogCommands.trySend(WatchdogCommand.Postpone)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        val needRestart
            get() = storeFlow.value.enableStatusService
                    && !isRunning.value
                    && PermissionStates.notification.updateAndGet()
                    && PermissionStates.foregroundServiceSpecialUse.updateAndGet()

        fun start() = IntentUtils.startForegroundServiceByClass(StatusService::class)
        fun stop() = IntentUtils.stopServiceByClass(StatusService::class)
        private var lastAutoStart = 0L
        fun autoStart() {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            // 重启自动打开通知栏状态服务
            // 需要已有服务或前台才能自主启动，否则报错 startForegroundService() not allowed due to mAllowStartForeground false
            if (needRestart) {
                start()
                lastAutoStart = System.currentTimeMillis()
            }
        }

        private val watchdogCommands = Channel<WatchdogCommand>(Channel.CONFLATED)

        private val ACTION_WATCHDOG_RESTART_NOW by lazy { META.appId + ".WATCHDOG_RESTART_NOW" }
        private val ACTION_WATCHDOG_POSTPONE by lazy { META.appId + ".WATCHDOG_POSTPONE" }

        private fun watchdogCommandIntent(action: String): PendingIntent {
            val intent = Intent(app, StatusService::class.java).setAction(action)
            return PendingIntent.getService(
                app,
                PostedNotificationKey.Watchdog.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        // 通过特权服务(Shizuku)重新授予自身权限, 再拉起无障碍
        // 供看门狗主循环与闹钟兜底接收器(WatchdogAlarmReceiver)共用
        suspend fun watchdogRestart() {
            try {
                withContext(Dispatchers.IO) {
                    if (Privilege.pingServer()) {
                        privilegeContextFlow.value?.grantSelf()
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(e)
            }
            if (!storeFlow.value.enableAutomator) {
                // 无障碍掉线时 onDestroyed 会将 enableAutomator 置为 false,
                // 必须先恢复才能通过 fixRestartAutomatorService 的内部检查
                updateEnableAutomator(true)
            }
            fixRestartAutomatorService()
        }
    }
}

private enum class WatchdogCommand {
    RestartNow,
    Postpone,
}

private fun watchdogBackoff(consecutiveFailures: Int) =
    (A11Y_WATCHDOG_BACKOFF_BASE shl consecutiveFailures.coerceAtMost(4))
        .coerceAtMost(A11Y_WATCHDOG_BACKOFF_MAX).milliseconds

private val defaultStatusNotification by lazy { NotificationCatalog.status() }

// 看门狗检查间隔
private const val A11Y_WATCHDOG_CHECK_INTERVAL = 5000L

// 掉线后通知询问的确认窗口时长
private const val A11Y_WATCHDOG_CONFIRM_TIMEOUT = 10_000L

// 触发重启后等待其生效的时间(略大于内部修复+启动等待的总时长)
const val A11Y_WATCHDOG_AWAIT_TIME = 4000L

// 连续失败时的指数退避基准与上限
private const val A11Y_WATCHDOG_BACKOFF_BASE = 5000L
private const val A11Y_WATCHDOG_BACKOFF_MAX = 60_000L

private fun String.replaceTemplate(ruleSummary: RuleSummary, count: Long): String {
    return replace($$"${i}", ruleSummary.globalGroups.size.toString())
        .replace($$"${k}", ruleSummary.appSize.toString())
        .replace($$"${u}", ruleSummary.appGroupSize.toString())
        .replace($$"${n}", count.toString())
}
