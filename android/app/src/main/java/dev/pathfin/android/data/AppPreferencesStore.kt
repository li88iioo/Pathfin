package dev.pathfin.android.data

import dev.pathfin.android.domain.AccentColor
import dev.pathfin.android.domain.BufferPreset
import dev.pathfin.android.domain.PlayerOrientation
import dev.pathfin.android.domain.PlaybackQuality
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.ui.theme.ThemePreference
import org.json.JSONObject

class AppPreferencesStore internal constructor(
    private val store: SharedPreferences,
    private val legacyTheme: SharedPreferences? = null,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences("pathfin_appearance", Context.MODE_PRIVATE),
    )

    fun load(): AppPreferences {
        // load 由启动存储 IO 调用；旧布局清理不能在首次打开网格时同步写盘。
        runCatching { migrateBrowserStyles() }
        return runCatching { decode(JSONObject(store.getString("settings", "{}")!!)) }.getOrDefault(AppPreferences())
    }
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun save(value: AppPreferences) {
        check(store.edit().putString("settings", encode(value).toString()).commit()) { tr("无法保存设置") }
    }
    /** 导入包含主题和普通偏好，一次 SharedPreferences commit，避免两个文件之间的部分成功。 */
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun saveWithTheme(value: AppPreferences, theme: ThemePreference) {
        check(store.edit().putString("settings", encode(value).toString()).putString("theme", theme.name).commit()) { tr("无法保存导入设置") }
    }
    /** 旧值只在主键缺失时迁入；迁移提交失败仍返回旧主题，不影响账号恢复。 */
    fun loadTheme(): ThemePreference {
        val current = store.getString("theme", null)
        val legacy = if (current == null) legacyTheme?.getString("mode", null) else null
        val theme = ThemePreference.entries.firstOrNull { it.name == (current ?: legacy) } ?: ThemePreference.SYSTEM
        if (legacy != null) runCatching { saveTheme(theme) }
        return theme
    }
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun saveTheme(mode: ThemePreference) {
        check(store.edit().putString("theme", mode.name).commit()) { tr("无法保存主题") }
    }
    fun hiddenLibraries(serverKey: String): Set<String> = store.getStringSet("hidden/$serverKey", emptySet())?.toSet().orEmpty()
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun saveHiddenLibraries(serverKey: String, ids: Set<String>) {
        check(store.edit().putStringSet("hidden/$serverKey", ids.toSet()).commit()) { tr("无法保存首页设置") }
    }
    fun browserStyle(key: String): String = normalizeBrowserStyle(store.getString("style/$key", "POSTER"))
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun saveBrowserStyle(key: String, style: String) {
        check(store.edit().putString("style/$key", normalizeBrowserStyle(style)).remove("columns/$key").commit()) { tr("无法保存布局") }
    }
    /** 每个媒体库各自记住排序字段与方向；值形如 "TITLE:asc"，无法识别时视为未保存。 */
    fun browserSort(key: String): Pair<dev.pathfin.android.domain.LibrarySort, Boolean>? {
        val (name, direction) = store.getString("sort/$key", null)?.split(':')?.takeIf { it.size == 2 } ?: return null
        val sort = dev.pathfin.android.domain.LibrarySort.entries.firstOrNull { it.name == name } ?: return null
        return sort to (direction == "desc")
    }
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    fun saveBrowserSort(key: String, sort: dev.pathfin.android.domain.LibrarySort, descending: Boolean) {
        check(store.edit().putString("sort/$key", "${sort.name}:${if (descending) "desc" else "asc"}").commit()) { tr("无法保存排序") }
    }
    @SuppressLint("UseKtx") // KTX edit 返回 Unit；这里必须检查同步 commit 是否成功。
    private fun migrateBrowserStyles() {
        val old = store.all.filter { (key, value) ->
            key.startsWith("columns/") || key.startsWith("style/") && value !in setOf("POSTER", "BACKDROP")
        }
        if (old.isEmpty()) return
        val editor = store.edit()
        old.forEach { (key, _) ->
            if (key.startsWith("columns/")) editor.remove(key) else editor.putString(key, "POSTER")
        }
        check(editor.commit()) { tr("无法迁移布局设置") }
    }
    companion object {
        private fun normalizeBrowserStyle(value: String?): String = if (value == "BACKDROP") "BACKDROP" else "POSTER"
        internal fun encode(v: AppPreferences) = JSONObject().apply {
            put("autoHideNavigation", v.autoHideNavigation)
            put("openLastServer", v.openLastServer); put("showArtwork", v.showArtwork)
            put("showPosterBadges", v.showPosterBadges); put("preferEpisodeImages", v.preferEpisodeImages)
            put("originalPathEnabled", v.originalPathEnabled); put("longPressSpeedEnabled", v.longPressSpeedEnabled)
            put("longPressSpeed", v.longPressSpeed); put("seekSeconds", v.seekSeconds)
            put("showClock", v.showClock); put("showBattery", v.showBattery)
            put("preferredAudioLanguage", v.preferredAudioLanguage); put("preferredSubtitleLanguage", v.preferredSubtitleLanguage)
            put("subtitlesEnabled", v.subtitlesEnabled); put("bufferPreset", v.bufferPreset.name)
            put("introSkipSeconds", v.introSkipSeconds)
            put("imageCacheSizeMb", v.imageCacheSizeMb)
            put("autoPlayNextEpisode", v.autoPlayNextEpisode); put("accentColor", v.accentColor.name)
            put("audioGainPercent", v.audioGainPercent.coerceIn(0, 200)); put("outroSkipSeconds", v.outroSkipSeconds.coerceIn(0, 180))
            put("exactSeek", v.exactSeek); put("showBufferSpeed", v.showBufferSpeed)
            put("blurAdultCovers", v.blurAdultCovers)
            put("hideSystemStatusBar", v.hideSystemStatusBar)
            put("blurTopBar", v.blurTopBar)
            put("pullToRefreshEnabled", v.pullToRefreshEnabled)
            put("backgroundBlurRadiusDp", v.backgroundBlurRadiusDp.coerceIn(0, 30))
            put("hideChaptersWithoutImage", v.hideChaptersWithoutImage); put("preferEpisodeBackgrounds", v.preferEpisodeBackgrounds)
            put("showDetailChapters", v.showDetailChapters); put("showPlayerChapters", v.showPlayerChapters)
            put("horizontalSeekEnabled", v.horizontalSeekEnabled)
            put("playbackSpeed", finite(v.playbackSpeed, 1f, .25f, 4f))
            put("playerBrightness", v.playerBrightness.takeIf { it.isFinite() && it in .02f..1f } ?: -1f)
            put("videoResizeMode", resizeMode(v.videoResizeMode))
            put("subtitleScale", finite(v.subtitleScale, 1f, .5f, 2f))
            put("subtitlePositionPercent", v.subtitlePositionPercent.coerceIn(0, 100))
            put("subtitleDelays", JSONObject(v.subtitleDelays.filter { (key, value) ->
                key.matches(Regex("[a-f0-9]{64}")) && value != 0L
            }.entries.toList().takeLast(256).associate { it.key to it.value.coerceIn(-60_000, 60_000) }))
            put("downloadNetwork", v.downloadNetwork.name)
            downloadDirectory(v.downloadDirectoryUri)?.let { put("downloadDirectoryUri", it) }
            put("playerOrientation", v.playerOrientation.name); put("rememberBrightness", v.rememberBrightness)
            put("liveTvEnabled", v.liveTvEnabled); put("liveTvDirectPlay", v.liveTvDirectPlay)
            put("serverIntroSkipEnabled", v.serverIntroSkipEnabled); put("autoSkipIntro", v.autoSkipIntro)
            put("introPromptSeconds", v.introPromptSeconds.coerceIn(0, 180))
            put("nextEpisodePromptSeconds", v.nextEpisodePromptSeconds.coerceIn(0, 180))
            put("longPressSlideSpeedEnabled", v.longPressSlideSpeedEnabled)
            put("rapidSeekWhileDragging", v.rapidSeekWhileDragging)
            put("backgroundPlaybackBehavior", backgroundBehavior(v.backgroundPlaybackBehavior))
            put("exoAssEnabled", v.exoAssEnabled)
            put("strmDefaultQuality", v.strmDefaultQuality.name)
            put("lockHighRefreshRate", v.lockHighRefreshRate)
            put("keepTracksForContinuousPlayback", v.keepTracksForContinuousPlayback)
            put("exoAudioDecoder", audioDecoder(v.exoAudioDecoder)); put("preferHardwareVideoDecoder", v.preferHardwareVideoDecoder)
            put("autoCheckUpdates", v.autoCheckUpdates)
        }
        private fun finite(value: Float, default: Float, min: Float, max: Float): Float =
            if (value.isFinite()) value.coerceIn(min, max) else default
        private fun finite(j: JSONObject, key: String, default: Float, min: Float, max: Float): Float =
            j.optDouble(key, default.toDouble()).toFloat().let { if (it.isFinite()) it.coerceIn(min, max) else default }
        internal fun decode(j: JSONObject) = AppPreferences(
            autoHideNavigation = j.optBoolean("autoHideNavigation", true),
            openLastServer = j.optBoolean("openLastServer", true), showArtwork = j.optBoolean("showArtwork", true),
            showPosterBadges = j.optBoolean("showPosterBadges", true), preferEpisodeImages = j.optBoolean("preferEpisodeImages", true),
            originalPathEnabled = j.optBoolean("originalPathEnabled", false), longPressSpeedEnabled = j.optBoolean("longPressSpeedEnabled", true),
            longPressSpeed = j.optDouble("longPressSpeed", 2.5).toFloat().let { if (it.isFinite()) it.coerceIn(1.25f, 4f) else 2.5f },
            seekSeconds = j.optInt("seekSeconds", 10).coerceIn(5, 60), showClock = j.optBoolean("showClock", true), showBattery = j.optBoolean("showBattery", true),
            preferredAudioLanguage = j.optString("preferredAudioLanguage", ""), preferredSubtitleLanguage = j.optString("preferredSubtitleLanguage", ""),
            subtitlesEnabled = j.optBoolean("subtitlesEnabled", true),
            bufferPreset = j.optString("bufferPreset").let { name -> BufferPreset.entries.firstOrNull { it.name == name } } ?: BufferPreset.AUTO,
            introSkipSeconds = j.optInt("introSkipSeconds", 0).coerceIn(0, 180),
            imageCacheSizeMb = j.optInt("imageCacheSizeMb", 1024).coerceIn(16, 65536),
            autoPlayNextEpisode = j.optBoolean("autoPlayNextEpisode", false),
            // 旧版只有“壁纸配色”开关：开着的迁移为壁纸取色。
            accentColor = j.optString("accentColor").let { name -> AccentColor.entries.firstOrNull { it.name == name } }
                ?: if (j.optBoolean("dynamicColors", false)) AccentColor.WALLPAPER else AccentColor.PATHFIN,
            audioGainPercent = j.optInt("audioGainPercent", 100).coerceIn(0, 200),
            outroSkipSeconds = j.optInt("outroSkipSeconds", 0).coerceIn(0, 180),
            exactSeek = j.optBoolean("exactSeek", true), showBufferSpeed = j.optBoolean("showBufferSpeed", false),
            blurAdultCovers = j.optBoolean("blurAdultCovers", false),
            hideSystemStatusBar = j.optBoolean("hideSystemStatusBar", false),
            blurTopBar = j.optBoolean("blurTopBar", false),
            pullToRefreshEnabled = j.optBoolean("pullToRefreshEnabled", true),
            backgroundBlurRadiusDp = j.optInt("backgroundBlurRadiusDp", 0).coerceIn(0, 30),
            hideChaptersWithoutImage = j.optBoolean("hideChaptersWithoutImage", false),
            showDetailChapters = j.optBoolean("showDetailChapters", true),
            showPlayerChapters = j.optBoolean("showPlayerChapters", true),
            horizontalSeekEnabled = j.optBoolean("horizontalSeekEnabled", true),
            playbackSpeed = j.optDouble("playbackSpeed", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1f,
            playerBrightness = j.optDouble("playerBrightness", -1.0).toFloat().takeIf { it.isFinite() && it in .02f..1f } ?: -1f,
            videoResizeMode = resizeMode(j.optString("videoResizeMode", "FIT")),
            subtitleScale = finite(j, "subtitleScale", 1f, .5f, 2f),
            subtitlePositionPercent = j.optInt("subtitlePositionPercent", 100).coerceIn(0, 100),
            subtitleDelays = j.optJSONObject("subtitleDelays")?.let { values ->
                values.keys().asSequence().filter { it.matches(Regex("[a-f0-9]{64}")) }.toList().takeLast(256)
                    .associateWith { values.optLong(it).coerceIn(-60_000, 60_000) }.filterValues { it != 0L }
            }.orEmpty(),
            downloadNetwork = dev.pathfin.android.domain.DownloadNetwork.entries.firstOrNull {
                it.name == j.optString("downloadNetwork")
            } ?: dev.pathfin.android.domain.DownloadNetwork.ANY,
            downloadDirectoryUri = downloadDirectory(j.optString("downloadDirectoryUri")),
            playerOrientation = j.optString("playerOrientation").let { name -> PlayerOrientation.entries.firstOrNull { it.name == name } }
                ?: PlayerOrientation.SENSOR,
            rememberBrightness = j.optBoolean("rememberBrightness", true),
            liveTvEnabled = j.optBoolean("liveTvEnabled", false), liveTvDirectPlay = j.optBoolean("liveTvDirectPlay", false),
            preferEpisodeBackgrounds = j.optBoolean("preferEpisodeBackgrounds", true),
            serverIntroSkipEnabled = j.optBoolean("serverIntroSkipEnabled", true), autoSkipIntro = j.optBoolean("autoSkipIntro", false),
            introPromptSeconds = j.optInt("introPromptSeconds", 0).coerceIn(0, 180),
            nextEpisodePromptSeconds = j.optInt("nextEpisodePromptSeconds", 30).coerceIn(0, 180),
            longPressSlideSpeedEnabled = j.optBoolean("longPressSlideSpeedEnabled", true),
            rapidSeekWhileDragging = j.optBoolean("rapidSeekWhileDragging", false),
            backgroundPlaybackBehavior = backgroundBehavior(j.optString("backgroundPlaybackBehavior", "PAUSE")),
            exoAssEnabled = j.optBoolean("exoAssEnabled", true),
            strmDefaultQuality = PlaybackQuality.entries.firstOrNull { it.name == j.optString("strmDefaultQuality") }
                ?: PlaybackQuality.ORIGINAL,
            lockHighRefreshRate = j.optBoolean("lockHighRefreshRate", false),
            keepTracksForContinuousPlayback = j.optBoolean("keepTracksForContinuousPlayback", false),
            exoAudioDecoder = audioDecoder(j.optString("exoAudioDecoder", "AUTO")),
            preferHardwareVideoDecoder = j.optBoolean("preferHardwareVideoDecoder", true),
            autoCheckUpdates = j.optBoolean("autoCheckUpdates", true),
        )
        private fun backgroundBehavior(value: String): String = value.takeIf { it in setOf("PAUSE", "PIP") } ?: "PAUSE"
        private fun resizeMode(value: String): String = value.takeIf { it in setOf("FIT", "FILL", "ZOOM") } ?: "FIT"
        private fun downloadDirectory(value: String?): String? {
            if (value.isNullOrBlank() || value.length > 4096 || value.any(Char::isISOControl)) return null
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            return value.takeIf { uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null && !uri.rawPath.isNullOrBlank() }
        }
        private fun audioDecoder(value: String): String = value.takeIf { it in setOf("AUTO", "HARDWARE", "SOFTWARE") } ?: "AUTO"
    }
}
