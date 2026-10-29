package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackResolverTest {
    private val session = Session(ServerConfig("https://media.example/jellyfin"), "jellyfin-secret", "user", "Alice", "device")
    private val item = MediaItem("item", "Movie", "Movie")

    @Test fun signedOriginalPathIsNeverRebuiltOrGivenServerToken() {
        val signed = "https://cdn.example/a%2Fb/movie.mkv?x=1&sig=Ab%2fCd%2B==&x=2&name=%E7%94%B5%E5%BD%B1"
        val source = MediaSource("source", path = signed, protocol = "Http", supportsDirectPlay = true,
            transcodingUrl = "/videos/item/master.m3u8")
        val result = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertEquals(signed, result.uri)
        assertFalse(result.trustedServer)
        assertTrue(result.headers.isEmpty())
        assertEquals(PlayMethod.DIRECT_PLAY, result.method)
    }

    @Test fun extensionlessDashOriginalUsesDeclaredContainerAndPreservesSignature() {
        val signed = "https://cdn.example/a%2Fb/play?sig=Ab%2fCd%2B==&format=opaque"
        for (container in listOf("mpd", "MPD", "dash", "DASH")) {
            val source = MediaSource("source", path = signed, protocol = "Http", container = container)
            val result = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
            assertEquals(container, "application/dash+xml", result.mimeType)
            assertEquals("不能重写已签名的原始地址", signed, result.uri)
            assertEquals(PlayMethod.DIRECT_PLAY, result.method)
            assertFalse(result.trustedServer)
        }
    }

    @Test fun explicitlyEnabledOriginalHttpDoesNotYieldToServerAlternativesForMovieEpisodeOrVideo() {
        for (type in listOf("Movie", "Episode", "Video")) for (alternative in listOf("direct", "transcode", "both")) {
            val url = "https://cdn.example/unsupported.mkv?sig=Ab%2fCd%2B==&x=1&x=2"
            val source = MediaSource("chosen", path = url, protocol = "Http", supportsDirectPlay = false,
                directStreamUrl = "/videos/item/remux.mkv".takeIf { alternative != "transcode" },
                transcodingUrl = "/videos/item/master.m3u8".takeIf { alternative != "direct" })
            val result = PlaybackResolver.resolve(session, item.copy(type = type), PlaybackInfo(listOf(source), "play"), source.id,
                preferOriginal = true)
            assertEquals("$type/$alternative 必须保留明确选择的原始URL", url, result.uri)
            assertEquals(PlayMethod.DIRECT_PLAY, result.method)
            assertFalse(result.trustedServer)
            assertTrue("原始地址不能携带JF凭据", result.headers.isEmpty())
            assertEquals(source.id, result.sourceId)
        }
    }

    @Test fun originalHttpOverrideNeverBypassesExplicitQualityDeliveryOrOtherMediaTypes() {
        val source = MediaSource("source", path = "https://cdn.example/original.mkv", protocol = "Http",
            supportsDirectPlay = false, transcodingUrl = "/videos/item/master.m3u8")
        val info = PlaybackInfo(listOf(source), "play")
        val cases = listOf(
            PlaybackResolver.resolve(session, item, info, source.id, preferOriginal = false),
            PlaybackResolver.resolve(session, item, info, source.id, quality = PlaybackQuality.STANDARD),
            PlaybackResolver.resolve(session, item, info, source.id, delivery = PlaybackDelivery.TRANSCODE),
            PlaybackResolver.resolve(session, item, info, source.id, delivery = PlaybackDelivery.SERVER_STREAM),
            PlaybackResolver.resolve(session, item.copy(type = "Audio"), info, source.id),
        )
        cases.forEach { assertEquals(PlayMethod.TRANSCODE, it.method); assertTrue(it.trustedServer) }
    }

    @Test fun liveChannelDirectSwitchPlaysTheTunerUrlEvenWhenTheServerWouldTranscode() {
        val channel = MediaItem("channel", "CCTV-1", "TvChannel")
        val tuner = "http://iptv.example/cctv1.m3u8"
        val source = MediaSource("live", path = tuner, protocol = "Http", supportsDirectPlay = false,
            transcodingUrl = "/videos/channel/master.m3u8")
        val info = PlaybackInfo(listOf(source), "play")
        val direct = PlaybackResolver.resolve(session, channel, info, source.id, preferOriginal = true)
        assertEquals("开启直连时直接播放直播源", tuner, direct.uri)
        assertEquals(PlayMethod.DIRECT_PLAY, direct.method)
        assertFalse("直播源不是 Jellyfin，不能带服务器凭据", direct.trustedServer)
        val server = PlaybackResolver.resolve(session, channel, info, source.id, preferOriginal = false)
        assertEquals("关闭直连时走服务器", PlayMethod.TRANSCODE, server.method)
        assertTrue(server.trustedServer)
        val lowered = PlaybackResolver.resolve(session, channel, info, source.id, preferOriginal = true,
            quality = PlaybackQuality.entries.first { it.maxBitrate != null })
        assertEquals("手动降画质仍由服务器转码", PlayMethod.TRANSCODE, lowered.method)
    }

    @Test fun liveSwitchAndStrmSwitchAreIndependent() {
        val preferences = AppPreferences(originalPathEnabled = false, liveTvDirectPlay = true)
        assertTrue(preferences.prefersOriginalSource(MediaItem("c", "Channel", "TvChannel")))
        assertFalse(preferences.prefersOriginalSource(MediaItem("m", "Movie", "Movie")))
        val swapped = AppPreferences(originalPathEnabled = true, liveTvDirectPlay = false)
        assertFalse(swapped.prefersOriginalSource(MediaItem("c", "Channel", "TvChannel")))
        assertTrue(swapped.prefersOriginalSource(MediaItem("m", "Movie", "Movie")))
    }

    @Test fun negotiatedRecoveryCannotReturnToTheOriginalUrlOrStaticDirectPlay() {
        val source = MediaSource("source", path = "https://cdn.example/failed.mkv", protocol = "Http",
            supportsDirectPlay = true, supportsDirectStream = true)
        for (delivery in listOf(PlaybackDelivery.SERVER_STREAM, PlaybackDelivery.TRANSCODE)) {
            val info = PlaybackInfo(listOf(source), "play", delivery = delivery)
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackResolver.resolve(session, item, info, source.id, preferOriginal = true)
            }
        }
        val negotiated = source.copy(directStreamUrl = "/videos/item/remux.mkv", transcodingUrl = "/videos/item/master.m3u8")
        val info = PlaybackInfo(listOf(negotiated), "play", delivery = PlaybackDelivery.TRANSCODE)
        val result = PlaybackResolver.resolve(session, item, info, source.id, delivery = PlaybackDelivery.AUTOMATIC)
        assertEquals("服务端精确预检升级后的约束不能被默认参数降级", PlayMethod.TRANSCODE, result.method)
        assertEquals("application/x-mpegURL", result.mimeType)
    }

    @Test fun extensionlessServerDashUsesDeclaredContainerWithoutLeakingQueryCredentials() {
        val source = MediaSource(
            "source", container = "mpd",
            directStreamUrl = "/videos/item/manifest?api_key=jellyfin-secret&Static=true",
        )
        val result = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertEquals("application/dash+xml", result.mimeType)
        assertEquals("https://media.example/jellyfin/videos/item/manifest?Static=true", result.uri)
        assertTrue(result.trustedServer)
        assertEquals(PlayMethod.DIRECT_STREAM, result.method)
    }

    @Test fun dashContainerCannotMislabelUnrelatedTranscodingOutput() {
        val source = MediaSource("source", container = "dash", transcodingUrl = "/videos/item/opaque")
        val result = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertEquals(PlayMethod.TRANSCODE, result.method)
        assertNull("转码输出格式不能根据原始容器猜测", result.mimeType)
    }

    @Test fun opaqueUrlsDoNotGuessDashFromQueryParametersOrStrmContainer() {
        for (container in listOf(null, "strm", "mkv")) {
            val source = MediaSource(
                "source", path = "https://cdn.example/play?filename=manifest.mpd&format=dash", container = container,
            )
            val result = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
            assertNull(container, result.mimeType)
        }
    }

    @Test fun originalDisabledUsesServerStreamAndNeverCopiesExternalHeaders() {
        val source = MediaSource(
            "source", path = "https://cdn.example/movie.mp4?sig=private", protocol = "Http",
            directStreamUrl = "/videos/item/stream?api_key=jellyfin-secret&Static=true",
            requiredHttpHeaders = mapOf("Authorization" to "Bearer external-secret", "Referer" to "https://source.example"),
        )
        val info = PlaybackInfo(listOf(source), "play")
        val stream = PlaybackResolver.resolve(session, item, info, source.id, preferOriginal = false)
        assertTrue(stream.trustedServer)
        assertEquals("https://media.example/jellyfin/videos/item/stream?Static=true", stream.uri)
        assertEquals(PlayMethod.DIRECT_STREAM, stream.method)
        assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)
        assertFalse(stream.headers.values.any { it.contains("external-secret") })
        val request = PlaybackRequest(item, info, source, stream, 0, session)
        assertTrue(PlaybackHeaders.forUrl(request, checkNotNull(source.path)).isEmpty())
    }

    @Test fun originalDisabledCanUseTranscodingOrServerStaticStream() {
        val original = MediaSource("source", path = "http://cdn.example/movie.mp4", protocol = "Http")
        // 关闭原始地址时，未授权的外部 HTTP 地址也不能阻止安全的服务器分支。
        val transcoding = original.copy(transcodingUrl = "/videos/item/master.m3u8")
        val stream = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(transcoding), "play"), original.id, false)
        assertEquals(PlayMethod.TRANSCODE, stream.method)
        assertTrue(stream.trustedServer)
        assertEquals("application/x-mpegURL", stream.mimeType)
        val direct = original.copy(supportsDirectPlay = true)
        val static = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(direct), "play"), original.id, false)
        assertEquals(PlayMethod.DIRECT_PLAY, static.method)
        assertTrue(static.trustedServer)
        assertTrue(static.uri.startsWith("https://media.example/jellyfin/Videos/item/stream?"))
    }

    @Test fun originalDisabledNeverFallsBackToOriginalOrAnotherSource() {
        val source = MediaSource("source", path = "https://cdn.example/movie.mp4", directStreamUrl = "https://untrusted.example/movie")
        val info = PlaybackInfo(listOf(source, source.copy(id = "other", supportsDirectPlay = true)), "play")
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, info, source.id, preferOriginal = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, info, "missing", preferOriginal = false)
        }
    }

    @Test fun missingSourceCannotSilentlySelectAnotherVersion() {
        val info = PlaybackInfo(listOf(MediaSource("one", path = "https://cdn.example/movie.mp4")), "play")
        assertThrows(IllegalArgumentException::class.java) { PlaybackResolver.resolve(session, item, info, "two") }
    }

    @Test fun multipleSourcesRequireExplicitSelection() {
        val sources = listOf(MediaSource("one"), MediaSource("two"))
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(sources, "play"), "")
        }
    }

    @Test fun localDiskPathUsesServerStaticStreamAndKeepsProxyPrefix() {
        val source = MediaSource("source", path = "/media/movie.mkv", protocol = "File", supportsDirectPlay = true)
        val stream = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertEquals("https://media.example/jellyfin/Videos/item/stream?Static=true&MediaSourceId=source&DeviceId=device", stream.uri)
        assertTrue(stream.trustedServer)
        assertEquals(setOf("Authorization"), stream.headers.keys)
        assertEquals("MediaBrowser Client=\"Pathfin\", Device=\"Android\", DeviceId=\"device\", Version=\"0.1.0\", Token=\"jellyfin-secret\"", stream.headers["Authorization"])
        assertFalse(stream.uri.contains("jellyfin-secret"))
    }

    @Test fun localAudioUsesAudioStaticStreamForDirectPlayAndDirectStream() {
        val audio = MediaItem("track", "Track", "Audio")
        for (directPlay in listOf(true, false)) {
            val source = MediaSource("audio-source", path = "/music/track.wav", protocol = "File", container = "wav",
                supportsDirectPlay = directPlay, supportsDirectStream = !directPlay)
            val stream = PlaybackResolver.resolve(session, audio, PlaybackInfo(listOf(source), "audio-play"), source.id)
            assertEquals("https://media.example/jellyfin/Audio/track/stream?Static=true&MediaSourceId=audio-source&DeviceId=device", stream.uri)
            assertEquals(if (directPlay) PlayMethod.DIRECT_PLAY else PlayMethod.DIRECT_STREAM, stream.method)
            assertTrue(stream.trustedServer)
            assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)
            assertFalse(stream.uri.contains(session.accessToken))
        }
    }

    @Test fun signedAudioOriginalKeepsItsSignatureAndOnlySourceHeaders() {
        val audio = MediaItem("track", "Track", "Audio")
        val signed = "https://music.example/a%2Fb/track.mp3?sig=Ab%2fCd%2B==&x=1&x=2"
        val source = MediaSource("audio-source", path = signed, protocol = "Http", container = "mp3",
            requiredHttpHeaders = mapOf("Referer" to "https://music.example", "X-Emby-Token" to session.accessToken))
        val info = PlaybackInfo(listOf(source), "audio-play")
        val stream = PlaybackResolver.resolve(session, audio, info, source.id)
        assertEquals(signed, stream.uri)
        assertEquals(PlayMethod.DIRECT_PLAY, stream.method)
        assertFalse(stream.trustedServer)
        assertEquals(mapOf("Referer" to "https://music.example"), stream.headers)
        val request = PlaybackRequest(audio, info, source, stream, 0, session)
        assertEquals(stream.headers, PlaybackHeaders.forUrl(request, "https://music.example/segment.mp3"))
        assertTrue(PlaybackHeaders.forUrl(request, "https://other.example/segment.mp3").isEmpty())
    }

    @Test fun disablingOriginalAudioUsesTheAudioServerEndpoint() {
        val audio = MediaItem("track", "Track", "Audio")
        val source = MediaSource("audio-source", path = "https://music.example/track.mp3?sig=private",
            protocol = "Http", supportsDirectPlay = true,
            requiredHttpHeaders = mapOf("Authorization" to "Bearer music-source"))
        val stream = PlaybackResolver.resolve(session, audio, PlaybackInfo(listOf(source), "audio-play"), source.id, preferOriginal = false)
        assertTrue(stream.uri.startsWith("https://media.example/jellyfin/Audio/track/stream?"))
        assertEquals(PlayMethod.DIRECT_PLAY, stream.method)
        assertTrue(stream.trustedServer)
        assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)
    }

    @Test fun audioUsesServerDirectAndTranscodedUrlsWithoutInventingAVideoPath() {
        val audio = MediaItem("track", "Track", "Audio")
        val direct = MediaSource("audio-source", container = "mp3",
            directStreamUrl = "/jellyfin/Audio/track/stream.mp3?api_key=jellyfin-secret&Static=true")
        val stream = PlaybackResolver.resolve(session, audio, PlaybackInfo(listOf(direct), "audio-play"), direct.id)
        assertEquals("https://media.example/jellyfin/Audio/track/stream.mp3?Static=true", stream.uri)
        assertEquals(PlayMethod.DIRECT_STREAM, stream.method)
        assertEquals(PlaybackHeaders.serverHeaders(session), stream.headers)

        val transcoded = direct.copy(directStreamUrl = null,
            transcodingUrl = "/Audio/track/stream.mp3?api_key=jellyfin-secret&AudioCodec=mp3")
        val transcode = PlaybackResolver.resolve(session, audio, PlaybackInfo(listOf(transcoded), "audio-play"), transcoded.id)
        assertEquals("https://media.example/jellyfin/Audio/track/stream.mp3?AudioCodec=mp3", transcode.uri)
        assertEquals(PlayMethod.TRANSCODE, transcode.method)
        assertTrue(transcode.trustedServer)
        assertEquals(PlaybackHeaders.serverHeaders(session), transcode.headers)
    }

    @Test fun absoluteExternalServerStreamIsRejected() {
        val source = MediaSource("source", directStreamUrl = "https://attacker.example/video.mp4")
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        }
    }

    @Test fun relativeTranscodeUrlPreservesExistingPrefixAndRemovesQueryCredential() {
        val source = MediaSource("source", transcodingUrl = "/jellyfin/videos/item/master.m3u8?api_key=jellyfin-secret&x=1")
        val stream = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertEquals("https://media.example/jellyfin/videos/item/master.m3u8?x=1", stream.uri)
        assertEquals("application/x-mpegURL", stream.mimeType)
        assertEquals(PlayMethod.TRANSCODE, stream.method)
    }

    @Test fun networkPathAndParentDirectoryCannotEscapeServerBase() {
        for (url in listOf("//attacker.example/video", "/../private/video", "/%2e%2e/private/video", "https://media.example/other/video")) {
            val source = MediaSource("source", directStreamUrl = url)
            assertThrows(url, IllegalArgumentException::class.java) {
                PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
            }
        }
    }

    @Test fun explicitExternalHttpOriginalKeepsSourceHeadersWithoutServerOptInOrIdentity() {
        val source = MediaSource("source", path = "http://cdn.example/movie.mp4?sig=a%2fb&x=1&x=2", protocol = "Http",
            requiredHttpHeaders = mapOf("Authorization" to "Bearer media-only", "X-Emby-Token" to session.accessToken))
        val info = PlaybackInfo(listOf(source), "play")
        val stream = PlaybackResolver.resolve(session, item, info, source.id)
        assertFalse(session.server.allowInsecureHttp)
        assertEquals(source.path, stream.uri)
        assertFalse(stream.trustedServer)
        assertEquals(mapOf("Authorization" to "Bearer media-only"), stream.headers)
        val request = PlaybackRequest(item, info, source, stream, 0, session)
        assertEquals(stream.headers, PlaybackHeaders.forUrl(request, "http://cdn.example/segment.ts"))
        assertTrue(PlaybackHeaders.forUrl(request, "http://other.example/segment.ts").isEmpty())
        assertTrue(PlaybackHeaders.forUrl(request, "https://cdn.example/segment.ts").isEmpty())
    }

    @Test fun externalOriginalCannotEmbedJellyfinCredentialInItsUrl() {
        for (token in listOf(session.accessToken, "jellyfin%2Dsecret", "jellyfin%252Dsecret")) {
            val source = MediaSource("source", path = "http://cdn.example/movie.mp4?api_key=$token", protocol = "Http")
            assertThrows(IllegalArgumentException::class.java) {
                PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
            }
        }
    }

    @Test fun ownHttpServerStillRequiresExplicitConfirmation() {
        val unconfirmed = session.copy(server = ServerConfig("http://media.example/jellyfin"))
        val source = MediaSource("source", path = "http://media.example/jellyfin/movie.mp4", protocol = "Http")
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(unconfirmed, item, PlaybackInfo(listOf(source), "play"), source.id)
        }
        val confirmed = unconfirmed.copy(server = unconfirmed.server.copy(allowInsecureHttp = true))
        assertEquals(source.path, PlaybackResolver.resolve(confirmed, item, PlaybackInfo(listOf(source), "play"), source.id).uri)
        val request = PlaybackRequest(item, PlaybackInfo(listOf(source), "play"), source,
            ResolvedPlayback(source.path!!, emptyMap(), PlayMethod.DIRECT_PLAY, source.id, trustedServer = true), 0, unconfirmed)
        assertTrue(PlaybackHeaders.forUrl(request, source.path).isEmpty())
    }

    @Test fun conflictingProtocolCannotMakeFilePathAnOriginalStream() {
        val source = MediaSource("source", path = "https://cdn.example/movie.mp4", protocol = "File", supportsDirectStream = true)
        val stream = PlaybackResolver.resolve(session, item, PlaybackInfo(listOf(source), "play"), source.id)
        assertTrue(stream.trustedServer)
    }

    @Test fun sourceHeadersRemoveInjectionAndJellyfinCredentials() {
        val source = MediaSource("source", path = "https://cdn.example/movie.mp4", requiredHttpHeaders = mapOf(
            "User-Agent" to "Player", "Referer" to "https://source.example/", "Authorization" to "Bearer external-secret",
            "X-Emby-Token" to session.accessToken, "Cookie" to "id=${session.accessToken}",
            "Host" to "attacker.example", "X-Injected" to "first\r\nX-Leak: true", "Bad Header" to "bad",
        ))
        val info = PlaybackInfo(listOf(source), "play")
        val stream = PlaybackResolver.resolve(session, item, info, source.id)
        assertEquals(setOf("User-Agent", "Referer", "Authorization"), stream.headers.keys)
        val request = PlaybackRequest(item, info, source, stream, 0, session)
        assertEquals(stream.headers, PlaybackHeaders.forUrl(request, "https://cdn.example/segments/1.ts"))
        assertTrue(PlaybackHeaders.forUrl(request, "https://other.example/segments/1.ts").isEmpty())
        assertTrue(PlaybackHeaders.forUrl(request, "https://cdn.example:8443/segment.ts").isEmpty())
        assertTrue(PlaybackHeaders.forUrl(request, "http://cdn.example/segment.ts").isEmpty())
    }

    @Test fun serverHeadersAreScopedToOriginAndProxyPathForEveryRequest() {
        val source = MediaSource("source", supportsDirectStream = true)
        val info = PlaybackInfo(listOf(source), "play")
        val stream = PlaybackResolver.resolve(session, item, info, source.id)
        val request = PlaybackRequest(item, info, source, stream, 0, session)
        assertEquals(stream.headers, PlaybackHeaders.forUrl(request, "https://media.example:443/jellyfin/segments/1.ts"))
        for (url in listOf("https://media.example.evil/jellyfin/a", "https://media.example/other/a", "https://media.example/jellyfin2/a", "https://media.example/jellyfin/%2e%2e/a")) {
            assertTrue(url, PlaybackHeaders.forUrl(request, url).isEmpty())
        }
    }

    @Test fun serverErrorAndEmptySourcesHaveReadableFailures() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(emptyList(), "play", "NotAllowed"), "source")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackResolver.resolve(session, item, PlaybackInfo(emptyList(), "play"), "source")
        }
    }
}
