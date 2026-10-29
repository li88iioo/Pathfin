package dev.pathfin.android.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaChapter
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.player.PlayerOptionFill
import dev.pathfin.android.ui.player.PlayerOptionShape
import dev.pathfin.android.ui.player.PlayerSelectedOptionFill
import dev.pathfin.android.ui.player.PlayerRoundButton
import dev.pathfin.android.ui.player.PlayerSecondary
import dev.pathfin.android.ui.player.PlayerText
import dev.pathfin.android.ui.player.playerAccent
/**
 * 播放器统一的侧面板：横屏从右侧滑出，背景是从透明渐变到深色的一整片，没有卡片边框，视频仍露出大半；
 * 竖屏从底部升起，同样以渐变过渡。选集、章节、播放质量与控制栏的全部弹层共用它，点面板外或返回键关闭。
 */
@Composable
internal fun PlayerSidePanel(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    closeLabel: String = tr("关闭%1\$s", title), closeTag: String = "playback-panel-close",
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // 对话框是独立窗口，不继承播放器的沉浸模式；在这里同样隐藏系统栏，并取消整窗变暗，让渐变自己承担遮挡。
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                WindowInsetsControllerCompat(it, it.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        val windowSize = LocalWindowInfo.current.containerSize
        val landscape = windowSize.width > windowSize.height
        val density = LocalDensity.current
        val panelWidth = with(density) { windowSize.width.toDp() }.coerceAtMost(352.dp)
        // 背景与面板分层：宽幅缓入缓出的暗场固定在窗口坐标，切换菜单不会出现移动的黑墙。
        val scrim = remember(windowSize, density.density, landscape, panelWidth) {
            val extent = (if (landscape) windowSize.width else windowSize.height).toFloat().coerceAtLeast(1f)
            val edge = if (landscape) extent - with(density) { panelWidth.toPx() } else extent * .42f
            val start = (edge - with(density) { (if (landscape) 220.dp else 160.dp).toPx() }).coerceAtLeast(0f)
            val end = (edge + with(density) { 64.dp.toPx() }).coerceAtMost(extent)
            val stops = Array(25) { index ->
                val t = index / 24f
                val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
                (start + (end - start) * t) / extent to Color.Black.copy(alpha = .985f * eased)
            }
            if (landscape) Brush.horizontalGradient(*stops) else Brush.verticalGradient(*stops)
        }
        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
        // 外部关闭不合并语义；内容空白区也接住点击，避免点到列表空隙就关闭。
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }) {
            AnimatedVisibility(shown, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Box(Modifier.fillMaxSize().background(scrim)) {
                    Column(
                        (if (landscape) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(panelWidth)
                         else Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.58f))
                            .animateEnterExit(enter = if (landscape) slideInHorizontally(tween(200)) { it / 8 }
                                else slideInVertically(tween(200)) { it / 8 })
                            .pointerInput(Unit) { detectTapGestures { } }
                            .testTag("player-side-panel")
                            .windowInsetsPadding(AppSafeDrawingInsets.only(
                                if (landscape) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                                color = PlayerText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            actions()
                            PlayerRoundButton(PathfinIcons.Close, closeLabel, closeTag, onClick = onDismiss)
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** 一行条目：16:9 缩略图 + 标题 + 副信息；当前项浅底高亮。 */
@Composable
private fun PlayerPanelRow(title: String, detail: String?, current: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    detailAccent: Boolean = false, onClick: () -> Unit, thumbnail: (@Composable () -> Unit)?) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(PlayerOptionShape)
        .background(if (current) PlayerSelectedOptionFill else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (thumbnail != null) Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = .06f))) { thumbnail() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = PlayerText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, lineHeight = 17.sp,
                color = if (current || detailAccent) playerAccent() else PlayerSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 选集：默认数字网格，几百集也能一屏扫到；跨季时按季分标签；右上角切换为带缩略图的列表。
 * 两种形态的第 N 项都是第 N 集，数字格的语义文字仍是完整集名，供读屏与查找使用。
 */
@Composable
internal fun PlaybackEpisodesSheet(content: ContentState, currentId: String, busy: Boolean,
    onSelect: (MediaItem) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onDismiss: () -> Unit,
    session: Session? = null, api: JellyfinApi? = null, grid: Boolean = true, onGridChange: (Boolean) -> Unit = {}) {
    // 跨多季时按季分标签，默认停在正在播放的那一季；单季剧不显示标签。
    val seasons = content.items.mapNotNull { it.parentIndexNumber }.distinct().sorted()
    val currentSeason = content.items.firstOrNull { it.id == currentId }?.parentIndexNumber
    var season by rememberSaveable(currentId) { mutableStateOf(currentSeason) }
    val selectedSeason = season?.takeIf { it in seasons } ?: currentSeason?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val episodes = if (seasons.size > 1) content.items.filter { it.parentIndexNumber == selectedSeason } else content.items
    PlayerSidePanel(tr("选集"), onDismiss, actions = {
        val titles = !grid
        val accent = playerAccent()
        val track by animateColorAsState(if (titles) accent else Color.White.copy(alpha = .18f), tween(160), label = "episode-title-track")
        val thumbOffset by animateDpAsState(if (titles) 14.dp else 2.dp, tween(160), label = "episode-title-thumb")
        Row(Modifier.widthIn(max = 156.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
            .toggleable(titles, role = Role.Switch) { onGridChange(!it) }
            .padding(horizontal = 8.dp).testTag("playback-episodes-layout"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("分集标题"), Modifier.weight(1f, fill = false), color = PlayerSecondary,
                fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(28.dp, 16.dp).clip(CircleShape).background(track)) {
                Box(Modifier.offset { IntOffset(thumbOffset.roundToPx(), 2.dp.roundToPx()) }.size(12.dp).clip(CircleShape).background(PlayerText))
            }
        }
    }) {
        if (seasons.size > 1) LazyRow(Modifier.fillMaxWidth().testTag("playback-episode-seasons"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons, key = { it }) { number ->
                val chosen = number == selectedSeason
                Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
                    .selectable(chosen, role = Role.Tab) { season = number }.padding(horizontal = 8.dp)
                    .testTag("playback-episode-season/$number"), contentAlignment = Alignment.Center) {
                    Text(tr("第 %1\$s 季", number), fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 1,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chosen) playerAccent() else PlayerSecondary)
                }
            }
        }
        RefreshablePage(content.loading || content.loadingMore, onRefresh, Modifier.fillMaxWidth().weight(1f), refreshOnEntry = false,
            indicatorTopInset = 0.dp) {
            if (grid) {
                val gridState = rememberLazyGridState()
                val cellWidth = with(LocalDensity.current) { 16.sp.toDp() * 2.5f }.coerceAtLeast(56.dp)
                AutomaticGridPagination(gridState, content.pagination, content.queryKey, onMore, enabled = !busy)
                LazyVerticalGrid(GridCells.Adaptive(cellWidth), Modifier.fillMaxSize().testTag("playback-episodes"), gridState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(episodes, key = { it.id }) { item ->
                        EpisodeNumberCell(item, item.id == currentId, !busy && item.id != currentId) { onSelect(item) }
                    }
                    episodeStates(content) { GridItemSpan(maxLineSpan) }
                }
            } else {
                val listState = rememberLazyListState()
                AutomaticListPagination(listState, content.pagination, content.queryKey, onMore, enabled = !busy)
                LazyColumn(Modifier.fillMaxSize().testTag("playback-episodes"), state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(episodes, key = { it.id }) { item ->
                        val current = item.id == currentId
                        PlayerPanelRow(episodeTitle(item), if (current) tr("正在播放") else item.overview.lineSequence().firstOrNull(), current,
                            enabled = !busy && !current, onClick = { onSelect(item) },
                            thumbnail = if (session != null && api != null) ({
                                PosterImage(item, session, api, Modifier.fillMaxSize(), wide = true, shape = RoundedCornerShape(8.dp),
                                    imageType = if (LocalAppPreferences.current.preferEpisodeImages) "Primary" else "Backdrop")
                            }) else null)
                    }
                    if (content.error != null) item(key = "episodes-error") { EpisodeStateText(content.error, error = true) }
                    if (!content.loading && content.error == null && content.items.isEmpty()) item(key = "episodes-empty") { EpisodeStateText(tr("没有可用剧集")) }
                }
            }
        }
    }
}

/** 数字格：深灰方块；正在播放的一集数字用强调色，看过的变暗并带小勾。 */
@Composable
private fun EpisodeNumberCell(item: MediaItem, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val number = item.indexNumber?.toString() ?: item.name.take(3)
    val accent = playerAccent()
    Box(Modifier.heightIn(min = 48.dp).aspectRatio(1f).clip(RoundedCornerShape(4.dp))
        .background(if (current) PlayerSelectedOptionFill else PlayerOptionFill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { text = AnnotatedString(episodeTitle(item)); if (current) stateDescription = tr("正在播放") else if (item.played) stateDescription = tr("已观看") },
        contentAlignment = Alignment.Center) {
        Text(number, Modifier.padding(horizontal = 4.dp), fontSize = if (number.length > 3) 13.sp else 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = when { current -> accent; item.played -> PlayerSecondary; else -> PlayerText })
        if (item.played && !current) Icon(PathfinIcons.Check, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(11.dp), tint = accent)
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.episodeStates(content: ContentState,
    span: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) {
    if (content.error != null) item(key = "episodes-error", span = span) { EpisodeStateText(content.error, error = true) }
    if (!content.loading && content.error == null && content.items.isEmpty()) item(key = "episodes-empty", span = span) { EpisodeStateText(tr("没有可用剧集")) }
}

@Composable
private fun EpisodeStateText(text: String, error: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 13.sp,
        color = if (error) MaterialTheme.colorScheme.error else PlayerSecondary)
}

@Composable
internal fun PlaybackChaptersSheet(chapters: List<MediaChapter>, onSelect: (MediaChapter) -> Unit, onDismiss: () -> Unit,
    session: Session? = null, item: MediaItem? = null, api: JellyfinApi? = null, hideWithoutImage: Boolean = false) {
    val visible = chapters.withIndex().filter { !hideWithoutImage || it.value.hasThumbnail() }
    PlayerSidePanel(tr("章节"), onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("playback-chapters"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visible, key = { it.index }) { indexed ->
                val chapter = indexed.value
                PlayerPanelRow(chapter.name, chapterTime(chapter), current = false, enabled = true,
                    modifier = Modifier.testTag("playback-chapter/${indexed.index}"), detailAccent = true, onClick = { onSelect(chapter) },
                    thumbnail = if (session != null && item != null && api != null && chapter.hasThumbnail()) ({
                        ChapterThumbnail(chapter, item, session, api, Modifier.fillMaxSize())
                    }) else null)
            }
            if (visible.isEmpty()) item {
                Text(if (hideWithoutImage && chapters.isNotEmpty()) tr("没有带缩略图的章节") else tr("没有可用章节"),
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = PlayerText, fontSize = 14.sp)
                if (hideWithoutImage && chapters.isNotEmpty()) Text(tr("可在媒体库设置中关闭隐藏无缩略图章节。"),
                    Modifier.padding(horizontal = 20.dp), color = PlayerSecondary, fontSize = 13.sp)
            }
        }
    }
}

internal fun episodeTitle(item: MediaItem): String {
    val index = item.indexNumber ?: return item.name
    return "S${item.parentIndexNumber ?: 1}E$index · ${item.name}"
}

/** 播放前选择媒体版本：一张分组卡片里一行一个版本，整行可点；副信息写格式、大小与码率，便于区分。 */
@Composable
internal fun PlaybackSourceDialog(selection: SourceSelection, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(tr("选择播放版本")) }, text = {
        GroupCard(container = PathfinAppearance.canvas) {
            selection.info.sources.forEachIndexed { index, source ->
                val details = listOfNotNull(source.container?.uppercase(),
                    source.size?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.2f GB", it / 1_073_741_824.0) },
                    source.bitRate?.takeIf { it > 0 }?.let { String.format(java.util.Locale.ROOT, "%.1f Mbps", it / 1_000_000.0) })
                Row(Modifier.fillMaxWidth().groupSeparator(PathfinAppearance.divider, index > 0)
                    .clickable(role = Role.Button) { onChoose(source.id) }.heightIn(min = 60.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("playback-source/${source.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(source.name?.takeIf(String::isNotBlank) ?: tr("媒体版本 %1\$s", index + 1), fontSize = 15.sp, lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(details.ifEmpty { listOf(tr("媒体源")) }.joinToString(" · "), fontSize = 12.5.sp, lineHeight = 17.sp,
                            color = PathfinAppearance.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(PathfinIcons.Play, tr("播放"), Modifier.size(22.dp), tint = PathfinAppearance.accent)
                }
            }
        }
    }, confirmButton = { DialogAction(tr("取消"), onDismiss) })
}
