package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.domain.TimeUnits
import dev.pathfin.android.i18n.tr

/** 主详情列表按三项分组调用；不足三项仍保留三列宽度，不把最后一张放大。 */
@Composable
internal fun DetailSeasonRow(
    seasons: List<MediaItem>,
    session: Session,
    api: JellyfinApi,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = seasons.distinctBy { it.id }.take(3)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { index ->
            val season = visible.getOrNull(index)
            if (season == null) Spacer(Modifier.weight(1f)) else key(season.id) {
                Column(Modifier.weight(1f).testTag("detail-season/${season.id}")
                    .clickable(role = Role.Button, onClick = { onOpen(season) }),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // 与媒体库海报卡同一排版：12dp 圆角封面，下方左对齐的名称与副信息。
                    PosterImage(season, session, api,
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).testTag("detail-season-poster/${season.id}"),
                        shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(season.name, Modifier.fillMaxWidth().testTag("detail-season-name/${season.id}"),
                        fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain,
                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(seasonMetadata(season).ifBlank { " " },
                        Modifier.fillMaxWidth().testTag("detail-season-count/${season.id}"),
                        fontSize = 12.sp, lineHeight = 16.sp, color = PathfinAppearance.textSub,
                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 季和单集都由调用方提供；点击只打开真实单集详情，不在组件内发起播放或伪造当前集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailEpisodePicker(
    season: MediaItem,
    items: List<MediaItem>,
    totalCount: Int,
    nextIndex: Int,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    seasons: List<MediaItem>,
    session: Session,
    api: JellyfinApi,
    onSeasonSelected: (MediaItem) -> Unit,
    onEpisodeOpen: (MediaItem) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    seasonsPage: ContentState? = null,
    onLoadSeasonsMore: () -> Unit = {},
    seasonSwitchEnabled: Boolean = true,
) {
    // 父详情的 presentationKey 在切季时保持；sheet和显示模式也应保持。
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var cards by rememberSaveable { mutableStateOf(false) }
    val episodes = items.distinctBy { it.id }
    val page = ContentState(items = episodes, total = totalCount, nextIndex = nextIndex,
        loading = loading, loadingMore = loadingMore, error = error)
    val tabs = (seasons + season).distinctBy { it.id }
    Column(modifier.fillMaxWidth().testTag("detail-episode-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 与其它区块同级的标题；右侧“共 N 集 ›”打开完整选集，数字/卡片形态只在抽屉里切换。
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("选集"), Modifier.weight(1f), fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold,
                color = PathfinAppearance.textMain)
            Row(Modifier.heightIn(min = 44.dp).clip(CircleShape).clickable(role = Role.Button) { sheetOpen = true }
                .padding(start = 10.dp, end = 4.dp).testTag("detail-episode-count"),
                verticalAlignment = Alignment.CenterVertically) {
                // 读取中先用季列表里已有的集数，避免“全部”与“共 N 集”来回切换。
                val count = totalCount.takeIf { it > 0 } ?: season.childCount?.takeIf { it > 0 }
                Text(if (count == null && loading && episodes.isEmpty()) tr("全部") else tr("共 %1\$s 集", (count ?: 0).coerceAtLeast(0)),
                    fontSize = 13.sp, color = PathfinAppearance.textSub)
                Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
            }
        }
        EpisodeSeasonTabs(season.id, tabs, onSeasonSelected, page = seasonsPage,
            onMore = onLoadSeasonsMore, enabled = !sheetOpen, switchEnabled = seasonSwitchEnabled)
        key(season.id, cards) {
            if (cards) EpisodeCardRow(page, session, api, !sheetOpen, onEpisodeOpen, onLoadMore, season.id)
            else EpisodeNumberRow(page, !sheetOpen, onEpisodeOpen, onLoadMore, season.id, placeholderCount = season.childCount)
        }
        EpisodePageMessage(page, Modifier.padding(horizontal = 16.dp))
    }
    if (sheetOpen) {
        AppModalBottomSheet(onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = PathfinAppearance.card) {
            val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            Column(Modifier.fillMaxWidth().height(windowHeight * .78f)
                .testTag("detail-episode-sheet")) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("选集"), Modifier.weight(1f), fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                        color = PathfinAppearance.textMain)
                    // 抽屉内原地切换整页数字网格与缩略图列表，与正文的数字/卡片形态共用一个状态。
                    PathfinIconButton(if (cards) PathfinIcons.Grid else PathfinIcons.List,
                        if (cards) tr("切换为数字选集") else tr("切换为剧集列表"), { cards = !cards },
                        Modifier.testTag("detail-episode-sheet-layout"), tint = PathfinAppearance.textSub, size = 44.dp)
                }
                EpisodeSeasonTabs(season.id, tabs, onSeasonSelected, "detail-episode-sheet-tabs",
                    page = seasonsPage, onMore = onLoadSeasonsMore, switchEnabled = seasonSwitchEnabled)
                key(season.id, cards) {
                    if (!cards) {
                        val grid = rememberLazyGridState()
                        AutomaticGridPagination(grid, page.pagination, season.id, onLoadMore)
                        // 与正文数字格同尺寸（48dp 起），一屏多排几集。
                        LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().weight(1f)
                            .testTag("detail-episode-sheet-grid"), grid,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            gridItems(episodes, key = { it.id }) { episode ->
                                EpisodeNumberCell(episode, "detail-episode-sheet-number/${episode.id}", Modifier.aspectRatio(1f)) {
                                    sheetOpen = false; onEpisodeOpen(episode)
                                }
                            }
                            if (page.items.isEmpty() || page.error != null) item(key = "episode-state", span = { GridItemSpan(maxLineSpan) }) {
                                EpisodePageMessage(page)
                            }
                        }
                    } else {
                        val list = rememberLazyListState()
                        AutomaticListPagination(list, page.pagination, season.id, onLoadMore)
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("detail-episode-sheet-list"), state = list,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(episodes, key = { it.id }) { episode ->
                                EpisodeSheetRow(episode, session, api) { sheetOpen = false; onEpisodeOpen(episode) }
                            }
                            if (page.items.isEmpty() || page.error != null) item(key = "episode-state") { EpisodePageMessage(page) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeSeasonTabs(selectedId: String, seasons: List<MediaItem>, onSelect: (MediaItem) -> Unit,
    tag: String = "detail-episode-tabs", page: ContentState? = null, onMore: () -> Unit = {}, enabled: Boolean = true,
    switchEnabled: Boolean = true) {
    val list = rememberLazyListState()
    if (page != null) AutomaticListPagination(list, page.pagination, seasons.firstOrNull()?.seriesId ?: seasons.firstOrNull()?.id,
        onMore, enabled = enabled)
    // 季标签：选中为强调色加一道短下划线，不用“/”分隔，读起来像分段而不是路径。
    LazyRow(Modifier.fillMaxWidth().testTag(tag), state = list, contentPadding = PaddingValues(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(seasons, key = { it.id }) { season ->
            val chosen = season.id == selectedId
            Column(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = switchEnabled, role = Role.Tab) { if (!chosen) onSelect(season) }
                .testTag("$tag/${season.id}").semantics { selected = chosen }
                .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(season.name, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        !switchEnabled -> PathfinAppearance.textMuted
                        chosen -> PathfinAppearance.accent
                        else -> PathfinAppearance.textSub
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.width(16.dp).height(2.dp).clip(CircleShape)
                    .background(if (chosen) PathfinAppearance.accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun EpisodeNumberRow(page: ContentState, enabled: Boolean, onOpen: (MediaItem) -> Unit, onMore: () -> Unit, seasonId: String,
    placeholderCount: Int? = null) {
    val list = rememberLazyListState()
    AutomaticListPagination(list, page.pagination, seasonId, onMore, enabled = enabled)
    LazyRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("detail-episode-numbers"), state = list,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 读取中先放同尺寸的浅色占位格，集数到达后原地换成数字，不出现“正在读取”文字与整排闪空。
        if (page.items.isEmpty() && page.loading) items((placeholderCount ?: 6).coerceIn(1, 12)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(PathfinAppearance.softFill))
        }
        items(page.items, key = { it.id }) { episode ->
            EpisodeNumberCell(episode, "detail-episode-number/${episode.id}", Modifier.size(48.dp)) { onOpen(episode) }
        }
    }
}

@Composable
private fun EpisodeNumberCell(episode: MediaItem, tag: String, modifier: Modifier, onOpen: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(PathfinAppearance.card)
        .border(PathfinAppearance.cardBorder, RoundedCornerShape(12.dp))
        .testTag(tag).semantics { stateDescription = episodeWatchState(episode) }
        .clickable(role = Role.Button, onClickLabel = tr("打开%1\$s详情", episode.name), onClick = onOpen),
        contentAlignment = Alignment.Center) {
        // 看过的集数字降为弱色并带一个小勾，未看的保持主文字色，一眼分出进度。
        Text(episode.indexNumber?.toString() ?: episode.name, Modifier.padding(horizontal = 3.dp),
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (episode.played) PathfinAppearance.textMuted else PathfinAppearance.textMain)
        if (episode.played) Icon(PathfinIcons.Check, tr("已观看"), Modifier.align(Alignment.TopEnd).padding(4.dp).size(11.dp),
            tint = PathfinAppearance.accent)
    }
}

@Composable
private fun EpisodeCardRow(page: ContentState, session: Session, api: JellyfinApi, enabled: Boolean,
    onOpen: (MediaItem) -> Unit, onMore: () -> Unit, seasonId: String) {
    val list = rememberLazyListState()
    AutomaticListPagination(list, page.pagination, seasonId, onMore, enabled = enabled)
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val textHeight = measurer.measure("\n", style.copy(fontSize = 14.sp, lineHeight = 20.sp)).size.height +
        measurer.measure("\n", style.copy(fontSize = 12.sp, lineHeight = 17.sp)).size.height
    val rowHeight = 196.dp * 9f / 16f + 10.dp + with(LocalDensity.current) { textHeight.toDp() }
    LazyRow(Modifier.fillMaxWidth().heightIn(min = rowHeight).testTag("detail-episode-cards"), state = list,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(page.items, key = { it.id }) { episode ->
            Column(Modifier.width(196.dp).testTag("detail-episode-card/${episode.id}")
                .clickable(role = Role.Button, onClick = { onOpen(episode) }), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EpisodeImage(episode, session, api, Modifier.fillMaxWidth())
                Text(detailEpisodeTitle(episode), Modifier.fillMaxWidth(), fontSize = 14.sp, lineHeight = 20.sp,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, color = PathfinAppearance.textMain)
                Text(episode.overview.ifBlank { episodeWatchState(episode) }, Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 17.sp,
                    color = PathfinAppearance.textSub, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EpisodeSheetRow(episode: MediaItem, session: Session, api: JellyfinApi, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("detail-episode-sheet-item/${episode.id}")
        .clickable(role = Role.Button, onClick = onOpen), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        EpisodeImage(episode, session, api, Modifier.width(104.dp))
        // 只在看过或看到一半时写进度；未看的集改放简介首行，不再每行重复“未观看”。
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(detailEpisodeTitle(episode), fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = PathfinAppearance.textMain)
            val progressed = episode.played || episode.positionTicks > 0
            val detail = if (progressed) episodeWatchState(episode) else episode.overview
            if (detail.isNotBlank()) Text(detail, Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 17.sp,
                color = if (progressed) PathfinAppearance.accent else PathfinAppearance.textSub,
                maxLines = if (progressed) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EpisodeImage(episode: MediaItem, session: Session, api: JellyfinApi, modifier: Modifier) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
        PosterImage(episode, session, api, Modifier.matchParentSize(), wide = true,
            imageType = if (LocalAppPreferences.current.preferEpisodeImages) "Primary" else "Backdrop")
        WatchProgress(episode, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun EpisodePageMessage(page: ContentState, modifier: Modifier = Modifier) {
    // 读取中不出文字（由占位格表达），只在失败或确实没有剧集时说明。
    val message = page.error ?: if (page.items.isEmpty() && !page.loading) tr("暂无可见剧集") else null
    Text(message.orEmpty(), modifier.fillMaxWidth().then(if (message != null)
        Modifier.testTag(if (page.error != null) "detail-episode-error" else "detail-episode-empty") else Modifier),
        color = if (page.error != null) MaterialTheme.colorScheme.error else PathfinAppearance.textSub,
        fontSize = 13.sp, lineHeight = 19.sp, minLines = 1)
}

private fun seasonMetadata(season: MediaItem): String = listOfNotNull(
    season.childCount?.takeIf { it >= 0 }?.let { tr("共%1\$s集", it) }, season.year?.toString(),
).joinToString(" · ")

private fun detailEpisodeTitle(episode: MediaItem): String = episode.indexNumber?.let { "$it. ${episode.name}" } ?: episode.name

private fun episodeWatchState(episode: MediaItem): String = when {
    episode.played -> tr("已观看")
    episode.positionTicks > 0 -> {
        val seconds = TimeUnits.ticksToMs(episode.positionTicks) / 1000
        tr("观看至 %1\$s:%2\$s", seconds / 60, (seconds % 60).toString().padStart(2, '0'))
    }
    else -> tr("未观看")
}
