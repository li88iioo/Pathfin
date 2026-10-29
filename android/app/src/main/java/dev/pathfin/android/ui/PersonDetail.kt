package dev.pathfin.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import java.net.URI
import java.time.LocalDate
import java.time.Period

/**
 * 人物详情：头像放大模糊作首屏底色，前景是竖版头像、姓名与原名、生平；下面是外部资料、简介，以及参演的电影与剧集。
 * 收藏在详情顶栏“更多”左侧；管理操作（识别、刷新、编辑元数据与图片）在“更多操作”里，与媒体详情同一入口。
 */
@Composable
internal fun PersonDetailContent(state: AppState, item: MediaItem, session: Session, vm: AppViewModel, onScrolled: (Boolean) -> Unit) {
    val horizontalSafeArea = AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
    val extras = state.detailExtras[item.id]
    val listState = rememberLazyListState()
    val collapseOffset = with(LocalDensity.current) { 180.dp.roundToPx() }
    val collapsed by remember(listState, collapseOffset) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseOffset } }
    LaunchedEffect(collapsed) { onScrolled(collapsed) }
    val movies = extras?.movies
    val series = extras?.series
    LazyColumn(Modifier.fillMaxSize().testTag("detail-content"), state = listState,
        contentPadding = PaddingValues(bottom = LocalNavigationBottomInset.current), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(key = "person-hero") {
            PersonHero(item, session, vm.api, movies?.totalCount ?: 0, series?.totalCount ?: 0)
        }
        state.detailError?.let { error -> item(key = "detail-error") {
            ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
        } }
        listOf("Movie" to (tr("电影") to movies), "Series" to (tr("剧集") to series)).forEach { (type, section) ->
            val (title, page) = section
            if (page != null && page.items.isNotEmpty()) item(key = "person-credits/$type") {
                Box(Modifier.windowInsetsPadding(horizontalSafeArea).testTag("person-credits/$type")) {
                    // 服务器上还有未显示的作品时才给出完整列表入口。
                    PosterShelf(title, page.items, session, vm.api, vm::open,
                        onMore = if (page.totalCount > page.items.size) ({ vm.openPersonCredits(item, type) }) else null)
                }
            }
        }
        if (extras != null && !extras.loading && extras.error == null && movies?.items.isNullOrEmpty() && series?.items.isNullOrEmpty()) {
            item(key = "person-credits-empty") { EmptyPanel(tr("暂无参演作品"), tr("当前账号可见的媒体库中没有这位人物的作品。")) }
        }
        extras?.error?.let { error -> item(key = "detail-extras-error") {
            ErrorPanel(error, modifier = Modifier.windowInsetsPadding(horizontalSafeArea).padding(horizontal = 16.dp))
        } }
    }
}

@Composable
private fun PersonHero(item: MediaItem, session: Session, api: JellyfinApi, movieCount: Int, seriesCount: Int) {
    val surface = MaterialTheme.colorScheme.surface
    val scrim = MaterialTheme.colorScheme.scrim
    val topInset = AppSafeDrawingInsets.asPaddingValues().calculateTopPadding()
    val safeArea = AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal)
    // 头像放大模糊铺满首屏，作为卡内采样源；外部资料胶囊是叠在上面的毛玻璃。
    CompositionLocalProvider(LocalTopBarBlurState provides rememberTopBarBlurState(LocalAppPreferences.current.blurTopBar)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val posterWidth = (maxWidth * .34f).coerceIn(108.dp, 150.dp)
        val backgroundHeight = topInset + 72.dp + posterWidth * 1.5f + 40.dp
        val gradient = remember(surface, scrim) { detailHeroGradient(surface, scrim, 0.92f) }
        Box(Modifier.fillMaxWidth().height(backgroundHeight).clipToBounds().topBarBlurSource(zIndex = -1f).testTag("person-hero-background")) {
            // 系统模糊需要 Android 12；更早的系统只铺渐隐，不显示一张清晰放大的头像。
            if (Build.VERSION.SDK_INT >= 31) PosterImage(item, session, api, Modifier.matchParentSize().blur(48.dp), wide = true,
                shape = RectangleShape, imageType = "Primary")
            Box(Modifier.matchParentSize().background(gradient))
        }
        Column(Modifier.fillMaxWidth().windowInsetsPadding(safeArea).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(topInset + 72.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                PosterImage(item, session, api, Modifier.width(posterWidth).aspectRatio(2f / 3f).testTag("person-portrait"),
                    shape = RoundedCornerShape(14.dp))
                Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, Modifier.testTag("person-name"), fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold,
                        color = PathfinAppearance.textMain, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    item.originalTitle?.takeIf { !it.equals(item.name, ignoreCase = true) }?.let { original ->
                        Text(original, Modifier.testTag("person-original-name"), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                            color = PathfinAppearance.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    personFacts(item).forEach { fact ->
                        Text(fact, fontSize = 13.sp, lineHeight = 19.sp, color = PathfinAppearance.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val credits = listOfNotNull(movieCount.takeIf { it > 0 }?.let { tr("电影 %1\$s 部", it) }, seriesCount.takeIf { it > 0 }?.let { tr("剧集 %1\$s 部", it) })
                    // 首屏先保留同高的一行，作品数量异步到达时姓名和生平不会向上跳动。
                    Text(credits.joinToString(" · "), Modifier.testTag("person-credit-count"),
                        fontSize = 13.sp, lineHeight = 19.sp, minLines = 1, color = PathfinAppearance.textMuted)
                }
            }
            PersonLinks(item)
            if (item.overview.isNotBlank()) DetailSynopsis(item)
        }
    }
    }
}

/** 外部人物资料：并排的小胶囊，点击打开对应网页；遵循全应用移除豆瓣入口的约定。 */
@Composable
private fun PersonLinks(item: MediaItem) {
    val uri = LocalUriHandler.current
    val links = remember(item.externalUrls) { item.externalUrls.filter { link ->
        val target = runCatching { URI(link.url) }.getOrNull()
        val host = target?.host.orEmpty().lowercase()
        target?.scheme?.lowercase() in setOf("http", "https") && host.isNotEmpty() &&
            !link.name.contains("豆瓣") /* 服务器数据，保持原文 */ && !link.name.contains("douban", ignoreCase = true) &&
            host != "douban.com" && !host.endsWith(".douban.com")
    }.distinctBy { it.url }.take(8) }
    if (links.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(links, key = { it.url }) { link ->
            Text(link.name, Modifier.clip(CircleShape).frostedControl().border(PathfinAppearance.cardBorder, CircleShape)
                .clickable(role = Role.Button, onClickLabel = tr("打开 %1\$s", link.name)) { runCatching { uri.openUri(link.url) } }
                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("person-link/${link.name}"),
                fontSize = 13.sp, lineHeight = 18.sp, color = PathfinAppearance.textMain, maxLines = 1)
        }
    }
}

/** 生平信息：按元数据日历日期展示出生、去世与出生地；异常日期不推算年龄。 */
internal fun personFacts(item: MediaItem, today: LocalDate = LocalDate.now()): List<String> {
    val birth = item.premiereDate?.let(::serverCalendarDate)
    val death = item.endDate?.let(::serverCalendarDate)
    val life = when {
        birth != null && death != null -> listOfNotNull("${dateLabel(birth)} – ${dateLabel(death)}",
            if (!death.isBefore(birth) && !death.isAfter(today)) tr("享年 %1\$s 岁", Period.between(birth, death).years) else null).joinToString(" · ")
        birth != null -> listOfNotNull(tr("%1\$s 出生", dateLabel(birth)),
            if (!birth.isAfter(today)) tr("%1\$s 岁", Period.between(birth, today).years) else null).joinToString(" · ")
        death != null -> tr("%1\$s 去世", dateLabel(death))
        else -> null
    }
    val place = item.productionLocations.firstOrNull()?.split(',', '，')?.map(String::trim)?.filter(String::isNotEmpty)?.joinToString(" · ")
    return listOfNotNull(life, place?.takeIf(String::isNotEmpty))
}

/** 出生、去世和首映日期是日历日期，不能随设备时区偏移一天；未编辑时仍保留完整服务器原值。 */
internal fun serverCalendarDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value.take(10))
}.getOrNull()?.takeIf { it.year in 1..9999 }

private fun dateLabel(date: LocalDate) = tr("%1\$s年%2\$s月%3\$s日", date.year, date.monthValue, date.dayOfMonth)
