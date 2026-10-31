package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr

/** 同一 STRM 的云端资源，不能把原画和同尺寸压缩版按分辨率合并。 */
data class CloudVariant(
    val id: String,
    val original: Boolean,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrate: Long? = null,
    val transport: String,
    val mimeType: String,
    val playable: Boolean = true,
) {
    val description: String get() = listOfNotNull(
        if (width != null && height != null) "${width}×${height}" else null,
        videoCodec?.uppercase(), if (original) tr("原始文件") else tr("云盘画质"),
    ).joinToString(" · ")
}

data class PlaybackQualityOption(val quality: PlaybackQuality, val description: String, val available: Boolean = true)

/** 原签名地址只用于枚举/续期，不能被临时 CDN 地址覆盖。 */
data class CloudPlayback(val sourceUrl: String, val variants: List<CloudVariant>, val selectedId: String = "original") {
    fun variant(quality: PlaybackQuality): CloudVariant? {
        if (quality == PlaybackQuality.ORIGINAL) return variants.firstOrNull { it.original }
        val maxSide = when (quality) {
            PlaybackQuality.STANDARD -> 1280
            PlaybackQuality.DATA_SAVER -> 854
            else -> Int.MAX_VALUE
        }
        return variants.filter { !it.original && it.playable && it.width != null && it.height != null &&
            maxOf(it.width, it.height) <= maxSide }
            .maxWithOrNull(compareBy<CloudVariant> { it.width!!.toLong() * it.height!! }.thenBy { it.bitrate ?: 0 })
    }

    /** 手选档位缺失时向下取最近可播档；都没有再按默认画质。默认原画不可播时退到高质量。 */
    fun quality(preferred: PlaybackQuality?, default: PlaybackQuality): PlaybackQuality {
        preferred?.let { wanted ->
            PlaybackQuality.entries.drop(wanted.ordinal).firstOrNull { variant(it)?.playable == true }?.let { return it }
        }
        if (default != PlaybackQuality.ORIGINAL || variant(PlaybackQuality.ORIGINAL)?.playable != false) return default
        require(variant(PlaybackQuality.HIGH) != null) { tr("本机不支持此原画，网盘也没有兼容画质。请更换片源。") }
        return PlaybackQuality.HIGH
    }

    fun options(): List<PlaybackQualityOption> = PlaybackQuality.entries.map { quality ->
        val variant = variant(quality)
        PlaybackQualityOption(quality, variant?.description ?: tr("没有可用的云盘档位"), variant?.playable == true)
    }
}
