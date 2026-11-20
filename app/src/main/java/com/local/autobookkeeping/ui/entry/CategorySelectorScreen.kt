package com.local.autobookkeeping.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.autobookkeeping.R
import com.local.autobookkeeping.domain.model.BUILT_IN_CATEGORY_NAMES
import com.local.autobookkeeping.domain.model.CATEGORY_WHALE_FEED

/** 图标槽位固定高度：emoji 与手绘图标混排时标签不会上下跳。 */
private val ICON_SLOT_HEIGHT = 24.dp

private val DEFAULT_CATEGORY_ICONS = mapOf(
    "餐饮" to "🍜", "交通" to "🚕", "日用" to "🧴", "购物" to "🛍",
    "零食" to "🍦", "饮品" to "🥤", "蔬菜" to "🥬", "水果" to "🍓",
    "服饰" to "👕", "娱乐" to "🎵", "美容" to "💄", "通讯" to "☎",
    "医疗" to "💊", "学习" to "🎓", "游戏" to "🎮", "红包" to "🧧",
    "住房" to "🏠", "礼品" to "🎁", "宠物" to "🐾", "鲸鱼饲料" to "🐳",
    "汽车" to "🚗", "数码" to "📷", "办公" to "💼", "运动" to "🏓", "礼金" to "✉",
    "捐赠" to "❤", "彩票" to "🎫", "转账" to "⇄", "其他" to "•••",
    "工资" to "💰", "奖金" to "🏆", "理财" to "📈", "分红" to "💹",
    "退款" to "↩",
)

internal fun categoryIconLabel(name: String): String =
    DEFAULT_CATEGORY_ICONS[name.trim()]
        ?: name.trim().codePoints().findFirst().orElse(-1)
            .takeIf { it >= 0 }
            ?.let(Character::toChars)
            ?.concatToString()
        ?: "•••"

internal fun filterCategoryChoices(
    choices: List<EntryChoice>,
    query: String,
): List<EntryChoice> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) choices else choices.filter {
        it.name.contains(normalized, ignoreCase = true)
    }
}

internal data class CategoryChoiceSections(
    val historicalChoice: EntryChoice?,
    val availableChoices: List<EntryChoice>,
)

internal fun categoryChoiceSections(
    choices: List<EntryChoice>,
    query: String,
): CategoryChoiceSections {
    val filtered = filterCategoryChoices(choices, query)
    return CategoryChoiceSections(
        historicalChoice = filtered.firstOrNull { it.historicalOnly },
        availableChoices = filtered.filterNot { it.historicalOnly },
    )
}

internal fun defaultEntryCategoryChoices(): List<EntryChoice> =
    BUILT_IN_CATEGORY_NAMES.map { EntryChoice(it, historicalOnly = false) }

@Composable
fun CategorySelectorScreen(
    choices: List<EntryChoice>,
    selectedCategory: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(choices, query) { categoryChoiceSections(choices, query) }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "返回编辑记录" },
            ) {
                Text("‹ 返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "选择分类",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索分类") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category-search"),
        )
        Spacer(Modifier.height(8.dp))
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .align(Alignment.CenterHorizontally)
                    .testTag("category-loading"),
            )

            sections.historicalChoice == null && sections.availableChoices.isEmpty() -> Text(
                text = if (query.isBlank()) "暂无可用分类" else "没有匹配的分类",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )

            else -> {
                sections.historicalChoice?.let { choice ->
                    CategoryChoiceCell(
                        choice = choice,
                        selected = choice.name == selectedCategory,
                        onClick = { onSelect(choice.name) },
                        modifier = Modifier
                            .testTag("category-historical-choice-${choice.name}")
                            .semantics { this.selected = choice.name == selectedCategory },
                    )
                }
                if (
                    sections.historicalChoice != null &&
                    sections.availableChoices.isNotEmpty()
                ) {
                    Spacer(Modifier.height(12.dp))
                }
                if (sections.availableChoices.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 12.dp)
                            .testTag("category-grid"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            sections.availableChoices,
                            key = { "${it.name}-${it.historicalOnly}" },
                        ) { choice ->
                            CategoryChoiceCell(
                                choice = choice,
                                selected = choice.name == selectedCategory,
                                onClick = { onSelect(choice.name) },
                                modifier = Modifier
                                    .testTag("category-choice-${choice.name}")
                                    .semantics {
                                        this.selected = choice.name == selectedCategory
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChoiceCell(
    choice: EntryChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .widthIn(min = 48.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(containerColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.height(ICON_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (choice.name.trim() == CATEGORY_WHALE_FEED) {
                // 素材是带透明留白的像素立绘，圆裁会把发梢切掉，直接铺满槽位即可。
                Image(
                    painter = painterResource(R.drawable.category_whale_feed),
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SLOT_HEIGHT),
                )
            } else {
                Text(
                    text = categoryIconLabel(choice.name),
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = if (choice.historicalOnly) "${choice.name}（历史分类）" else choice.name,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
