package dev.pathfin.android.domain

import org.junit.Assert.*
import org.junit.Test

class CloudPlaybackTest {
    @Test fun qualitySelectsRealCloudVariantsAndKeepsOriginalSeparateAtTheSameResolution() {
        val original = variant("original", 3840, 1600, original = true)
        val highest = variant("uhd", 3840, 1600)
        val standard = variant("hd", 1280, 536)
        val flow = variant("sd", 854, 358)
        val cloud = CloudPlayback("https://mediaflux.example/signed", listOf(
            original, highest, variant("fhd", 1920, 800), standard, flow,
        ))

        assertEquals(original, cloud.variant(PlaybackQuality.ORIGINAL))
        assertEquals(highest, cloud.variant(PlaybackQuality.HIGH))
        assertEquals(standard, cloud.variant(PlaybackQuality.STANDARD))
        assertEquals(flow, cloud.variant(PlaybackQuality.DATA_SAVER))
        assertEquals(listOf(original, highest), cloud.variants.filter { it.width == 3840 })
        assertTrue(cloud.options().all { it.available })
        assertTrue(cloud.options().single { it.quality == PlaybackQuality.STANDARD }.description.contains("1280×536"))
        assertTrue(cloud.options().single { it.quality == PlaybackQuality.DATA_SAVER }.description.contains("854×358"))
    }

    @Test fun standardAndFlowBoundTheLongEdgeAndUseBitrateOnlyToBreakEqualSizeTies() {
        val portrait = variant("portrait-hd", 536, 1280)
        val flow = variant("portrait-sd", 358, 854)
        val betterFlow = flow.copy(id = "portrait-sd-better", bitrate = 2_000_000)
        val cloud = CloudPlayback("https://mediaflux.example/signed", listOf(
            variant("original", 3840, 2160, original = true),
            variant("too-tall", 720, 1920), variant("just-over-standard", 1281, 500),
            variant("just-over-flow", 855, 360), portrait, flow, betterFlow,
        ))

        assertEquals(portrait, cloud.variant(PlaybackQuality.STANDARD))
        assertEquals(betterFlow, cloud.variant(PlaybackQuality.DATA_SAVER))
        assertEquals("too-tall", cloud.variant(PlaybackQuality.HIGH)?.id)
    }

    @Test fun unavailableAndUnknownSizeVariantsDoNotInventLowerQualityOptions() {
        val original = variant("original", 1920, 800, original = true).copy(playable = false)
        val unknown = variant("unknown", null, null)
        val blocked = variant("unsupported", 1280, 536).copy(playable = false)
        val cloud = CloudPlayback("https://mediaflux.example/signed", listOf(original, unknown, blocked))

        assertEquals(original, cloud.variant(PlaybackQuality.ORIGINAL))
        for (quality in listOf(PlaybackQuality.HIGH, PlaybackQuality.STANDARD, PlaybackQuality.DATA_SAVER)) {
            assertNull(cloud.variant(quality))
        }
        assertEquals(PlaybackQuality.entries.toSet(), cloud.options().map { it.quality }.toSet())
        assertTrue(cloud.options().none { it.available })
        assertFalse(unknown.description.contains("×"))
        assertTrue(cloud.options().filter { it.quality != PlaybackQuality.ORIGINAL }.none { it.description.contains("×") })
    }

    @Test fun carriedManualQualityWinsOverTheDefaultWhenTheEpisodeHasIt() {
        val cloud = CloudPlayback("https://mediaflux.example/signed", listOf(
            variant("original", 3840, 2160, original = true), variant("fhd", 1920, 1080), variant("hd", 1280, 720)))

        assertEquals(PlaybackQuality.HIGH, cloud.quality(PlaybackQuality.HIGH, PlaybackQuality.STANDARD))
        assertEquals(PlaybackQuality.STANDARD, cloud.quality(PlaybackQuality.STANDARD, PlaybackQuality.ORIGINAL))
        assertEquals(PlaybackQuality.ORIGINAL, cloud.quality(PlaybackQuality.ORIGINAL, PlaybackQuality.STANDARD))
        assertEquals("没有手选时仍用默认画质", PlaybackQuality.STANDARD, cloud.quality(null, PlaybackQuality.STANDARD))
    }

    @Test fun missingCarriedQualityStepsDownToTheNearestPlayableTierBeforeUsingTheDefault() {
        val unplayableOriginal = variant("original", 3840, 2160, original = true).copy(playable = false)
        val compressed = CloudPlayback("https://mediaflux.example/signed", listOf(
            unplayableOriginal, variant("fhd", 1920, 1080), variant("sd", 854, 480)))
        assertEquals("原画不可播时向下取最近档", PlaybackQuality.HIGH, compressed.quality(PlaybackQuality.ORIGINAL, PlaybackQuality.ORIGINAL))
        assertEquals(PlaybackQuality.DATA_SAVER, compressed.quality(PlaybackQuality.DATA_SAVER, PlaybackQuality.HIGH))

        val onlyHigh = CloudPlayback("https://mediaflux.example/signed", listOf(
            variant("original", 3840, 2160, original = true), variant("fhd", 1920, 1080)))
        assertEquals("向下都没有档位时回到默认", PlaybackQuality.ORIGINAL, onlyHigh.quality(PlaybackQuality.STANDARD, PlaybackQuality.ORIGINAL))
        assertEquals(PlaybackQuality.HIGH, onlyHigh.quality(PlaybackQuality.DATA_SAVER, PlaybackQuality.HIGH))
    }

    @Test fun anUnplayableOriginalDefaultFallsBackToHighOrFailsWhenNoCompatibleTierExists() {
        val unplayableOriginal = variant("original", 3840, 2160, original = true).copy(playable = false)
        val withHigh = CloudPlayback("https://mediaflux.example/signed", listOf(unplayableOriginal, variant("fhd", 1920, 1080)))
        assertEquals(PlaybackQuality.HIGH, withHigh.quality(null, PlaybackQuality.ORIGINAL))

        val originalOnly = CloudPlayback("https://mediaflux.example/signed", listOf(unplayableOriginal))
        for (preferred in listOf(null, PlaybackQuality.ORIGINAL, PlaybackQuality.DATA_SAVER)) {
            val error = runCatching { originalOnly.quality(preferred, PlaybackQuality.ORIGINAL) }.exceptionOrNull()
            assertTrue("$preferred", error is IllegalArgumentException && error.message!!.contains("本机不支持此原画"))
        }
        assertEquals("非原画默认档缺失仍交给解析阶段报错", PlaybackQuality.STANDARD, originalOnly.quality(null, PlaybackQuality.STANDARD))
    }

    private fun variant(id: String, width: Int?, height: Int?, original: Boolean = false) = CloudVariant(
        id = id, original = original, label = id, width = width, height = height, videoCodec = "h264",
        bitrate = 1_000_000, transport = if (original) "file" else "hls",
        mimeType = if (original) "video/mp4" else "application/x-mpegURL",
    )
}
