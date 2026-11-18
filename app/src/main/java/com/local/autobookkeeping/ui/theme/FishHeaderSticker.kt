package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 头卡上的像素小图：跟底栏那批贴纸同源（320x320 的 GIF），
 * 由 `tools/gen_fish_art.py` 按统一取景切成竖版逐帧 PNG（连「好运来」一起留着），
 * 放在 `assets/fish_header/NN.png`。
 */
internal const val HEADER_STICKER_FRAMES = 24
internal const val HEADER_STICKER_FRAME_MILLIS = 80L

private object HeaderStickerCache {
    private var firstFrame: ImageBitmap? = null
    private var allFrames: List<ImageBitmap>? = null

    fun first(context: Context): ImageBitmap = synchronized(this) {
        firstFrame ?: decode(context, 0).also { firstFrame = it }
    }

    fun all(context: Context): List<ImageBitmap> = synchronized(this) {
        allFrames ?: (0 until HEADER_STICKER_FRAMES).map { decode(context, it) }.also { allFrames = it }
    }

    private fun decode(context: Context, index: Int): ImageBitmap =
        context.assets.open("fish_header/${index.toString().padStart(2, '0')}.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
}

/**
 * 一直循环：[HEADER_STICKER_FRAMES] 帧转完接着从头来，中途不会被停住。
 * 抽成普通挂起函数，单元测试里可以用虚拟时间验证「转完一圈会接上」。
 */
internal suspend fun loopStickerFrames(
    frameCount: Int,
    frameMillis: Long,
    onFrame: (Int) -> Unit,
): Nothing {
    while (true) {
        for (index in 0 until frameCount) {
            onFrame(index)
            delay(frameMillis)
        }
    }
}

/** 首帧同步解码避免闪白，其余帧后台读完再开转；高度定死、宽度按素材原比例。 */
@Composable
fun LoopingFishSticker(
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
) {
    val context = LocalContext.current
    val firstFrame = remember { HeaderStickerCache.first(context) }
    var frames by remember { mutableStateOf(listOf(firstFrame)) }
    LaunchedEffect(Unit) {
        frames = withContext(Dispatchers.IO) { HeaderStickerCache.all(context) }
    }

    var frameIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames.size) {
        if (frames.size <= 1) return@LaunchedEffect
        loopStickerFrames(frames.size, HEADER_STICKER_FRAME_MILLIS) { frameIndex = it }
    }

    Image(
        bitmap = frames.getOrNull(frameIndex) ?: firstFrame,
        contentDescription = null,
        modifier = modifier
            .height(height)
            .width(height * (firstFrame.width.toFloat() / firstFrame.height.toFloat())),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * 头卡右上角的贴纸：GIF 自带的「好运来」是深蓝色的，直接压在深海渐变上会糊成一片，
 * 所以垫一张近白的圆角贴纸牌、再给一点投影，整块像是贴上头卡的贴纸徽章。
 */
@Composable
fun FishHeaderSticker(
    modifier: Modifier = Modifier,
    height: Dp = 74.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .background(Color(0xFFFDFEFF).copy(alpha = 0.96f), shape)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        LoopingFishSticker(height = height)
    }
}
