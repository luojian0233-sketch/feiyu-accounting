package com.local.autobookkeeping.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.ledger.LedgerGroup
import com.local.autobookkeeping.ui.ledger.LedgerScreen
import com.local.autobookkeeping.ui.ledger.LedgerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate

/**
 * 四个页面共用一张头卡：标题换成页名，装饰语言不变。
 * 截图落 `build/screenshots/`，用来确认四张卡并排时不打架、账单页的工具行没被挤坏。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PageChromeRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fourPageHeadersRender() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    ThemedHomeHeader()
                    Spacer(Modifier.height(12.dp))
                    ThemedHomeHeader(
                        title = "账单",
                        subtitle = "每一笔都算得清",
                        page = HeaderPage.LEDGER,
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemedHomeHeader(
                        title = "统计",
                        subtitle = "钱都花在哪儿了",
                        page = HeaderPage.STATISTICS,
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemedHomeHeader(
                        title = "设置",
                        subtitle = "按你的习惯来",
                        page = HeaderPage.SETTINGS,
                    )
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        save("page-headers")
    }

    @Test
    fun fourBubblesSideBySide() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    HeaderPage.entries.forEach { page ->
                        ThemedHomeHeader(title = page.name, page = page)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        save("page-sticker-kinds")
    }

    /** 四个气泡并排看：贴纸大小、字幕位置是否一致，四页是不是各说各的。 */
    @Test
    fun stickerKindsCloseup() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF16365E))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    HeaderStickerKind.entries.forEach { kind -> FishHeaderSticker(kind = kind) }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        save("page-sticker-bubbles")
    }

    @Test
    fun ledgerScreenKeepsToolRow() {
        compose.setContent {
            AutoBookkeepingTheme(ThemeStyle.FATTY_FISH) {
                LedgerScreen(
                    state = LedgerUiState(groups = listOf(sampleGroup())),
                    onFiltersChanged = {},
                    onEdit = {},
                    onDelete = {},
                    onConfirm = {},
                )
            }
        }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        save("page-ledger")
    }

    private fun sampleGroup() = LedgerGroup(
        date = LocalDate.of(2026, 11, 18),
        transactions = listOf(
            Transaction(
                id = 1,
                amountMinor = 2_800,
                direction = Direction.EXPENSE,
                category = "餐饮",
                account = "支付宝",
                merchant = "楼下小面馆",
                occurredAt = Instant.parse("2026-11-18T04:10:00Z"),
            ),
            Transaction(
                id = 2,
                amountMinor = 1_200,
                direction = Direction.EXPENSE,
                category = "交通",
                account = "微信",
                merchant = "地铁",
                occurredAt = Instant.parse("2026-11-18T00:20:00Z"),
            ),
        ),
    )

    private fun save(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        FileOutputStream(File(dir, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
