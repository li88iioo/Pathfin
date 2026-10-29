package dev.pathfin.android

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import dev.pathfin.android.createVerificationComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathfin.android.data.AppPreferencesStore
import dev.pathfin.android.data.SessionStore
import dev.pathfin.android.domain.DiscoveryCategory
import dev.pathfin.android.domain.LibraryQuery
import dev.pathfin.android.domain.favoriteDiscoveryCategories
import dev.pathfin.android.ui.AppViewModel
import dev.pathfin.android.ui.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 录屏功能回归：添加服务器、真实收藏请求、收藏页与 STRM 偏好持久化。
 *
 * 先启动 tools/fixture_server.py，并 adb reverse tcp:8097 tcp:8097。
 * 仅允许独立 .verification 包；在 Activity 启动前清理合成账号和偏好，不触碰日常包。
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class VideoFeaturesFlowTest {
    private val compose = createVerificationComposeRule<MainActivity>()
    private val isolatedStorage = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(context.packageName.endsWith(".verification")) { "功能回归只允许独立验证包" }
                clearSyntheticStorage(context)
                try {
                    base.evaluate()
                } finally {
                    clearSyntheticStorage(context)
                }
            }
        }
    }

    // 外层规则在 Compose/Activity 规则之前执行，阻止旧会话自动连接并保证包名检查先于写入。
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedStorage).around(compose)

    @Test
    fun favoriteFromDetailsAppearsInItsCategoryAndStrmPreferenceSurvivesRecreate() {
        val app = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        compose.connectServer("http://127.0.0.1:8097", "pathfin", "pathfin", allowHttp = true)
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.loginError?.let { throw AssertionError("测试服务连接失败：$it") }
            state.home.error?.let { throw AssertionError("测试媒体库读取失败：$it") }
            state.session != null && state.home.loaded && !state.home.loading
        }
        compose.onAllNodesWithText(MOVIE_NAME).onFirst().performScrollTo().performVerificationClick(compose)
        waitForDetail(app)

        // 允许 fixture 在其他用例之后复用；仅通过实际 UI 归一化这个合成影片的收藏状态。
        if (app.state.value.details.getValue(ITEM_ID).isFavorite) setFavorite(app, false)
        setFavorite(app, true)
        val session = checkNotNull(app.state.value.session)
        assertTrue("独立详情请求必须读到服务器已保存的收藏", runBlocking { app.api.item(session, ITEM_ID).isFavorite })

        // 详情按参考不显示底部导航；先返回首页，再打开收藏，仍验证真实服务器写回。
        compose.onNodeWithContentDescription("收藏").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) { app.state.value.route == Route.Home }
        compose.onNodeWithContentDescription("收藏").performVerificationClick(compose)
        waitForFavorites(app, shouldContainMovie = true)
        compose.onNodeWithText(MOVIE_NAME).assertVerificationDisplayed(compose)
        compose.onNodeWithTag("discovery-section/MOVIES").assertVerificationDisplayed(compose)
        favoriteDiscoveryCategories.filterNot { it == DiscoveryCategory.MOVIES }.forEach {
            compose.onNodeWithTag("discovery-section/${it.name}").assertDoesNotExist()
        }
        compose.onNodeWithTag("browser-toolbar").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多").assertDoesNotExist()
        compose.onNodeWithTag("discovery-item/MOVIES/$ITEM_ID").performVerificationClick(compose)
        waitForDetail(app)
        setFavorite(app, false)
        assertFalse("取消收藏必须写回服务器", runBlocking { app.api.item(session, ITEM_ID).isFavorite })
        val favoritesFromServer = runBlocking {
            app.api.items(session, options = LibraryQuery(favorite = true, itemTypes = setOf("Movie")))
        }
        assertTrue("服务器收藏过滤结果必须同步移除影片", favoritesFromServer.items.none { it.id == ITEM_ID })
        compose.onNodeWithContentDescription("返回").performVerificationClick(compose)
        waitForFavorites(app, shouldContainMovie = false)
        compose.onNodeWithText("还没有收藏").assertVerificationDisplayed(compose)
        compose.onNodeWithTag("discovery-section/MOVIES").assertDoesNotExist()
        compose.onAllNodesWithText(MOVIE_NAME).assertCountEquals(0)

        compose.onNodeWithContentDescription("首页").performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) { app.state.value.route == Route.Home }
        compose.onNodeWithTag("home-more").performVerificationClick(compose)
        compose.onNodeWithText("设置", substring = false).performVerificationClick(compose)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("播放") and hasClickAction())
        compose.onNode(hasText("播放") and hasClickAction()).performVerificationClick(compose)
        compose.onNodeWithText(STRM_LABEL).performScrollTo().assertIsOff().performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) { app.state.value.preferences.originalPathEnabled }
        assertTrue("STRM 开关必须保存到独立 Store", AppPreferencesStore(compose.activity).load().originalPathEnabled)

        compose.activityRule.scenario.recreate()
        val recreated = compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
        assertTrue("重建后的界面必须读取已保存的开启值", recreated.state.value.preferences.originalPathEnabled)
        assertTrue("Activity 重建不应重置落盘偏好", AppPreferencesStore(compose.activity).load().originalPathEnabled)
        compose.onNodeWithText(STRM_LABEL).performScrollTo().assertIsOn().performVerificationClick(compose)
        compose.waitUntil(WAIT_MS) { !recreated.state.value.preferences.originalPathEnabled }
        assertFalse("再次关闭也必须保存", AppPreferencesStore(compose.activity).load().originalPathEnabled)
    }

    private fun waitForDetail(app: AppViewModel) {
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.detailError?.let { throw AssertionError("测试详情读取失败：$it") }
            state.route is Route.Detail && !state.detailLoading && state.details[ITEM_ID] != null
        }
    }

    private fun setFavorite(app: AppViewModel, favorite: Boolean) {
        val target = compose.onNodeWithContentDescription(if (favorite) "收藏媒体" else "取消收藏")
        VerificationDisplay.awaitWindowReady(compose, "favorite-action-$favorite", target)
        target.performVerificationTouchInput(compose) {
            click()
        }
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            state.notice?.let { throw AssertionError("收藏请求失败：$it") }
            "favorite/$ITEM_ID" !in state.actionBusy && state.details[ITEM_ID]?.isFavorite == favorite
        }
        VerificationDisplay.awaitWindowReady(compose, "favorite-result-$favorite",
            compose.onNodeWithContentDescription(if (favorite) "取消收藏" else "收藏媒体"))
    }

    private fun waitForFavorites(app: AppViewModel, shouldContainMovie: Boolean) {
        compose.waitUntil(WAIT_MS) {
            val state = app.state.value
            val favorites = state.favorites
            favorites.error?.let { throw AssertionError("收藏页读取失败：$it") }
            state.route == Route.Favorites && favorites.requested &&
                favorites.sections.keys == favoriteDiscoveryCategories.toSet() &&
                favorites.sections.values.all { !it.loading && !it.loadingMore && it.queryKey == "favorites" } &&
                favorites.sections[DiscoveryCategory.MOVIES]?.items?.any { it.id == ITEM_ID } == shouldContainMovie
        }
    }

    private fun clearSyntheticStorage(context: Context) {
        val store = SessionStore(context)
        store.loadProfiles().forEach { store.removeProfile(it.id) }
        store.clear()
        check(context.getSharedPreferences("pathfin_preferences", Context.MODE_PRIVATE).edit().clear().commit())
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val ITEM_ID = "fixture-movie"
        const val MOVIE_NAME = "Pathfin · 原始路径播放测试"
        const val STRM_LABEL = "STRM 原始地址"
    }
}
