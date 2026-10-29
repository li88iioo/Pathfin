package dev.pathfin.ass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** A transparent child of PlayerView's UI overlay; never mutates setVideoEffects or SubtitleView. */
@UnstableApi
internal class AssOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var rect = RectF()
    private val surfaceMatrix = Matrix()
    private val overlayMatrix = Matrix()
    private val inverseOverlayMatrix = Matrix()
    private var scale = 1f
    private var position = 100
    fun setPresentation(scale: Float, positionPercent: Int) {
        this.scale = scale
        position = positionPercent
        invalidate()
    }
    init {
        tag = "pathfin.libass.overlay"
        isClickable = false; isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }
    fun setFrame(next: Bitmap?) {
        bitmap = next // Do not recycle a bitmap that another display-list frame may still reference.
        invalidate()
    }
    fun videoRect(playerView: PlayerView, size: VideoSize): RectF {
        val surface = playerView.videoSurfaceView
        rect = if (surface != null && surface.width > 0 && surface.height > 0) {
            // Both descendants are mapped into PlayerView, then into this Canvas. Unlike
            // location-minus-width, this also preserves ancestor scale and works on API26.
            surfaceMatrix.reset(); overlayMatrix.reset()
            mapToAncestor(surface, playerView, surfaceMatrix)
            mapToAncestor(this, playerView, overlayMatrix)
            check(overlayMatrix.invert(inverseOverlayMatrix))
            surfaceMatrix.postConcat(inverseOverlayMatrix)
            RectF(0f, 0f, surface.width.toFloat(), surface.height.toFloat()).also(surfaceMatrix::mapRect)
        } else {
            val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f
            val fitWidth = minOf(width.toFloat(), height * aspect)
            val fitHeight = minOf(height.toFloat(), width / aspect)
            RectF((width - fitWidth) / 2, (height - fitHeight) / 2, (width + fitWidth) / 2, (height + fitHeight) / 2)
        }
        invalidate()
        return rect
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        canvas.save()
        canvas.translate(0f, -(100 - position) / 100f * rect.height() * .8f)
        canvas.scale(scale, scale, rect.centerX(), rect.bottom)
        canvas.drawBitmap(image, null, rect, paint)
        canvas.restore()
    }

    private fun mapToAncestor(view: View, ancestor: View, result: Matrix) {
        if (view === ancestor) return
        val parent = view.parent as View
        mapToAncestor(parent, ancestor, result)
        result.preTranslate((view.left - parent.scrollX).toFloat(), (view.top - parent.scrollY).toFloat())
        result.preConcat(view.matrix)
    }
}
