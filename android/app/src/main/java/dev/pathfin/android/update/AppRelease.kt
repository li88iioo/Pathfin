package dev.pathfin.android.update

import org.json.JSONObject

internal const val RELEASE_REPOSITORY = "li88iioo/Pathfin"
internal const val RELEASES_PAGE = "https://github.com/$RELEASE_REPOSITORY/releases"

/** GitHub 正式 Release 中可直接安装的 APK。 */
data class AppRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** GitHub 资产摘要；旧 Release 可能没有。 */
    val sha256: String?,
)

internal object ReleaseParser {
    private val tag = Regex("^v(\\d+\\.\\d+\\.\\d+)$")
    private val digest = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** 只接受 vX.Y.Z 正式版和属于当前标签的唯一 APK；歧义资产不按顺序任选。 */
    fun parse(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tagName = json.optString("tag_name").trim()
        val version = tag.matchEntire(tagName)?.groupValues?.get(1) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val asset = (0 until assets.length()).mapNotNull(assets::optJSONObject).singleOrNull {
            it.optString("name").endsWith(".apk", ignoreCase = true) &&
                it.optString("browser_download_url").startsWith("$RELEASES_PAGE/download/$tagName/") && it.optLong("size") > 0
        } ?: return null
        return AppRelease(
            version = version,
            tag = tagName,
            notes = json.optString("body").trim(),
            apkUrl = asset.optString("browser_download_url"),
            apkSize = asset.optLong("size"),
            sha256 = digest.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase(),
        )
    }

    /** 按数字段比较，缺省段视为 0；非数字后缀（如 -debug）不参与比较。 */
    fun compareVersions(left: String, right: String): Int {
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
