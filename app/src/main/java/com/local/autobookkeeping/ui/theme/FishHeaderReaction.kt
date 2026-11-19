package com.local.autobookkeeping.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 肥鱼头卡右上角的四种点击反馈。素材还是 `assets/fish_header/` 那四张循环贴纸，
 * 但外面那层「像素气泡」已经不用了——它把整张头卡裹住，分量比其他三页重太多。
 *
 * 现在每页只做一件小事，动作曲线全在代码里（跟 `tools/gen_fish_reaction_preview.py`
 * 一一对应，预览脚本先出 GIF 给人看，这里才是真正跑在 App 里的版本）：
 * 首页蹦五颗小星星、账单落金币、统计盖一个角标、设置荡两圈像素光环。
 * 所有动作共用一条 40ms 时间轴，贴纸本身做一个「先压扁、再弹起」的 squash。
 */
internal enum class FishReaction(
    val durationMillis: Long,
    val label: String,
) {
    STARS(1_050L, "好运星星"),
    COINS(1_400L, "金币雨"),
    STAMP(900L, "像素盖章"),
    RING(1_050L, "光圈弹跳"),
}

internal fun HeaderStickerKind.reaction(): FishReaction = when (this) {
    HeaderStickerKind.HOME -> FishReaction.STARS
    HeaderStickerKind.LEDGER -> FishReaction.COINS
    HeaderStickerKind.STATISTICS -> FishReaction.STAMP
    HeaderStickerKind.SETTINGS -> FishReaction.RING
}

internal const val REACTION_STEP_MILLIS = 40L
internal const val REACTION_TEST_TAG = "fishHeaderReaction"
internal const val REACTION_STATE_IDLE = "贴纸待机"
internal const val REACTION_STATE_PLAYING = "贴纸回应中"

private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
private fun easeOut(t: Float): Float {
    val x = clamp01(t)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}
private fun easeIn(t: Float): Float {
    val x = clamp01(t)
    return x * x
}

// ---- 贴纸的压扁曲线：1.0 是原样 -----------------------------------------------------

private fun starsSquash(t: Float): Float = when {
    t < 0.10f -> lerp(1f, 0.82f, easeOut(t / 0.10f))
    t < 0.30f -> lerp(0.82f, 1.12f, easeOut((t - 0.10f) / 0.20f))
    t < 0.52f -> lerp(1.12f, 0.97f, easeOut((t - 0.30f) / 0.22f))
    else -> lerp(0.97f, 1f, (t - 0.52f) / 0.48f)
}

private fun stampSquash(t: Float): Float = when {
    t < 0.14f -> lerp(1f, 0.84f, easeOut(t / 0.14f))
    t < 0.32f -> lerp(0.84f, 1.07f, easeOut((t - 0.14f) / 0.18f))
    t < 0.55f -> lerp(1.07f, 0.98f, easeOut((t - 0.32f) / 0.23f))
    else -> lerp(0.98f, 1f, (t - 0.55f) / 0.45f)
}

private fun ringSquash(t: Float): Float = when {
    t < 0.12f -> lerp(1f, 0.86f, easeOut(t / 0.12f))
    t < 0.34f -> lerp(0.86f, 1.10f, easeOut((t - 0.12f) / 0.22f))
    t < 0.58f -> lerp(1.10f, 0.97f, easeOut((t - 0.34f) / 0.24f))
    else -> lerp(0.97f, 1f, (t - 0.58f) / 0.42f)
}

private val COIN_STARTS = floatArrayOf(0.00f, 0.08f, 0.16f, 0.25f, 0.34f, 0.44f, 0.54f)
private const val COIN_SPAN = 0.46f

private fun coinsSquash(t: Float): Float {
    var s = 1f
    for (start in COIN_STARTS) {
        val hit = start + COIN_SPAN * 0.58f
        val d = (t - hit) / 0.055f
        if (d > -3f && d < 3f) s -= 0.15f * exp(-d * d)
    }
    return maxOf(0.82f, s)
}

/** 给贴纸做缩放用：改高度时宽度反向补一点，压扁才像「有体积地被按下」。 */
internal fun reactionSquash(reaction: FishReaction, t: Float): Float = when (reaction) {
    FishReaction.STARS -> starsSquash(t)
    FishReaction.COINS -> coinsSquash(t)
    FishReaction.STAMP -> stampSquash(t)
    FishReaction.RING -> ringSquash(t)
}

internal fun reactionWidthScale(squash: Float): Float = 1f + (1f - squash) * 0.55f

/** 一次完整反馈：0→1 走 [durationMillis]，每 40ms 推一帧，抽出来用虚拟时间验节奏。 */
internal suspend fun playFishReaction(durationMillis: Long, onFrame: (Float) -> Unit) {
    val steps = (durationMillis / REACTION_STEP_MILLIS).toInt()
    for (i in 0..steps) {
        onFrame(i.toFloat() / steps)
        if (i < steps) delay(REACTION_STEP_MILLIS)
    }
}

// ---- 绘制：所有尺寸都按槽位边长 S 取比例，换密度也不跑位 -----------------------------

private val INK = Color(226, 244, 255)
private val RING_COLOR = Color(176, 236, 255)
private val STAR_GLOW = Color(255, 226, 128)
private val STAR_CORE = Color(255, 248, 214)
private val COIN_INK = Color(122, 76, 12)
private val COIN_DARK = Color(222, 165, 44)
private val COIN_FILL = Color(255, 214, 96)
private val COIN_LIGHT = Color(255, 246, 206)

private val STAR_BIG = listOf("..A..", ".A.A.", "A.B.A", ".A.A.", "..A..")
private val STAR_SMALL = listOf(".A.", "ABA", ".A.")

private val COIN_SPRITE: Array<Array<Color?>> = run {
    val n = 11
    val r = n / 2f
    Array(n) { y ->
        Array(n) { x ->
            val dx = x + 0.5f - r
            val dy = y + 0.5f - r
            val d = kotlin.math.hypot(dx, dy)
            when {
                d > r -> null
                d > r - 1.1f -> COIN_INK
                dx + dy > 1.2f -> COIN_DARK
                else -> COIN_FILL
            }
        }
    }.also { grid ->
        for ((x, y) in listOf(4 to 3, 3 to 4, 4 to 4)) grid[y][x] = COIN_LIGHT
    }
}

/** 星星画在贴纸下层，像是从角色身后蹦出来的。 */
private fun DrawScope.drawStars(t: Float) {
    val s = size.minDimension
    val unit = (s / 64f).coerceAtLeast(3f)
    val ox = 0.50f * s
    val oy = 0.44f * s
    val starts = floatArrayOf(0.02f, 0.11f, 0.20f, 0.30f, 0.40f)
    for ((i, start) in starts.withIndex()) {
        val p = clamp01((t - start) / 0.52f)
        if (p <= 0f || p >= 0.88f) continue
        val angle = -Math.PI.toFloat() / 2f + (i - 2) * 0.62f
        val dist = lerp(0.055f * s, (0.24f + 0.035f * (i % 3)) * s, easeOut(p))
        val x = ox + cos(angle) * dist
        val y = oy + sin(angle) * dist * 0.92f
        val big = p >= 0.14f && p < 0.60f && ((t * 1000f / 80f).toLong() % 2L == 0L)
        val mask = if (big) STAR_BIG else STAR_SMALL
        val x0 = x - mask[0].length * unit / 2f
        val y0 = y - mask.size * unit / 2f
        for ((ry, row) in mask.withIndex()) {
            for ((rx, ch) in row.withIndex()) {
                if (ch == '.') continue
                drawRect(
                    color = if (ch == 'B') STAR_CORE else STAR_GLOW,
                    topLeft = Offset(x0 + rx * unit, y0 + ry * unit),
                    size = Size(unit, unit),
                )
            }
        }
    }
}

private fun DrawScope.drawStamp(t: Float) {
    val fade = when {
        t < 0.05f -> 0f
        t < 0.18f -> easeOut((t - 0.05f) / 0.13f)
        t > 0.72f -> (1f - easeOut((t - 0.72f) / 0.26f)).coerceAtLeast(0f)
        else -> 1f
    }
    if (fade <= 0.01f) return
    val s = size.minDimension
    val color = INK.copy(alpha = fade)
    val left = 0.20f * s
    val right = 0.80f * s
    val top = 0.13f * s
    val bottom = 0.60f * s
    val thick = 0.034f * s
    val arm = 0.10f * s
    for (cx in listOf(left, right)) {
        for (cy in listOf(top, bottom)) {
            val sx = if (cx == left) 1f else -1f
            val sy = if (cy == top) 1f else -1f
            drawRect(
                color,
                Offset(minOf(cx, cx + arm * sx), minOf(cy, cy + thick * sy)),
                Size(arm, thick),
            )
            drawRect(
                color,
                Offset(minOf(cx, cx + thick * sx), minOf(cy, cy + arm * sy)),
                Size(thick, arm),
            )
        }
    }
    val grow = easeOut((t - 0.09f) / 0.26f)
    if (grow > 0f) {
        val half = 0.22f * s * grow
        drawRect(
            INK.copy(alpha = fade * 225f / 240f),
            Offset(s / 2f - half, 0.885f * s),
            Size(half * 2f, 0.022f * s),
        )
    }
}

/** 在槽位上按格子摆小方块拼出圆环，保住像素游戏的阶梯边，不用抗锯齿的圆。 */
private fun DrawScope.drawPixelRing(radiusFrac: Float, thickFrac: Float, alpha: Int) {
    val s = size.minDimension
    val cell = s / 75f
    val radius = radiusFrac * s
    val thick = (thickFrac * s).coerceAtLeast(cell)
    val color = RING_COLOR.copy(alpha = alpha / 255f)
    val cx = s / 2f
    val cy = s / 2f
    val steps = 120
    for (k in 0 until steps) {
        val a = (k * 2.0 * Math.PI / steps).toFloat()
        // 把采样点吸到格子上，环边才会是一格一格的阶梯而不是平滑圆
        val gx = ((cx + cos(a) * radius) / cell).toInt() * cell
        val gy = ((cy + sin(a) * radius) / cell).toInt() * cell
        drawRect(color, Offset(gx - thick / 2f, gy - thick / 2f), Size(thick, thick))
    }
}

private fun DrawScope.drawRings(t: Float) {
    for ((start, span, thick) in listOf(
        Triple(0.00f, 0.62f, 0.055f),
        Triple(0.10f, 0.66f, 0.030f),
    )) {
        val p = clamp01((t - start) / span)
        if (p <= 0f || p >= 1f) continue
        val radius = lerp(0.185f, 0.455f, easeOut(p))
        val alpha = (245f * (1f - p).pow(0.85f)).toInt()
        if (alpha > 5) drawPixelRing(radius, thick, alpha)
    }
}

private fun DrawScope.drawCoins(t: Float) {
    // 待机态 t 恒为 1。这里必须先挡一道：`(1f - 0.54f) / 0.46f` 在 Float 下算出
    // 0.99999996，过不了下面的 `p >= 1f`，最后一枚金币就会永久停在「已落出槽位」
    // 的位置，卡片右下角挂着一枚被圆角切掉一半的金币。
    if (t >= 1f) return
    val s = size.minDimension
    val unit = (s / 64f).coerceAtLeast(3f)
    val land = 0.255f * s
    for ((i, start) in COIN_STARTS.withIndex()) {
        val p = clamp01((t - start) / COIN_SPAN)
        if (p <= 0f || p >= 1f) continue
        val x = (0.17f + 0.115f * i) * s + 4f * sin(p * 2f * Math.PI.toFloat() + i)
        val y: Float
        val squish: Float
        when {
            p < 0.62f -> {
                y = lerp(-0.12f * s, land, clamp01(p / 0.62f).pow(1.7f))
                squish = 1f
            }
            p < 0.78f -> {
                val q = (p - 0.62f) / 0.16f
                y = lerp(land, land - 0.115f * s, sin(q * Math.PI.toFloat()))
                squish = 0.80f
            }
            else -> {
                val q = (p - 0.78f) / 0.22f
                y = lerp(land - 0.015f * s, 1.16f * s, easeIn(q))
                squish = 0.92f
            }
        }
        val half = 11 * unit / 2f
        for ((ry, row) in COIN_SPRITE.withIndex()) {
            for ((rx, color) in row.withIndex()) {
                if (color == null) continue
                drawRect(
                    color,
                    Offset(x - half + rx * unit, y - half + ry * unit * squish),
                    Size(unit - 0.4f, unit * squish - 0.4f),
                )
            }
        }
    }
}

/** 背景特效：星星在贴纸后、盖章与光圈也在贴纸后。 */
internal fun DrawScope.drawReactionBack(reaction: FishReaction, t: Float) {
    when (reaction) {
        FishReaction.STARS -> drawStars(t)
        FishReaction.STAMP -> drawStamp(t)
        FishReaction.RING -> drawRings(t)
        FishReaction.COINS -> Unit
    }
}

/** 金币要从头顶砸下来，所以画在贴纸上层。 */
internal fun DrawScope.drawReactionFront(reaction: FishReaction, t: Float) {
    if (reaction == FishReaction.COINS) drawCoins(t)
}
