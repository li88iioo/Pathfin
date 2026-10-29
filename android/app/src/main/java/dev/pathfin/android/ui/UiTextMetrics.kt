package dev.pathfin.android.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit

/** Compose 非线性字号缩放仍保持行高/字号比例，不能单独换算 lineHeight 的 sp。 */
internal fun Density.scaledLineHeightPx(fontSize: TextUnit, lineHeight: TextUnit): Float =
    kotlin.math.ceil(fontSize.toPx() * lineHeight.value / fontSize.value)
