package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

// ---- 气泡（用户自己做的像素素材） -------------------------------------------------

internal const val BUBBLE_IDLE_FRAMES = 16
internal const val BUBBLE_IDLE_FRAME_MILLIS = 120L

/** 「触碰时」20 帧的原速：起手两帧 120ms，破碎段压到 70~90ms，收尾再放慢。 */
internal val BUBBLE_TAP_FRAME_MILLIS = longArrayOf(
    120, 120, 90, 70, 70, 70, 80, 80, 80, 120,
    80, 80, 80, 80, 80, 80, 80, 80, 80, 120,
)

/** 气泡破掉、还没重新凝聚的这几帧里把贴纸藏起来（第 4 帧开始裂，第 15 帧小泡冒头）。 */
internal val BUBBLE_TAP_STICKER_HIDDEN = 4..15

internal const val BUBBLE_TEST_TAG = "fishHeaderBubble"

internal val BUBBLE_SIZE = 84.dp

/** 气泡内径只有外径的六成多，贴纸得压到内圈里，否则「好运来」会骑在环上。 */
internal val BUBBLE_STICKER_HEIGHT = 48.dp

private object BubbleFrameCache {
    private var idleFrames: List<ImageBitmap>? = null
    private var tapFrames: List<ImageBitmap>? = null
    private var firstIdle: ImageBitmap? = null

    fun firstIdle(context: Context): ImageBitmap = synchronized(this) {
        idleFrames?.first() ?: firstIdle ?: decode(context, "idle", 0).also { firstIdle = it }
    }

    fun idle(context: Context): List<ImageBitmap> = synchronized(this) {
        idleFrames ?: (0 until BUBBLE_IDLE_FRAMES).map { decode(context, "idle", it) }
            .also { idleFrames = it }
    }

    fun tap(context: Context): List<ImageBitmap> = synchronized(this) {
        tapFrames ?: BUBBLE_TAP_FRAME_MILLIS.indices.map { decode(context, "tap", it) }
            .also { tapFrames = it }
    }

    private fun decode(context: Context, set: String, index: Int): ImageBitmap =
        context.assets.open("fish_bubble/$set/${index.toString().padStart(2, '0')}.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
}

/** 一次「戳破」：按每帧自己的时长走完 20 帧，抽出来是为了能用虚拟时间验证节奏。 */
internal suspend fun playBubbleBurst(frameMillis: LongArray, onFrame: (Int) -> Unit) {
    for (index in frameMillis.indices) {
        onFrame(index)
        delay(frameMillis[index])
    }
}

/**
 * 头卡右上角：像素气泡 + 里面的竖版贴纸。
 *
 * 气泡是用户做的两套 GIF 素材（`assets/fish_bubble/`）：平时循环「默认状态」16 帧，
 * 点一下播一遍「触碰时」20 帧——泡泡鼓一下、碎成水花、再重新凝聚回原样，
 * 播完接着回到默认循环。破碎那几帧贴纸跟着一起淡出，否则会挡在空气泡中间。
 * 贴纸上的「好运来」由 `tools/gen_fish_art.py` 翻成白字（深蓝字在深海底上会糊）。
 *
 * 点击用 clickable + 关掉 indication：气泡自己就是反馈，不叠涟漪；
 * 顺带让读屏也能点到（头卡其余部分是纯装饰，不挂语义）。
 */
@Composable
fun FishHeaderSticker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val firstIdle = remember { BubbleFrameCache.firstIdle(context) }
    var idleFrames by remember { mutableStateOf(listOf(firstIdle)) }
    var tapFrames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var bursting by remember { mutableStateOf(false) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var stickerShown by remember { mutableStateOf(true) }

    // 首帧同步解码防闪白，其余帧（含很少用到的破碎帧）进页面后就在后台读完，点下去不卡。
    LaunchedEffect(Unit) {
        tapFrames = withContext(Dispatchers.IO) { BubbleFrameCache.tap(context) }
        idleFrames = withContext(Dispatchers.IO) { BubbleFrameCache.idle(context) }
    }

    LaunchedEffect(bursting, idleFrames.size) {
        if (bursting || idleFrames.size <= 1) return@LaunchedEffect
        var index = 0
        while (true) {
            frameIndex = index % idleFrames.size
            index++
            delay(BUBBLE_IDLE_FRAME_MILLIS)
        }
    }

    LaunchedEffect(bursting) {
        if (!bursting) return@LaunchedEffect
        val frames = tapFrames.ifEmpty { withContext(Dispatchers.IO) { BubbleFrameCache.tap(context) } }
        playBubbleBurst(BUBBLE_TAP_FRAME_MILLIS) { index ->
            frameIndex = index.coerceAtMost(frames.lastIndex.coerceAtLeast(0))
            stickerShown = index !in BUBBLE_TAP_STICKER_HIDDEN
        }
        stickerShown = true
        bursting = false
    }

    val stickerAlpha by animateFloatAsState(
        targetValue = if (stickerShown) 1f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "bubbleSticker",
    )
    val frames = if (bursting) tapFrames else idleFrames

    Box(
        modifier = modifier
            .size(BUBBLE_SIZE)
            .testTag(BUBBLE_TEST_TAG)
            // 点一下戳破：不要涟漪、不要按压态，气泡本身就是反馈
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!bursting) bursting = true },
    ) {
        Image(
            bitmap = frames.getOrNull(frameIndex) ?: firstIdle,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
        LoopingFishSticker(
            modifier = Modifier
                .align(Alignment.Center)
                // 贴纸素材里人物在上、「好运来」在下，整体居中会显得偏下，抬 2dp 找平
                .offset(y = (-2).dp)
                .alpha(stickerAlpha),
            height = BUBBLE_STICKER_HEIGHT,
        )
    }
}
