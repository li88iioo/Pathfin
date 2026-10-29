package dev.pathfin.android.domain

import dev.pathfin.android.i18n.tr
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class SubtitlePermissions(val canManage: Boolean = false, val canDelete: Boolean = false)

/** 只保存当前编辑器读取的轨道；内置轨道可查看，不能删除。 */
class ManagedSubtitle internal constructor(val sourceId: String, internal val stream: MediaStream) {
    val index: Int get() = requireNotNull(stream.index)
    val title: String get() = stream.displayTitle?.takeIf(String::isNotBlank)
        ?: stream.title?.takeIf(String::isNotBlank)
        ?: stream.language?.takeIf(String::isNotBlank) ?: tr("字幕 %1\$s", index + 1)
    val language: String? get() = stream.language
    val format: String? get() = stream.codec
    val isExternal: Boolean get() = stream.isExternal
    val isForced: Boolean get() = stream.isForced
    val isHearingImpaired: Boolean get() = stream.isHearingImpaired
}

/** 权限与轨道绑定实际读取它们的认证；不允许把旧服务器的结果提交给新账号。 */
class SubtitleDocument internal constructor(
    val item: MediaItem,
    val sourceId: String?,
    val permissions: SubtitlePermissions,
    val tracks: List<ManagedSubtitle>,
    internal val authentication: Session,
    /** 写入端点仅接受 itemId；源的 GUID 必须对应这个媒体实体，数组位置不代表写入目标。 */
    val isDefaultSource: Boolean,
)

/** 提供商 Id 只用于发回产生它的 Jellyfin，不转换为外站 URL，也不落盘。 */
class RemoteSubtitle internal constructor(
    internal val id: String,
    val name: String,
    val provider: String,
    val language: String?,
    val format: String?,
    val author: String?,
    val comment: String?,
    val downloadCount: Int?,
    val communityRating: Double?,
    val isHashMatch: Boolean,
    val isForced: Boolean,
    val isHearingImpaired: Boolean,
    internal val itemId: String,
    internal val authentication: Session,
)

/** 本机导入与服务器上传共用的文本验证，不依赖扩展名猜格式。 */
class SubtitleUploadFile private constructor(val format: String, internal val content: ByteArray) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        fun prepare(bytes: ByteArray): SubtitleUploadFile {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { tr("字幕文件为空或超过 8 MB") }
            val text = try {
                if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                        (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) {
                    Charsets.UTF_16.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                } else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException(tr("字幕编码无法识别，请选择 UTF-8 或 UTF-16 文本字幕"))
            }
            require(!text.contains('\u0000')) { tr("请选择有效的文本字幕") }
            val normalized = text.trimStart('\uFEFF')
            val format = when {
                normalized.trimStart().startsWith("WEBVTT") -> "vtt"
                normalized.contains("[Script Info]", true) && normalized.contains("[Events]", true) -> "ass"
                SRT_TIMING.containsMatchIn(normalized) -> "srt"
                else -> throw IllegalArgumentException(tr("仅支持 SRT、VTT、ASS 或 SSA 文本字幕"))
            }
            val content = normalized.toByteArray(Charsets.UTF_8)
            require(content.size <= MAX_BYTES) { tr("转换后的字幕不能超过 8 MB") }
            return SubtitleUploadFile(format, content)
        }

        private val SRT_TIMING = Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}")
    }
}

data class SubtitleLanguage(val code: String, val label: String)

object SubtitleLanguages {
    val common: List<SubtitleLanguage> get() = listOf(
        SubtitleLanguage("zho", tr("中文")), SubtitleLanguage("eng", tr("英语")), SubtitleLanguage("jpn", tr("日语")),
        SubtitleLanguage("kor", tr("韩语")), SubtitleLanguage("fra", tr("法语")), SubtitleLanguage("deu", tr("德语")),
        SubtitleLanguage("spa", tr("西班牙语")), SubtitleLanguage("rus", tr("俄语")), SubtitleLanguage("und", tr("未指定")),
    )
    fun normalize(value: String): String {
        val code = value.trim().lowercase(Locale.ROOT)
        require(code.matches(Regex("[a-z]{3}"))) { tr("语言代码应为 3 个英文字母") }
        return code
    }
}
