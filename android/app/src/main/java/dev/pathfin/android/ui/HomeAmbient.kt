package dev.pathfin.android.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.Session
import dev.pathfin.android.i18n.tr
import kotlin.math.roundToInt

internal enum class AmbientKind { RESUME, NEXT_UP, LATEST }

internal data class AmbientEntry(val item: MediaItem, val kind: AmbientKind)

/**
 * 首页氛围区的条目：先是看到一半的，再接各剧下一集（与续播同剧的不重复）；
 * 都没有时才取最近入库里有剧照的电影和剧集。续播或下一集还没返回时返回空，由调用方占位。
 */
internal fun homeAmbientEntries(home: HomeState): List<AmbientEntry> {
    if (!home.resumeLoaded || (home.resume.isEmpty() && !home.nextUpLoaded && home.loading)) return emptyList()
    val resumeSeries = home.resume.mapNotNullTo(mutableSetOf()) { it.seriesId }
    val nextUp = home.nextUp.filter { next -> home.resume.none { it.id == next.id } && next.seriesId !in resumeSeries }
    val watching = home.resume.map { AmbientEntry(it, AmbientKind.RESUME) } + nextUp.map { AmbientEntry(it, AmbientKind.NEXT_UP) }
    if (watching.isNotEmpty()) return watching
    val shelves = home.libraries.map { home.libraryItems[it.id].orEmpty() }.ifEmpty { listOf(home.recent) }
    val depth = shelves.maxOfOrNull { it.size } ?: 0
    return (0 until depth).flatMap { index -> shelves.mapNotNull { it.getOrNull(index) } }
        .filter { it.type in setOf("Movie", "Series") && !it.backdropTag.isNullOrBlank() }
        .distinctBy { it.id }.take(8).map { AmbientEntry(it, AmbientKind.LATEST) }
}

/** 首轮候选请求全部结束前保持占位；已确认的空首页在暖刷新时不重新占位。 */
internal fun homeAmbientPending(home: HomeState): Boolean = home.loading && !home.initialized && homeAmbientEntries(home).isEmpty()

/**
 * 氛围首页：整块背景是选中条目的剧照（Android 12 起模糊），切换下面的小图时淡入淡出；
 * 前景始终压在加深的剧照上，用白色；底部留一段空白渐隐进页面底色。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun HomeAmbient(
    entries: List<AmbientEntry>, session: Session, api: JellyfinApi, topInset: Dp, preparingIds: Set<String>,
    onOpen: (MediaItem) -> Unit, onPlay: ((MediaItem) -> Unit)?,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf<AmbientKind?>(null) }
    // 继续观看与下一集分组显示；当前分组被清空时退回仍有内容的那组。
    val groups = entries.map { it.kind }.distinct()
    val shownKind = group?.takeIf { it in groups } ?: groups.firstOrNull()
    val shown = entries.filter { it.kind == shownKind }
    val selected = shown.firstOrNull { it.item.id == selectedId } ?: shown.firstOrNull()
    val canvas = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().testTag("home-ambient")) {
        Box(Modifier.matchParentSize().background(MediaImagePlaceholder)) {
            updateTransition(selected?.item, label = "home-ambient-background").Crossfade(
                Modifier.fillMaxSize(), animationSpec = tween(200),
                contentKey = { item -> item?.let { api.imageUrl(session, it, type = "Backdrop") } }) { item ->
                if (item != null) PosterImage(item, session, api, Modifier.fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
                    wide = true, shape = RectangleShape, imageType = "Backdrop")
            }
            Box(Modifier.fillMaxSize().drawWithCache {
                val brush = ambientScrim(canvas, ((size.height - AmbientFade.toPx()) / size.height).coerceIn(.5f, .95f))
                onDrawBehind { drawRect(brush) }
            })
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(AppSafeDrawingInsets.only(WindowInsetsSides.Horizontal))
                .padding(top = topInset + AmbientContentGap, bottom = AmbientFade), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.fillMaxWidth().height(AmbientHeroHeight)) {
                    if (selected != null) AmbientHero(selected, session, api, selected.item.id in preparingIds, onOpen, onPlay)
                }
                AmbientThumbs(entries, shownKind, selected?.item?.id, session, api, onGroup = { group = it; selectedId = null }) { entry ->
                    if (entry.item.id == selected?.item?.id) onOpen(entry.item) else selectedId = entry.item.id
                }
            }
        }
    }
}

@Composable
private fun AmbientHero(entry: AmbientEntry, session: Session, api: JellyfinApi, preparing: Boolean,
    onOpen: (MediaItem) -> Unit, onPlay: ((MediaItem) -> Unit)?) {
    val item = entry.item
    val title = item.seriesName?.takeIf(String::isNotBlank) ?: item.name
    Column(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = tr("详情")) { onOpen(item) }
        .padding(horizontal = 20.dp).widthIn(max = 560.dp).testTag("home-ambient-hero/${item.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
        // 属于哪一组由下方加粗的分组标签说明，封面上不再重复。
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.BottomStart) {
            DetailTitle(item.copy(name = title), session, api, tag = "home-ambient")
        }
        val facts = listOfNotNull(resumeSubtitle(item, title).takeIf(String::isNotBlank),
            resumeTiming(item, entry.kind == AmbientKind.NEXT_UP))
        Text(facts.joinToString(" · ").ifBlank { " " }, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = .88f))
        Box(Modifier.fillMaxWidth(.6f).height(3.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (item.positionTicks > 0) .28f else 0f))) {
            if (item.positionTicks > 0 && item.runTimeTicks > 0) Box(Modifier.fillMaxHeight()
                .fillMaxWidth((item.positionTicks.toFloat() / item.runTimeTicks).coerceIn(0f, 1f)).background(Color.White))
        }
        // 详情从点击剧照区或再次点击已选中的小图进入，这里只留播放。
        if (onPlay != null) PlayButton(if (item.positionTicks > 0) tr("继续播放") else tr("播放"), preparing,
            Modifier.padding(top = 6.dp)) { onPlay(item) }
    }
}

/** 压在剧照上的播放键：白色实心胶囊，两套主题相同。 */
@Composable
private fun PlayButton(label: String, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content = Color(0xFF16171B)
    Row(modifier.heightIn(min = 44.dp).clip(CircleShape).background(Color.White)
        .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
        .semantics { if (busy) stateDescription = tr("正在准备播放") }
        .padding(horizontal = 20.dp).testTag("home-ambient-play"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) PathfinSpinner(content, Modifier.size(18.dp))
        else Icon(PathfinIcons.Play, null, Modifier.size(18.dp), tint = content)
        Text(label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}

/**
 * 剧照上的遮罩：顶部压暗托住状态栏，中段轻、标题处再加深；最后一段黑色逐渐退掉、页面底色逐渐盖上，
 * 剧照本身的颜色直接淡进底色，浅色模式下不会先变成一段灰。
 */
private fun ambientScrim(canvas: Color, fadeStart: Float): Brush {
    fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun mix(from: Float, to: Float, t: Float) = from + (to - from) * smooth(t)
    val stops = Array(64) { index ->
        val position = index / 63f
        val dark = when {
            position < .28f -> mix(.5f, .16f, position / .28f)
            position < fadeStart -> mix(.16f, .42f, (position - .28f) / (fadeStart - .28f))
            else -> .42f * (1f - smooth((position - fadeStart) / (1f - fadeStart)))
        }
        val cover = smooth((position - fadeStart) / (1f - fadeStart))
        position to canvas.copy(alpha = cover).compositeOver(Color.Black.copy(alpha = dark))
    }
    return Brush.verticalGradient(*stops)
}

@Composable
private fun AmbientThumbs(entries: List<AmbientEntry>, shownKind: AmbientKind?, selectedId: String?, session: Session, api: JellyfinApi,
    onGroup: (AmbientKind) -> Unit, onClick: (AmbientEntry) -> Unit) {
    val groups = entries.groupBy { it.kind }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current
    // 字体含中文回退；各子项分别取整后相加，与真实布局一致，避免小数密度下少分一像素。
    val groupHeight = with(density) { (measurer.measure("国Ag", textStyle.copy(fontSize = 13.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold), maxLines = 1).size.height + 4.dp.roundToPx() * 3 + 2.dp.roundToPx()).toDp() }.coerceAtLeast(32.dp)
    val thumbHeight = with(density) { (measurer.measure("国Ag", textStyle.copy(fontSize = 11.sp, lineHeight = 14.sp),
        maxLines = 1).size.height + (ThumbWidth.roundToPx() * 9f / 16f).roundToInt() + 6.dp.roundToPx()).toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 14.dp).height(groupHeight), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            groups.forEach { (kind, items) -> AmbientGroupTab(kind, items.size, kind == shownKind, groups.size > 1) { onGroup(kind) } }
        }
        key(shownKind) {
            LazyRow(Modifier.fillMaxWidth().height(thumbHeight).testTag("home-ambient-thumbs"),
                contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups[shownKind].orEmpty(), key = { it.item.id }) { entry ->
                    AmbientThumb(entry, entry.item.id == selectedId, session, api) { onClick(entry) }
                }
            }
        }
    }
}

/** 分组标签：纯文字，选中的加粗并在下方带一道短细线；只有一组时只是一行说明，不可点。 */
@Composable
private fun AmbientGroupTab(kind: AmbientKind, count: Int, selected: Boolean, switchable: Boolean, onClick: () -> Unit) {
    val label = when (kind) {
        AmbientKind.RESUME -> tr("继续观看 %1\$s", count)
        AmbientKind.NEXT_UP -> tr("下一集 %1\$s", count)
        AmbientKind.LATEST -> tr("最近入库")
    }
    val active = selected && switchable
    Column(Modifier.clip(RoundedCornerShape(6.dp))
        .then(if (switchable) Modifier.clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
            else Modifier.semantics(mergeDescendants = true) {})
        .padding(horizontal = 6.dp, vertical = 4.dp).testTag("home-ambient-group/${kind.name}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = Color.White.copy(alpha = if (selected || !switchable) .95f else .55f), maxLines = 1)
        Box(Modifier.width(14.dp).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = if (active) .9f else 0f)))
    }
}

@Composable
private fun AmbientThumb(entry: AmbientEntry, selected: Boolean, session: Session, api: JellyfinApi, onClick: () -> Unit) {
    val item = entry.item
    val shape = RoundedCornerShape(12.dp)
    val ownPrimary = item.primaryImageItemId == null || item.primaryImageItemId == item.id
    val episodeImage = item.type == "Episode" && ownPrimary && LocalAppPreferences.current.preferEpisodeImages
    val title = item.seriesName?.takeIf(String::isNotBlank) ?: item.name
    Column(Modifier.width(ThumbWidth).clickable(role = Role.Button, onClickLabel = if (selected) tr("详情") else null, onClick = onClick)
        .semantics { if (selected) stateDescription = tr("已选中") }.testTag("home-ambient-thumb/${item.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
            .border(if (selected) 1.5.dp else 1.dp, Color.White.copy(alpha = if (selected) .95f else .22f), shape)) {
            PosterImage(item, session, api, Modifier.fillMaxSize(), wide = true, shape = shape,
                imageType = if (episodeImage) "Primary" else "Backdrop", displayWidth = ThumbWidth, displayAspectRatio = 16f / 9f)
            WatchProgress(item, Modifier.align(Alignment.BottomCenter))
        }
        Text(title, fontSize = 11.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = if (selected) 1f else .8f))
    }
}

/** 一排只有细描边的库标签，没有底色，落在氛围区渐隐的末端。 */
@Composable
internal fun LibraryPill(library: MediaItem, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 38.dp).clip(CircleShape)
        .border(PathfinAppearance.cardBorder, CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(start = 14.dp, end = 16.dp).testTag("home-library/${library.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(libraryTypeIcon(library), null, Modifier.size(16.dp), tint = PathfinAppearance.textSub)
        Text(library.name, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            color = PathfinAppearance.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val AmbientHeroHeight = 212.dp
/** 标题栏与氛围区第一行内容之间的留白；滚过这段，内容就会滑到标题栏下方。 */
internal val AmbientContentGap = 56.dp
private val AmbientFade = 112.dp
private val ThumbWidth = 112.dp
