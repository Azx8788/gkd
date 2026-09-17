package li.gkd.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import li.gkd.app.a11y.A11yCommonImpl
import li.gkd.app.a11y.A11yRuleEngine
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.a11y.A11yState
import li.gkd.app.a11y.currentTopActivity
import li.gkd.app.a11y.updateTopActivity
import li.gkd.app.appScope
import li.gkd.app.platform.lifecycle.LifecycleHooks
import li.gkd.app.platform.overlay.KeepAliveOverlayCoordinator
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.store.AppStore.updateEnableAutomator
import li.gkd.app.util.AndroidTarget
import li.gkd.app.util.AutomatorModeOption
import li.gkd.app.util.LogUtils
import li.gkd.app.util.componentName
import li.gkd.app.util.ToastUtils.toast
import kotlin.coroutines.resume

@SuppressLint("AccessibilityPolicy")
abstract class A11yService : AccessibilityService(), A11yCommonImpl {
    private val lifecycleHooks = LifecycleHooks()
    override val scope = MainScope()
    override val mode get() = AutomatorModeOption.A11yMode
    override val windowNodeInfo: AccessibilityNodeInfo? get() = rootInActiveWindow
    override val windowInfos: List<AccessibilityWindowInfo> get() = windows
    override suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (AndroidTarget.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }

                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            if (cont.isActive) {
                                cont.resume(
                                    Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer, screenshot.colorSpace
                                    )
                                )
                            }
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                }
            )
        } else {
            cont.resume(null)
        }
    }

    override val ruleEngine by lazy { A11yRuleEngine(this) }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = ruleEngine.onA11yEvent(event)

    private val startTime = System.currentTimeMillis()
    override var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < 3_000
            }
            return field
        }

    private var tempShutdownFlag = false

    override fun shutdown(temp: Boolean) {
        if (temp) {
            tempShutdownFlag = true
        }
        disableSelf()
    }

    private var destroyed = false
    private var connected = false

    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    init {
        lifecycleHooks.useLogLifecycle(this)
        lifecycleHooks.onCreated {
            isRunning.value = true
            if (currentAppUseA11y) {
                updateEnableAutomator(true)
            } else {
                toast("当前为自动化模式，无障碍将自动关闭", forced = true)
                scope.launch {
                    delay(1)
                    shutdown(true)
                }
            }
            StatusService.autoStart()
            scope.launch {
                delay(3000)
                if (!(destroyed || connected)) {
                    toast("无障碍启动超时，请尝试关闭重启", forced = true)
                }
            }
        }
        lifecycleHooks.onDestroyed {
            scope.cancel()
            if (instance === this) {
                instance = null
            }
            isRunning.value = false
            releaseKeepAliveOverlayAfterHandoff()
            if (tempShutdownFlag) {
                toast("无障碍局部关闭")
            } else {
                toast("无障碍已关闭")
                updateEnableAutomator(false)
            }
            A11yState.withTopActivityLock {
                privilegeContextFlow.value?.run {
                    topCpn()?.let { cpn ->
                        // com.android.systemui
                        if (!currentTopActivity.sameAs(cpn.packageName, cpn.className)) {
                            updateTopActivity(cpn.packageName, cpn.className)
                        }
                    }
                }
            }
            destroyed = true
        }
    }

    private fun attachKeepAliveOverlay() {
        if (!KeepAliveOverlayCoordinator.acquire(
                source = KeepAliveOverlayCoordinator.Source.Accessibility,
                owner = this,
                context = this,
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            )
        ) {
            toast("添加无障碍保活失败\n请尝试重启无障碍")
        }
    }

    private fun releaseKeepAliveOverlayAfterHandoff() {
        appScope.launch {
            KeepAliveOverlayCoordinator.releaseAfterHandoff(
                source = KeepAliveOverlayCoordinator.Source.Accessibility,
                owner = this@A11yService,
                replacement = KeepAliveOverlayCoordinator.Source.Status
                    .takeIf { StatusService.isRunning.value },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleHooks.dispatchCreated()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogUtils.d("onA11yConnected -> ${this::class.simpleName}")
        instance = this
        scope.launch {
            // 悬浮窗保活跟随设置开关, 修改后即时挂载/移除
            storeFlow.map { it.enableKeepAliveOverlay }.distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        attachKeepAliveOverlay()
                    } else {
                        KeepAliveOverlayCoordinator.release(
                            source = KeepAliveOverlayCoordinator.Source.Accessibility,
                            owner = this@A11yService,
                        )
                    }
                }
        }
        connected = true
        toast("无障碍已启动")
        if (currentAppUseA11y) {
            A11yRuntime.onA11yConnected(this)
        }
    }

    override fun onDestroy() {
        lifecycleHooks.dispatchDestroyed()
        super.onDestroy()
    }

    companion object {
        val a11yCn by lazy { SelectToSpeakService::class.componentName }
        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        @Volatile
        var instance: A11yService? = null
            private set
    }
}
