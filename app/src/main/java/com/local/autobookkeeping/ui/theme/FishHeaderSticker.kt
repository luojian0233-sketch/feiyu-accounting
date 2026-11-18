package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 头卡上的像素小图：跟底栏那批贴纸同源（320x320 的 GIF），由 `tools/gen_fish_art.py`
 * 按统一取景切成竖版逐帧 PNG（字幕翻成白字一起留着），放在 `assets/fish_header/<kind>/`。
 *
 * 四个页面各用一张：首页「好运来」、账单「让我看看」、统计「买买买」、设置「安排」，
 * 素材在 `tools/fish_src/`，`--stickers` 一次重刷四套。
 */
enum class HeaderStickerKind(
    internal val assetDir: String,
    /** 每帧自己的时长：素材里有停两拍的格子，统一成 80ms 会把节奏踩平。 */
    internal val frameMillis: LongArray,
) {
    HOME("home", LongArray(24) { 80L }),
    LEDGER("ledger", LongArray(24) { 80L }),
    STATISTICS("statistics", LongArray(24) { 80L }),
    SETTINGS(
        "settings",
        longArrayOf(80, 160, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 160, 160, 80, 160, 160, 80),
    ),
}

/** 页面身份 → 肥鱼的哪张台词贴纸。 */
internal fun HeaderPage.stickerKind(): HeaderStickerKind = when (this) {
    HeaderPage.HOME -> HeaderStickerKind.HOME
    HeaderPage.LEDGER -> HeaderStickerKind.LEDGER
    HeaderPage.STATISTICS -> HeaderStickerKind.STATISTICS
    HeaderPage.SETTINGS -> HeaderStickerKind.SETTINGS
}

private object HeaderStickerCache {
    private val firstFrames = HashMap<HeaderStickerKind, ImageBitmap>()
    private val allFrames = HashMap<HeaderStickerKind, List<ImageBitmap>>()

    fun first(context: Context, kind: HeaderStickerKind): ImageBitmap = synchronized(this) {
        firstFrames.getOrPut(kind) { decode(context, kind, 0) }
    }

    fun all(context: Context, kind: HeaderStickerKind): List<ImageBitmap> = synchronized(this) {
        allFrames.getOrPut(kind) { kind.frameMillis.indices.map { decode(context, kind, it) } }
    }

    private fun decode(context: Context, kind: HeaderStickerKind, index: Int): ImageBitmap =
        context.assets.open("fish_header/${kind.assetDir}/${index.toString().padStart(2, '0')}.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
}

/**
 * 一直循环：帧转完接着从头来，中途不会被停住。每帧时长可以不一样
 * （设置那张有几个 160ms 的长拍）。抽成普通挂起函数，单元测试里能用虚拟时间验证。
 */
internal suspend fun loopStickerFrames(
    frameMillis: LongArray,
    onFrame: (Int) -> Unit,
): Nothing {
    while (true) {
        for (index in frameMillis.indices) {
            onFrame(index)
            delay(frameMillis[index])
        }
    }
}

/** 等长帧的简写：整段都是同一个节奏时不用手写字面量数组。 */
internal suspend fun loopStickerFrames(
    frameCount: Int,
    frameMillis: Long,
    onFrame: (Int) -> Unit,
): Nothing = loopStickerFrames(LongArray(frameCount) { frameMillis }, onFrame)

/** 首帧同步解码避免闪白，其余帧后台读完再开转；高度定死、宽度按素材原比例。 */
@Composable
fun LoopingFishSticker(
    kind: HeaderStickerKind = HeaderStickerKind.HOME,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
) {
    val context = LocalContext.current
    val firstFrame = remember(kind) { HeaderStickerCache.first(context, kind) }
    var frames by remember(kind) { mutableStateOf(listOf(firstFrame)) }
    LaunchedEffect(kind) {
        frames = withContext(Dispatchers.IO) { HeaderStickerCache.all(context, kind) }
    }

    var frameIndex by remember(kind) { mutableIntStateOf(0) }
    LaunchedEffect(frames.size, kind) {
        if (frames.size <= 1) return@LaunchedEffect
        loopStickerFrames(kind.frameMillis) { frameIndex = it }
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

// ---- 头卡右上角的点击反馈 ----------------------------------------------------------

/** 74dp：槽位跟原来的气泡一样大，但里面不再塞一圈光环，四页的视觉分量终于齐了。 */
internal val STICKER_SLOT_SIZE = 74.dp

/** 字幕最宽也就四字，卡在 41dp 才不会顶到槽位边。 */
internal val STICKER_HEIGHT = 41.dp

/**
 * 头卡右上角：一直循环的角色贴纸 + 点一下才播的页面专属小动画。
 *
 * 以前这里套着一圈像素气泡，点一下播「戳破」的 20 帧 GIF；气泡本身太抢戏，
 * 把角色整个裹住，跟另外三页的头卡不搭。现在改成代码画的四种反馈（见 [FishReaction]），
 * 贴纸仍是主角，点下去时压扁再弹起，特效只在它身边发生。
 *
 * 读屏只知道「这里能点」：stateDescription 说清当前是在循环还是刚被点过。
 */
@Composable
fun FishHeaderSticker(
    kind: HeaderStickerKind = HeaderStickerKind.HOME,
    modifier: Modifier = Modifier,
) {
    val reaction = remember(kind) { kind.reaction() }
    var playing by remember(kind) { mutableStateOf(false) }
    var progress by remember(kind) { mutableFloatStateOf(1f) }

    LaunchedEffect(kind, playing) {
        if (!playing) return@LaunchedEffect
        playFishReaction(reaction.durationMillis) { progress = it }
        playing = false
        progress = 1f
    }

    // 待机时 t=1：所有特效的曲线在末帧都是「什么都不画」，贴纸也回到原尺寸
    val t = if (playing) progress else 1f
    val squash = reactionSquash(reaction, t)

    Box(
        modifier = modifier
            .size(STICKER_SLOT_SIZE)
            .testTag(REACTION_TEST_TAG)
            .semantics {
                stateDescription = if (playing) REACTION_STATE_PLAYING else REACTION_STATE_IDLE
            }
            // 点一下就播，不叠涟漪；动作没播完再点不重新开始，避免把节奏踩乱
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!playing) {
                    progress = 0f
                    playing = true
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawReactionBack(reaction, t) }
        LoopingFishSticker(
            kind = kind,
            modifier = Modifier
                .align(Alignment.Center)
                // 贴纸素材人物在上、字幕在下，整体居中会显得偏下，抬 2dp 找平
                .offset(y = (-3).dp)
                // 以底边为轴压扁，跟预览脚本 paste_sticker 的锚点一致
                .graphicsLayer(
                    scaleX = reactionWidthScale(squash),
                    scaleY = squash,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                ),
            height = STICKER_HEIGHT,
        )
        Canvas(Modifier.fillMaxSize()) { drawReactionFront(reaction, t) }
    }
}
