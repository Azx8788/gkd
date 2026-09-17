package li.gkd.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.gkd.app.META
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.util.LogUtils
import kotlin.time.Duration.Companion.milliseconds

// 进程死亡后的看门狗兜底间隔: 闹钟由系统持有, 应用被杀仍会触发拉活进程
private const val A11Y_WATCHDOG_ALARM_INTERVAL = 30_000L

/**
 * 无障碍看门狗闹钟接收器.
 *
 * StatusService 内的看门狗循环只在进程存活时工作; 进程被系统杀死后,
 * 由 AlarmManager 闹钟触发本 receiver 完成进程自愈:
 * 1. 看门狗已被关闭 → 取消闹钟, 停止自愈循环
 * 2. 无障碍在线 → 仅续期闹钟
 * 3. 无障碍掉线 → 直接自动重启(Shizuku 授权 + 拉起无障碍 + 恢复常驻通知服务)
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_WATCHDOG_ALARM -> {
                if (!storeFlow.value.enableA11yWatchdog) {
                    WatchdogAlarm.cancel(context)
                    return
                }
                if (A11yService.isRunning.value) {
                    WatchdogAlarm.schedule(context)
                    return
                }
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 进程刚被闹钟拉活时无障碍必然离线, 直接自动重启;
                        // 询问通知流程仅适用于进程内存活的主看门狗循环
                        StatusService.watchdogRestart()
                        delay(A11Y_WATCHDOG_AWAIT_TIME.milliseconds)
                        if (A11yService.isRunning.value) {
                            // 重启成功, 恢复常驻通知服务让主看门狗循环继续接管
                            if (StatusService.needRestart) {
                                runCatching { StatusService.start() }
                            }
                        } else {
                            val hasWriteSecure = PermissionStates.writeSecureSettings.updateAndGet()
                            NotificationCatalog.watchdogFail(
                                if (hasWriteSecure) {
                                    "自动重启无障碍失败，请尝试手动重启"
                                } else {
                                    "缺少「写入安全设置权限」且 Shizuku 特权服务未连接，无法自动重启"
                                }
                            ).post()
                        }
                    } catch (e: Exception) {
                        LogUtils.d(e)
                    } finally {
                        WatchdogAlarm.schedule(context)
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        val ACTION_WATCHDOG_ALARM by lazy { META.appId + ".WATCHDOG_ALARM" }
    }
}

object WatchdogAlarm {
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            .setAction(WatchdogAlarmReceiver.ACTION_WATCHDOG_ALARM)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 非精确闹钟无需额外权限; Doze 下会被系统节流, 兜底场景可接受
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + A11Y_WATCHDOG_ALARM_INTERVAL,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }
}
