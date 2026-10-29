package dev.pathfin.android.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private val apkUrl = "https://github.com/li88iioo/Pathfin/releases/download/v0.2.0/Pathfin-0.2.0.apk"

    private fun asset(name: String = "Pathfin-0.2.0.apk", url: String = apkUrl, size: Long = 42_000_000, digest: String? = null) =
        JSONObject().put("name", name).put("browser_download_url", url).put("size", size).apply { digest?.let { put("digest", it) } }

    private fun release(tag: String = "v0.2.0", vararg assets: JSONObject = arrayOf(asset())) = JSONObject()
        .put("tag_name", tag).put("draft", false).put("prerelease", false).put("body", "  修复若干问题  ")
        .put("html_url", "https://github.com/li88iioo/Pathfin/releases/tag/$tag").put("assets", JSONArray(assets.toList()))

    @Test fun stableReleaseWithApkIsParsed() {
        val parsed = ReleaseParser.parse(release())!!
        assertEquals("0.2.0", parsed.version)
        assertEquals("v0.2.0", parsed.tag)
        assertEquals("修复若干问题", parsed.notes)
        assertEquals(apkUrl, parsed.apkUrl)
        assertEquals(42_000_000, parsed.apkSize)
        assertNull("旧 Release 没有摘要时不强制校验", parsed.sha256)
    }

    @Test fun assetDigestIsNormalized() {
        val hex = "AB".repeat(32)
        assertEquals(hex.lowercase(), ReleaseParser.parse(release(assets = arrayOf(asset(digest = "sha256:$hex"))))!!.sha256)
        assertNull(ReleaseParser.parse(release(assets = arrayOf(asset(digest = "md5:abc"))))!!.sha256)
    }

    @Test fun firstMatchingApkWinsOverOtherAssets() {
        val parsed = ReleaseParser.parse(release(assets = arrayOf(asset(name = "source.tar", url = apkUrl.replace(".apk", ".tar")), asset())))
        assertEquals(apkUrl, parsed!!.apkUrl)
    }

    @Test fun draftsPrereleasesAndForeignTagsAreIgnored() {
        assertNull(ReleaseParser.parse(release().put("draft", true)))
        assertNull(ReleaseParser.parse(release().put("prerelease", true)))
        for (tag in listOf("android-v0.2.0", "0.2.0", "v0.2.0-beta", "v", "release")) {
            assertNull(tag, ReleaseParser.parse(release(tag = tag)))
        }
    }

    @Test fun releasesWithoutATrustedApkAreIgnored() {
        assertNull("没有资产", ReleaseParser.parse(release(assets = emptyArray())))
        assertNull("不是 APK", ReleaseParser.parse(release(assets = arrayOf(asset(name = "Pathfin.aab")))))
        assertNull("其他仓库", ReleaseParser.parse(release(assets = arrayOf(asset(url = apkUrl.replace("li88iioo", "someone"))))))
        assertNull("非 HTTPS", ReleaseParser.parse(release(assets = arrayOf(asset(url = apkUrl.replace("https", "http"))))))
        assertNull("空文件", ReleaseParser.parse(release(assets = arrayOf(asset(size = 0)))))
    }

    @Test fun foreignReleasePageFallsBackToTheRepository() {
        val parsed = ReleaseParser.parse(release().put("html_url", "https://example.com/phish"))
        assertEquals(RELEASES_PAGE, parsed!!.pageUrl)
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(ReleaseParser.compareVersions("0.1.1", "0.1.0") > 0)
        assertTrue(ReleaseParser.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(ReleaseParser.compareVersions("0.2", "0.1.9") > 0)
        assertTrue(ReleaseParser.compareVersions("0.1.0", "0.2.0") < 0)
        assertEquals(0, ReleaseParser.compareVersions("1.0", "1.0.0"))
        assertEquals(0, ReleaseParser.compareVersions("v0.1.0", "0.1.0"))
        assertEquals("开发版后缀不影响比较", 0, ReleaseParser.compareVersions("0.1.0", "0.1.0-debug"))
    }
}
