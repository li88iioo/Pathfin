package dev.pathfin.android

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.PendingPlaybackCancellationTest.Fixture
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.*
import dev.pathfin.android.playback.PlaybackViewModel
import dev.pathfin.android.ui.AppViewModel
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** 真实App起播/菜单/恢复；只有显式传 cloudEndpoint 才访问经授权的只读云盘验证桥。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaFluxCloudPlaybackFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val compose = createVerificationComposeRule<MainActivity>()
    private val network = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val setup = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            check(context.packageName == "dev.pathfin.android.verification")
            clearSynthetic()
            val store = AppPreferencesStore(context)
            val previous = store.load()
            store.save(AppPreferences(strmDefaultQuality = PlaybackQuality.STANDARD, originalPathEnabled = true,
                subtitlesEnabled = false, exoAssEnabled = false, serverIntroSkipEnabled = false,
                autoPlayNextEpisode = false, showClock = false, showBattery = false))
            try { base.evaluate() } finally {
                clearSynthetic(); store.save(previous)
                network.connectionPool.evictAll(); network.dispatcher.executorService.shutdown()
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(setup).around(compose)

    @Test fun liveCloudUsesDefaultThenSwitchesThroughMenuAndKeepsPositionOnFailure() = verifyLiveCloudFlow(false)

    @Test fun liveCloudThroughReverseProxyUsesDefaultThenSwitchesAndKeepsPositionOnFailure() = verifyLiveCloudFlow(true)

    private fun verifyLiveCloudFlow(reverseProxyPayload: Boolean) {
        val endpoint = InstrumentationRegistry.getArguments().getString("cloudEndpoint")
        assumeTrue("需要显式授权的只读云盘桥", !endpoint.isNullOrBlank())
        val base = requireNotNull(endpoint)
        val sourceUrl = get(base + "/bootstrap").getString("url")
        withApp(sourceUrl, reverseProxyPayload = reverseProxyPayload) { fixture, app, playback ->
            val expectedSourceUrl = if (reverseProxyPayload) URI(sourceUrl).let { "${fixture.origin}${it.rawPath}?${it.rawQuery}" } else sourceUrl
            start(app, playback)
            await { playback.currentRequest?.quality == PlaybackQuality.STANDARD && playback.state.value.hasRenderedFrame }
            main {
                assertNotNull(playback.currentRequest?.cloudPlayback)
                assertFalse(playback.currentRequest!!.stream.trustedServer)
                assertTrue(playback.currentRequest!!.stream.headers.isEmpty())
                assertEquals(expectedSourceUrl, playback.currentRequest!!.cloudPlayback!!.sourceUrl)
                if (reverseProxyPayload) {
                    assertEquals("File", playback.currentRequest!!.source.protocol)
                    assertEquals(sourceUrl, playback.currentRequest!!.source.cloudPlaybackUrl)
                    assertNotEquals(sourceUrl, playback.currentRequest!!.source.path)
                }
                assertEquals("application/x-mpegURL", playback.currentRequest!!.stream.mimeType)
                assertEquals(720, playback.player!!.videoSize.height)
                playback.player!!.volume = 0f
                playback.player!!.seekTo(90_000)
            }
            await { playback.player?.isPlaying == true && playback.player!!.currentPosition > 90_300 }
            val negotiations = fixture.nextPlaybackRequests.get()
            val position = main { playback.player!!.pause(); playback.player!!.setPlaybackSpeed(1.5f); playback.player!!.currentPosition }
            // 真实菜单使用同一四档入口，不能仍显示Jellyfin码率说明。
            compose.onNodeWithTag("player-more").performVerificationClick(compose)
            compose.onNodeWithTag("player-tools").performVerificationClick(compose)
            compose.onNodeWithTag("player-quality-sheet").assertVerificationDisplayed(compose)
            compose.onAllNodesWithText("码率上限", substring = true).assertCountEquals(0)
            compose.onNodeWithTag("player-quality-high").performVerificationClick(compose)
            await { playback.currentRequest?.quality == PlaybackQuality.HIGH && !playback.state.value.isChangingQuality && playback.state.value.hasRenderedFrame }
            main {
                assertEquals(1080, playback.player!!.videoSize.height)
                assertFalse(playback.player!!.playWhenReady)
                assertTrue(kotlin.math.abs(playback.player!!.currentPosition - position) < 500)
                assertEquals(1.5f, playback.player!!.playbackParameters.speed)
            }
            assertEquals("云盘切档不能触发JF转码协商", negotiations, fixture.nextPlaybackRequests.get())
            assertTrue(fixture.playbackBodies.all { !it.has("MaxStreamingBitrate") && it.getBoolean("EnableDirectPlay") })
            val owner = main { playback.player!! }
            control(base, "failure", true)
            try {
                main { playback.changeQuality(PlaybackQuality.DATA_SAVER) }
                await { !playback.state.value.isChangingQuality && playback.state.value.qualityError != null }
                main {
                    assertSame("取流失败必须保留旧播放器", owner, playback.player)
                    assertEquals(PlaybackQuality.HIGH, playback.currentRequest!!.quality)
                    assertFalse(playback.player!!.playWhenReady)
                }
            } finally { control(base, "failure", false) }
            // 只对下一次返回地址注入403；实际恢复必须再请求原STRM，而不是重放过期CDN。
            control(base, "expire_once", true)
            main { playback.retry() }
            await { !playback.state.value.isPreparing && playback.state.value.errorMessage == null && playback.state.value.hasRenderedFrame && playback.player?.playbackState == Player.STATE_READY }
            main {
                assertEquals(PlaybackQuality.HIGH, playback.currentRequest!!.quality)
                assertEquals(expectedSourceUrl, playback.currentRequest!!.cloudPlayback!!.sourceUrl)
                assertFalse(playback.player!!.playWhenReady)
            }
            main { playback.changeQuality(PlaybackQuality.ORIGINAL) }
            await { playback.currentRequest?.quality == PlaybackQuality.ORIGINAL && !playback.state.value.isChangingQuality && playback.state.value.hasRenderedFrame }
            main { assertEquals("video/mp4", playback.currentRequest!!.stream.mimeType); assertFalse(playback.player!!.playWhenReady) }
            assertEquals(negotiations, fixture.nextPlaybackRequests.get())
            runBlocking { withTimeout(WAIT) { playback.awaitPendingReports() } }
            assertEquals(1, fixture.eventCount("start", ITEM))
            assertEquals(0, fixture.eventCount("stop", ITEM))
            if (reverseProxyPayload) {
                assertEquals("反代发现必须从会话 origin 转发", 1, fixture.cloudVariantRequests.get())
                assertTrue("切档和断流恢复都必须经过反代", fixture.cloudResolutions.size >= 5)
            }
            Log.i("CloudQualityAcceptance", "default720+seek90+menu1080+paused+failed-switch-preserved+403-refresh+original:PASS")
        }
    }

    @Test fun reverseProxyPayloadStartsSwitchesAndRefreshesSubtitlesWhileKeepingPositionOnFailure() {
        withApp(cloudScenario = Fixture.CloudScenario.WITH_H264_RENDITIONS, reverseProxyPayload = true) { fixture, app, playback ->
            start(app, playback)
            val sourceUrl = main {
                val request = requireNotNull(playback.currentRequest)
                assertEquals("File", request.source.protocol)
                val advertised = URI(requireNotNull(request.source.cloudPlaybackUrl))
                assertEquals("反代广告复现端口丢失", -1, advertised.port)
                assertEquals("${fixture.origin}${advertised.rawPath}?${advertised.rawQuery}", request.cloudPlayback!!.sourceUrl)
                assertNotEquals(request.source.path, request.cloudPlayback!!.sourceUrl)
                assertTrue(request.stream.headers.isEmpty())
                assertEquals(PlaybackQuality.STANDARD, request.quality)
                playback.player!!.pause()
                playback.player!!.seekTo(3_000)
                playback.player!!.setPlaybackSpeed(1.5f)
                request.cloudPlayback!!.sourceUrl
            }
            await { playback.player?.playbackState == Player.STATE_READY && kotlin.math.abs(playback.player!!.currentPosition - 3_000) < 500 }
            val position = main { playback.player!!.currentPosition }
            val negotiations = fixture.nextPlaybackRequests.get()
            assertEquals("默认云盘档只使用首次 JF PlaybackInfo", 1, negotiations)
            main { playback.changeQuality(PlaybackQuality.HIGH) }
            await { playback.currentRequest?.quality == PlaybackQuality.HIGH && !playback.state.value.isChangingQuality && playback.state.value.hasRenderedFrame }
            main {
                assertEquals(sourceUrl, playback.currentRequest!!.cloudPlayback!!.sourceUrl)
                assertFalse(playback.player!!.playWhenReady)
                assertTrue(kotlin.math.abs(playback.player!!.currentPosition - position) < 500)
                assertEquals(1.5f, playback.player!!.playbackParameters.speed)
            }
            assertEquals("反代云盘切档不能重新协商 JF", negotiations, fixture.nextPlaybackRequests.get())
            val owner = main { playback.player!! }
            fixture.cloudResolveStatus.set(503)
            try {
                main { playback.changeQuality(PlaybackQuality.STANDARD) }
                await { !playback.state.value.isChangingQuality && playback.state.value.qualityError != null }
                main {
                    assertSame(owner, playback.player)
                    assertEquals(PlaybackQuality.HIGH, playback.currentRequest!!.quality)
                    assertFalse(playback.player!!.playWhenReady)
                    assertTrue(kotlin.math.abs(playback.player!!.currentPosition - position) < 500)
                    assertTrue(playback.state.value.qualityError!!.contains("云盘未登录"))
                }
            } finally { fixture.cloudResolveStatus.set(200) }

            val discoveries = fixture.cloudVariantRequests.get()
            fixture.playbackSourceSuffix.set("-subtitle-refresh")
            main { playback.refreshSubtitles(requireNotNull(playback.currentRequest).item) }
            await {
                playback.currentRequest?.source?.name == "first-subtitle-refresh" &&
                    !playback.state.value.isChangingQuality && playback.state.value.hasRenderedFrame
            }
            main {
                assertEquals(sourceUrl, playback.currentRequest!!.cloudPlayback!!.sourceUrl)
                assertEquals(PlaybackQuality.HIGH, playback.currentRequest!!.quality)
                assertFalse(playback.player!!.playWhenReady)
                assertTrue(kotlin.math.abs(playback.player!!.currentPosition - position) < 500)
            }
            assertEquals("代理 Path 改变而补全 origin 后的云盘 URL 未变时复用发现结果", discoveries, fixture.cloudVariantRequests.get())
            assertEquals(negotiations + 1, fixture.nextPlaybackRequests.get())
            val resolutions = fixture.cloudResolutions.size
            main { playback.retry() }
            await {
                fixture.cloudResolutions.size > resolutions && !playback.state.value.isPreparing &&
                    playback.state.value.errorMessage == null && playback.state.value.hasRenderedFrame && playback.player?.playbackState == Player.STATE_READY
            }
            main {
                assertEquals(sourceUrl, playback.currentRequest!!.cloudPlayback!!.sourceUrl)
                assertEquals(PlaybackQuality.HIGH, playback.currentRequest!!.quality)
                assertFalse(playback.player!!.playWhenReady)
                assertTrue(kotlin.math.abs(playback.player!!.currentPosition - position) < 500)
                assertEquals(1.5f, playback.player!!.playbackParameters.speed)
            }
            assertEquals("恢复必须通过会话 origin 重新解析画质", resolutions + 1, fixture.cloudResolutions.size)
            assertEquals("云盘恢复不能追加 JF 协商", negotiations + 1, fixture.nextPlaybackRequests.get())
            assertNoJellyfinFallback(fixture)
        }
    }

    @Test fun disabledOriginalPathKeepsReverseProxyPlaybackOnJellyfin() = verifyReverseProxyJellyfinFallback(false)

    @Test fun invalidReverseProxyExtensionKeepsNormalJellyfinPlayback() = verifyReverseProxyJellyfinFallback(true)

    private fun verifyReverseProxyJellyfinFallback(originalPathEnabled: Boolean) {
        withApp(reverseProxyPayload = true) { fixture, app, playback ->
            if (originalPathEnabled) fixture.cloudExtensionProtocol.set("mediaflux.playback.v2")
            main { app.setPreferences(app.state.value.preferences.copy(originalPathEnabled = originalPathEnabled)) }
            start(app, playback)
            main {
                assertEquals("File", playback.currentRequest!!.source.protocol)
                assertNull(playback.currentRequest!!.cloudPlayback)
                assertNull(playback.state.value.cloudQualityOptions)
                assertEquals(PlaybackQuality.ORIGINAL, playback.currentRequest!!.quality)
                assertTrue(playback.currentRequest!!.stream.trustedServer)
            }
            assertEquals(0, fixture.cloudVariantRequests.get())
            assertTrue(fixture.cloudResolutions.isEmpty())
            assertTrue(fixture.serverMediaRequests.get() > 0)
        }
    }

    @Test fun aLocalFileIgnoresTheCloudDefaultAndKeepsJellyfinQualityNegotiation() {
        withApp(null) { fixture, app, playback ->
            start(app, playback)
            main {
                assertNull(playback.currentRequest!!.cloudPlayback)
                assertNull(playback.state.value.cloudQualityOptions)
                assertEquals(PlaybackQuality.ORIGINAL, playback.currentRequest!!.quality)
                assertTrue(playback.currentRequest!!.stream.trustedServer)
            }
            val before = fixture.nextPlaybackRequests.get()
            main { playback.player!!.pause(); playback.changeQuality(PlaybackQuality.STANDARD) }
            // 合成JF不提供转码流：仍应按旧路径协商4Mbps并保留正在播放的本地源。
            await { !playback.state.value.isChangingQuality && playback.state.value.qualityError != null }
            assertEquals(before + 1, fixture.nextPlaybackRequests.get())
            val request = fixture.playbackBodies.last()
            assertEquals(4_000_000, request.getInt("MaxStreamingBitrate"))
            assertFalse(request.getBoolean("EnableDirectPlay"))
            main { assertEquals(PlaybackQuality.ORIGINAL, playback.currentRequest!!.quality); assertNull(playback.currentRequest!!.cloudPlayback) }
        }
    }

    @Test fun anUnsupportedOriginalWithoutCloudRenditionsReportsLocalDecodeErrorWithoutServerFallback() {
        withApp(cloudScenario = Fixture.CloudScenario.ORIGINAL_ONLY) { fixture, app, playback ->
            preferOriginal(app)
            main { app.preparePlayback(MediaItem(ITEM, "Cloud verification", "Episode", seriesId = "pending-series"), true) }
            awaitUnsupportedOriginal(app, playback)
            assertNoJellyfinFallback(fixture)
            assertTrue("已知不支持的云盘原画不能解析或尝试起播", fixture.cloudResolutions.isEmpty())
            assertTrue(fixture.cloudMediaRequests.isEmpty())
            assertTrue(fixture.cloudVariantRequests.get() > 0)
            assertEquals(PlaybackQuality.ORIGINAL, AppPreferencesStore(context).load().strmDefaultQuality)
            dismissFailedPreparation(app, playback)
            assertNoJellyfinFallback(fixture)
            assertEquals("未出帧不能虚报播放开始", 0, fixture.eventCount("start", ITEM))
        }
    }

    @Test fun compatibleH264CloudRenditionDoesNotInheritTheOriginalDolbyVisionRestriction() {
        withApp(cloudScenario = Fixture.CloudScenario.WITH_H264_RENDITIONS) { fixture, app, playback ->
            preferOriginal(app)
            start(app, playback)
            main {
                val request = requireNotNull(playback.currentRequest)
                assertFalse(request.stream.trustedServer)
                assertEquals(PlayMethod.DIRECT_PLAY, request.stream.method)
                assertEquals(PlaybackQuality.HIGH, request.quality)
                val cloud = requireNotNull(request.cloudPlayback)
                assertEquals("high", cloud.selectedId)
                assertFalse("原画使用完整 Jellyfin 轨道信息判定", cloud.variants.single { it.original }.playable)
                assertTrue("云端 H264 档必须按自身元数据判定", cloud.variants.single { it.id == "high" }.playable)
                assertTrue(playback.player!!.videoSize.width > 0 && playback.player!!.videoSize.height > 0)
                assertEquals(PlaybackQuality.ORIGINAL, app.state.value.preferences.strmDefaultQuality)
            }
            assertEquals(listOf("high"), fixture.cloudResolutions.toList())
            assertTrue(fixture.cloudMediaRequests.contains("high"))
            assertFalse(fixture.cloudMediaRequests.contains("original"))
            assertNoJellyfinFallback(fixture)
            assertEquals("兼容云档必须直接交给本机解码", PlaybackDelivery.AUTOMATIC,
                main { playback.currentRequest!!.delivery })
            assertEquals("自动选档不能改写持久偏好", PlaybackQuality.ORIGINAL, AppPreferencesStore(context).load().strmDefaultQuality)
        }
    }

    @Test fun unsupportedSelectedVersionCanBeDismissedThenAnotherVersionStartsWithoutJellyfinFallback() {
        withApp(cloudScenario = Fixture.CloudScenario.ORIGINAL_ONLY, multipleSources = true) { fixture, app, playback ->
            preferOriginal(app)
            main { app.preparePlayback(MediaItem(ITEM, "Cloud verification", "Episode", seriesId = "pending-series"), true) }
            await {
                app.state.value.playbackError?.let { throw AssertionError("版本列表加载失败：$it") }
                app.state.value.sourceSelection?.info?.sources?.size == 2
            }
            assertEquals("尚未选择版本不能预先解析云盘", 0, fixture.cloudVariantRequests.get())
            assertTrue(fixture.playbackBodies.all { it.getBoolean("EnableDirectPlay") })
            val selected = "source-$ITEM-second"
            compose.onNodeWithTag("playback-source/$selected").performVerificationClick(compose)
            awaitUnsupportedOriginal(app, playback)
            assertNoJellyfinFallback(fixture)
            assertTrue("不能解析不兼容版本的原画", fixture.cloudResolutions.isEmpty())
            main { assertEquals(selected, app.state.value.pendingPlayback!!.sourceId) }
            dismissFailedPreparation(app, playback)

            main { app.preparePlayback(MediaItem(ITEM, "Cloud verification", "Episode", seriesId = "pending-series"), true) }
            await { app.state.value.sourceSelection?.info?.sources?.size == 2 && !app.state.value.preparingPlay }
            val compatible = "source-$ITEM-first"
            compose.onNodeWithTag("playback-source/$compatible").performVerificationClick(compose)
            awaitVideo(app, playback)
            main {
                val request = requireNotNull(playback.currentRequest)
                assertEquals(compatible, request.source.id)
                assertEquals(compatible, request.stream.sourceId)
                assertFalse(request.stream.trustedServer)
                assertEquals(PlayMethod.DIRECT_PLAY, request.stream.method)
                assertEquals("original", request.cloudPlayback!!.selectedId)
                assertEquals(PlaybackQuality.ORIGINAL, request.quality)
            }
            assertEquals("只有兼容的另一版本可以解析原画", listOf("original"), fixture.cloudResolutions.toList())
            assertTrue(fixture.cloudMediaRequests.contains("original"))
            assertNoJellyfinFallback(fixture)
        }
    }

    @Test fun unsupportedRawOriginalNeverUsesJellyfinWhenDiscoveryRedirects() =
        verifyUnsupportedRawOriginal("redirect")

    @Test fun unsupportedRawOriginalNeverUsesJellyfinWhenDiscoveryIsNotRecognized() =
        verifyUnsupportedRawOriginal("unknown-json")

    @Test fun unsupportedRawOriginalNeverUsesJellyfinForAnOrdinaryHttpSource() =
        verifyUnsupportedRawOriginal("ordinary-http")

    private fun verifyUnsupportedRawOriginal(discovery: String) {
        val media = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        RawOriginalSource(media, discovery).use { raw ->
            withApp(raw.url, cloudScenario = Fixture.CloudScenario.ORIGINAL_ONLY) { fixture, app, playback ->
                preferOriginal(app)
                main { app.preparePlayback(MediaItem(ITEM, "Raw original", "Episode", seriesId = "pending-series"), true) }
                awaitUnsupportedOriginal(app, playback)
                assertNoJellyfinFallback(fixture)
                assertEquals("$discovery 不应重新协商服务器播放", 1, fixture.nextPlaybackRequests.get())
                assertEquals("已知不能解码的原画不应直接读取", 0, raw.mediaRequests.get())
                if (discovery != "ordinary-http") assertEquals(1, raw.discoveryRequests.get())
                dismissFailedPreparation(app, playback)
            }
        }
    }

    @Test fun rawOriginalRuntimeFormatFailureDoesNotSilentlyReplaceItWithJellyfin() {
        val media = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        RawOriginalSource(media, "redirect").use { raw ->
            // 无 CloudPlayback、无预先已知的视频限制，必须先经原始HTTP地址真实出帧。
            withApp(raw.url) { fixture, app, playback ->
                preferOriginal(app)
                start(app, playback)
                val before = fixture.nextPlaybackRequests.get()
                val owner = main { checkNotNull(playback.player).also {
                    assertNull(playback.currentRequest?.cloudPlayback)
                    assertFalse(checkNotNull(playback.currentRequest).stream.trustedServer)
                    it.pause()
                    val index = (0 until it.rendererCount).first { renderer -> it.getRendererType(renderer) == C.TRACK_TYPE_VIDEO }
                    val unsupported = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(320).setHeight(180).build()
                    val failure = ExoPlaybackException.createForRenderer(IllegalStateException("Owned unsupported raw format"),
                        it.getRenderer(index).name, index, unsupported, C.FORMAT_UNSUPPORTED_SUBTYPE, null, false,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                    it.createMessage { _, _ -> throw failure }.send()
                } }
                await { playback.state.value.errorMessage != null || fixture.nextPlaybackRequests.get() > before }
                assertEquals("原始HTTP解码失败不能增加JF协商", before, fixture.nextPlaybackRequests.get())
                assertNoJellyfinFallback(fixture)
                main {
                    assertSame("不能静默换成服务器播放器", owner, playback.player)
                    assertNotNull(playback.state.value.errorMessage)
                    assertFalse(checkNotNull(playback.currentRequest).stream.trustedServer)
                }
                assertTrue("必须真的读取原始HTTP视频", raw.mediaRequests.get() > 0)
            }
        }
    }

    @Test fun rawOriginalNetworkRetryRejectsAChangedFileSourceWithoutInstallingJellyfin() {
        val media = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        RawOriginalSource(media, "ordinary-http").use { raw ->
            // 既有合成服务器每次都返回 File，用真实 raw 播放请求模拟起播后源被修改。
            withApp(null) { fixture, app, playback ->
                preferOriginal(app)
                start(app, playback)
                main {
                    val file = checkNotNull(playback.currentRequest)
                    assertEquals("File", file.source.protocol)
                    val source = file.source.copy(path = raw.url, protocol = "Http")
                    val request = file.copy(source = source, info = file.info.copy(sources = listOf(source)),
                        stream = ResolvedPlayback(raw.url, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, false))
                    playback.stop()
                    playback.play(request, app.state.value.preferences)
                }
                awaitVideo(app, playback)
                val before = fixture.nextPlaybackRequests.get()
                val serverMediaBefore = fixture.serverMediaRequests.get()
                val owner = main { checkNotNull(playback.player).also {
                    assertFalse(checkNotNull(playback.currentRequest).stream.trustedServer)
                    assertEquals(raw.url, playback.currentRequest!!.stream.uri)
                    it.pause()
                    val failure = ExoPlaybackException.createForSource(IOException("Owned transient raw transport"),
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                    it.createMessage { _, _ -> throw failure }.send()
                } }
                await { fixture.nextPlaybackRequests.get() > before && !playback.state.value.isPreparing }
                assertEquals("网络恢复仅允许一次元数据刷新", before + 1, fixture.nextPlaybackRequests.get())
                assertTrue("源变为 File 后不能追加转码协商", fixture.playbackBodies.all {
                    it.getBoolean("EnableDirectPlay") && it.getBoolean("AllowVideoStreamCopy") && !it.has("MaxStreamingBitrate")
                })
                assertEquals("不能读取替代的服务器媒体流", serverMediaBefore, fixture.serverMediaRequests.get())
                assertEquals(0, fixture.transcodeMediaRequests.get())
                main {
                    assertSame("源变更应保留原播放器并报告失败", owner, playback.player)
                    assertNotNull(playback.state.value.errorMessage)
                    assertFalse(checkNotNull(playback.currentRequest).stream.trustedServer)
                    assertEquals(raw.url, playback.currentRequest!!.stream.uri)
                }
                assertTrue("网络错误前必须实际读取 raw 视频", raw.mediaRequests.get() > 0)
            }
        }
    }

    private fun start(app: AppViewModel, playback: PlaybackViewModel) {
        main { app.preparePlayback(MediaItem(ITEM, "Cloud verification", "Episode", seriesId = "pending-series"), true) }
        awaitVideo(app, playback)
    }

    private fun awaitVideo(app: AppViewModel, playback: PlaybackViewModel) {
        await {
            app.state.value.playbackError?.let { throw AssertionError("准备播放失败：$it") }
            playback.state.value.errorMessage?.let { throw AssertionError("实际播放失败：$it") }
            playback.state.value.hasRenderedFrame && playback.player?.isPlaying == true
        }
    }

    private fun preferOriginal(app: AppViewModel) {
        main { app.setPreferences(app.state.value.preferences.copy(strmDefaultQuality = PlaybackQuality.ORIGINAL)) }
        await { AppPreferencesStore(context).load().strmDefaultQuality == PlaybackQuality.ORIGINAL }
    }

    private fun awaitUnsupportedOriginal(app: AppViewModel, playback: PlaybackViewModel) {
        await { !app.state.value.preparingPlay && (app.state.value.playbackError != null || app.state.value.activePlayback != null) }
        main {
            assertNotNull("本机不能解码时应明确报错，不能安装服务器播放源", app.state.value.playbackError)
            val error = requireNotNull(app.state.value.playbackError)
            assertTrue("错误应明确说明本机解码能力不足：$error", error.contains("本机") && error.contains("不支持"))
            assertNotNull("失败仍可通过真实返回取消准备", app.state.value.pendingPlayback)
            assertNull(app.state.value.activePlayback)
            assertNull(playback.currentRequest)
            assertNull(playback.player)
            assertFalse(playback.state.value.hasRenderedFrame)
        }
    }

    private fun dismissFailedPreparation(app: AppViewModel, playback: PlaybackViewModel) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await {
            app.state.value.pendingPlayback == null && app.state.value.activePlayback == null &&
                app.state.value.sourceSelection == null && app.state.value.playbackError == null && !app.state.value.preparingPlay &&
                playback.player == null && !compose.activity.isFinishing
        }
    }

    private fun assertNoJellyfinFallback(fixture: Fixture) {
        assertTrue("STRM 直连不能触发 Jellyfin 转码协商", fixture.playbackBodies.all {
            it.getBoolean("EnableDirectPlay") && it.getBoolean("AllowVideoStreamCopy") && !it.has("MaxStreamingBitrate")
        })
        assertEquals("STRM 直连不能读取任何 Jellyfin 媒体流", 0, fixture.serverMediaRequests.get())
        assertEquals(0, fixture.transcodeMediaRequests.get())
    }

    private fun withApp(sourceUrl: String? = null, cloudScenario: Fixture.CloudScenario? = null, multipleSources: Boolean = false,
        reverseProxyPayload: Boolean = false,
        block: (Fixture, AppViewModel, PlaybackViewModel) -> Unit) {
        val bytes = instrumentation.context.assets.open("ass-extension/plain.mp4").use { it.readBytes() }
        Fixture(bytes, externalSource = sourceUrl, cloudScenario = cloudScenario, multipleSources = multipleSources,
            reverseProxyPayload = reverseProxyPayload).use { fixture ->
            val app = main { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
            val playback = main { ViewModelProvider(compose.activity)[PlaybackViewModel::class.java] }
            try {
                await { !app.state.value.restoringSession }
                main { app.login(fixture.origin, "pending-fixture-user", "pending-fixture-password", true, "Cloud fixture") }
                await {
                    app.state.value.loginError?.let { throw AssertionError("合成登录失败：$it") }
                    !app.state.value.loginBusy && app.state.value.session?.serverId == SERVER_ID &&
                        app.state.value.session?.server?.baseUrl == fixture.origin &&
                        app.state.value.home.loaded && !app.state.value.home.loading
                }
                block(fixture, app, playback)
                fixture.assertHealthy()
            } finally {
                main { playback.stop(); app.playerClosed(playback.lastPositionMs) }
                runBlocking { withTimeout(WAIT) { playback.awaitPendingReports() } }
            }
        }
    }
    private fun get(url: String): JSONObject = network.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful) { "验证桥不可用" }; JSONObject(it.body.string())
    }
    private fun control(base: String, mode: String, enabled: Boolean) {
        network.newCall(Request.Builder().url("$base/probe-control?mode=$mode&enabled=$enabled")
            .post(ByteArray(0).toRequestBody()).build()).execute().use { check(it.isSuccessful) }
    }
    private fun clearSynthetic() {
        val store = SessionStore(context)
        val profiles = store.loadProfiles()
        check(profiles.all { it.session.serverId == SERVER_ID && it.session.accessToken == "pending-playback-synthetic-token" }) { "验证包中存在其它账号，拒绝清理" }
        profiles.forEach { store.removeProfile(it.id) }; store.clear()
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(WAIT) { main(condition) }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)

    /** 旧/非 MediaFlux 的自有HTTP源；仅提供本地素材，绝不请求真实网盘。 */
    private class RawOriginalSource(private val media: ByteArray, private val discovery: String) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newFixedThreadPool(3)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closed = AtomicBoolean()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val origin = "http://127.0.0.1:${server.localPort}"
        val url = if (discovery == "ordinary-http") "$origin/legacy-original.mp4?sig=owned"
            else "$origin/playgy/legacy/disk/file/original?v=1&sig=${"a".repeat(64)}"
        val discoveryRequests = AtomicInteger()
        val mediaRequests = AtomicInteger()
        private val accept = Thread({ while (!closed.get()) try {
            val socket = server.accept()
            sockets += socket
            workers.execute {
                try { serve(socket) } catch (_: IOException) { }
                catch (error: Throwable) { failures += error }
                finally { sockets -= socket; runCatching { socket.close() } }
            }
        } catch (error: IOException) { if (!closed.get()) failures += error }
        }, "Pathfin-raw-original-fixture").apply { isDaemon = true; start() }

        private fun serve(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = (reader.readLine() ?: return).split(' ')
            check(request.size == 3 && request[0] in setOf("GET", "HEAD"))
            val headers = mutableMapOf<String, String>()
            var ended = false
            repeat(48) { if (!ended) {
                val line = reader.readLine() ?: return
                check(line.length <= 8_192)
                if (line.isEmpty()) ended = true else headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            } }
            check(ended)
            check(headers["authorization"].isNullOrEmpty() && headers["x-emby-token"].isNullOrEmpty())
            val uri = URI(request[1])
            val variants = uri.rawQuery.orEmpty().split('&').any { it == "view=variants" }
            if (variants) {
                discoveryRequests.incrementAndGet()
                if (discovery == "redirect") respond(socket, 302, ByteArray(0), "video/mp4", "Location: $origin/legacy-original.mp4\r\n")
                else respond(socket, 200, "{\"protocol\":\"unrecognized\"}".toByteArray(), "application/json")
                return
            }
            mediaRequests.incrementAndGet()
            val range = headers["range"]?.let { Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(it) ?: error("Invalid owned range") }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(media.lastIndex) ?: media.lastIndex
            if (start !in media.indices || end < start) respond(socket, 416, ByteArray(0), "video/mp4")
            else respond(socket, if (range == null) 200 else 206, media.copyOfRange(start, end + 1), "video/mp4",
                if (range == null) "" else "Content-Range: bytes $start-$end/${media.size}\r\n", request[0] == "HEAD")
        }

        private fun respond(socket: Socket, status: Int, bytes: ByteArray, type: String, extra: String = "", head: Boolean = false) {
            val header = "HTTP/1.1 $status Owned\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\n$extra\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); if (!head) write(bytes); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            server.close()
            accept.join(1_000)
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            failures.firstOrNull()?.let { throw AssertionError("原始HTTP测试源失败", it) }
        }
    }

    private companion object {
        const val SERVER_ID = "pending-playback-fixture"
        const val ITEM = "pending-episode-two"
        const val WAIT = 35_000L
    }
}
