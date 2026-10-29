package dev.pathfin.android.data

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.PlaybackQuality
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppPreferencesTest {
    @Test fun roundTripIncludesPlaybackAndNavigationWithoutCredentials() {
        val value = AppPreferences(autoHideNavigation = false, accentColor = dev.pathfin.android.domain.AccentColor.IRIS, openLastServer = false,
            showArtwork = false, showPosterBadges = false, preferEpisodeImages = false, originalPathEnabled = true, liveTvEnabled = true,
            longPressSpeedEnabled = false, longPressSpeed = 3f, seekSeconds = 30, showClock = false, showBattery = false,
            preferredAudioLanguage = "ja", preferredSubtitleLanguage = "zh", subtitlesEnabled = false,
            bufferPreset = dev.pathfin.android.domain.BufferPreset.LARGE, introSkipSeconds = 60, imageCacheSizeMb = 2048,
            hideSystemStatusBar = true, blurTopBar = true, blurAdultCovers = true,
            pullToRefreshEnabled = false)
        val encoded = AppPreferencesStore.encode(value)
        assertEquals(value, AppPreferencesStore.decode(encoded))
        assertFalse(encoded.toString().contains("token", ignoreCase = true))
        assertFalse(encoded.toString().contains("password", ignoreCase = true))
    }
    @Test fun legacyWallpaperSwitchMigratesToAccentChoiceAndUnknownNamesFallBack() {
        assertEquals(dev.pathfin.android.domain.AccentColor.WALLPAPER,
            AppPreferencesStore.decode(JSONObject().put("dynamicColors", true)).accentColor)
        assertEquals(dev.pathfin.android.domain.AccentColor.PATHFIN,
            AppPreferencesStore.decode(JSONObject().put("dynamicColors", false)).accentColor)
        assertEquals("新键优先于旧开关", dev.pathfin.android.domain.AccentColor.PINE,
            AppPreferencesStore.decode(JSONObject().put("dynamicColors", true).put("accentColor", "PINE")).accentColor)
        assertEquals(dev.pathfin.android.domain.AccentColor.PATHFIN,
            AppPreferencesStore.decode(JSONObject().put("accentColor", "NEON")).accentColor)
        assertFalse(AppPreferencesStore.encode(AppPreferences()).has("dynamicColors"))
        assertFalse("悬浮导航已固定，不再写出开关", AppPreferencesStore.encode(AppPreferences()).has("floatingNavigation"))
    }

    @Test fun automaticUpdateChecksDefaultOnAndPersistWhenDisabled() {
        assertTrue(AppPreferences().autoCheckUpdates)
        assertTrue("旧设置没有此键时保持开启", AppPreferencesStore.decode(JSONObject()).autoCheckUpdates)
        assertFalse(AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(autoCheckUpdates = false))).autoCheckUpdates)
    }

    @Test fun missingKeysUseWorkingDefaults() {
        val restored = AppPreferencesStore.decode(JSONObject())
        assertEquals(AppPreferences(), restored)
        assertFalse("默认关闭 STRM 原始地址", restored.originalPathEnabled)
        assertFalse("默认关闭直播电视", restored.liveTvEnabled)
        assertTrue(restored.pullToRefreshEnabled)
        assertEquals(PlaybackQuality.ORIGINAL, restored.strmDefaultQuality)
    }

    @Test fun strmQualityPersistsEnumNamesWithoutChangingSourceOrLiveTvChoices() {
        for (quality in PlaybackQuality.entries) {
            val value = AppPreferences(strmDefaultQuality = quality, originalPathEnabled = false, liveTvDirectPlay = true)
            val encoded = AppPreferencesStore.encode(value)
            assertEquals(quality.name, encoded.getString("strmDefaultQuality"))
            assertFalse(encoded.has("preferredVersionName"))
            assertEquals(value, AppPreferencesStore.decode(JSONObject(encoded.toString())))
        }
    }

    @Test fun invalidStrmQualityFallsBackToOriginalWithoutGuessingFromText() {
        for (invalid in listOf(JSONObject.NULL, "", "UNKNOWN", "high", "高质量", "1080p.FLUX", " STANDARD ", 1, true)) {
            val imported = AppPreferencesStore.decode(JSONObject().put("strmDefaultQuality", invalid))
            assertEquals(invalid.toString(), PlaybackQuality.ORIGINAL, imported.strmDefaultQuality)
        }
    }

    @Test fun legacyPreferredVersionTextIsDiscardedEvenWhenItLooksLikeAQuality() {
        for (legacy in listOf("1080p.FLUX", "HIGH", "STANDARD", "流畅")) {
            val imported = JSONObject().put("preferredVersionName", legacy)
            val restored = AppPreferencesStore.decode(imported)
            assertEquals(PlaybackQuality.ORIGINAL, restored.strmDefaultQuality)
            assertFalse(AppPreferencesStore.encode(restored).has("preferredVersionName"))
            imported.put("strmDefaultQuality", PlaybackQuality.DATA_SAVER.name)
            assertEquals(PlaybackQuality.DATA_SAVER, AppPreferencesStore.decode(imported).strmDefaultQuality)
        }
    }

    @Test fun importedNumbersAreBoundedBeforeReachingPlayer() {
        val value = AppPreferencesStore.decode(JSONObject().put("longPressSpeed", "NaN")
            .put("bufferPreset", "UNKNOWN").put("seekSeconds", 0).put("introSkipSeconds", 99999))
        assertEquals(2.5f, value.longPressSpeed)
        assertEquals("未知档位回落为自动", dev.pathfin.android.domain.BufferPreset.AUTO, value.bufferPreset)
        assertEquals(5, value.seekSeconds)
        assertEquals(180, value.introSkipSeconds)
    }
    @Test fun cacheCapacityIsBoundedWithoutChangingIndependentAppearancePreferences() {
        assertEquals(1024, AppPreferencesStore.decode(JSONObject()).imageCacheSizeMb)
        val value = AppPreferencesStore.decode(JSONObject().put("imageCacheSizeMb", Int.MAX_VALUE)
            .put("hideSystemStatusBar", true).put("blurAdultCovers", true))
        assertEquals(65536, value.imageCacheSizeMb)
        assertTrue(value.hideSystemStatusBar)
        assertTrue(value.blurAdultCovers)
        assertEquals(16, AppPreferencesStore.decode(JSONObject().put("imageCacheSizeMb", -1)).imageCacheSizeMb)
    }

    @Test fun appearanceOptionsAreIndependentAndDisabledByDefault() {
        val defaults = AppPreferencesStore.decode(JSONObject())
        assertFalse(defaults.hideSystemStatusBar)
        assertFalse(defaults.blurAdultCovers)
        assertFalse(defaults.blurTopBar)
        val options = listOf("hideSystemStatusBar", "blurTopBar", "blurAdultCovers")
        options.forEach { selected ->
            val restored = AppPreferencesStore.decode(JSONObject().put(selected, true))
            val encoded = AppPreferencesStore.encode(restored)
            options.forEach { key -> assertEquals("$selected 不得修改 $key", key == selected, encoded.getBoolean(key)) }
            assertEquals(restored, AppPreferencesStore.decode(encoded))
        }
    }

}
