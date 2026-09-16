package li.gkd.app.notif

import android.app.PendingIntent
import android.app.Service
import li.gkd.app.META
import li.gkd.app.R
import li.gkd.app.service.ActivityService
import li.gkd.app.service.ButtonService
import li.gkd.app.service.EventService
import li.gkd.app.service.HttpService
import li.gkd.app.service.ScreenshotService
import li.gkd.app.service.TrackService
import li.gkd.app.snapshot.SnapshotScreenshotStatus
import kotlin.reflect.KClass

enum class ForegroundNotificationKey(
    val id: Int,
    val channel: AppNotificationChannel = AppNotificationChannel.Service,
) {
    Status(id = 100),
    Screenshot(id = 101),
    Button(id = 102),
    Http(id = 103),
    Expose(id = 104),
    Activity(id = 106),
    Event(id = 107),
    Track(id = 108),
}

enum class PostedNotificationKey(
    val id: Int,
    val channel: AppNotificationChannel,
) {
    SnapshotSaved(id = 105, channel = AppNotificationChannel.Snapshot),
    Watchdog(id = 109, channel = AppNotificationChannel.Service),
}

data class NotificationAction(
    val title: String,
    val intent: PendingIntent,
)

sealed interface AppNotificationSpec {
    val id: Int
    val channel: AppNotificationChannel
    val smallIcon: Int
    val title: String
    val text: String?
    val uri: String?
    val ongoing: Boolean
    val autoCancel: Boolean
    val stopService: KClass<out Service>?
    val actions: List<NotificationAction>
        get() = emptyList()
}

data class ForegroundNotification(
    val key: ForegroundNotificationKey,
    override val title: String,
    override val text: String? = null,
    override val uri: String? = null,
    override val smallIcon: Int = R.drawable.ic_status,
    override val stopService: KClass<out Service>? = null,
) : AppNotificationSpec {
    override val id: Int
        get() = key.id
    override val channel: AppNotificationChannel
        get() = key.channel
    override val ongoing = true
    override val autoCancel = false

    context(service: Service)
    fun startForeground() = NotificationDispatcher.startForeground(service, this)
}

data class PostedNotification(
    val key: PostedNotificationKey,
    override val title: String,
    override val text: String? = null,
    override val uri: String? = null,
    override val actions: List<NotificationAction> = emptyList(),
    override val smallIcon: Int = R.drawable.ic_status,
    override val ongoing: Boolean = false,
    override val autoCancel: Boolean = true,
) : AppNotificationSpec {
    override val id: Int
        get() = key.id
    override val channel: AppNotificationChannel
        get() = key.channel
    override val stopService: KClass<out Service>? = null

    fun post() {
        NotificationDispatcher.post(this)
    }
}

object NotificationCatalog {
    fun status(
        title: String = META.appName,
        text: String? = "无障碍正在运行",
        uri: String? = null,
    ) = ForegroundNotification(
        key = ForegroundNotificationKey.Status,
        title = title,
        text = text,
        uri = uri,
    )

    fun screenshot() = ForegroundNotification(
        key = ForegroundNotificationKey.Screenshot,
        title = "快照截屏已开启",
        text = "保存快照时截取屏幕",
        uri = "gkd://page/1",
        stopService = ScreenshotService::class,
    )

    fun button() = ForegroundNotification(
        key = ForegroundNotificationKey.Button,
        title = "快照按钮已开启",
        text = "点击按钮捕获快照",
        uri = "gkd://page/1",
        stopService = ButtonService::class,
    )

    fun http(port: Int) = ForegroundNotification(
        key = ForegroundNotificationKey.Http,
        title = "HTTP 服务已开启",
        text = "监听端口：$port",
        uri = "gkd://page/1",
        stopService = HttpService::class,
    )

    fun expose() = ForegroundNotification(
        key = ForegroundNotificationKey.Expose,
        title = "正在处理外部调用",
        text = "任务完成后自动关闭",
    )

    fun snapshotSaved(
        appName: String,
        activityId: String?,
        screenshotStatus: SnapshotScreenshotStatus,
        savedToDownloads: Boolean,
    ) = PostedNotification(
        key = PostedNotificationKey.SnapshotSaved,
        title = "快照已保存 · $appName",
        text = buildList {
            activityId?.let(::add)
            screenshotStatus.detailText()?.let(::add)
            if (savedToDownloads) add("已保存至下载")
        }.joinToString(separator = " · ").takeIf { it.isNotEmpty() },
        uri = "gkd://page/2",
    )

    fun activity(text: String? = null) = ForegroundNotification(
        key = ForegroundNotificationKey.Activity,
        title = "界面信息显示中",
        text = text,
        uri = "gkd://page/1",
        stopService = ActivityService::class,
    )

    fun event() = ForegroundNotification(
        key = ForegroundNotificationKey.Event,
        title = "无障碍事件记录中",
        uri = "gkd://page/1",
        stopService = EventService::class,
    )

    fun track() = ForegroundNotification(
        key = ForegroundNotificationKey.Track,
        title = "轨迹提示已开启",
        uri = "gkd://page?tab=3",
        stopService = TrackService::class,
    )

    fun watchdogAsk(
        allowIntent: PendingIntent,
        postponeIntent: PendingIntent,
    ) = PostedNotification(
        key = PostedNotificationKey.Watchdog,
        title = "无障碍已断开",
        text = "10 秒后自动重启无障碍，可点击立即处理",
        actions = listOf(
            NotificationAction("立即重启", allowIntent),
            NotificationAction("暂不重启", postponeIntent),
        ),
    )

    fun watchdogFail(text: String) = PostedNotification(
        key = PostedNotificationKey.Watchdog,
        title = "看门狗重启无障碍失败",
        text = text,
    )
}
