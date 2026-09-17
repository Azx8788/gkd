package li.gkd.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.service.StatusService
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfIconButton
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.component.SettingItem
import li.gkd.app.ui.component.TextListDialog
import li.gkd.app.ui.component.TextSwitch
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.share.launchUiAction
import li.gkd.app.ui.style.EmptyHeight
import li.gkd.app.ui.style.cardHorizontalPadding
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.throttle
import li.gkd.app.util.ToastUtils.toast

@Serializable
data object KeepAliveRoute : NavKey

@Composable
fun KeepAlivePage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<KeepAliveVm>()
    val writeSecureSettings by PermissionStates.writeSecureSettings.stateFlow.collectAsStateWithLifecycle()
    val ignoreBatteryOptimizations by PermissionStates.ignoreBatteryOptimizations.stateFlow.collectAsStateWithLifecycle()
    val privilegeContext by privilegeContextFlow.collectAsStateWithLifecycle()
    val store by storeFlow.collectAsStateWithLifecycle()
    var showVendorDialog by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    if (showVendorDialog) {
        TextListDialog(
            onDismiss = { showVendorDialog = false },
            textList = VendorKeepAliveGuide.entries.map { vendor ->
                vendor.label to {
                    showVendorDialog = false
                    vm.scope.launchUi {
                        mainVm.dialogRequests.showMessage(
                            title = "${vendor.label} 保活设置",
                            text = vendor.steps,
                        )
                    }
                }
            },
        )
    }

    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
        PerfTopAppBar(scrollBehavior = scrollBehavior, navigationIcon = {
            PerfIconButton(
                imageVector = PerfIcon.ArrowBack,
                onClick = {
                    mainVm.popPage()
                })
        }, title = {
            Text(text = "保活设置")
        })
    }) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = itemHorizontalPadding)
                    .fillMaxWidth(),
                colors = surfaceCardColors,
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(top = 12.dp),
                    text = "保活原理",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(vertical = 8.dp),
                    text = "保活效果由三层叠加决定：\n1. 前台服务 + 无障碍身份，降低被系统选中杀死的概率\n2. 电池优化白名单 + 厂商后台管理设置，降低系统策略层面的限制\n3. 看门狗机制，缩短进程被杀后到重新拉活之间的时间窗口\n\n不存在绝对不被杀的方案，建议尽量开启下方各项保活手段",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .padding(horizontal = itemHorizontalPadding)
                    .fillMaxWidth(),
                colors = surfaceCardColors,
            ) {
                TextSwitch(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontalPadding),
                    paddingDisabled = true,
                    title = "常驻通知",
                    subtitle = "前台服务持续通知，保持进程前台优先级，是最基础稳定的保活方式",
                    checked = store.enableStatusService,
                    onCheckedChange = vm.scope.launchUiAction { enabled ->
                        if (enabled) {
                            if (!mainVm.permissionRequests.ensurePermissions(
                                    PermissionStates.foregroundServiceSpecialUse,
                                    PermissionStates.notification,
                                )
                            ) {
                                toast("缺少「常驻通知」相关权限")
                                return@launchUiAction
                            }
                        }
                        vm.setStatusServiceEnabled(enabled)
                    },
                )
                TextSwitch(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontalPadding),
                    paddingDisabled = true,
                    title = "无障碍看门狗",
                    subtitle = "断开后发通知确认，10 秒内确认或超时自动重启；应用进程被杀后由闹钟兜底自动复活；需「写入安全设置权限」或 Shizuku 特权服务",
                    checked = store.enableA11yWatchdog,
                    onCheckedChange = vm.scope.launchUiAction { enabled ->
                        if (enabled && !writeSecureSettings && privilegeContext == null) {
                            toast("缺少「写入安全设置权限」且 Shizuku 未连接，看门狗将无法自动重启")
                        }
                        if (enabled && !StatusService.isRunning.value) {
                            if (!mainVm.permissionRequests.ensurePermissions(
                                    PermissionStates.foregroundServiceSpecialUse,
                                    PermissionStates.notification,
                                )
                            ) {
                                toast("需要「常驻通知」相关权限以保活看门狗")
                                return@launchUiAction
                            }
                            vm.setStatusServiceEnabled(true)
                        }
                        vm.setA11yWatchdogEnabled(enabled)
                    },
                )
                TextSwitch(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontalPadding),
                    paddingDisabled = true,
                    title = "悬浮窗保活",
                    subtitle = "1×1 像素透明悬浮窗提升进程优先级，降低被系统杀死的概率；个别厂商 ROM 可能识别并限制",
                    checked = store.enableKeepAliveOverlay,
                    onCheckedChange = vm.scope.launchUiAction { enabled ->
                        vm.setKeepAliveOverlayEnabled(enabled)
                    },
                )
                TextSwitch(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontalPadding),
                    paddingDisabled = true,
                    title = "开机自启",
                    subtitle = "设备开机后自动恢复无障碍与常驻通知（依赖看门狗开启）",
                    checked = store.enableBootRevive,
                    onCheckedChange = vm.scope.launchUiAction { enabled ->
                        vm.setBootReviveEnabled(enabled)
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .padding(horizontal = itemHorizontalPadding)
                    .fillMaxWidth(),
                colors = surfaceCardColors,
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(top = 12.dp),
                    text = "系统设置引导",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    modifier = Modifier
                        .padding(horizontal = cardHorizontalPadding)
                        .padding(vertical = 8.dp),
                    text = "以下设置需要手动操作，是厂商 ROM 层面最有效的保活手段",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingItem(
                    title = if (ignoreBatteryOptimizations) {
                        "忽略电池优化（已忽略）"
                    } else {
                        "忽略电池优化（未忽略）"
                    },
                    onClickLabel = "申请忽略电池优化",
                    onClick = throttle {
                        vm.scope.launchUi {
                            mainVm.permissionRequests.ensurePermissions(
                                PermissionStates.ignoreBatteryOptimizations,
                            )
                        }
                    },
                )
                SettingItem(
                    title = "应用详情设置",
                    subtitle = "自启动、通知、权限等系统入口",
                    onClickLabel = "打开应用详情页面",
                    onClick = throttle {
                        IntentUtils.openAppDetailsSettings()
                    },
                )
                SettingItem(
                    title = "厂商保活引导",
                    subtitle = "小米 / 华为 / OPPO / vivo 等机型手动设置步骤",
                    onClickLabel = "打开厂商保活引导",
                    onClick = throttle {
                        showVendorDialog = true
                    },
                )
            }
            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}

private enum class VendorKeepAliveGuide(val label: String, val steps: String) {
    Xiaomi(
        "小米 MIUI/HyperOS",
        "1. 设置 → 应用设置 → 应用管理 → GKD → 自启动，开启「自启动」\n" +
            "2. 同页面 → 省电策略，选择「无限制」\n" +
            "3. 最近任务界面长按 GKD 卡片，选择「锁定」\n" +
            "4. 旧版 MIUI 可在开发者选项中关闭「神隐模式」",
    ),
    Huawei(
        "华为 EMUI/鸿蒙",
        "1. 设置 → 应用和服务 → 应用启动管理 → GKD，关闭「自动管理」改为手动管理\n" +
            "2. 手动管理中开启「允许自启动」「允许关联启动」「允许后台活动」\n" +
            "3. 旧版 EMUI：设置 → 电池 → 启动管理，将 GKD 加入受保护应用",
    ),
    Oppo(
        "OPPO ColorOS / 一加",
        "1. 设置 → 应用管理 → 应用列表 → GKD → 允许自启动（或最近任务卡片下拉点击锁定）\n" +
            "2. 设置 → 电池 → 应用耗电管理 → GKD，关闭「智能耗电保护」/ 允许完全后台行为\n" +
            "3. 一加机型与 ColorOS 操作一致",
    ),
    Vivo(
        "vivo OriginOS/Funtouch",
        "1. 设置 → 电池 → 后台耗电管理 → GKD，允许后台高耗电\n" +
            "2. 设置 → 快捷与辅助 → 应用启动管理，允许 GKD 自启动与关联启动\n" +
            "3. i 管家类型机型可加入后台白名单/加速保护名单",
    ),
    Samsung(
        "三星 One UI",
        "1. 设置 → 电池 → 后台使用限制，将 GKD 从「深度睡眠」应用中移除\n" +
            "2. 设置 → 应用程序 → GKD → 电池 → 不受限制\n" +
            "3. 近期任务中长按 GKD 卡片选择「锁定」",
    ),
}
