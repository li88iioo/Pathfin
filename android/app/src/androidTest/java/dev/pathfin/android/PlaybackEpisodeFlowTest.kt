package dev.pathfin.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.*
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 仅自有 Jellyfin 12.3 合成连续剧；实际上一集/下一集/选集/重复自动连播及停止边界。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackEpisodeFlowTest {
    @get:Rule val compose = createVerificationComposeRule<MainActivity>()

    @Test fun episodeButtonsSelectionAndRepeatedAutoAdvanceKeepOriginalDetail() = fixture { app, playback, episodes ->
        val original = app.state.value.routes
        start(app, playback, episodes.first())
        compose.onNodeWithTag("player-previous").assertIsNotEnabled()
        val first = main { checkNotNull(playback.player) }
        compose.onNodeWithTag("player-next").performVerificationClick(compose)
        awaitEpisode(app, playback, episodes[1])
        main { assertTrue(first.isReleased) }
        compose.onNodeWithTag("player-previous").performVerificationClick(compose)
        awaitEpisode(app, playback, episodes.first())
        compose.onNodeWithTag("player-title").assertIsEnabled().performVerificationClick(compose)
        compose.waitUntil(WAIT) { app.state.value.playbackEpisodes.items.size == 3 }
        compose.onNodeWithTag("playback-episodes").performScrollToNode(hasText("S1E3 · Synthetic Episode 3"))
        compose.onNodeWithText("S1E3 · Synthetic Episode 3").performVerificationClick(compose)
        awaitEpisode(app, playback, episodes.last())
        compose.onNodeWithTag("player-next").assertIsNotEnabled()
        // 同一集再次播放后也必须能自动前进，不能以 itemId 永久去重。
        main { app.setPreferences(app.state.value.preferences.copy(autoPlayNextEpisode = true)) }
        repeat(2) {
            main { app.prepareEpisodePlayback(episodes.first()) }
            awaitEpisode(app, playback, episodes.first())
            endCurrent(playback)
            awaitEpisode(app, playback, episodes[1])
        }
        main { app.prepareEpisodePlayback(episodes.last()) }
        awaitEpisode(app, playback, episodes.last())
        main { playback.setControlsLocked(true); assertTrue(playback.state.value.controlsLocked) }
        endCurrent(playback)
        // 锁定控件也应在末集播完后退出，不能把自然结束当成一次返回解锁。
        compose.waitUntil(WAIT) { app.state.value.activePlayback == null }
        main {
            assertNull(playback.player)
            assertFalse(playback.state.value.controlsLocked)
            assertFalse(app.state.value.preparingPlay)
            assertEquals(original, app.state.value.routes)
            assertFalse(compose.activity.isFinishing)
        }
    }

    @Test fun autoAdvanceIsOffByDefaultAndEndingReturnsToDetail() = fixture { app, playback, episodes ->
        assertFalse(app.state.value.preferences.autoPlayNextEpisode)
        start(app, playback, episodes.first())
        main {
            playback.setControlsLocked(true)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(WAIT) { !playback.state.value.controlsLocked }
        main {
            assertFalse(playback.state.value.controlsLocked)
            assertEquals(episodes.first().id, app.state.value.activePlayback?.item?.id)
            assertNotNull(playback.player)
            playback.setControlsLocked(true)
            assertTrue(playback.state.value.controlsLocked)
        }
        endCurrent(playback)
        compose.waitUntil(WAIT) { app.state.value.activePlayback == null }
        main {
            assertNull(playback.player)
            assertFalse(playback.state.value.controlsLocked)
            assertFalse(app.state.value.preparingPlay)
            assertEquals(Route.Detail(episodes.first().id), app.state.value.route)
            assertFalse(compose.activity.isFinishing)
        }
    }

    private fun fixture(block: (AppViewModel, PlaybackViewModel, List<MediaItem>) -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pathfin.episodeFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val origin = requireNotNull(args.getString("pathfin.managementServer"))
        val uri = URI(origin)
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null)
        val store = AppPreferencesStore(context)
        val previous = store.load()
        val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
        var profile: String? = null
        try {
            compose.waitUntil(WAIT) { !app.state.value.restoringSession }
            main {
                app.setPreferences(AppPreferences(showClock = false, showBattery = false))
                app.login(origin, requireNotNull(args.getString("pathfin.managementUser")),
                    requireNotNull(args.getString("pathfin.managementPassword")), true, "Synthetic episode test")
            }
            compose.waitUntil(WAIT) {
                app.state.value.loginError?.let { throw AssertionError(it) }
                app.state.value.session?.server?.baseUrl == origin && app.state.value.home.loaded && !app.state.value.home.loading
            }
            profile = app.state.value.currentProfileId
            val session = requireNotNull(app.state.value.session)
            val episodes = runBlocking { app.api.items(session, options = LibraryQuery(itemTypes = setOf("Episode"), episodeOrder = true, descending = false)).items }
                .filter { it.seriesName == "Home Contract Series" }.sortedBy { it.indexNumber }
            check(episodes.size == 3 && episodes.all { it.name.startsWith("Synthetic Episode ") })
            main { app.open(episodes.first()) }
            compose.waitUntil(WAIT) { app.state.value.route == Route.Detail(episodes.first().id) && !app.state.value.detailLoading }
            block(app, playback, episodes)
        } finally {
            main { playback.stop(); if (app.state.value.activePlayback != null) app.playerClosed(playback.lastPositionMs) }
            runBlocking { playback.awaitPendingReports() }
            profile?.let { SessionStore(context).removeProfile(it) }
            store.save(previous)
        }
    }

    private fun start(app: AppViewModel, playback: PlaybackViewModel, episode: MediaItem) {
        main { app.preparePlayback(app.state.value.details.getValue(episode.id), fromStart = true) }
        awaitEpisode(app, playback, episode)
    }
    private fun awaitEpisode(app: AppViewModel, playback: PlaybackViewModel, item: MediaItem) {
        compose.waitUntil(WAIT) {
            app.state.value.playbackError?.let { throw AssertionError(it) }
            playback.state.value.errorMessage?.let { throw AssertionError(it) }
            app.state.value.activePlayback?.item?.id == item.id && playback.state.value.hasRenderedFrame &&
                playback.state.value.isPlaying && app.state.value.playbackNeighbors.any { it.id == item.id }
        }
        main { playback.player!!.pause() }
        compose.waitUntil(WAIT) { !playback.state.value.isPlaying }
        if (compose.onAllNodes(hasTestTag("player-back")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("player-video").performVerificationTouchInput(compose) { click(Offset(width * .5f, height * .3f)) }
    }
    private fun endCurrent(playback: PlaybackViewModel) = main {
        val player = requireNotNull(playback.player)
        assertTrue("使用真实10分钟合成片源", player.duration in 599_000..601_000)
        player.seekTo(player.duration - 500)
        player.play()
    }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)
    private companion object { const val WAIT = 30_000L }
}
