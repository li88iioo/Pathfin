package dev.pathfin.android.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.i18n.tr
import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 详情正文使用中性信息卡；播放器地址和请求头不进入展示或剪贴板。 */
@Composable
internal fun MediaStreamInfo(item: MediaItem, modifier: Modifier = Modifier) {
    val sources = item.mediaSources
    val source = sources.firstOrNull() ?: return
    if (sources.none { fileInformation(it).isNotEmpty() || it.mediaStreams.isNotEmpty() }) return
    val fields = fileInformation(source)
    val summaries = listOf("Video", "Audio", "Subtitle").mapNotNull { type ->
        source.mediaStreams.firstOrNull { it.type.equals(type, true) && it.isDefault }
            ?: source.mediaStreams.firstOrNull { it.type.equals(type, true) }
    }
    var details by remember(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("detail-media-streams"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (fields.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InformationHeading(tr("文件信息"))
                FileInformationCard(source, "detail-file-info")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InformationHeading(tr("视频信息"))
            InformationCard(Modifier.testTag("detail-media-summary")) {
                if (summaries.isEmpty()) {
                    Text(tr("服务器未提供轨道信息"), Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else summaries.forEach { stream ->
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { details = true }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val icon = when {
                                stream.type.equals("Video", true) -> PathfinIcons.Video
                                stream.type.equals("Audio", true) -> PathfinIcons.Audio
                                else -> PathfinIcons.Subtitles
                            }
                            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(streamTypeName(stream), fontSize = 13.sp, lineHeight = 19.sp,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(streamDescription(stream), fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
                Hairline(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { details = true }
                    .testTag("detail-media-info-open").padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("查看全部"), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp)
                    Icon(PathfinIcons.Chevron, null, Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (details) MediaInformationSheet(item, onDismiss = { details = false })
}

@Composable
private fun InformationHeading(text: String) {
    Text(text, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textMain)
}

@Composable
private fun InformationCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(PathfinAppearance.card), content = content)
}

@Composable
private fun FileInformationCard(source: MediaSource, tag: String) {
    val context = LocalContext.current
    val fields = fileInformation(source)
    if (fields.isEmpty()) return
    InformationCard(Modifier.testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            fields.forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, fontSize = 12.5.sp, lineHeight = 17.sp, color = PathfinAppearance.textSub)
                        Text(value, fontSize = 14.sp, lineHeight = 21.sp, color = PathfinAppearance.textMain,
                            modifier = if (label == tr("文件位置")) Modifier.testTag("$tag/path") else Modifier)
                    }
                    // 显示解码后的可读路径；复制仍用脱敏后的原始地址，粘贴到浏览器或播放器可直接使用。
                    if (label == tr("文件位置")) PathfinIconButton(PathfinIcons.Copy, tr("复制文件位置"), {
                        val copied = mediaInformationPath(source.path) ?: value
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr("文件位置"), copied))
                        Toast.makeText(context, tr("已复制文件位置"), Toast.LENGTH_SHORT).show()
                    }, Modifier.testTag("$tag/copy"), tint = PathfinAppearance.textSub, size = 48.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaInformationSheet(item: MediaItem, onDismiss: () -> Unit) {
    // 高度随内容，最多九成屏；只有一个短版本时不再留出大片空白。
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("detail-media-info-sheet"), dragHandle = null,
        // 弹窗以画布灰作底，里面的白色信息卡片才分得出层次。
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), containerColor = PathfinAppearance.canvas) {
        SheetDragHandle(Modifier.testTag("detail-media-info-handle"))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = windowHeight * .82f).testTag("detail-media-info-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item.mediaSources.forEachIndexed { version, source ->
                item(key = "version/$version") {
                    Text(source.name?.takeIf(String::isNotBlank) ?: tr("媒体版本 %1\$s", version + 1),
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("detail-media-version/$version"),
                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = PathfinAppearance.textSub,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (fileInformation(source).isNotEmpty()) item(key = "file/$version") {
                    FileInformationCard(source, "detail-media-version/$version/file")
                }
                source.mediaStreams.forEachIndexed { track, stream ->
                    item(key = "stream/$version/$track") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("detail-media-version/$version/stream/$track")) {
                            InformationHeading(streamTypeName(stream))
                            InformationCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(streamDescription(stream), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    streamInformation(stream).forEach { (label, value) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.Top) {
                                            Text(label, Modifier.weight(.4f), fontSize = 13.sp, lineHeight = 20.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(value, Modifier.weight(.6f), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fileInformation(source: MediaSource): List<Pair<String, String>> = buildList {
    mediaInformationPath(source.path)?.let { add(tr("文件位置") to mediaInformationDisplayPath(it)) }
    source.size?.takeIf { it > 0 }?.let { add(tr("文件大小") to mediaFileSize(it)) }
    source.container?.takeIf(String::isNotBlank)?.let { add(tr("封装格式") to it.uppercase(Locale.ROOT)) }
    source.bitRate?.takeIf { it > 0 }?.let { add(tr("码率") to mediaBitRate(it)) }
}

/** HTTP 链接的查询、片段和用户信息一律移除；结果用于复制，显示前再经 [mediaInformationDisplayPath] 解码。 */
internal fun mediaInformationPath(path: String?): String? {
    val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.any(Char::isISOControl)) return null
    value.toHttpUrlOrNull()?.let { url ->
        return url.newBuilder().username("").password("").query(null).fragment(null).build().toString()
    }
    if (value.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(value)) {
        val url = runCatching { URI(value) }.getOrNull() ?: return null
        val host = url.host ?: return null
        return runCatching { URI(url.scheme, null, host, url.port, url.path, null, null).toString() }.getOrNull()
    }
    return value
}

/** 把链接里的百分号编码还原成可读文字（如中文文件名）；无法解码时原样返回。 */
internal fun mediaInformationDisplayPath(path: String): String =
    if (!path.contains('%')) path
    else runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

private fun mediaFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var size = bytes / 1024.0
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return "${mediaDecimal(size)} ${units[unit]}"
}

private fun mediaBitRate(bits: Int) = if (bits >= 1_000_000) "${mediaDecimal(bits / 1_000_000.0)} Mbps"
    else "${mediaDecimal(bits / 1_000.0)} kbps"

private fun mediaDecimal(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

private fun streamTypeName(stream: MediaStream): String = when {
    stream.type.equals("Video", true) -> tr("视频")
    stream.type.equals("Audio", true) -> tr("音频")
    stream.type.equals("Subtitle", true) -> tr("字幕")
    else -> stream.type?.takeIf(String::isNotBlank) ?: tr("媒体流")
}

private fun streamDescription(stream: MediaStream): String {
    stream.displayTitle?.takeIf(String::isNotBlank)?.let { return it }
    return listOfNotNull(
        if (stream.type.equals("Video", true)) stream.height?.takeIf { it > 0 }?.let { "${it}p" } else stream.language,
        stream.codec?.uppercase(Locale.ROOT), stream.videoRangeType ?: stream.videoRange,
        if (stream.type.equals("Video", true)) stream.bitRate?.takeIf { it > 0 }?.let(::mediaBitRate) else null,
        if (stream.type.equals("Audio", true)) stream.channelLayout ?: stream.channels?.takeIf { it > 0 }?.let { tr("%1\$s 声道", it) } else null,
        if (stream.type.equals("Audio", true)) stream.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" } else null,
        stream.title, tr("默认").takeIf { stream.isDefault }, tr("强制").takeIf { stream.isForced }, tr("外挂").takeIf { stream.isExternal },
    ).filter(String::isNotBlank).distinct().joinToString(" · ").ifBlank { streamTypeName(stream) }
}

private fun streamInformation(stream: MediaStream): List<Pair<String, String>> = buildList {
    fun text(label: String, value: String?) { value?.takeIf(String::isNotBlank)?.let { add(label to it) } }
    stream.index?.takeIf { it >= 0 }?.let { add(tr("轨道序号") to it.toString()) }
    text(tr("标题"), stream.title)
    text(tr("语言"), stream.language)
    text(tr("编码器"), stream.codec)
    text(tr("配置"), stream.profile)
    stream.level?.takeIf { it.isFinite() && it > 0 }?.let { add(tr("等级") to mediaDecimal(it)) }
    val width = stream.width?.takeIf { it > 0 }
    val height = stream.height?.takeIf { it > 0 }
    if (width != null && height != null) add(tr("分辨率") to "$width × $height")
    else {
        width?.let { add(tr("宽度") to "$it px") }
        height?.let { add(tr("高度") to "$it px") }
    }
    (stream.averageFrameRate?.takeIf { it.isFinite() && it > 0 }
        ?: stream.realFrameRate?.takeIf { it.isFinite() && it > 0 })?.let { add(tr("帧率") to "${mediaDecimal(it)} fps") }
    stream.bitRate?.takeIf { it > 0 }?.let { add(tr("码率") to mediaBitRate(it)) }
    stream.bitDepth?.takeIf { it > 0 }?.let { add(tr("位深度") to "$it bit") }
    text(tr("视频动态范围"), stream.videoRangeType ?: stream.videoRange)
    text(tr("杜比视界"), stream.videoDoViTitle)
    stream.dvProfile?.takeIf { it > 0 }?.let { add(tr("杜比视界配置") to it.toString()) }
    text(tr("色彩范围"), stream.colorRange)
    text(tr("色彩原色"), stream.colorPrimaries)
    text(tr("色彩空间"), stream.colorSpace)
    text(tr("色彩转换"), stream.colorTransfer)
    text(tr("像素格式"), stream.pixelFormat)
    stream.channels?.takeIf { it > 0 }?.let { add(tr("声道") to it.toString()) }
    text(tr("声道布局"), stream.channelLayout)
    stream.sampleRate?.takeIf { it > 0 }?.let { add(tr("采样率") to "$it Hz") }
    // 模型没有保存“布尔字段是否存在”，只展示明确成立的标志，不把缺失字段写成“否”。
    if (stream.isDefault) add(tr("默认") to tr("是"))
    if (stream.isForced) add(tr("强制") to tr("是"))
    if (stream.isExternal) add(tr("外部字幕") to tr("是"))
    if (stream.isHearingImpaired) add(tr("听障") to tr("是"))
}
