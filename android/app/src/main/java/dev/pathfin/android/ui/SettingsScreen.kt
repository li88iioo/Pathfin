@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pathfin.android.ui

import dev.pathfin.android.domain.BufferPreset
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.pathfin.android.domain.AccentColor
import dev.pathfin.android.domain.PlayerOrientation
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathfin.android.BuildConfig
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.theme.ThemePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal enum class GroupPosition { SINGLE, TOP, MIDDLE, BOTTOM }

@Composable
internal fun SettingsScreen(state: AppState, onTheme: (ThemePreference) -> Unit,
    onPage: (String?) -> Unit = {}, onPreferences: (AppPreferences) -> Unit = {}, onDownloads: () -> Unit = {},
    onImportSettings: suspend (AppPreferences, ThemePreference) -> Boolean = { _, _ -> false }) {
    val p = state.preferences
    val page = (state.route as? Route.SettingsPage)?.page
    if (page == "licenses") {
        OpenSourceLicensesScreen(onBack = { onPage("about") }, showTopBar = false)
        return
    }
    val context = LocalContext.current
    val updates: dev.pathfin.android.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val loader = LocalPosterLoader.current
    val coroutineScope = rememberCoroutineScope()
    var clearingImages by remember { mutableStateOf(false) }
    var cacheSizeDialog by remember { mutableStateOf(false) }
    var cacheSizeDraft by remember(p.imageCacheSizeMb) { mutableStateOf(p.imageCacheSizeMb.toString()) }
    var themeDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf<String?>(null) }
    var languageSearch by remember { mutableStateOf("") }
    var backgroundDialog by remember { mutableStateOf(false) }
    var orientationDialog by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var transferJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val currentPreferences by rememberUpdatedState(p)
    val currentTheme by rememberUpdatedState(state.theme)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !transferBusy) {
            transferBusy = true
            val snapshot = currentPreferences
            val theme = currentTheme
            transferJob = coroutineScope.launch {
                try {
                    dev.pathfin.android.data.SettingsTransfer.write(context, uri, snapshot, theme)
                    feedback = tr("设置已导出")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback = tr("导出失败，请检查目标文件") }
                finally { transferBusy = false }
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !transferBusy) {
            transferBusy = true
            transferJob = coroutineScope.launch {
                try {
                    val (restored, theme) = dev.pathfin.android.data.SettingsTransfer.read(context, uri)
                    feedback = if (onImportSettings(restored, theme)) tr("设置已导入") else tr("设置保存失败，请重试")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback = tr("无法读取有效的 Pathfin 设置备份，请检查文件与访问权限") }
                finally { transferBusy = false }
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(PathfinAppearance.canvas).testTag("settings-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = LocalPageTopInset.current + 8.dp, bottom = LocalNavigationBottomInset.current + 28.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (page) {
            null -> {
                item("common-label") { SettingLabel(tr("通用")) }
                item("general") { SettingsEntryItem(PathfinIcons.Sliders, tr("常规"), tr("系统语言、启动连接、下拉刷新"), GroupPosition.TOP) { onPage("general") } }
                item("appearance") { SettingsEntryItem(PathfinIcons.Palette, tr("外观"), tr("深色模式、主题色、毛玻璃效果"), GroupPosition.BOTTOM) { onPage("appearance") } }
                item("media-label") { SettingLabel(tr("媒体与播放")) }
                item("library") { SettingsEntryItem(MediaNavigationIcons.Movies, tr("媒体库"), tr("艺术图、续播截图、封面角标与推荐"), GroupPosition.TOP) { onPage("library") } }
                item("player") { SettingsEntryItem(PathfinIcons.Play, tr("播放"), tr("音轨字幕、片头片尾与缓冲"), GroupPosition.MIDDLE) { onPage("player") } }
                item("interaction") { SettingsEntryItem(PathfinIcons.Gesture, tr("手势与控制"), tr("长按倍速、双击快进快退、控制栏状态"), GroupPosition.BOTTOM) { onPage("interaction") } }
                item("data-label") { SettingLabel(tr("数据与关于")) }
                item("cache") { SettingsEntryItem(PathfinIcons.Archive, tr("存储与备份"), tr("封面缓存管理、设置导入与导出"), GroupPosition.TOP) { onPage("cache") } }
                item("about") { SettingsEntryItem(PathfinIcons.Info, tr("关于"), BuildConfig.VERSION_NAME, GroupPosition.BOTTOM) { onPage("about") } }
            }
            "general" -> {
                item("locale") { AppLocaleSettings(GroupPosition.TOP) }
                item("open-last") { SettingsSwitch(tr("启动时打开上次的服务器"), p.openLastServer, tr("关闭后，重新启动进入服务器列表"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(openLastServer = it)) } }
                item("pull-to-refresh") { SettingsSwitch(tr("下拉刷新"), p.pullToRefreshEnabled,
                    tr("关闭后，进入页面或返回应用自动刷新"), tag = "pull-to-refresh-enabled", position = GroupPosition.BOTTOM) {
                    onPreferences(p.copy(pullToRefreshEnabled = it))
                } }
            }
            "appearance" -> {
                item("theme-label") { SettingLabel(tr("主题")) }
                item("theme") { SettingsRow(tr("深色模式"), state.theme.label, position = GroupPosition.TOP) { themeDialog = true } }
                item("accent-color") { AccentColorPicker(p.accentColor) { onPreferences(p.copy(accentColor = it)) } }
                item("navigation-label") { SettingLabel(tr("导航")) }
                item("hide-navigation") { SettingsSwitch(tr("滚动时隐藏导航栏"), p.autoHideNavigation, tr("向下滚动列表时自动收起导航栏")) { onPreferences(p.copy(autoHideNavigation = it)) } }
                item("window-label") { SettingLabel(tr("窗口")) }
                item("hide-status-bar") { SettingsSwitch(tr("隐藏系统状态栏"), p.hideSystemStatusBar,
                    tr("浏览与详情页面隐藏系统状态栏"), tag = "settings-hide-system-status-bar", position = GroupPosition.TOP) {
                    onPreferences(p.copy(hideSystemStatusBar = it))
                } }
                item("blur-top-bar") { SettingsSwitch(tr("毛玻璃效果"), p.blurTopBar,
                    tr("状态栏、标题栏、导航栏、菜单与弹窗透出背后内容"), tag = "settings-blur-top-bar", position = GroupPosition.BOTTOM) {
                    onPreferences(p.copy(blurTopBar = it))
                } }
            }
            "library" -> {
                item("covers-label") { SettingLabel(tr("封面")) }
                item("artwork") { SettingsSwitch(tr("显示艺术图"), p.showArtwork, tr("在浏览与详情页加载海报与背景图"), position = GroupPosition.TOP) { onPreferences(p.copy(showArtwork = it)) } }
                item("episode-images") { SettingsSwitch(tr("续播卡片使用集截图"), p.preferEpisodeImages, tr("继续观看优先显示单集画面"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(preferEpisodeImages = it)) } }
                item("badges") { SettingsSwitch(tr("封面角标"), p.showPosterBadges, tr("评分与观看状态"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(showPosterBadges = it)) } }
                item("details-label") { SettingLabel(tr("详情")) }
                item("episode-backgrounds") { SettingsSwitch(tr("背景优先使用集截图"), p.preferEpisodeBackgrounds, tr("单集详情优先采用当前集画面作为背景"), position = GroupPosition.TOP) { onPreferences(p.copy(preferEpisodeBackgrounds = it)) } }
                item("detail-chapters") { SettingsSwitch(tr("显示章节"), p.showDetailChapters, tr("在媒体详情页列出章节与缩略图"), tag = "detail-chapters-switch", position = GroupPosition.MIDDLE) { onPreferences(p.copy(showDetailChapters = it)) } }
                item("hide-chapters") { SettingsSwitch(tr("隐藏无缩略图的章节"), p.hideChaptersWithoutImage, tr("仅展示包含预览图的章节标记"),
                    enabled = p.showDetailChapters || p.showPlayerChapters, position = GroupPosition.MIDDLE) { onPreferences(p.copy(hideChaptersWithoutImage = it)) } }
                if (android.os.Build.VERSION.SDK_INT >= 31) item("background-blur") {
                    SettingsSlider(tr("背景模糊"), p.backgroundBlurRadiusDp.toFloat(), 0f..30f, 29, { "${it.roundToInt()} dp" }, position = GroupPosition.BOTTOM) {
                        onPreferences(p.copy(backgroundBlurRadiusDp = it.roundToInt()))
                    }
                } else item("background-blur-unavailable") { SettingsRow(tr("背景模糊"), tr("需要 Android 12"), position = GroupPosition.BOTTOM) }
                item("personalization-label") { SettingLabel(tr("内容")) }
                item("personalization") { LibraryPersonalizationRows(p, onPreferences) }
            }
            "interaction" -> {
                item("hold-label") { SettingLabel(tr("长按")) }
                item("hold-speed") { SettingsSwitch(tr("长按加速"), p.longPressSpeedEnabled, tr("播放画面长按时临时倍速播放"), position = GroupPosition.TOP) { onPreferences(p.copy(longPressSpeedEnabled = it)) } }
                item("hold-multiplier") { SettingsSlider(tr("加速倍率"), p.longPressSpeed, 1.25f..4f, 10, { "${(it * 4).roundToInt() / 4f}×" },
                    subtitle = tr("乘以当前速度，最高 4×；松手恢复"), enabled = p.longPressSpeedEnabled, position = GroupPosition.MIDDLE) {
                    onPreferences(p.copy(longPressSpeed = (it * 4).roundToInt() / 4f))
                } }
                item("hold-slide") { SettingsSwitch(tr("滑动调整倍速"), p.longPressSlideSpeedEnabled, tr("长按期间横向滑动动态调节倍速"), enabled = p.longPressSpeedEnabled, position = GroupPosition.BOTTOM) { onPreferences(p.copy(longPressSlideSpeedEnabled = it)) } }
                item("seek-label") { SettingLabel(tr("跳转")) }
                item("double-tap") { SettingsSlider(tr("双击快进 / 快退"), p.seekSeconds.toFloat(), 5f..60f, 10, { tr("%1\$s 秒", it.roundToInt()) }, position = GroupPosition.TOP) { onPreferences(p.copy(seekSeconds = it.roundToInt())) } }
                item("horizontal-seek") { SettingsSwitch(tr("横向滑动调整进度"), p.horizontalSeekEnabled, tr("在画面中部左右滑动，松手后跳转"), tag = "horizontal-seek-switch", position = GroupPosition.MIDDLE) { onPreferences(p.copy(horizontalSeekEnabled = it)) } }
                item("exact-seek") { SettingsSwitch(tr("准确跳转"), p.exactSeek, tr("关闭后定位到邻近关键帧"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(exactSeek = it)) } }
                item("rapid-seek") { SettingsSwitch(tr("拖动时即时跳转"), p.rapidSeekWhileDragging, tr("关闭后松手跳转"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(rapidSeekWhileDragging = it)) } }
                item("controls-label") { SettingLabel(tr("控制栏")) }
                item("clock") { SettingsSwitch(tr("时间"), p.showClock, tr("在播放控制栏显示系统时间"), position = GroupPosition.TOP) { onPreferences(p.copy(showClock = it)) } }
                item("battery") { SettingsSwitch(tr("电量"), p.showBattery, tr("在播放控制栏显示电量百分比"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(showBattery = it)) } }
                item("player-chapters") { SettingsSwitch(tr("章节"), p.showPlayerChapters, tr("在播放控制栏显示章节入口"), tag = "player-chapters-switch", position = GroupPosition.MIDDLE) { onPreferences(p.copy(showPlayerChapters = it)) } }
                item("read-speed") { SettingsSwitch(tr("读取速度"), p.showBufferSpeed, tr("在播放控制栏显示实时读取速率"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(showBufferSpeed = it)) } }
            }
            "player" -> {
                item("source-label") { SettingLabel(tr("媒体源")) }
                item("original-path") { SettingsSwitch(tr("STRM 原始地址"), p.originalPathEnabled, tr("关闭后使用 Jellyfin 播放地址"), position = GroupPosition.TOP) { onPreferences(p.copy(originalPathEnabled = it)) } }
                item("strm-default-quality") { StrmQualitySettings(p, onPreferences, GroupPosition.BOTTOM) }
                item("live-tv-label") { SettingLabel(tr("直播电视")) }
                item("live-tv-enabled") { SettingsSwitch(tr("启用直播电视"), p.liveTvEnabled, tr("关闭后首页“更多”里不再显示直播电视"),
                    tag = "live-tv-enabled-switch", position = GroupPosition.TOP) { onPreferences(p.copy(liveTvEnabled = it)) } }
                item("live-tv-direct") { SettingsSwitch(tr("直连直播源"), p.liveTvDirectPlay, tr("直接播放频道的直播源地址，不经服务器转码；设备不支持其编码时可能无法播放"),
                    enabled = p.liveTvEnabled, tag = "live-tv-direct-switch", position = GroupPosition.BOTTOM) { onPreferences(p.copy(liveTvDirectPlay = it)) } }
                item("behavior-label") { SettingLabel(tr("播放行为")) }
                item("auto-next") { SettingsSwitch(tr("自动播放下一集"), p.autoPlayNextEpisode, tr("单集播放完毕后自动连播下一集"), position = GroupPosition.TOP) { onPreferences(p.copy(autoPlayNextEpisode = it)) } }
                item("keep-tracks") { SettingsSwitch(tr("连播保留音轨和字幕"), p.keepTracksForContinuousPlayback, tr("同账号、同剧集沿用手动选择"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(keepTracksForContinuousPlayback = it)) } }
                item("background") { SettingsRow(tr("返回桌面时"), if (p.backgroundPlaybackBehavior == "PIP") tr("画中画") else tr("暂停"), position = GroupPosition.MIDDLE) { backgroundDialog = true } }
                item("player-orientation") { SettingsRow(tr("播放器默认方向"), p.playerOrientation.label, tag = "player-orientation", position = GroupPosition.MIDDLE) { orientationDialog = true } }
                item("remember-brightness") { SettingsSwitch(tr("记忆亮度"), p.rememberBrightness, tr("播放时恢复上次手势调整的亮度；关闭则跟随系统亮度"),
                    tag = "remember-brightness-switch", position = GroupPosition.MIDDLE) { onPreferences(p.copy(rememberBrightness = it)) } }
                item("refresh-rate") { SettingsSwitch(tr("高刷新率"), p.lockHighRefreshRate, tr("使用同分辨率最高刷新率，可能增加耗电"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(lockHighRefreshRate = it)) } }
                item("options-label") { SettingLabel(tr("播放参数")) }
                item("tracks") { SettingsRow(tr("音轨与字幕"), if (p.subtitlesEnabled) tr("已启用字幕 · 音量增益 %1\$s%%", p.audioGainPercent) else tr("已关闭字幕 · 音量增益 %1\$s%%", p.audioGainPercent), position = GroupPosition.TOP) { onPage("tracks") } }
                item("segments") { SettingsRow(tr("片头与片尾"), if (p.serverIntroSkipEnabled) tr("优先使用服务端片段") else tr("使用自定义提示时长"), position = GroupPosition.MIDDLE) { onPage("segments") } }
                item("buffer") { BufferPresetSettings(p, onPreferences, GroupPosition.MIDDLE) }
                item("exo") { SettingsRow(tr("解码与字幕渲染"), tr("音频解码、硬件解码与 ASS 字幕扩展"), tag = "settings-exo", position = GroupPosition.BOTTOM) { onPage("exo") } }
            }
            "tracks" -> {
                item("audio-label") { SettingLabel(tr("音轨")) }
                item("audio-language") { SettingsRow(tr("音轨语言"), languageName(p.preferredAudioLanguage), position = GroupPosition.TOP) { languageSearch = ""; languageDialog = "audio" } }
                item("gain") { SettingsSlider(tr("音量增益"), p.audioGainPercent.toFloat(), 0f..200f, 39, { "${it.roundToInt()}%" }, subtitle = tr("100% 为原音量，增强后可能失真"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(audioGainPercent = it.roundToInt())) } }
                item("subtitles-label") { SettingLabel(tr("字幕")) }
                item("subtitles") { SettingsSwitch(tr("启用字幕"), p.subtitlesEnabled, tr("播放时默认开启字幕轨"), position = GroupPosition.TOP) { onPreferences(p.copy(subtitlesEnabled = it)) } }
                item("subtitle-language") { SettingsRow(tr("字幕语言"), languageName(p.preferredSubtitleLanguage), position = GroupPosition.BOTTOM) { languageSearch = ""; languageDialog = "subtitle" } }
            }
            "segments" -> {
                item("intro-label") { SettingLabel(tr("片头")) }
                item("server-intro") { SettingsSwitch(tr("读取服务端片头"), p.serverIntroSkipEnabled, tr("优先使用 Jellyfin 提供的片段"), position = GroupPosition.TOP) { onPreferences(p.copy(serverIntroSkipEnabled = it)) } }
                item("auto-skip") { SettingsSwitch(tr("自动跳过片头"), p.autoSkipIntro, tr("关闭后显示跳过按钮"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(autoSkipIntro = it)) } }
                item("intro-prompt") { SettingsSlider(tr("片头提示时长"), p.introPromptSeconds.toFloat(), 0f..180f, 35, { secondsLabel(it) }, subtitle = tr("没有服务端片段时使用"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(introPromptSeconds = it.roundToInt())) } }
                item("intro-offset") { SettingsSlider(tr("起播跳过"), p.introSkipSeconds.toFloat(), 0f..180f, 35, { secondsLabel(it) }, subtitle = tr("仅从头播放时生效"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(introSkipSeconds = it.roundToInt())) } }
                item("outro-label") { SettingLabel(tr("片尾")) }
                item("next-prompt") { SettingsSlider(tr("下一集提示"), p.nextEpisodePromptSeconds.toFloat(), 0f..180f, 35, { secondsLabel(it) }, subtitle = tr("剩余时间低于此值时显示"), position = GroupPosition.TOP) { onPreferences(p.copy(nextEpisodePromptSeconds = it.roundToInt())) } }
                item("outro-offset") { SettingsSlider(tr("跳过片尾"), p.outroSkipSeconds.toFloat(), 0f..180f, 35, { secondsLabel(it) }, position = GroupPosition.BOTTOM) { onPreferences(p.copy(outroSkipSeconds = it.roundToInt())) } }
            }
            "exo" -> {
                item("audio-decoder") { DecoderPreferenceSettings(p, onPreferences, GroupPosition.TOP) }
                item("hardware-video") { SettingsSwitch(tr("优先硬件解码"), p.preferHardwareVideoDecoder, tr("关闭后优先使用系统软件解码器"), position = GroupPosition.MIDDLE) { onPreferences(p.copy(preferHardwareVideoDecoder = it)) } }
                item("ass") { SettingsSwitch(tr("ASS 字幕扩展"), p.exoAssEnabled, tr("ARM64 使用 libass，其他架构使用基础字幕"), position = GroupPosition.BOTTOM) { onPreferences(p.copy(exoAssEnabled = it)) } }
            }
            "cache" -> {
                item("download-label") { SettingLabel(tr("下载")) }
                item("download-settings") { DownloadSettings(p, onPreferences) }
                if (state.session == null || state.showServers) item("downloads") { SettingsRow(tr("下载任务"), tr("查看与管理本机下载队列"), position = GroupPosition.SINGLE, onClick = onDownloads) }
                item("cache-label") { SettingLabel(tr("封面缓存")) }
                item("cache-size") {
                    // 当前账号的磁盘缓存实际占用；清理完成或进入本页时重新读取。
                    val used by produceState<Long?>(null, loader, clearingImages) {
                        if (!clearingImages) value = withContext(Dispatchers.IO) { runCatching { loader.diskCache?.size }.getOrNull() }
                    }
                    SettingsRow(tr("容量上限"), (used?.let { tr("已用 %1\$s · ", cacheSizeLabel(it)) } ?: "") + tr("上限 %1\$s MB · 每个账号独立缓存", p.imageCacheSizeMb),
                        tag = "cache-usage", position = GroupPosition.TOP) {
                    cacheSizeDraft = p.imageCacheSizeMb.toString(); cacheSizeDialog = true
                } }
                item("clear-cache") { SettingsRow(tr("清除缓存"),
                    if (state.session == null) tr("连接服务器后可清理") else if (clearingImages) tr("正在清理") else tr("仅清理当前账号的本机封面"),
                    enabled = state.session != null && !clearingImages,
                    position = GroupPosition.BOTTOM,
                    onClick = if (state.session == null || clearingImages) null else {
                        {
                            coroutineScope.launch {
                                clearingImages = true
                                try {
                                    loader.memoryCache?.clear()
                                    withContext(Dispatchers.IO) { requireNotNull(loader.diskCache).clear() }
                                    feedback = tr("缓存已清除")
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    feedback = tr("清除失败，请重试")
                                } finally { clearingImages = false }
                            }
                        }
                    }) }
                item("backup-label") { SettingLabel(tr("设置备份")) }
                item("export") { SettingsRow(tr("导出设置"), tr("不包含账号和凭据"), position = GroupPosition.TOP) { if (!transferBusy) export.launch("Pathfin-settings.json") } }
                item("import") { SettingsRow(tr("导入设置"), tr("从备份文件恢复偏好设置"), position = GroupPosition.BOTTOM) { if (!transferBusy) restore.launch(arrayOf("application/json")) } }
            }
            "crash" -> crashLogItems()
            "faq", "privacy", "disclaimer" -> documentItems(page)
            else -> aboutItems(p, updateState, onPreferences, onPage, updates::check)
        }
    }
    if (cacheSizeDialog) {
        val valid = cacheSizeDraft.toIntOrNull()?.let { it in 16..65536 } == true
        AppDialog({ cacheSizeDialog = false }, tr("缓存容量"), subtitle = tr("16–65536 MB，每个账号独立计算"), actions = {
            DialogAction(tr("取消"), { cacheSizeDialog = false }, Modifier.weight(1f))
            DialogAction(tr("保存"), {
                cacheSizeDraft.toIntOrNull()?.takeIf { it in 16..65536 }?.let { onPreferences(p.copy(imageCacheSizeMb = it)); cacheSizeDialog = false }
            }, Modifier.weight(1f), enabled = valid, role = DialogActionRole.Primary)
        }) {
            GroupCard(container = PathfinAppearance.canvas) {
                GroupField(cacheSizeDraft, { cacheSizeDraft = it.filter(Char::isDigit).take(5) }, tr("容量（MB）"), "1024", PathfinIcons.Archive,
                    Modifier.testTag("cache-size-input"), divider = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512, 1024, 2048).forEach { size ->
                    PresetChip(if (size < 1024) "$size MB" else "${size / 1024} GB", cacheSizeDraft.toIntOrNull() == size) { cacheSizeDraft = size.toString() }
                }
            }
        }
    }
    if (themeDialog) SettingsChoiceDialog(tr("深色模式"), onDismiss = { themeDialog = false }) {
        ThemePreference.entries.forEach { mode ->
            val icon = when (mode) { ThemePreference.SYSTEM -> PathfinIcons.Device; ThemePreference.LIGHT -> PathfinIcons.Sun; ThemePreference.DARK -> PathfinIcons.Moon }
            SettingsOptionRow(mode.label, state.theme == mode, icon = icon) { onTheme(mode); themeDialog = false }
        }
    }
    if (languageDialog != null) AppDialog({ languageDialog = null }, if (languageDialog == "audio") tr("首选音轨语言") else tr("首选字幕语言"),
        actions = { DialogAction(tr("取消"), { languageDialog = null }, Modifier.weight(1f)) }) {
        GroupCard(container = PathfinAppearance.canvas) {
            GroupField(languageSearch, { languageSearch = it }, tr("搜索"), tr("语言名称或代码"), PathfinIcons.Globe, divider = false)
        }
        val languageOptions = remember(LocalConfiguration.current.locales[0]) { preferredLanguageOptions() }
        val matching = remember(languageSearch, languageOptions) {
            languageOptions.filter { (code, label) ->
                languageSearch.isBlank() || label.contains(languageSearch.trim(), true) || code.contains(languageSearch.trim(), true)
            }
        }
        GroupCard(Modifier.padding(top = 10.dp), container = PathfinAppearance.canvas) {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(matching.size, key = { matching[it].first }) { index ->
                    val (code, label) = matching[index]
                    SettingsOptionRow(label, code == if (languageDialog == "audio") p.preferredAudioLanguage else p.preferredSubtitleLanguage) {
                        onPreferences(if (languageDialog == "audio") p.copy(preferredAudioLanguage = code) else p.copy(preferredSubtitleLanguage = code))
                        languageDialog = null
                    }
                }
            }
        }
    }
    if (orientationDialog) SettingsChoiceDialog(tr("播放器默认方向"), onDismiss = { orientationDialog = false }, note = tr("进入播放器时的屏幕方向；跟随传感器时随手机旋转")) {
        PlayerOrientation.entries.forEach { option ->
            SettingsOptionRow(option.label, p.playerOrientation == option, icon = when (option) {
                PlayerOrientation.LANDSCAPE, PlayerOrientation.LANDSCAPE_SENSOR -> PathfinIcons.RotateToLandscape
                PlayerOrientation.PORTRAIT, PlayerOrientation.PORTRAIT_SENSOR -> PathfinIcons.RotateToPortrait
                PlayerOrientation.SENSOR -> PathfinIcons.Refresh
            }) { onPreferences(p.copy(playerOrientation = option)); orientationDialog = false }
        }
    }
    if (backgroundDialog) SettingsChoiceDialog(tr("返回桌面时"), onDismiss = { backgroundDialog = false }) {
        val pipAvailable = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        listOf("PAUSE" to tr("暂停视频"), "PIP" to tr("进入画中画")).forEach { (value, label) ->
            val enabled = value != "PIP" || pipAvailable
            SettingsOptionRow(if (enabled) label else tr("%1\$s（设备不支持）", label), p.backgroundPlaybackBehavior == value, enabled,
                icon = if (value == "PIP") PathfinIcons.PictureInPicture else PathfinIcons.Pause) {
                onPreferences(p.copy(backgroundPlaybackBehavior = value)); backgroundDialog = false
            }
        }
    }
    if (transferBusy) AppDialog({ transferJob?.cancel() }, tr("正在传输设置…"), actions = { DialogAction(tr("取消"), { transferJob?.cancel() }, Modifier.weight(1f)) }) {
        PathfinSpinner(PathfinAppearance.accent, Modifier.padding(vertical = 8.dp).size(22.dp))
    }
    feedback?.let { message ->
        AppDialog({ feedback = null }, message, actions = { DialogAction(tr("确定"), { feedback = null }, Modifier.weight(1f), role = DialogActionRole.Primary) }) {}
    }
}

@Composable
internal fun SettingLabel(text: String) {
    Eyebrow(text, Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp))
}

/** 单选弹窗：选项放在同一张分组卡片里，底部只保留“取消”。 */
@Composable
internal fun SettingsChoiceDialog(
    title: String,
    onDismiss: () -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialog(onDismiss, title, subtitle = note, actions = { DialogAction(tr("取消"), onDismiss, Modifier.weight(1f)) }) {
        GroupCard(container = PathfinAppearance.canvas, content = content)
    }
}

/** 预设值的小胶囊：选中时墨色描边与文字，不铺色块。 */
@Composable
internal fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).border(1.dp, if (selected) PathfinAppearance.textMain else PathfinAppearance.divider, CircleShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (selected) PathfinAppearance.textMain else PathfinAppearance.textSub)
    }
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GroupCard(modifier) { Column(Modifier.padding(contentPadding), content = content) }
}

/** 按位置拼接成一张分组卡片：首尾圆角、组间留白，行间细线从 dividerStartPadding 起画。 */
@Composable
internal fun SettingsGroupItem(
    position: GroupPosition,
    dividerStartPadding: androidx.compose.ui.unit.Dp = 16.dp,
    dividerEndPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val radius = CardRadius
    val shape: Shape = when (position) {
        GroupPosition.SINGLE -> RoundedCornerShape(radius)
        GroupPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius)
        GroupPosition.MIDDLE -> RectangleShape
        GroupPosition.BOTTOM -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    }
    val bottomGap = if (position == GroupPosition.BOTTOM || position == GroupPosition.SINGLE) 18.dp else 0.dp
    Column(Modifier.fillMaxWidth().padding(bottom = bottomGap).clip(shape).background(PathfinAppearance.card)) {
        content()
        if (position == GroupPosition.TOP || position == GroupPosition.MIDDLE) {
            SettingsDivider(Modifier.padding(start = dividerStartPadding, end = dividerEndPadding))
        }
    }
}

@Composable
internal fun SettingsDivider(modifier: Modifier = Modifier) {
    Hairline(modifier)
}

@Composable
internal fun SettingsEntryItem(
    icon: ImageVector,
    title: String,
    desc: String? = null,
    position: GroupPosition = GroupPosition.SINGLE,
    onClick: () -> Unit,
) {
    SettingsGroupItem(position = position, dividerStartPadding = GroupTextInset) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 68.dp).padding(start = 16.dp, end = 12.dp, top = 15.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupIcon(icon)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
                if (!desc.isNullOrBlank()) Text(desc, fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp), tint = PathfinAppearance.textMuted)
        }
    }
}

@Composable
private fun SettingsText(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 15.sp, lineHeight = 21.sp,
            color = if (enabled) PathfinAppearance.textMain else PathfinAppearance.textMuted)
        if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp,
            color = PathfinAppearance.textMuted.copy(alpha = if (enabled) 1f else 0.6f))
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
    position: GroupPosition = GroupPosition.SINGLE,
    onClick: (() -> Unit)? = null,
) {
    SettingsGroupItem(position = position) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (tag == null) Modifier else Modifier.testTag(tag))
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .heightIn(min = 58.dp)
                .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsText(title, subtitle, enabled, Modifier.weight(1f))
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(PathfinIcons.Chevron, null, Modifier.size(16.dp),
                    tint = PathfinAppearance.textMuted.copy(alpha = if (enabled) 1f else 0.5f))
            }
        }
    }
}

/**
 * 主题色：色样即该色在当前深浅模式下校正后的真实强调色；壁纸取色需 Android 12，
 * 系统为单色主题时如实告知已沿用默认蓝。
 */
@Composable
private fun AccentColorPicker(selected: AccentColor, onChange: (AccentColor) -> Unit) {
    val wallpaperSupported = android.os.Build.VERSION.SDK_INT >= 31
    val achromatic = dev.pathfin.android.ui.theme.LocalAccentPalette.current.achromaticSource
    val dark = PathfinAppearance.canvas.luminance() < 0.5f
    val subtitle = when {
        selected == AccentColor.WALLPAPER && !wallpaperSupported -> tr("壁纸取色需要 Android 12，已使用默认蓝")
        selected == AccentColor.WALLPAPER && achromatic -> tr("系统主题色为黑白，已沿用默认蓝")
        selected == AccentColor.WALLPAPER -> tr("已跟随系统主题色")
        else -> selected.label
    }
    val colorLabel = if (selected == AccentColor.WALLPAPER && (!wallpaperSupported || achromatic))
        "${selected.label} · ${AccentColor.PATHFIN.label}" else selected.label
    SettingsGroupItem(GroupPosition.BOTTOM) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("主题色"), fontSize = 15.sp, lineHeight = 21.sp, color = PathfinAppearance.textMain,
                    maxLines = 1)
                Text(colorLabel, Modifier.weight(1f).padding(start = 12.dp).testTag("settings-accent-current")
                    .semantics { if (selected == AccentColor.WALLPAPER) stateDescription = subtitle },
                    fontSize = 12.sp, lineHeight = 21.sp, color = PathfinAppearance.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val options = AccentColor.entries.filter { it != AccentColor.WALLPAPER || wallpaperSupported }
            Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState())
                .selectableGroup().testTag("settings-accent-options")) {
                // 色点保持紧凑，完整触摸区保持独立；窄屏横滑，不压缩或折行。
                options.forEach { option ->
                    val chosen = option == selected
                    val palette = option.argb?.let { dev.pathfin.android.ui.theme.accentPalette(Color(it), dark) }
                        ?: dev.pathfin.android.ui.theme.LocalAccentPalette.current
                    val fill: Brush = if (option == AccentColor.WALLPAPER && !chosen) Brush.sweepGradient(WallpaperSwatch)
                        else SolidColor(palette.accent)
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .selectable(chosen, role = Role.RadioButton) { onChange(option) }
                        .semantics { contentDescription = option.label }.testTag("settings-accent/${option.name}"),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(32.dp)
                            .border(1.dp, if (chosen) PathfinAppearance.textMain else Color.Transparent, CircleShape)
                            .padding(2.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(PathfinIcons.Check, null, Modifier.size(16.dp), tint = palette.onAccent)
                        }
                    }
                }
            }
        }
    }
}

private val WallpaperSwatch = listOf(Color(0xFF5B89FA), Color(0xFF7458C9), Color(0xFFC24E7A), Color(0xFFC98428),
    Color(0xFF3E8E5E), Color(0xFF0E8A8F), Color(0xFF5B89FA))

@Composable
internal fun SettingsSwitch(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
    position: GroupPosition = GroupPosition.SINGLE,
    onChange: (Boolean) -> Unit,
) {
    SettingsGroupItem(position = position) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (tag == null) Modifier else Modifier.testTag(tag))
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = 58.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsText(title, subtitle, enabled, Modifier.weight(1f).padding(end = 12.dp))
            PathfinSwitch(checked, enabled)
        }
    }
}

@Composable
internal fun SettingsSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    subtitle: String? = null,
    enabled: Boolean = true,
    sliderTag: String? = null,
    position: GroupPosition = GroupPosition.SINGLE,
    onChange: (Float) -> Unit,
) {
    val safeInitial = if (value.isFinite()) value.coerceIn(range) else range.start
    var draft by remember(safeInitial) { mutableFloatStateOf(safeInitial) }
    SettingsGroupItem(position = position) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsText(title, subtitle, enabled, Modifier.weight(1f).padding(end = 12.dp))
                Text(valueLabel(draft), fontSize = 13.sp,
                    color = if (enabled) PathfinAppearance.textMain else PathfinAppearance.textMuted)
            }
            PathfinSlider(draft, { draft = it }, Modifier.fillMaxWidth().then(if (sliderTag == null) Modifier else Modifier.testTag(sliderTag)),
                onValueChangeFinished = { onChange(draft) }, range = range, steps = steps, enabled = enabled)
        }
    }
}

/** 选项整行可点，单一语义；选中时墨色文字加点缀色对勾，不铺底色。有明确含义的选项可带图标，选中时图标同样转为点缀色。 */
@Composable
internal fun SettingsOptionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    tag: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = if (icon == null) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (icon == null) 12.dp else 14.dp),
    ) {
        if (icon != null) GroupIcon(icon, when {
            !enabled -> PathfinAppearance.textMuted
            selected -> PathfinAppearance.accent
            else -> PathfinAppearance.textSub
        })
        Text(label, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (!enabled) PathfinAppearance.textMuted else PathfinAppearance.textMain)
        Icon(PathfinIcons.Check, null, Modifier.size(18.dp), tint = if (selected) PathfinAppearance.accent else Color.Transparent)
    }
}

private fun secondsLabel(value: Float) = if (value.roundToInt() == 0) tr("关闭") else tr("%1\$s 秒", value.roundToInt())

internal fun settingsPageTitle(page: String): String = when (page) {
    "general" -> tr("常规"); "appearance" -> tr("外观"); "library" -> tr("媒体库"); "player" -> tr("播放")
    "interaction" -> tr("手势与控制"); "cache" -> tr("存储与备份")
    "tracks" -> tr("音轨与字幕"); "segments" -> tr("片头与片尾")
    "exo" -> tr("解码与字幕渲染"); "licenses" -> tr("开源许可"); "about" -> tr("关于")
    "faq" -> tr("常见问题"); "crash" -> tr("崩溃日志"); "privacy" -> tr("隐私协议"); "disclaimer" -> tr("免责声明")
    else -> tr("设置")
}

/** 缓冲三档：直接在设置页里并排三张同宽小卡片，点即生效，不再弹窗。 */
@Composable
private fun BufferPresetSettings(p: AppPreferences, onPreferences: (AppPreferences) -> Unit, position: GroupPosition) {
    SettingsGroupItem(position = position) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp).testTag("settings-buffer"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("缓冲"), Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp, color = PathfinAppearance.textMain)
                Text(tr("下次播放生效"), fontSize = 12.sp, lineHeight = 17.sp, color = PathfinAppearance.textMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BufferPreset.entries.forEach { preset ->
                    val selected = p.bufferPreset == preset
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.weight(1f).clip(shape)
                        .background(if (selected) PathfinAppearance.accentSoft else PathfinAppearance.softFill)
                        .border(1.2.dp, if (selected) PathfinAppearance.accent else Color.Transparent, shape)
                        .selectable(selected, role = Role.RadioButton) { if (!selected) onPreferences(p.copy(bufferPreset = preset)) }
                        .padding(vertical = 12.dp).testTag("buffer-preset/${preset.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(preset.label, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) PathfinAppearance.accent else PathfinAppearance.textMain)
                        Text(tr("%1\$s 秒 · %2\$s MB", preset.seconds, preset.megabytes), fontSize = 11.5.sp, lineHeight = 15.sp,
                            color = if (selected) PathfinAppearance.accent.copy(alpha = .8f) else PathfinAppearance.textMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun cacheSizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${(bytes / 1024).coerceAtLeast(0)} KB"
}
