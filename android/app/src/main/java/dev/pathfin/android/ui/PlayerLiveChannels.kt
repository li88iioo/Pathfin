package dev.pathfin.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.domain.LiveTvChannel
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.player.PlayerDisabled
import dev.pathfin.android.ui.player.PlayerRoundButton
import dev.pathfin.android.ui.player.PlayerSecondary
import dev.pathfin.android.ui.player.PlayerText
import dev.pathfin.android.ui.player.playerAccent
import java.time.Instant

/** 选台面板复用会话的频道快照，浏览分组时保持正在播放的频道。 */
@Composable
internal fun PlayerLiveChannels(state: LiveTvState, currentId: String, onSelect: (LiveTvChannel) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(state) { state.enter() }
    val sections = remember(state.channels, state.groups) { state.groups.sections(state.channels) }
    val byGroup = remember(sections) { sections.associate { (it.group ?: OTHER_GROUP) to it.channels } }
    val allChannels = remember(sections) { sections.flatMap { it.channels } }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val activeGroup = group?.takeIf { it in byGroup }
    val channels = byGroup[activeGroup] ?: allChannels
    val currentIndex = remember(channels, currentId) { channels.indexOfFirst { it.id == currentId } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    var positioned by rememberSaveable(currentId) { mutableStateOf(currentIndex >= 0) }
    LaunchedEffect(currentIndex) {
        if (!positioned && currentIndex >= 0) {
            list.requestScrollToItem(currentIndex)
            positioned = true
        }
    }
    val accent = playerAccent()
    val error = state.error
    val canPlay = state.access?.canPlay == true
    // 少量频道时面板贴底。刷新或切换分组期间保持本次打开的高度。
    val initialCount = remember(state) { allChannels.size }
    val initiallyGrouped = remember(state) { byGroup.size > 1 }
    val rowHeight = with(LocalDensity.current) { maxOf(56.dp, 20.sp.toDp() + 16.sp.toDp() + 18.dp) }
    val compactHeight = initialCount.takeIf { it in 1..6 }?.let { count ->
        76.dp + (rowHeight + 3.dp) * count + (if (initiallyGrouped) 48.dp else 0.dp)
    }
    PlayerSidePanel(tr("频道列表"), onDismiss, closeTag = "player-live-channels-close", fromStart = true,
        portraitContentHeight = compactHeight, actions = {
        if (allChannels.isNotEmpty()) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (state.refreshing) PathfinSpinner(accent, Modifier.size(18.dp))
                else if (error != null) PlayerRoundButton(PathfinIcons.Refresh, tr("重试"), "player-live-channels-retry", onClick = state::load)
            }
        }
    }) {
        if (byGroup.size > 1) {
            val groupKeys = remember(byGroup) { listOf<String?>(null) + byGroup.keys }
            LazyRow(
                Modifier.fillMaxWidth().selectableGroup().testTag("player-live-channel-groups"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(groupKeys, key = { it?.let { "group/$it" } ?: "all" }) { key ->
                    val selected = activeGroup == key
                    val label = when (key) { null -> tr("全部"); OTHER_GROUP -> tr("其他"); else -> key }
                    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape)
                        .selectable(selected, role = Role.Tab) {
                            if (!selected) {
                                val target = byGroup[key] ?: allChannels
                                list.requestScrollToItem(target.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
                                positioned = true
                                group = key
                            }
                        }, contentAlignment = Alignment.Center) {
                        Text(label, Modifier.widthIn(max = 180.dp).clip(CircleShape)
                            .background(if (selected) accent.copy(alpha = .17f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) accent else PlayerSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).testTag("player-live-channel-list")) {
            if (channels.isNotEmpty()) {
                val now = liveTvClock()
                LazyColumn(
                    Modifier.fillMaxSize().selectableGroup(),
                    state = list,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        PlayerLiveChannelRow(state, channel, now, channel.id == currentId, canPlay, accent) { onSelect(channel) }
                    }
                }
            } else {
                val access = state.access
                val loading = access?.canView != false && access?.isConfigured != false &&
                    (state.refreshing || !state.loaded && error == null)
                val message = when {
                    access?.canView == false -> tr("没有直播电视权限")
                    access?.isConfigured == false -> tr("服务器尚未启用直播电视")
                    loading -> tr("正在加载")
                    error != null -> error
                    else -> tr("暂无直播频道")
                }
                Column(Modifier.align(Alignment.Center).padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) PathfinSpinner(accent, Modifier.size(20.dp))
                    Text(message, color = PlayerText, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    if (error != null && !loading) TextButton(onClick = state::load, modifier = Modifier.testTag("player-live-channels-retry")) {
                        Text(tr("重试"), color = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLiveChannelRow(
    state: LiveTvState,
    channel: LiveTvChannel,
    now: Instant,
    current: Boolean,
    canPlay: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val detail = if (!current && state.access?.canPlay == false) tr("未获播放权限")
        else channel.nowShowing(now)?.name?.takeIf(String::isNotBlank)
    val playing = tr("正在播放")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (current) accent.copy(alpha = .16f) else Color.Transparent)
            .then(if (current) Modifier.border(1.dp, accent.copy(alpha = .28f), shape) else Modifier)
            .selectable(current, enabled = current || canPlay, role = Role.RadioButton, onClick = onClick)
            .semantics { if (current) stateDescription = playing }
            .testTag("player-live-channel-${channel.id}")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveTvChannelMark(channel, state.session, state.api, preparing = false, modifier = Modifier.size(48.dp, 34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channel.name, color = if (current || canPlay) PlayerText else PlayerDisabled,
                fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = PlayerSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        channel.displayNumber?.takeIf { state.api.hasImage(channel.item) }?.let { Text(it, color = PlayerSecondary, fontSize = 12.sp, maxLines = 1) }
        Box(Modifier.size(16.dp)) {
            if (current) Icon(PathfinIcons.Check, null, Modifier.size(16.dp), tint = accent)
        }
    }
}
