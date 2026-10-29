package dev.pathfin.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.pathfin.android.BuildConfig
import dev.pathfin.android.diagnostics.CrashLog
import dev.pathfin.android.domain.AppPreferences
import dev.pathfin.android.i18n.tr
import dev.pathfin.android.update.RELEASE_REPOSITORY
import dev.pathfin.android.update.UpdateState
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal const val PROJECT_URL = "https://github.com/$RELEASE_REPOSITORY"
internal fun LazyListScope.aboutItems(
    p: AppPreferences,
    update: UpdateState,
    onPreferences: (AppPreferences) -> Unit,
    onPage: (String) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    item("version") { SettingsRow("Pathfin", BuildConfig.VERSION_NAME, position = GroupPosition.TOP) }
    item("update") {
        SettingsRow(tr("检查更新"), updateSummary(update), tag = "about-update", enabled = update != UpdateState.Checking,
            position = GroupPosition.MIDDLE, onClick = onCheckUpdate)
    }
    item("auto-update") {
        SettingsSwitch(tr("自动检查更新"), p.autoCheckUpdates, tag = "about-auto-update", position = GroupPosition.MIDDLE) {
            onPreferences(p.copy(autoCheckUpdates = it))
        }
    }
    item("project") {
        val context = LocalContext.current
        SettingsRow(tr("项目主页"), position = GroupPosition.BOTTOM) { context.openLink(PROJECT_URL) }
    }
    item("help-label") { SettingLabel(tr("帮助与反馈")) }
    item("faq") { SettingsRow(tr("常见问题"), tag = "about-faq", position = GroupPosition.TOP) { onPage("faq") } }
    item("crash") { SettingsRow(tr("崩溃日志"), tr("只保存在本机，可复制或提交反馈"), tag = "about-crash", position = GroupPosition.BOTTOM) { onPage("crash") } }
    item("legal-label") { SettingLabel(tr("协议与许可")) }
    item("privacy") { SettingsRow(tr("隐私协议"), tag = "about-privacy", position = GroupPosition.TOP) { onPage("privacy") } }
    item("disclaimer") { SettingsRow(tr("免责声明"), tag = "about-disclaimer", position = GroupPosition.MIDDLE) { onPage("disclaimer") } }
    item("licenses") { SettingsRow(tr("开源许可"), tr("查看随应用打包的第三方组件与许可证"), position = GroupPosition.BOTTOM) { onPage("licenses") } }
}

@Composable
private fun updateSummary(state: UpdateState): String = when (state) {
    UpdateState.Idle -> tr("当前版本 %1\$s", BuildConfig.VERSION_NAME)
    UpdateState.Checking -> tr("正在检查…")
    UpdateState.UpToDate -> tr("已是最新版本")
    is UpdateState.Failed -> state.message
    is UpdateState.Available -> tr("发现新版本 %1\$s", state.release.tag)
    is UpdateState.Downloading -> tr("正在下载 %1\$s%%", state.bytes * 100 / state.release.apkSize.coerceAtLeast(1))
    is UpdateState.Ready -> tr("%1\$s 已下载，点击安装", state.release.tag)
}

internal fun LazyListScope.documentItems(page: String) {
    item(page) {
        val (lead, sections) = when (page) {
            "faq" -> tr("更完整的说明见项目主页的帮助文档。") to faqSections()
            "privacy" -> tr("生效日期：2026 年 10 月 29 日") to privacySections()
            else -> tr("生效日期：2026 年 10 月 29 日") to disclaimerSections()
        }
        Column(Modifier.fillMaxWidth().testTag("about-document-$page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(lead, Modifier.padding(start = 6.dp, top = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = PathfinAppearance.textMuted)
            sections.forEach { (title, body) -> DocumentSection(title, body) }
        }
    }
}

@Composable
private fun DocumentSection(title: String, body: String) {
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = PathfinAppearance.textMain)
            Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = PathfinAppearance.textSub)
        }
    }
}

internal fun LazyListScope.crashLogItems() {
    item("crash-log") { CrashLogPage() }
}

@Composable
private fun CrashLogPage() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var readFailed by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        try {
            log = withContext(Dispatchers.IO) { CrashLog.read(context) }
            readFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log = null
            readFailed = true
        }
        loaded = true
    }
    val device = remember { CrashLog.deviceInfo() }
    Column(Modifier.fillMaxWidth().testTag("about-crash-log"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("崩溃记录只保存在本机，不会自动上传。提交反馈前已隐藏令牌、密码和服务器地址，仍请检查后再公开。"),
            Modifier.padding(start = 6.dp, top = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = PathfinAppearance.textMuted)
        LogCard(device)
        val current = log
        when {
            !loaded -> Text(tr("正在读取崩溃记录…"), Modifier.padding(start = 6.dp), fontSize = 13.sp, color = PathfinAppearance.textMuted)
            readFailed -> SettingsRow(tr("无法读取崩溃记录，点击重试"), tag = "about-crash-retry") { revision++ }
            current == null -> Text(tr("暂无崩溃记录"), Modifier.padding(start = 6.dp).testTag("about-crash-empty"),
                fontSize = 13.sp, color = PathfinAppearance.textMuted)
            else -> LogCard(CrashLog.tail(current, 32 * 1024))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogAction(tr("复制"), {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(tr("崩溃日志"), device + "\n\n" + CrashLog.tail(current.orEmpty(), 64 * 1024)))
                feedback = tr("已复制崩溃日志")
            }, Modifier.weight(1f).testTag("about-crash-copy"), enabled = current != null)
            DialogAction(tr("清空"), {
                feedback = if (CrashLog.clear(context)) tr("已清空崩溃日志") else tr("清空失败，请重试")
                revision++
            }, Modifier.weight(1f).testTag("about-crash-clear"), enabled = current != null || readFailed, role = DialogActionRole.Danger)
            DialogAction(tr("提交反馈"), { context.openLink(issueUrl(device, current)) },
                Modifier.weight(1f).testTag("about-crash-report"), role = DialogActionRole.Primary)
        }
    }
    feedback?.let { message ->
        AppDialog({ feedback = null }, message, actions = { DialogAction(tr("确定"), { feedback = null }, Modifier.weight(1f), role = DialogActionRole.Primary) }) {}
    }
}

@Composable
private fun LogCard(text: String) {
    GroupCard {
        SelectionContainer {
            Text(text, Modifier.padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                color = PathfinAppearance.textSub)
        }
    }
}

/** GitHub 新建 Issue 页面通过 URL 预填；地址长度有限，只带最近一条记录。 */
private fun issueUrl(device: String, log: String?): String {
    val latest = log?.let { CrashLog.tail(it, 5000) } ?: tr("（暂无崩溃记录）")
    val body = tr("## 问题描述\n\n\n## 复现步骤\n\n\n## 设备与日志\n") + "```\n$device\n\n$latest\n```\n"
    return "$PROJECT_URL/issues/new?body=" + URLEncoder.encode(body, "UTF-8")
}

internal fun android.content.Context.openLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
}

private fun faqSections() = listOf(
    tr("无法连接服务器？") to tr("确认手机与服务器网络互通，并填写最终的服务器地址，不要带 ? 或 # 之后的内容。局域网内的 http:// 服务器，需要在该服务器设置中开启“允许 HTTP 明文连接与播放”。"),
    tr("STRM 无法直连或不能切换画质？") to tr("在“设置 → 播放 → 媒体源”开启“STRM 原始地址”。STRM 直连播放与画质切换目前仅在 MediaFlux 生成的 STRM 和光鸭云盘上测试通过；画质切换还需要服务端提供 mediaflux.playback.v1。"),
    tr("提示本机不支持此原画？") to tr("Pathfin 不会把 STRM 悄悄改成 Jellyfin 转码。请更换片源、选择较低的云盘画质，或关闭“STRM 原始地址”后选择服务器画质。"),
    tr("视频无法播放或只有声音？") to tr("Pathfin 优先使用系统解码器；ARM64 设备上 H.264/HEVC 可回退到软件解码，上限 4096×2304，是否流畅取决于设备。可在“设置 → 播放 → 解码与字幕渲染”中调整。"),
    tr("如何批量操作媒体？") to tr("在媒体库长按封面进入多选，点选更多封面后，用标题栏的“更多”批量标记播放状态、刷新元数据或删除。删除和刷新需要服务器上的相应权限。"),
    tr("如何切换语言？") to tr("在“设置 → 常规 → 语言”中选择，默认跟随系统，支持简体中文和英文。"),
    tr("如何更新应用？") to tr("在“关于 → 检查更新”下载 GitHub 正式版，Pathfin 会校验包名、签名和版本后交给系统安装，安装完成后自动删除安装包。首次安装需要允许 Pathfin 安装未知应用。"),
    tr("遇到问题如何反馈？") to tr("打开“关于 → 崩溃日志”，点“提交反馈”会在 GitHub 新建 Issue 并附上设备信息和最近的崩溃记录。请补充复现步骤，不要贴出服务器地址、令牌或密码。"),
)

private fun privacySections() = listOf(
    tr("1. 不收集个人信息") to tr("Pathfin 没有自己的服务器，不包含统计、广告或第三方追踪 SDK，不收集、出售或上传你的个人信息。"),
    tr("2. 本机保存的数据") to tr("服务器地址、账号令牌、偏好设置、封面缓存、下载记录和崩溃日志都保存在应用私有目录，并且不参与系统云备份或设备迁移。导出的设置备份不包含账号和凭据。"),
    tr("3. 网络访问") to tr("Pathfin 只连接你主动配置的地址：Jellyfin 服务器、STRM 与直播源地址、局域网内的 DLNA 设备，以及用于检查和下载更新的 GitHub。访问 GitHub 时只发送带版本号的常规请求，不包含账号信息；在线字幕搜索由你的 Jellyfin 服务器完成。"),
    tr("4. 设备权限") to tr("网络与局域网访问用于连接服务器和投屏；通知用于下载与投屏进度；安装未知应用仅在你确认安装更新时使用。"),
    tr("5. 崩溃日志") to tr("崩溃记录只保存在本机，不会自动上传。只有你主动复制或提交反馈时才会离开设备，提交前会隐藏令牌、密码和服务器地址。"),
    tr("6. 删除数据") to tr("可以在应用内移除账号、清除缓存和崩溃日志；卸载 Pathfin 会删除全部本机数据。服务器上的数据由服务器管理员管理。"),
    tr("7. 协议变更") to tr("本协议可能随版本更新而调整，更新后的内容会随新版本一同发布。"),
)

private fun disclaimerSections() = listOf(
    tr("1. 项目性质") to tr("Pathfin 是开源的第三方 Jellyfin 客户端，与 Jellyfin 项目、MediaFlux 或任何网盘服务商没有隶属或合作关系，按“现状”提供，不附带任何明示或暗示的担保。"),
    tr("2. 不提供任何内容") to tr("Pathfin 不提供、托管或分发任何影视内容，所有媒体都来自你自己配置的服务器或地址。你需要确保对访问、播放、下载的内容拥有合法权利。"),
    tr("3. 使用风险") to tr("因使用本软件导致的数据丢失、设备异常、服务中断或其他损失，开发者不承担责任。批量删除、覆盖元数据等管理操作会直接修改服务器数据，请谨慎使用。"),
    tr("4. 第三方服务") to tr("Jellyfin 服务器、网盘、直播源和 GitHub 等第三方服务的可用性与条款由其提供方负责，请同时遵守相应的服务条款与当地法律法规。"),
    tr("5. 官方渠道") to tr("请只从项目的 GitHub Releases 获取安装包。来自其他渠道、签名不一致的安装包可能被篡改，应用内更新会拒绝安装这类安装包。"),
)
