package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 本地偏好只包含可实际生效的客户端功能，不包含账号或播放凭据。 */
data class AppPreferences(
    val autoHideNavigation: Boolean = true,
    val openLastServer: Boolean = true,
    val showArtwork: Boolean = true,
    val showPosterBadges: Boolean = true,
    val preferEpisodeImages: Boolean = true,
    val originalPathEnabled: Boolean = false,
    /** 显示直播电视入口。 */
    val liveTvEnabled: Boolean = false,
    /** 直播频道直接播放直播源地址，不经服务器转码。 */
    val liveTvDirectPlay: Boolean = false,
    val longPressSpeedEnabled: Boolean = true,
    val longPressSpeed: Float = 2.5f,
    val seekSeconds: Int = 10,
    val showClock: Boolean = true,
    val showBattery: Boolean = true,
    val preferredAudioLanguage: String = "",
    val preferredSubtitleLanguage: String = "",
    val subtitlesEnabled: Boolean = true,
    val bufferPreset: BufferPreset = BufferPreset.AUTO,
    val introSkipSeconds: Int = 0,
    val imageCacheSizeMb: Int = 1024,
    val autoPlayNextEpisode: Boolean = false,
    val accentColor: AccentColor = AccentColor.PATHFIN,
    val audioGainPercent: Int = 100,
    val outroSkipSeconds: Int = 0,
    val exactSeek: Boolean = true,
    val showBufferSpeed: Boolean = false,
    val blurAdultCovers: Boolean = false,
    val hideSystemStatusBar: Boolean = false,
    val blurTopBar: Boolean = false,
    val pullToRefreshEnabled: Boolean = true,
    val backgroundBlurRadiusDp: Int = 0,
    val hideChaptersWithoutImage: Boolean = false,
    /** 媒体详情页是否显示章节区块。 */
    val showDetailChapters: Boolean = true,
    /** 播放控制栏是否显示章节入口。 */
    val showPlayerChapters: Boolean = true,
    /** 播放画面横向滑动调整进度。 */
    val horizontalSeekEnabled: Boolean = true,
    /** 上次使用的播放倍速，起播时套用。 */
    val playbackSpeed: Float = 1f,
    /** 播放器窗口亮度（0.02–1）；负数表示跟随系统。退出播放器后恢复系统亮度。 */
    val playerBrightness: Float = -1f,
    /** 进入播放器时恢复上次手势调好的亮度；关闭则每次跟随系统亮度，也不再记录。 */
    val rememberBrightness: Boolean = true,
    val videoResizeMode: String = "FIT",
    val subtitleScale: Float = 1f,
    val subtitlePositionPercent: Int = 100,
    val subtitleDelays: Map<String, Long> = emptyMap(),
    val downloadNetwork: DownloadNetwork = DownloadNetwork.ANY,
    val downloadDirectoryUri: String? = null,
    /** 进入播放器时的屏幕方向。 */
    val playerOrientation: PlayerOrientation = PlayerOrientation.SENSOR,
    val preferEpisodeBackgrounds: Boolean = true,
    val serverIntroSkipEnabled: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val introPromptSeconds: Int = 0,
    val nextEpisodePromptSeconds: Int = 30,
    val longPressSlideSpeedEnabled: Boolean = true,
    val rapidSeekWhileDragging: Boolean = false,
    val backgroundPlaybackBehavior: String = "PAUSE",
    val exoAssEnabled: Boolean = true,
    /** 仅用于开启 STRM 原始地址时的云盘画质，本地媒体与直播保持各自默认行为。 */
    val strmDefaultQuality: PlaybackQuality = PlaybackQuality.ORIGINAL,
    val lockHighRefreshRate: Boolean = false,
    val keepTracksForContinuousPlayback: Boolean = false,
    val exoAudioDecoder: String = "AUTO",
    val preferHardwareVideoDecoder: Boolean = true,
    /** 正式包启动时每天最多检查一次 GitHub 新版本。 */
    val autoCheckUpdates: Boolean = true,
)

/** 主题色：几套预置色或壁纸取色（Android 12+）。预置色只决定色相，明度由主题按对比度自动校正。 */
enum class AccentColor(private val labelKey: String, val argb: Long?) {
    PATHFIN("默认蓝", 0xFF2563EB),
    LAKE("湖青", 0xFF0E8A8F),
    PINE("松绿", 0xFF3E8E5E),
    AMBER("琥珀", 0xFFC98428),
    CORAL("珊瑚", 0xFFE0645A),
    ROSE("胭脂", 0xFFC24E7A),
    IRIS("鸢尾", 0xFF7458C9),
    WALLPAPER("壁纸取色", null);

    val label: String get() = tr(labelKey)
}

/** 直播频道走“直连直播源”开关，其余媒体走 STRM 原始地址开关。 */
fun AppPreferences.prefersOriginalSource(item: MediaItem): Boolean =
    if (item.type == "TvChannel") liveTvDirectPlay else originalPathEnabled

/** 播放器默认方向：固定横/竖屏，或在横/竖屏内、全方向随传感器旋转。 */
enum class PlayerOrientation(private val labelKey: String) {
    LANDSCAPE("横屏"),
    LANDSCAPE_SENSOR("横屏跟随传感器"),
    PORTRAIT("竖屏"),
    PORTRAIT_SENSOR("竖屏跟随传感器"),
    SENSOR("跟随传感器");

    val label: String get() = tr(labelKey)
}

/** 播放缓冲三档：最长缓冲时长与缓冲容量一起变化，不再分别手调。 */
enum class BufferPreset(private val labelKey: String, val seconds: Int, val megabytes: Int) {
    AUTO("自动", 60, 64),
    LARGE("大", 120, 128),
    HUGE("特大", 180, 256);

    val label: String get() = tr(labelKey)
}
