package dev.pathfin.android.domain

/** 一次播放协商的约束；恢复后的请求沿用约束，不能又回到已失败的原地址。 */
enum class PlaybackDelivery {
    AUTOMATIC,
    SERVER_STREAM,
    TRANSCODE,
}
