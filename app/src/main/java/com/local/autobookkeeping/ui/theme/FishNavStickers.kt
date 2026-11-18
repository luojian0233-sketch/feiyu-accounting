package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 蓝色肥鱼底栏用的像素小图。素材是四张 320x320 的 GIF，
 * 由 `tools/gen_fish_nav.py` 去掉底部文字、统一留白后切成逐帧 PNG，
 * 放在 `assets/fish_nav/<目录>/NN.png`。
 */
enum class FishStickerKind(val directory: String, val frameCount: Int) {
    HOME("home", 24),
    LEDGER("ledger", 24),
    STATISTICS("stats", 24),
    SETTINGS("settings", 22),
    ;

    /** 每帧停留时长，跟原 GIF 一致；设置那张第 17 帧是 240ms。 */
    fun frameDurationMillis(index: Int): Int =
        if (this == SETTINGS && index == 17) 240 else 80
}

private object FishStickerCache {
    private val firstFrames = mutableMapOf<FishStickerKind, ImageBitmap>()
    private val allFrames = mutableMapOf<FishStickerKind, List<ImageBitmap>>()

    fun first(context: Context, kind: FishStickerKind): ImageBitmap = synchronized(this) {
        firstFrames.getOrPut(kind) { decode(context, kind, 0) }
    }

    fun all(context: Context, kind: FishStickerKind): List<ImageBitmap> = synchronized(this) {
        allFrames.getOrPut(kind) { (0 until kind.frameCount).map { decode(context, kind, it) } }
    }

    private fun decode(context: Context, kind: FishStickerKind, index: Int): ImageBitmap {
        val path = "fish_nav/${kind.directory}/${index.toString().padStart(2, '0')}.png"
        return context.assets.open(path).use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }
}

/**
 * 完整播一遍：逐帧回调并停够每帧时长，最后回到第 0 帧。
 * 抽成普通挂起函数是为了能在单元测试里用虚拟时间验证「只循环一次」。
 */
internal suspend fun playStickerLoop(kind: FishStickerKind, onFrame: (Int) -> Unit) {
    for (index in 0 until kind.frameCount) {
        onFrame(index)
        delay(kind.frameDurationMillis(index).toLong())
    }
    onFrame(0)
}

/**
 * 静止时停在第 0 帧，每次 [playToken] 递增就完整播放一遍动画，
 * 播完回到第 0 帧——正好是「点一下、循环一次」的效果。
 */
@Composable
fun FishSticker(
    kind: FishStickerKind,
    playToken: Int,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val context = LocalContext.current
    val firstFrame = remember(kind) { FishStickerCache.first(context, kind) }
    var frames by remember(kind) { mutableStateOf(listOf(firstFrame)) }
    LaunchedEffect(kind) {
        val loaded = withContext(Dispatchers.IO) { FishStickerCache.all(context, kind) }
        frames = loaded
    }

    var frameIndex by remember(kind) { mutableIntStateOf(0) }
    LaunchedEffect(playToken, kind) {
        if (playToken == 0) {
            // 切换到别的导航项时把停住的帧复位，免得图标卡在半路。
            frameIndex = 0
            return@LaunchedEffect
        }
        if (frames.size <= 1) {
            // 刚切到这套皮肤就点，帧可能还在解码；最多等 400ms。
            withTimeoutOrNull(400) { snapshotFlow { frames.size }.first { it > 1 } }
        }
        if (frames.size <= 1) return@LaunchedEffect
        playStickerLoop(kind) { frameIndex = it }
    }

    Image(
        bitmap = frames.getOrNull(frameIndex) ?: firstFrame,
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
    )
}
