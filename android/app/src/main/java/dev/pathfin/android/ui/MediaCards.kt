@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.pathfin.android.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import kotlin.math.roundToInt

@Composable
internal fun BrowserMediaCard(item: MediaItem, session: Session, api: JellyfinApi, style: PosterStyle, modifier: Modifier = Modifier, compact: Boolean = false, imageWidth: androidx.compose.ui.unit.Dp? = null, libraryStyle: Boolean = false,
    selected: Boolean? = null, onLongClick: (() -> Unit)? = null, onOpen: () -> Unit) {
    val showBadges = LocalAppPreferences.current.showPosterBadges
    val titleLines = if (compact || libraryStyle) 1 else 2
    val subtitleFontSize = if (compact && !libraryStyle) 11.sp else 12.sp
    val subtitleLineHeight = if (compact && !libraryStyle) 14.sp else 16.sp
    val cardShape = RoundedCornerShape(if (libraryStyle) 10.dp else 12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    // 外层 Column 负责整卡点击但不绘制直角阴影；按压反馈仅绘制在圆角封面容器内。
    Column(
        modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onOpen,
            onLongClick = onLongClick,
            role = if (selected == null) Role.Button else Role.Checkbox,
        ).then(if (selected == null) Modifier else Modifier.semantics { this.selected = selected }),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(style.aspectRatio)
                .clip(cardShape)
                .border(PathfinAppearance.cardBorder, cardShape)
                .indication(interactionSource, LocalIndication.current),
        ) {
            PosterImage(item, session, api, Modifier.matchParentSize(), shape = cardShape, imageType = when (style) {
                PosterStyle.POSTER -> "Primary"; PosterStyle.BACKDROP -> "Backdrop"
            }, displayWidth = imageWidth, displayAspectRatio = style.aspectRatio)
            if (showBadges) PosterArtworkMarks(item)
            WatchProgress(item, Modifier.align(Alignment.BottomCenter))
            if (selected != null) SelectionMark(selected, cardShape)
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact || libraryStyle) 2.dp else 5.dp)) {
            Text(
                if (libraryStyle) libraryCardTitle(item) else posterCardTitle(item, compact),
                Modifier.fillMaxWidth()
                    .height(with(LocalDensity.current) { (scaledLineHeightPx(14.sp, 20.sp) * titleLines + 2).toDp() })
                    .testTag("poster-title/${item.id}"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = PathfinAppearance.textMain,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                ),
                textAlign = TextAlign.Start,
                maxLines = titleLines,
                minLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (libraryStyle) libraryCardSubtitle(item) else posterCardSubtitle(item, compact)).ifBlank { " " },
                Modifier.fillMaxWidth()
                    .height(with(LocalDensity.current) { (scaledLineHeightPx(subtitleFontSize, subtitleLineHeight) + 2).toDp() })
                    .testTag("poster-subtitle/${item.id}"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = subtitleFontSize,
                    lineHeight = subtitleLineHeight,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = PathfinAppearance.textSub,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 库卡片保持单行主名；单集使用服务器真实提供的剧名。 */
internal fun libraryCardTitle(item: MediaItem): String =
    if (item.type == "Episode") item.seriesName?.trim()?.takeIf(String::isNotEmpty) ?: item.name else item.name

/** ChildCount 的含义取决于条目类型；没有数量或日期时留空，不额外逐卡请求。 */
internal fun libraryCardSubtitle(item: MediaItem): String {
    val year = item.year?.takeIf { it in 1..9999 }
    return when (item.type) {
        "Folder" -> tr("文件夹")
        "Series" -> {
            val years = year?.let { start ->
                item.endYear?.takeIf { it in (start + 1)..9999 }?.let { "$start–$it" } ?: start.toString()
            }.orEmpty()
            listOfNotNull(item.childCount?.takeIf { it > 0 }?.let { tr("共%1\$s季", it) }, years.takeIf(String::isNotEmpty))
                .joinToString(" · ")
        }
        "Season" -> listOfNotNull(item.childCount?.takeIf { it > 0 }?.let { tr("共%1\$s集", it) }, year?.toString()).joinToString(" · ")
        "Episode" -> {
            val code = buildString {
                item.parentIndexNumber?.takeIf { it >= 0 }?.let { append('S').append(it) }
                item.indexNumber?.takeIf { it >= 0 }?.let { append('E').append(it) }
            }
            listOf(code, item.name.trim().takeUnless { it == libraryCardTitle(item) }.orEmpty())
                .filter(String::isNotBlank).joinToString(tr("："))
        }
        else -> year?.toString().orEmpty()
    }
}

/** 直接写在封面上的小字：带柔和投影的白字，不加底牌，读起来像海报自带的信息。 */
private val RatingGold = Color(0xFFF5C451)

internal val ArtworkTextStyle = TextStyle(
    color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 4f),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * 封面上的标记：评分写在底部渐隐里；已看与未看集数都在右上角小圆里，在任何封面上都读得清。
 */
/** 多选态：未选中的封面轻压暗，选中的描点缀色边并在左上角打勾。 */
@Composable
private fun BoxScope.SelectionMark(selected: Boolean, shape: androidx.compose.ui.graphics.Shape) {
    if (selected) Box(Modifier.matchParentSize().border(2.dp, PathfinAppearance.accent, shape))
    else Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .18f)))
    val mark = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
    // 未选只留一圈白色细环，避免和已选的勾混淆。
    if (selected) Box(mark.background(Color.White), contentAlignment = Alignment.Center) {
        Icon(PathfinIcons.CheckCircleFilled, null, Modifier.size(22.dp), tint = PathfinAppearance.accent)
    } else Box(mark.background(Color.Black.copy(alpha = .28f)).border(1.5.dp, Color.White, CircleShape))
}

@Composable
private fun BoxScope.PosterArtworkMarks(item: MediaItem) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    item.communityRating?.takeIf { it.isFinite() && it in 0.0..10.0 }?.let { rating ->
        val label = ratingText(rating)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp).testTag("poster-rating")
                .semantics(mergeDescendants = true) { contentDescription = tr("评分 %1\$s", label) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(PathfinIcons.Star, null, Modifier.size(10.dp * scale), tint = RatingGold)
            Text(label, Modifier.width(IntrinsicSize.Max), style = ArtworkTextStyle.copy(color = RatingGold), maxLines = 1)
        }
    }
    // 已看与未看集数共用一枚小圆：毛玻璃开启时为烟熏玻璃（封面本身在采样源内，按半透明深底加细白边呈现），否则为主题色实底。
    val glass = frostedEnabled
    val badge = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).then(
        if (glass) Modifier.background(Color.Black.copy(alpha = 0.38f)).border(0.5.dp, Color.White.copy(alpha = 0.32f), CircleShape)
        else Modifier.background(PathfinAppearance.accent))
    val onBadge = if (glass) Color.White else PathfinAppearance.onAccent
    if (item.played) {
        Box(badge.size(18.dp * scale).semantics { contentDescription = tr("已观看") }, contentAlignment = Alignment.Center) {
            Icon(PathfinIcons.Check, null, Modifier.size(12.dp * scale), tint = onBadge)
        }
    } else item.unplayedItemCount?.takeIf { it > 0 }?.let { count ->
        Box(
            badge.heightIn(min = 18.dp * scale).widthIn(min = 18.dp * scale).padding(horizontal = 5.dp)
                .semantics(mergeDescendants = true) { contentDescription = tr("未观看 %1\$s 项", count) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 999) "999+" else count.toString(), Modifier.width(IntrinsicSize.Max), maxLines = 1,
                style = ArtworkTextStyle.copy(color = onBadge, fontSize = 10.5.sp, shadow = null))
        }
    }
}

/** 首页按剧名识别单集，浏览网格保留条目本身的名称。 */
internal fun posterCardTitle(item: MediaItem, compact: Boolean): String =
    if (compact && item.type in setOf("Episode", "Season")) item.seriesName?.takeIf(String::isNotBlank) ?: item.name else item.name

internal fun posterCardSubtitle(item: MediaItem, compact: Boolean): String {
    val episodeCode = buildString {
        item.parentIndexNumber?.takeIf { it >= 0 }?.let { append('S').append(it) }
        item.indexNumber?.takeIf { it >= 0 }?.let { append('E').append(it) }
    }
    if (!compact) return if (item.type == "Episode") episodeCode else item.year?.toString().orEmpty()
    return when (item.type) {
        "Episode" -> listOf(episodeCode, item.name.trim().takeUnless { it == posterCardTitle(item, true) }.orEmpty())
            .filter(String::isNotBlank).joinToString(tr("："))
        "Season" -> listOf(item.indexNumber?.takeIf { it >= 0 }?.let { tr("第%1\$s季", it) }
            ?: item.name.takeUnless { it == posterCardTitle(item, true) }.orEmpty(), item.year?.toString().orEmpty())
            .filter(String::isNotBlank).joinToString(" · ")
        "Series" -> {
            // Latest 的分组 ChildCount 已在 API 边界清空；这里仅采用真正的季数。
            val years = item.year?.let { start ->
                item.endYear?.takeIf { it > start }?.let { "$start–$it" } ?: start.toString()
            }.orEmpty()
            listOf(item.childCount?.takeIf { it > 0 }?.let { tr("共%1\$s季", it) }.orEmpty(), years)
                .filter(String::isNotBlank).joinToString(" · ")
        }
        else -> item.year?.toString().orEmpty()
    }
}

/** 与首页实际海报、单行标题和副文案共用的预留高度，随字号增长。 */
@Composable
internal fun compactPosterHeight() = with(LocalDensity.current) {
    // 图片先将112dp宽度取整，再按宽高比测量；不能用168dp直接估高。
    val imageHeight = (112.dp.roundToPx() / PosterStyle.POSTER.aspectRatio).roundToInt()
    (imageHeight + 5.dp.roundToPx() + 2.dp.roundToPx() + scaledLineHeightPx(14.sp, 20.sp) + scaledLineHeightPx(11.sp, 14.sp) + 4).toDp()
}
