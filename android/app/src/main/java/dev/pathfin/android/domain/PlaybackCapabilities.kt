package dev.pathfin.android.domain

/** 本机可直接解码的格式，向服务器声明直放条件；能播的文件不应被转码。编码名使用 Jellyfin 的命名。 */
data class PlaybackCapabilities(
    val video: Map<String, VideoLimit>,
    val audioCodecs: Set<String>,
    val dolbyVisionProfiles: Set<Int> = emptySet(),
) {
    val dolbyVision: Boolean get() = dolbyVisionProfiles.isNotEmpty()

    fun supportsDolbyVisionProfile(profile: Int): Boolean = profile in dolbyVisionProfiles

    /** 一个解码器实际共支持的尺寸及其编码档位；不能拼接不同解码器的上限。 */
    data class VideoLimit(
        val maxWidth: Int,
        val maxHeight: Int,
        val maxBitDepth: Int,
        val profiles: Set<String> = emptySet(),
        val maxLevel: Double? = null,
        val maxFrameRate: Double? = null,
    ) {
        companion object {
            /** 保留高位深能力，再取完整的最大候选；逐个片源另查实际尺寸/帧率组合。 */
            fun select(candidates: List<VideoLimit>): VideoLimit? = candidates.maxWithOrNull(
                compareBy<VideoLimit> { it.maxBitDepth }
                    .thenBy { it.maxWidth.toLong() * it.maxHeight }
                    .thenBy { it.maxFrameRate ?: 0.0 }
            )
        }
    }

    companion object {
        /** 无法确认本机能力时（或投屏到未知接收端）的保守声明，与服务器转码输出一致。 */
        val Baseline = PlaybackCapabilities(mapOf("h264" to VideoLimit(1920, 1080, 8)), setOf("aac"))
    }
}
