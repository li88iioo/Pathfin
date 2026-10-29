package dev.pathfin.android

import android.content.ClipboardManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaSource
import dev.pathfin.android.domain.MediaStream
import dev.pathfin.android.ui.MediaStreamInfo
import dev.pathfin.android.ui.mediaInformationDisplayPath
import dev.pathfin.android.ui.mediaInformationPath
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** verification 合成技术信息，不访问服务器、账号或真实媒体。 */
@RunWith(AndroidJUnit4::class)
class MediaStreamInfoTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun realFileValuesAndAllVersionsRemainAccessibleAndBothClosePathsWork() {
        val item = mutableStateOf(MediaItem("technical", "合成技术信息", "Movie", mediaSources = listOf(
            MediaSource("primary", path = "/synthetic/media/测试文件.strm", container = "strm", size = 297,
                bitRate = 2_000_000, name = "合成主版本", mediaStreams = listOf(
                    MediaStream(index = 0, type = "Video", codec = "h264", displayTitle = "1080p H264 SDR", width = 1920, height = 1080),
                    MediaStream(index = 1, type = "Audio", codec = "aac", language = "eng", channels = 2, sampleRate = 48_000),
                    MediaStream(index = 2, type = "Subtitle", codec = "srt", language = "chi", isExternal = true),
                )),
            MediaSource("alternate", path = "/synthetic/media/second.mkv", container = "mkv", size = 2_147_483_648,
                name = "合成备用版本", mediaStreams = listOf(MediaStream(index = 0, type = "Video", codec = "hevc",
                    profile = "Main 10", width = 3840, height = 2160, bitDepth = 10, averageFrameRate = 23.976, videoRangeType = "HDR10"))),
        )))
        show(item)
        compose.onNodeWithTag("detail-media-streams").assertExists()
        compose.onNodeWithTag("detail-file-info/path").assertTextEquals("/synthetic/media/测试文件.strm")
        compose.onNodeWithText("297 B").assertExists()
        compose.onNodeWithText("STRM").assertExists()
        compose.onNodeWithText("文件创建日期").assertDoesNotExist()
        compose.onNodeWithText("添加日期").assertDoesNotExist()
        openSheet()
        compose.onNodeWithTag("detail-media-info-list").performScrollToNode(hasTestTag("detail-media-version/1"))
        compose.onNodeWithText("合成备用版本").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("detail-media-info-list").performScrollToNode(hasTestTag("detail-media-version/1/stream/0"))
        compose.onNodeWithText("3840 × 2160").assertExists()
        compose.onNodeWithText("Main 10").assertExists()
        compose.onNodeWithTag("detail-media-info-close").assertDoesNotExist()
        compose.onNodeWithTag("detail-media-info-sheet").performVerificationTouchInput(compose) {
            swipeDown(startY = 10f, endY = height.toFloat() - 1f, durationMillis = 250)
        }
        compose.onNodeWithTag("detail-media-info-sheet").assertDoesNotExist()
        openSheet()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("detail-media-info-sheet").assertDoesNotExist()
        compose.runOnIdle { assertFalse("关闭媒体信息不能退出宿主页面", compose.activity.isFinishing) }
    }

    @Test fun remotePathIsRedactedBeforeRenderingAndActualClipboardCopy() {
        val safe = "https://synthetic.invalid/media/Synthetic%20file.mkv"
        // 显示解码后的可读路径，复制仍是脱敏后的原始地址。
        val shown = "https://synthetic.invalid/media/Synthetic file.mkv"
        val item = mutableStateOf(MediaItem("redacted", "合成远程地址", "Movie", mediaSources = listOf(
            MediaSource("remote", path = "https://path-user:path-password@synthetic.invalid/media/Synthetic%20file.mkv?api_key=path-query-secret&signature=path-signature#path-fragment",
                container = "mkv", requiredHttpHeaders = mapOf("Authorization" to "source-header-secret"),
                directStreamUrl = "https://synthetic.invalid/stream?api_key=source-stream-secret"),
        )))
        show(item)
        val clipboard = requireNotNull(instrumentation.targetContext.getSystemService(ClipboardManager::class.java))
        try {
            compose.onNodeWithTag("detail-file-info/path").assertTextEquals(shown)
            compose.onNodeWithTag("detail-file-info/copy").performScrollTo().performVerificationClick(compose)
            compose.runOnIdle {
                val clip = clipboard.primaryClip
                assertTrue("复制内容必须是脱敏后的原始地址，不得包含链接认证", clip != null && clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == safe)
            }
            openSheet()
            compose.onNodeWithTag("detail-media-info-list").performScrollToNode(hasTestTag("detail-media-version/0/file/path"))
            compose.onNodeWithTag("detail-media-version/0/file/path").assertTextEquals(shown)
            listOf("path-password", "path-query-secret", "path-signature", "path-fragment", "source-header-secret", "source-stream-secret").forEach {
                compose.onNodeWithText(it, substring = true).assertDoesNotExist()
            }
            compose.runOnIdle {
                assertEquals("smb://synthetic.invalid/share/movie.mkv", mediaInformationPath("smb://user:password@synthetic.invalid/share/movie.mkv?token=hidden#hidden"))
                assertNull(mediaInformationPath("https://"))
                assertEquals("/媒体/移动迷宫.mkv", mediaInformationDisplayPath("/%E5%AA%92%E4%BD%93/%E7%A7%BB%E5%8A%A8%E8%BF%B7%E5%AE%AB.mkv"))
                assertEquals("编码残缺时原样返回", "/broken%E5%A.mkv", mediaInformationDisplayPath("/broken%E5%A.mkv"))
                assertEquals("加号不能被当成空格", "/a+b.mkv", mediaInformationDisplayPath("/a+b.mkv"))
            }
        } finally {
            compose.runOnIdle {
                // 只清本次合成复制，不覆盖测试期间其他来源新写入的内容，也不打印剪贴板。
                if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == safe) {
                    if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            }
        }
    }

    @Test fun unknownFieldsStayAbsentAndLongValuesWrapInBothThemesWithLargeText() {
        val item = mutableStateOf(MediaItem("invalid", "未知技术值", "Movie", mediaSources = listOf(
            MediaSource("unknown", size = 0, bitRate = -1, mediaStreams = listOf(MediaStream(index = 0, type = "Video",
                width = 0, height = -1, level = 0.0, averageFrameRate = Double.NaN, realFrameRate = 0.0,
                bitRate = 0, bitDepth = -1, channels = 0, sampleRate = -1, dvProfile = 0))),
        )))
        val theme = mutableStateOf(ThemePreference.LIGHT)
        val fontScale = mutableStateOf(1f)
        show(item, theme, fontScale)
        compose.onNodeWithTag("detail-file-info").assertDoesNotExist()
        openSheet()
        listOf("文件大小", "分辨率", "等级", "帧率", "位深度", "码率", "声道", "采样率", "杜比视界配置", "默认").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        val path = "/synthetic/" + "很长的文件夹和长文件名_".repeat(18) + "/feature.mkv"
        val profile = "包含很长配置名称的真实字段_".repeat(8)
        compose.runOnIdle {
            item.value = item.value.copy(id = "long", mediaSources = listOf(MediaSource("long", path = path, size = 1024,
                mediaStreams = listOf(MediaStream(type = "Video", codec = "hevc", displayTitle = "真实视频轨道",
                    profile = profile, averageFrameRate = 0.0, realFrameRate = 23.976)))))
            fontScale.value = 1.6f
        }
        listOf(ThemePreference.LIGHT, ThemePreference.DARK).forEach { value ->
            compose.runOnIdle { theme.value = value }
            compose.onNodeWithTag("detail-file-info/path").performScrollTo().assertTextEquals(path)
            assertWrapsWithoutOverflow(compose.onNodeWithTag("detail-file-info/path"))
            val summary = compose.onNodeWithTag("detail-media-summary").performScrollTo().assertVerificationDisplayed(compose)
            val pixels = summary.captureToImage().toPixelMap()
            val background = pixels[pixels.width / 2, pixels.height - 3].luminance()
            assertTrue("中性卡应随深浅主题切换，不得保留浅色背景", if (value == ThemePreference.LIGHT) background > .6f else background < .2f)
        }
        openSheet()
        compose.onNodeWithTag("detail-media-info-list").performScrollToNode(hasText(profile))
        assertWrapsWithoutOverflow(compose.onNodeWithText(profile))
        compose.onNodeWithText("23.98 fps").assertExists()
    }

    private fun show(item: State<MediaItem>, theme: State<ThemePreference> = mutableStateOf(ThemePreference.LIGHT),
        fontScale: State<Float> = mutableStateOf(1f)) {
        check(instrumentation.targetContext.packageName == "dev.pathfin.android.verification")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                PathfinTheme(theme.value) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                MediaStreamInfo(item.value)
                            }
                        }
                    }
                }
            }
        }
        VerificationDisplay.awaitWindowReady(compose, "technical-information", compose.onNodeWithTag("detail-media-streams"))
    }

    private fun openSheet() {
        compose.onNodeWithTag("detail-media-info-open").performScrollTo().performVerificationClick(compose)
        VerificationDisplay.awaitWindowReady(compose, "technical-information-sheet",
            compose.onNodeWithTag("detail-media-info-handle"), compose.onNodeWithTag("detail-media-info-list"))
    }

    private fun assertWrapsWithoutOverflow(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("必须取得实际文本测量", layouts.isNotEmpty())
        layouts.forEach {
            assertTrue("长值应换行保留完整内容", it.lineCount > 1)
            assertFalse("长值不能被裁切", it.hasVisualOverflow)
        }
    }
}
