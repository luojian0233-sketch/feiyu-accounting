package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * 头卡右上角的贴纸：外面裹一层半透明白的「深海气泡」。
 *
 * 早前是一块近白实心牌，压在深蓝渐变上太抢眼；换成玻璃泡后底色能透上来，
 * 只剩一圈高光和描边把贴纸托住。GIF 里的「好运来」也在生成脚本里翻成了白字
 * （见 `tools/gen_fish_art.py` 的 `recolor_sticker_text`），深蓝字压在深海底上会糊成一片。
 */
@Composable
fun FishHeaderSticker(
    modifier: Modifier = Modifier,
    height: Dp = 74.dp,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.18f),
                    0.52f to Color.White.copy(alpha = 0.05f),
                    1f to Color.White.copy(alpha = 0.08f),
                ),
            )
            .drawBehind { bubbleSheen() }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.52f), Color.White.copy(alpha = 0.14f)),
                ),
                shape = shape,
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        LoopingFishSticker(height = height)
    }
}

/** 左上角一颗收得住的反光 + 底边内侧一线微光，玻璃泡的体积全靠这两笔。 */
private fun DrawScope.bubbleSheen() {
    val glow = size.minDimension * 0.46f
    val center = Offset(size.width * 0.26f, size.height * 0.14f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.27f),
            1f to Color.Transparent,
            center = center,
            radius = glow,
        ),
        radius = glow,
        center = center,
    )
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.07f)),
            startY = size.height * 0.74f,
            endY = size.height,
        ),
    )
}
