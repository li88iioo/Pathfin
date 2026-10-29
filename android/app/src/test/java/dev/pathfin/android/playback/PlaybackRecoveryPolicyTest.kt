package dev.pathfin.android.playback

import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import dev.pathfin.android.domain.*
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackRecoveryPolicyTest {
    @Test fun aBrokenOriginalCanEscalateTwiceButNeverLoopsBackAfterTranscoding() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(PlaybackDelivery.SERVER_STREAM, policy.next(request(), PlaybackFailure.FORMAT))
        assertEquals(PlaybackDelivery.TRANSCODE, policy.next(request(PlaybackDelivery.SERVER_STREAM, PlayMethod.DIRECT_STREAM), PlaybackFailure.FORMAT))
        assertNull(policy.next(request(PlaybackDelivery.TRANSCODE, PlayMethod.TRANSCODE), PlaybackFailure.FORMAT))
        assertNull(policy.next(request(PlaybackDelivery.TRANSCODE, PlayMethod.TRANSCODE), PlaybackFailure.FORMAT))
    }

    @Test fun aServerRemuxCanStillEscalateButForcedTranscodingAndChosenLowerQualityCannot() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(PlaybackDelivery.TRANSCODE, policy.next(request(method = PlayMethod.TRANSCODE), PlaybackFailure.FORMAT))
        assertNull(policy.next(request(PlaybackDelivery.TRANSCODE, PlayMethod.TRANSCODE), PlaybackFailure.FORMAT))
        assertNull(policy.next(request().copy(quality = PlaybackQuality.STANDARD), PlaybackFailure.FORMAT))
        assertEquals(PlaybackDelivery.TRANSCODE, policy.next(request(method = PlayMethod.DIRECT_STREAM), PlaybackFailure.FORMAT))
    }

    @Test fun aNetworkFailureRefreshesTheSameModeOnceAndSharesTheBudgetAcrossSourceChanges() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(PlaybackDelivery.AUTOMATIC, policy.next(request(), PlaybackFailure.TRANSIENT))
        assertNull(policy.next(request(), PlaybackFailure.TRANSIENT))
        assertEquals(PlaybackDelivery.SERVER_STREAM, policy.next(request(), PlaybackFailure.FORMAT))
        assertNull(policy.next(request(PlaybackDelivery.SERVER_STREAM), PlaybackFailure.TRANSIENT))
        val transcode = request(PlaybackDelivery.TRANSCODE, PlayMethod.TRANSCODE)
        val separate = PlaybackRecoveryPolicy()
        assertEquals(PlaybackDelivery.TRANSCODE, separate.next(transcode, PlaybackFailure.TRANSIENT))
        assertNull(separate.next(transcode, PlaybackFailure.TRANSIENT))
    }

    @Test fun permissionFailuresAndLiveChannelsNeverStartAnAutomaticTranscode() {
        val policy = PlaybackRecoveryPolicy()
        assertNull(policy.next(request(), PlaybackFailure.TERMINAL))
        val live = request().copy(item = MediaItem("live", "Channel", "TvChannel"))
        assertNull(policy.next(live, PlaybackFailure.FORMAT))
        assertNull(policy.next(live, PlaybackFailure.TRANSIENT))
    }

    @Test fun parserAndDecoderErrorsCanRecoverWhileDrmTlsAndUnknownFailuresRemainTerminal() {
        val formats = listOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        formats.forEach { assertEquals(PlaybackFailure.FORMAT, playbackFailure(FixtureException(it))) }
        assertEquals(PlaybackFailure.TRANSIENT, playbackFailure(FixtureException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(PlaybackFailure.TERMINAL, playbackFailure(FixtureException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, SSLHandshakeException("fixture"))))
        assertEquals(PlaybackFailure.TERMINAL, playbackFailure(FixtureException(PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR)))
        assertEquals(PlaybackFailure.TERMINAL, playbackFailure(FixtureException(PlaybackException.ERROR_CODE_UNSPECIFIED)))
    }

    @Test fun cloud401And403RefreshOnceWhileLocalAuthorizationFailuresStayTerminal() {
        val local = request()
        val original = originalHttpRequest()
        val cloud = original.copy(cloudPlayback = CloudPlayback(original.source.path!!, emptyList()))
        for (status in listOf(401, 403)) {
            val error = httpFailure(status)
            assertEquals(PlaybackFailure.TERMINAL, playbackFailure(error))
            assertNull(PlaybackRecoveryPolicy().next(local, playbackFailure(error)))
            assertEquals(PlaybackFailure.TRANSIENT, playbackFailure(error, cloudUrl = true))
            val policy = PlaybackRecoveryPolicy()
            assertEquals(PlaybackDelivery.AUTOMATIC, policy.next(cloud, playbackFailure(error, cloudUrl = true)))
            val refreshed = cloud.copy(stream = cloud.stream.copy(uri = "https://cdn.example/renewed.m3u8"))
            assertNull(policy.next(refreshed, playbackFailure(error, cloudUrl = true)))
        }
    }

    @Test fun cloudFormatFailuresNeverEscalateToJellyfinOrConsumeTheNetworkRefreshBudget() {
        val base = originalHttpRequest()
        val cloud = base.copy(cloudPlayback = CloudPlayback(base.source.path!!, emptyList()))
        val policy = PlaybackRecoveryPolicy()
        for (code in listOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)) {
            val failure = playbackFailure(FixtureException(code), cloudUrl = true)
            assertEquals(PlaybackFailure.FORMAT, failure)
            assertNull(policy.next(cloud, failure))
            assertNull(policy.next(cloud.copy(stream = cloud.stream.copy(method = PlayMethod.DIRECT_STREAM)), failure))
        }
        assertEquals(PlaybackDelivery.AUTOMATIC, policy.next(cloud, PlaybackFailure.TRANSIENT))
        assertNull(policy.next(cloud, PlaybackFailure.TRANSIENT))
    }

    @Test fun originalHttpFormatFailuresNeverSilentlyBecomeServerPlaybackWithoutCloudDiscovery() {
        for (type in listOf("Movie", "Episode", "Video")) {
            val original = originalHttpRequest().copy(item = MediaItem("item", "Original", type))
            assertNull(original.cloudPlayback)
            assertFalse(original.stream.trustedServer)
            val policy = PlaybackRecoveryPolicy()
            assertNull("$type 原始HTTP直连不依赖云画质协议，也不能自动升级JF", policy.next(original, PlaybackFailure.FORMAT))
            assertNull(policy.next(original, PlaybackFailure.FORMAT))
            assertEquals("格式失败不能耗掉原地址的一次网络恢复预算", PlaybackDelivery.AUTOMATIC,
                policy.next(original, PlaybackFailure.TRANSIENT))
            assertNull(policy.next(original, PlaybackFailure.TRANSIENT))
        }
    }

    private fun httpFailure(status: Int): PlaybackException {
        // 这里只分类 HTTP 状态；反射传入未使用的 null DataSpec，避免 JVM 测试调用 Android Uri 桩。
        val cause = HttpDataSource.InvalidResponseCodeException::class.java.constructors.single()
            .newInstance(status, "fixture", null, emptyMap<String, List<String>>(), null, byteArrayOf()) as Throwable
        return FixtureException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, java.io.IOException("fixture", cause))
    }

    private class FixtureException(code: Int, cause: Throwable? = null) :
        PlaybackException("fixture", cause, code, Bundle.EMPTY, 0L)

    private fun request(delivery: PlaybackDelivery = PlaybackDelivery.AUTOMATIC,
        method: PlayMethod = PlayMethod.DIRECT_PLAY): PlaybackRequest {
        val source = MediaSource("source", path = "/library/video.mkv", protocol = "File")
        return PlaybackRequest(MediaItem("item", "Movie", "Movie"),
            PlaybackInfo(listOf(source), "play", delivery = delivery), source,
            ResolvedPlayback("https://server.example/Videos/item/stream?Static=true", emptyMap(), method, source.id, true), 0,
            Session(ServerConfig("https://server.example"), "fixture", "user", "User", "device"))
    }

    private fun originalHttpRequest(): PlaybackRequest {
        val server = request()
        val url = "https://cdn.example/video.mkv?sig=owned"
        val source = server.source.copy(path = url, protocol = "Http")
        return server.copy(source = source, info = server.info.copy(sources = listOf(source)),
            stream = server.stream.copy(uri = url, trustedServer = false))
    }
}
