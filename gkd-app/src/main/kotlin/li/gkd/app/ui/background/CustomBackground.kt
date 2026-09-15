package li.gkd.app.ui.background

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import li.gkd.app.app
import li.gkd.app.util.AndroidTarget
import java.io.File

/**
 * 全局自定义背景:
 * - 支持用户从相册选择图片(含 GIF/WebP 等表情包动图格式, coil 自动解析)
 * - 存储到私有目录, 进程启动时恢复
 * - scrim 遮罩浓度可调, 保证界面文字可读性
 */
object CustomBackgroundState {
    private const val PREFS = "custom_background"
    private const val KEY_PATH = "bg_path"
    private const val KEY_SCRIM = "bg_scrim"
    const val DEFAULT_SCRIM = 0.6f
    val SCRIM_RANGE = 0.2f..0.95f

    val pathFlow = MutableStateFlow<String?>(null)
    val scrimFlow = MutableStateFlow(DEFAULT_SCRIM)

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PATH, null)
        if (path != null && File(path).isFile) {
            pathFlow.value = path
        } else {
            pathFlow.value = null
        }
        scrimFlow.value = prefs.getFloat(KEY_SCRIM, DEFAULT_SCRIM)
    }

    fun setScrim(context: Context, alpha: Float) {
        val value = alpha.coerceIn(SCRIM_RANGE.start, SCRIM_RANGE.endInclusive)
        scrimFlow.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCRIM, value)
            .apply()
    }

    /** 从相册 Uri 复制背景到私有目录; 支持 png/jpg/webp/gif, 返回是否成功 */
    suspend fun setFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.filesDir.resolve("background").apply { mkdirs() }
            val mime = context.contentResolver.getType(uri)
            // 文件名扩展名优先, 其次 mime 推断; 保留扩展名保证 coil 正确识别动图
            val displayName = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            val nameExt = displayName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it in setOf("gif", "webp", "png", "jpg", "jpeg") }
            val ext = nameExt ?: when {
                mime?.contains("gif") == true -> "gif"
                mime?.contains("webp") == true -> "webp"
                mime?.contains("png") == true -> "png"
                mime?.contains("jpeg") == true || mime?.contains("jpg") == true -> "jpg"
                else -> "img"
            }
            val newFile = dir.resolve("custom_bg.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use { source ->
                newFile.outputStream().use { output -> source.copyTo(output) }
            }
            // 清理旧背景(不同名文件)
            dir.listFiles()?.filter { it.absolutePath != newFile.absolutePath }?.forEach {
                it.delete()
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PATH, newFile.absolutePath)
                .apply()
            pathFlow.value = newFile.absolutePath
            true
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        pathFlow.value?.let { File(it).delete() }
        pathFlow.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PATH)
            .apply()
    }
}

private val bgImageLoader by lazy {
    ImageLoader.Builder(app)
        .components {
            if (AndroidTarget.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()
}

/** 全局背景图层, 挂在 AppRoot 最底层; 未设置背景时不渲染 */
@Composable
fun CustomBackgroundLayer() {
    val path by CustomBackgroundState.pathFlow.collectAsStateWithLifecycle()
    val scrim by CustomBackgroundState.scrimFlow.collectAsStateWithLifecycle()
    val bgPath = path ?: return
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = File(bgPath),
            imageLoader = bgImageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = scrim)),
        )
    }
}
