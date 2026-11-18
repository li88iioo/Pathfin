package dev.pathfin.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileSubtitleTest {
    private fun methods(): Map<String, Set<String>> {
        val profiles = JellyfinApi().deviceProfile().getJSONArray("SubtitleProfiles")
        return (0 until profiles.length()).map { profiles.getJSONObject(it) }
            .groupBy({ it.getString("Format") }, { it.getString("Method") }).mapValues { it.value.toSet() }
    }

    @Test fun textSubtitlesAreDeliveredAsFilesOrEmbeddedInsteadOfBurnedIn() {
        val methods = methods()
        for (format in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")) {
            assertEquals("$format 必须可外挂下发并可直接使用内嵌轨", setOf("External", "Embed"), methods[format])
        }
    }

    @Test fun imageSubtitlesDeclareOnlyTheSupportedDeliveryMethods() {
        val methods = methods()
        assertEquals(setOf("Embed", "External"), methods["pgssub"])
        assertEquals(setOf("Embed"), methods["dvdsub"])
        val vobsub = JellyfinApi().deviceProfile().getJSONArray("SubtitleProfiles").let { profiles ->
            (0 until profiles.length()).map { profiles.getJSONObject(it) }.single { it.getString("Format") == "dvdsub" }
        }
        assertEquals(setOf("mkv", "matroska"), vobsub.getString("Container").split(',').toSet())
        assertTrue("不声明烧录方式，是否烧录交给服务器在无法显示时决定", methods.values.none { "Encode" in it })
    }
}
