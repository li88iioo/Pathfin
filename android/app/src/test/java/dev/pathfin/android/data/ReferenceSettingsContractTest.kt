package dev.pathfin.android.data

import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.PlaybackQuality
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReferenceSettingsContractTest {
    @Test fun independentPlaybackAndAppearanceSettingsRoundTrip() {
        val settings = AppPreferences(backgroundBlurRadiusDp = 20, hideChaptersWithoutImage = true, preferEpisodeBackgrounds = false,
            serverIntroSkipEnabled = false, autoSkipIntro = true, introPromptSeconds = 75, nextEpisodePromptSeconds = 0,
            longPressSlideSpeedEnabled = false, rapidSeekWhileDragging = true, backgroundPlaybackBehavior = "PIP",
            exoAssEnabled = false, strmDefaultQuality = PlaybackQuality.HIGH, lockHighRefreshRate = true,
            keepTracksForContinuousPlayback = true, exoAudioDecoder = "SOFTWARE", preferHardwareVideoDecoder = false,
            hideSystemStatusBar = true, blurAdultCovers = true)
        assertEquals(settings, AppPreferencesStore.decode(AppPreferencesStore.encode(settings)))
        assertFalse(AppPreferencesStore.decode(JSONObject()).hideChaptersWithoutImage)
    }

    @Test fun importedAndInMemoryNumbersKeepEffectsAndPlaybackPromptsBounded() {
        val value = AppPreferencesStore.decode(JSONObject().put("backgroundBlurRadiusDp", -1)
            .put("introPromptSeconds", Int.MAX_VALUE).put("nextEpisodePromptSeconds", -1))
        assertEquals(0, value.backgroundBlurRadiusDp)
        assertEquals(180, value.introPromptSeconds)
        assertEquals(0, value.nextEpisodePromptSeconds)
        val restored = AppPreferencesStore.decode(AppPreferencesStore.encode(AppPreferences(backgroundBlurRadiusDp = 99)))
        assertEquals(30, restored.backgroundBlurRadiusDp)
        assertEquals("PAUSE", AppPreferencesStore.decode(JSONObject().put("backgroundPlaybackBehavior", "BACKGROUND_AUDIO")).backgroundPlaybackBehavior)
    }

    @Test fun filteringSortingAndDeduplicatingChaptersKeepsOriginalImageIndices() {
        val item = JsonMapping.mediaItem(JSONObject("""{"Id":"episode","Type":"Episode","Chapters":[
            null,{"Name":"Later","StartPositionTicks":300000000,"ImageTag":"later"},
            {"StartPositionTicks":-1,"ImageTag":"invalid"},
            {"Name":"Start","StartPositionTicks":0,"ImageTag":"start"},
            {"Name":"Duplicate","StartPositionTicks":0,"ImageTag":"duplicate"},
            {"Name":"No image","StartPositionTicks":600000000,"ImageTag":" "}
        ]}"""))
        assertEquals(listOf(3, 1, 5), item.chapters.map { it.imageIndex })
        assertEquals(listOf("Start", "Later", "No image"), item.chapters.map { it.name })
        assertNull(item.chapters.last().imageTag)
        val api = JellyfinApi()
        val session = Session(ServerConfig("https://media.example/proxy"), "fixture-token", "user", "User", "device")
        val url = api.chapterImageUrl(session, item, item.chapters.first().imageIndex, "tag & ?/#", 99999)!!.toHttpUrl()
        assertEquals("/proxy/Items/episode/Images/Chapter/3", url.encodedPath)
        assertEquals("tag & ?/#", url.queryParameter("tag"))
        assertEquals("2048", url.queryParameter("maxWidth"))
        assertFalse(url.toString().contains(session.accessToken))
        assertNull(api.chapterImageUrl(session, item, -1, "tag"))
        assertNull(api.chapterImageUrl(session, item, 0, ""))
        assertNull(api.chapterImageUrl(session, item, 0, "\n"))
        assertNull(api.chapterImageUrl(session, item.copy(id = "../other"), 0, "tag"))
    }
}
