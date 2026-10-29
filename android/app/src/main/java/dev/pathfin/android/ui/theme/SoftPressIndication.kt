package dev.pathfin.android.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * 替代 Material 水波纹：按下时内容整体淡到约 65%，保持绘制边界不变。
 * 外层裁剪和底色通常位于 indication 之前，不能只缩放内层内容，否则会露出直角边与底边接缝。
 */
internal object SoftPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SoftPressNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0
}

private class SoftPressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val pressed = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val active = mutableSetOf<PressInteraction.Press>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> active += interaction
                    is PressInteraction.Release -> active -= interaction.press
                    is PressInteraction.Cancel -> active -= interaction.press
                }
                val target = if (active.isEmpty()) 0f else 1f
                launch { pressed.animateTo(target, tween(if (target > 0f) 90 else 220)) { invalidateDraw() } }
            }
        }
    }

    private val layerPaint = Paint()

    override fun ContentDrawScope.draw() {
        val amount = pressed.value
        if (amount <= 0f) { drawContent(); return }
        layerPaint.alpha = 1f - 0.35f * amount
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
        drawContent()
        drawContext.canvas.restore()
    }
}
