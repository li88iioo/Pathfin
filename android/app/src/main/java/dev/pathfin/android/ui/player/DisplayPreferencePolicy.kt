package dev.pathfin.android.ui.player

/** Display.Mode 的可测试快照；不依赖逻辑窗口大小、横竖屏方向或假定的 120Hz 档位。 */
internal data class DisplayModeSpec(
    val id: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
) {
    private val valid: Boolean
        get() = id > 0 && physicalWidth > 0 && physicalHeight > 0 && refreshRate.isFinite() && refreshRate > 0f

    internal fun matchesResolutionOf(other: DisplayModeSpec): Boolean =
        valid && physicalWidth == other.physicalWidth && physicalHeight == other.physicalHeight

    internal fun isValid(): Boolean = valid
}

/**
 * 只请求系统明确提供、与当前物理分辨率相同且不降低刷新率的模式。
 * 相同刷新率优先保留当前 mode，其他并列项取最小 id，避免重复查询时来回切换。
 * 没有合法模式时不写窗口参数，不能把逻辑上的 120Hz 宣称为设备能力。
 */
internal fun highestRefreshRateMode(
    current: DisplayModeSpec,
    supported: List<DisplayModeSpec>,
): DisplayModeSpec? {
    if (!current.isValid()) return null
    return supported.asSequence()
        .filter { it.matchesResolutionOf(current) && it.refreshRate >= current.refreshRate }
        .maxWithOrNull(compareBy<DisplayModeSpec> { it.refreshRate }
            .thenBy { it.id == current.id }
            .thenByDescending { it.id })
}
