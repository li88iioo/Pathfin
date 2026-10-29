package dev.pathfin.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.JellyfinApi
import dev.pathfin.android.data.MediaManagementApi
import dev.pathfin.android.data.PosterImageCache
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.ui.LocalPosterLoader
import dev.pathfin.android.ui.MediaToolsScreen
import dev.pathfin.android.ui.theme.PathfinTheme
import dev.pathfin.android.ui.theme.ThemePreference
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 仅显式指定的回环隔离 Jellyfin 及合成条目；本测试会保存该专用条目。 */
@RunWith(AndroidJUnit4::class)
class MediaManagementFlowTest {
    @get:Rule val compose = createVerificationComposeRule<ComponentActivity>()

    @Test fun editMetadataOnIsolatedJellyfin() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pathfin.managementFixture") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "dev.pathfin.android.verification")
        val origin = requireNotNull(args.getString("pathfin.managementServer"))
        val uri = URI(origin)
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.userInfo == null)
        val itemId = requireNotNull(args.getString("pathfin.managementItem"))
        val api = JellyfinApi()
        val session = api.authenticate(ServerConfig(origin, true), requireNotNull(args.getString("pathfin.managementUser")),
            requireNotNull(args.getString("pathfin.managementPassword")), "management-ui-fixture")
        check(session.isAdministrator && session.canDelete)
        val item = api.item(session, itemId)
        check(item.name.startsWith("Pathfin UI fixture")) { "只允许带专用标记的合成媒体" }
        val management = MediaManagementApi(api)
        val before = management.editableMetadata(session, itemId).metadata
        val editedTitle = if (before.title == "Pathfin UI fixture edited") "Pathfin UI fixture edited again"
            else "Pathfin UI fixture edited"
        val saved = AtomicInteger()
        val loader = PosterImageCache.createLoader(context, session, OkHttpClient())
        try {
            compose.setContent {
                PathfinTheme(ThemePreference.LIGHT) {
                    CompositionLocalProvider(LocalPosterLoader provides loader) {
                        MediaToolsScreen(item, session, api, "metadata", onChanged = { saved.incrementAndGet() })
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("metadata-title")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("metadata-title").performTextReplacement(editedTitle)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("放弃未保存的修改？").assertVerificationDisplayed(compose)
            compose.onNodeWithText("继续编辑").performVerificationClick(compose)
            compose.onNodeWithTag("metadata-save").assertVerificationDisplayed(compose)
            compose.onNodeWithTag("metadata-save").performVerificationClick(compose)
            compose.waitUntil(15_000) { saved.get() == 1 }
            val after = management.editableMetadata(session, itemId).metadata
            assertEquals(editedTitle, after.title)
            assertEquals(before.overview, after.overview)
            assertEquals(before.genres, after.genres)
        } finally {
            PosterImageCache.release(loader)
            PosterImageCache.clearAccount(context, session)
        }
    }
}
