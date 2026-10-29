package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 码率是服务端协商的上限，不把它伪装成源视频没有的分辨率。 */
enum class PlaybackQuality(private val labelKey: String, private val descriptionKey: String, val maxBitrate: Int?) {
    ORIGINAL("原画", "保持原始媒体质量", null),
    HIGH("高质量", "码率上限 12 Mbps", 12_000_000),
    STANDARD("标准", "码率上限 4 Mbps", 4_000_000),
    DATA_SAVER("流畅", "码率上限 1.5 Mbps", 1_500_000);

    val label: String get() = tr(labelKey)
    val description: String get() = tr(descriptionKey)
}

/** 字幕时差按账号、媒体和版本隔离；持久化键不包含令牌或明文地址。 */
fun PlaybackRequest.subtitlePreferenceKey(): String {
    val parts = listOf(session.serverId.ifBlank { session.server.baseUrl }, session.userId, item.id, source.id)
    val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** 只识别当前 Jellyfin 转码实际选中的烧录轨；原文件没有字幕轨不代表存在硬字幕。 */
fun PlaybackRequest.hasServerBurnedSubtitles(): Boolean {
    if (stream.method != PlayMethod.TRANSCODE || !stream.trustedServer) return false
    val query = runCatching { java.net.URI(stream.uri).rawQuery }.getOrNull().orEmpty()
    val parameters = query.split('&').mapNotNull { entry ->
        val pair = entry.split('=', limit = 2)
        if (pair.size != 2) null else runCatching {
            java.net.URLDecoder.decode(pair[0], "UTF-8").lowercase(java.util.Locale.ROOT) to
                java.net.URLDecoder.decode(pair[1], "UTF-8")
        }.getOrNull()
    }.toMap()
    val index = if (parameters.containsKey("subtitlestreamindex")) {
        parameters["subtitlestreamindex"]?.toIntOrNull() ?: return false
    } else source.defaultSubtitleStreamIndex ?: return false
    if (index < 0) return false
    return parameters["subtitlemethod"]?.equals("Encode", true)
        ?: source.mediaStreams.any { it.index == index && it.type.equals("Subtitle", true) && it.deliveryMethod.equals("Encode", true) }
}
