package li.gkd.app.feature.snapshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import li.gkd.app.data.ComplexSnapshot
import li.gkd.app.data.snapshot.SnapshotRepository
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.ui.component.PerfIconButton
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.ui.style.scaffoldPadding
import li.gkd.app.util.ToastUtils.copyText
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.json
import kotlin.math.roundToInt

@Serializable
data class SnapshotInspectRoute(val snapshotId: Long) : NavKey

private data class InspectState(
    val snapshot: ComplexSnapshot,
    val bitmap: Bitmap,
    val contexts: Map<Int, SelectorGen.NodeContext>,
    val autoDetected: List<SelectorGen.Candidate>,
)

@Composable
fun SnapshotInspectPage(route: SnapshotInspectRoute) {
    val mainVm = LocalMainViewModel.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember(route.snapshotId) { mutableStateOf<InspectState?>(null) }
    var loadError by remember(route.snapshotId) { mutableStateOf<String?>(null) }
    var selectedId by remember(route.snapshotId) { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(route.snapshotId) {
        withContext(Dispatchers.IO) {
            try {
                val text = SnapshotRepository.snapshotFile(route.snapshotId).readText()
                val snapshot = json.decodeFromString<ComplexSnapshot>(text)
                val bitmap = BitmapFactory.decodeFile(
                    SnapshotRepository.screenshotFile(route.snapshotId).absolutePath,
                ) ?: error("截图解码失败")
                val (contexts, _) = SelectorGen.buildContexts(snapshot.nodes)
                val detected = SelectorGen.autoDetect(contexts)
                withContext(Dispatchers.Main) {
                    state = InspectState(snapshot, bitmap, contexts, detected)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadError = e.message ?: e.toString()
                }
            }
        }
    }

    fun saveCandidates(candidates: List<SelectorGen.Candidate>) {
        val st = state ?: return
        if (candidates.isEmpty() || saving) return
        scope.launch {
            saving = true
            val result = runCatching {
                val appName = st.snapshot.appInfo?.name ?: st.snapshot.appId
                val group = SelectorGen.buildGroup(
                    appId = st.snapshot.appId,
                    appName = appName,
                    candidates = candidates,
                )
                SubscriptionRepository.saveRuleGroupToLocalStorage(
                    appId = st.snapshot.appId,
                    appName = appName,
                    group = group,
                )
            }
            saving = false
            result.fold(
                onSuccess = { key ->
                    toast("已保存到本地订阅(规则组key=$key), 立即生效")
                    mainVm.popPage()
                },
                onFailure = { e ->
                    toast("保存失败: ${e.message}")
                },
            )
        }
    }

    Scaffold(
        topBar = {
            PerfTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = { mainVm.popPage() },
                    )
                },
                title = {
                    val st = state
                    Text(
                        text = "生成跳过广告规则 · ${st?.snapshot?.appInfo?.name ?: "加载中"}",
                        maxLines = 1,
                    )
                },
            )
        },
    ) { contentPadding ->
        val st = state
        val error = loadError
        when {
            error != null -> Box(
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { Text("加载失败: $error") }

            st == null -> Box(
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .scaffoldPadding(contentPadding),
            ) {
                // ===== 全屏截图区: 点击选节点, 选中红框高亮 =====
                val selectedCtx = selectedId?.let { st.contexts[it] }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val density = LocalDensity.current
                    val imageRatio = st.bitmap.width.toFloat() / st.bitmap.height
                    val boxRatio = maxWidth / maxHeight
                    val displayW: Dp
                    val displayH: Dp
                    if (imageRatio > boxRatio) {
                        displayW = maxWidth
                        displayH = maxWidth / imageRatio
                    } else {
                        displayH = maxHeight
                        displayW = maxHeight * imageRatio
                    }
                    val scaleX = with(density) { displayW.toPx() } / st.snapshot.screenWidth
                    val scaleY = with(density) { displayH.toPx() } / st.snapshot.screenHeight
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(displayW, displayH)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .pointerInput(st) {
                                detectTapGestures { pos ->
                                    val sx = pos.x / scaleX
                                    val sy = pos.y / scaleY
                                    selectedId = findNodeAt(st, sx, sy)?.node?.id
                                }
                            },
                    ) {
                        Image(
                            bitmap = st.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        val sel = selectedCtx
                        if (sel != null) {
                            val a = sel.node.attr
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (a.left * scaleX).roundToInt(),
                                            (a.top * scaleY).roundToInt(),
                                        )
                                    }
                                    .size(
                                        with(density) { (a.width * scaleX).toDp() },
                                        with(density) { (a.height * scaleY).toDp() },
                                    )
                                    .background(Color.Red.copy(alpha = 0.18f))
                                    .border(2.dp, Color.Red),
                            )
                        }
                    }
                }

                // ===== 底部工具栏 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            selectedCtx != null -> "已选中节点, 可一键生成规则"
                            st.autoDetected.isNotEmpty() -> "点击图中位置选择节点(共识别 ${st.autoDetected.size} 个疑似广告按钮)"
                            else -> "点击图中位置选择广告按钮"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (st.autoDetected.isNotEmpty()) {
                        TextButton(
                            onClick = { saveCandidates(st.autoDetected) },
                            enabled = !saving,
                        ) {
                            Text("识别结果一键保存(${st.autoDetected.size})")
                        }
                    }
                }

                // ===== 选中节点详情浮层 =====
                if (selectedCtx != null) {
                    val a = selectedCtx.node.attr
                    val selector = remember(selectedId) {
                        SelectorGen.generateSelector(selectedCtx)
                    }
                    val path = remember(selectedId) {
                        SelectorGen.nodePathSelector(selectedCtx, st.contexts)
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                text = "@${a.name?.substringAfterLast('.')}  (${a.width}×${a.height})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (!a.text.isNullOrEmpty()) {
                                Text("text: ${a.text}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!a.desc.isNullOrEmpty()) {
                                Text("desc: ${a.desc}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!a.id.isNullOrEmpty()) {
                                Text("id: ${a.id}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!a.vid.isNullOrEmpty()) {
                                Text("vid: ${a.vid}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            if (selector.isNotEmpty()) {
                                Text(
                                    selector,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row {
                                if (selector.isNotEmpty()) {
                                    TextButton(onClick = { copyText(selector) }) {
                                        Text("复制选择器")
                                    }
                                }
                                TextButton(onClick = { copyText(path) }) {
                                    Text("复制路径")
                                }
                            }
                            Button(
                                onClick = {
                                    saveCandidates(listOf(SelectorGen.Candidate("手动", selector)))
                                },
                                enabled = selector.isNotEmpty() && !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (saving) "保存中..." else "生成规则并保存")
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun findNodeAt(
    state: InspectState,
    x: Float,
    y: Float,
): SelectorGen.NodeContext? {
    return state.contexts.values
        .filter { c ->
            val a = c.node.attr
            a.left <= x && x < a.right && a.top <= y && y < a.bottom
        }
        .minByOrNull { c -> c.node.attr.width.toFloat() * c.node.attr.height }
}