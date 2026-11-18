package com.local.autobookkeeping.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 头卡上那枚「标注徽章」认的页面身份。四套皮肤共用同一批页面，
 * 每套皮肤给每个页面准备一枚自己的徽章（见 [HeaderBadgeKind]）。
 */
enum class HeaderPage {
    HOME,
    LEDGER,
    STATISTICS,
    SETTINGS,
}

/** 待机循环：16 帧、每帧 100ms，1.6 秒一轮呼吸。 */
internal const val BADGE_IDLE_FRAMES = 16
internal const val BADGE_IDLE_FRAME_MILLIS = 100L

/** 戳一下：起手两帧慢、动作段压快、收尾再放慢，和脚本里的 TAP_MILLIS 对齐。 */
internal val BADGE_TAP_FRAME_MILLIS = longArrayOf(
    90, 80, 60, 60, 60, 70, 70, 80, 80, 90, 90, 100, 100, 110, 110, 120,
)

internal const val BADGE_TEST_TAG = "headerBadge"
internal const val BADGE_STATE_IDLE = "徽章待机"
internal const val BADGE_STATE_TAPPED = "徽章按下"

/** 56dp：头卡 100dp，上下各留 22dp，和小票齿孔、圆章的边都不打架。 */
internal val BADGE_SIZE = 56.dp

/**
 * 三套非肥鱼皮肤 × 四个页面，素材由 `tools/gen_header_badges.py` 生成到
 * `assets/header_badge/<skin>_<page>/{idle,tap}/`。
 *
 * 图标语义四套皮肤一致（今天=太阳 / 账单=单据 / 统计=柱状 / 设置=齿轮或滑块），
 * 只换材质：薄荷是玻璃圆章、账本是齿孔纸签、纸感是墨线批注。
 */
enum class HeaderBadgeKind(
    /** 素材目录名，同时也是测试里核对的那份清单。 */
    internal val assetDir: String,
) {
    MINT_HOME("mint_home"),
    MINT_LEDGER("mint_ledger"),
    MINT_STATISTICS("mint_statistics"),
    MINT_SETTINGS("mint_settings"),
    RECEIPT_HOME("receipt_home"),
    RECEIPT_LEDGER("receipt_ledger"),
    RECEIPT_STATISTICS("receipt_statistics"),
    RECEIPT_SETTINGS("receipt_settings"),
    PAPER_HOME("paper_home"),
    PAPER_LEDGER("paper_ledger"),
    PAPER_STATISTICS("paper_statistics"),
    PAPER_SETTINGS("paper_settings"),
}

/** 皮肤 + 页面 → 徽章。肥鱼不用徽章（那头有像素气泡），返回 null。 */
internal fun headerBadgeFor(decor: HeaderDecor, page: HeaderPage): HeaderBadgeKind? = when (decor) {
    HeaderDecor.MINT_SPROUT -> when (page) {
        HeaderPage.HOME -> HeaderBadgeKind.MINT_HOME
        HeaderPage.LEDGER -> HeaderBadgeKind.MINT_LEDGER
        HeaderPage.STATISTICS -> HeaderBadgeKind.MINT_STATISTICS
        HeaderPage.SETTINGS -> HeaderBadgeKind.MINT_SETTINGS
    }
    HeaderDecor.RECEIPT_STUB -> when (page) {
        HeaderPage.HOME -> HeaderBadgeKind.RECEIPT_HOME
        HeaderPage.LEDGER -> HeaderBadgeKind.RECEIPT_LEDGER
        HeaderPage.STATISTICS -> HeaderBadgeKind.RECEIPT_STATISTICS
        HeaderPage.SETTINGS -> HeaderBadgeKind.RECEIPT_SETTINGS
    }
    HeaderDecor.PAPER_RULE -> when (page) {
        HeaderPage.HOME -> HeaderBadgeKind.PAPER_HOME
        HeaderPage.LEDGER -> HeaderBadgeKind.PAPER_LEDGER
        HeaderPage.STATISTICS -> HeaderBadgeKind.PAPER_STATISTICS
        HeaderPage.SETTINGS -> HeaderBadgeKind.PAPER_SETTINGS
    }
    HeaderDecor.OCEAN_WAVE -> null
}

private object BadgeFrameCache {
    private val firstIdle = HashMap<HeaderBadgeKind, ImageBitmap>()
    private val idleFrames = HashMap<HeaderBadgeKind, List<ImageBitmap>>()
    private val tapFrames = HashMap<HeaderBadgeKind, List<ImageBitmap>>()

    fun firstIdle(context: Context, kind: HeaderBadgeKind): ImageBitmap = synchronized(this) {
        firstIdle.getOrPut(kind) { decode(context, kind, "idle", 0) }
    }

    fun idle(context: Context, kind: HeaderBadgeKind): List<ImageBitmap> = synchronized(this) {
        idleFrames.getOrPut(kind) { (0 until BADGE_IDLE_FRAMES).map { decode(context, kind, "idle", it) } }
    }

    fun tap(context: Context, kind: HeaderBadgeKind): List<ImageBitmap> = synchronized(this) {
        tapFrames.getOrPut(kind) { BADGE_TAP_FRAME_MILLIS.indices.map { decode(context, kind, "tap", it) } }
    }

    private fun decode(context: Context, kind: HeaderBadgeKind, state: String, index: Int): ImageBitmap =
        context.assets.open("header_badge/${kind.assetDir}/$state/${index.toString().padStart(2, '0')}.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
}

/** 一次「按下」：按每帧自己的时长走完 16 帧，抽出来是为了能用虚拟时间验证节奏。 */
internal suspend fun playBadgeTap(frameMillis: LongArray, onFrame: (Int) -> Unit) {
    for (index in frameMillis.indices) {
        onFrame(index)
        delay(frameMillis[index])
    }
}

/**
 * 头卡右上角的标注徽章：平时按 100ms 循环呼吸，点一下播一遍「按下」
 * （弹缩、荡开一圈、收尾），播完接着回到待机循环。
 *
 * 和气泡联动的那套一样：首帧同步解码防闪白，其余帧进页面后在后台读完；
 * 点击用 clickable + 关掉 indication，徽章自己就是反馈，不叠涟漪。
 */
@Composable
fun HeaderBadge(
    kind: HeaderBadgeKind,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val firstFrame = remember(kind) { BadgeFrameCache.firstIdle(context, kind) }
    var idleFrames by remember(kind) { mutableStateOf(listOf(firstFrame)) }
    var tapFrames by remember(kind) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var tapping by remember(kind) { mutableStateOf(false) }
    var frameIndex by remember(kind) { mutableIntStateOf(0) }

    LaunchedEffect(kind) {
        tapFrames = withContext(Dispatchers.IO) { BadgeFrameCache.tap(context, kind) }
        idleFrames = withContext(Dispatchers.IO) { BadgeFrameCache.idle(context, kind) }
    }

    LaunchedEffect(kind, tapping, idleFrames.size) {
        if (tapping || idleFrames.size <= 1) return@LaunchedEffect
        var index = 0
        while (true) {
            frameIndex = index % idleFrames.size
            index++
            delay(BADGE_IDLE_FRAME_MILLIS)
        }
    }

    LaunchedEffect(kind, tapping) {
        if (!tapping) return@LaunchedEffect
        val frames = tapFrames.ifEmpty { withContext(Dispatchers.IO) { BadgeFrameCache.tap(context, kind) } }
        playBadgeTap(BADGE_TAP_FRAME_MILLIS) { index ->
            frameIndex = index.coerceAtMost(frames.lastIndex.coerceAtLeast(0))
        }
        tapping = false
    }

    val frames = if (tapping) tapFrames else idleFrames

    Image(
        bitmap = frames.getOrNull(frameIndex) ?: firstFrame,
        contentDescription = null,
        modifier = modifier
            .size(BADGE_SIZE)
            .testTag(BADGE_TEST_TAG)
            .semantics { stateDescription = if (tapping) BADGE_STATE_TAPPED else BADGE_STATE_IDLE }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!tapping) tapping = true },
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
    )
}
