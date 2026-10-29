package dev.pathfin.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertTrue

private fun ComposeTestRule.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeTestRule.setFieldText(tag: String, value: String) {
    onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { assertTrue("无法填入 $tag", it(AnnotatedString(value))) }
}

/** 已有服务器时从媒体空间进入连接页；没有服务器时首次连接页即为当前页面。 */
internal fun ComposeTestRule.openServerForm(timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) { exists("server-add") || exists("login-server") }
    if (!exists("login-server")) onNodeWithTag("server-add").performClick()
    waitUntil(timeoutMillis) { exists("login-server") }
}

/** 连接页第一步：填写地址，HTTP 地址需显式允许明文连接。 */
internal fun ComposeTestRule.enterServerAddress(url: String, allowHttp: Boolean) {
    openServerForm()
    setFieldText("login-server", url)
    if (allowHttp) onNodeWithTag("login-http").performScrollTo().assertIsOff().performClick()
}

/** 提交地址并等待第二步；服务器公开了账号列表时切换到手动输入用户名。 */
internal fun ComposeTestRule.continueToAccount(timeoutMillis: Long = 20_000) {
    onNodeWithTag("login-submit").performClick()
    waitUntil(timeoutMillis) { exists("server-display-name") }
    if (!exists("login-username")) onNodeWithText("其他").performClick()
}

internal fun ComposeTestRule.enterAccount(username: String, password: String) {
    setFieldText("login-username", username)
    setFieldText("login-password", password)
}

/** 完整走完两步连接并提交登录，不等待结果。 */
internal fun ComposeTestRule.connectServer(url: String, username: String, password: String, allowHttp: Boolean, displayName: String? = null) {
    enterServerAddress(url, allowHttp)
    continueToAccount()
    enterAccount(username, password)
    if (displayName != null) setFieldText("server-display-name", displayName)
    // 提交按钮固定在连接页底部，不在滚动区域内。
    onNodeWithTag("login-submit").performClick()
}
