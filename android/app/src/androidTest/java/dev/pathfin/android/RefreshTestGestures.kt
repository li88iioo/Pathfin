package dev.pathfin.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry

/** 使用生产容器的真实触摸和嵌套滚动；不会调用刷新回调或自定义语义动作。 */
internal fun pullPageToRefresh(
    compose: AndroidComposeTestRule<*, *>,
    page: SemanticsNodeInteraction = compose.onAllNodesWithTag("refreshable-page").onLast(),
    stage: String = "pull-to-refresh",
    horizontalFraction: Float = .5f,
) {
    check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.pathfin.android.verification")
    require(horizontalFraction.isFinite() && horizontalFraction > 0f && horizontalFraction < 1f)
    VerificationDisplay.awaitWindowReady(compose, stage, page)
    page.assertVerificationDisplayed(compose).performVerificationTouchInput(compose) {
        swipe(Offset(width * horizontalFraction, height * .15f), Offset(width * horizontalFraction, height * .95f), durationMillis = 750)
    }
    compose.waitForIdle()
}
