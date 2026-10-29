package dev.pathfin.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 全 App 描边图标的构建入口：24dp 画布、1.4 细描边、圆端圆角，由 Icon 的 tint 着色。filled 用于选中态，轮廓与空心版一致。 */
internal fun strokeIcon(name: String, filled: Boolean = false, content: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = if (filled) SolidColor(Color.Black) else null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = content)
    }.build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    close()
}

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    moveTo(left + radius, top); lineTo(right - radius, top); quadTo(right, top, right, top + radius)
    lineTo(right, bottom - radius); quadTo(right, bottom, right - radius, bottom)
    lineTo(left + radius, bottom); quadTo(left, bottom, left, bottom - radius)
    lineTo(left, top + radius); quadTo(left, top, left + radius, top); close()
}

private fun PathBuilder.dot(x: Float, y: Float) { moveTo(x, y); lineTo(x + 0.01f, y) }

/** 服务器与账号链路使用的图标。 */
internal object PathfinIcons {
    val Server by lazy { strokeIcon("Server") {
        roundRect(3f, 4f, 21f, 10.5f, 2.5f); roundRect(3f, 13.5f, 21f, 20f, 2.5f)
        dot(7f, 7.25f); dot(7f, 16.75f)
        moveTo(11f, 7.25f); lineTo(17f, 7.25f); moveTo(11f, 16.75f); lineTo(17f, 16.75f)
    } }
    val User by lazy { strokeIcon("User") {
        circle(12f, 8f, 4f)
        moveTo(4.5f, 20f); curveTo(5.5f, 16.2f, 8.4f, 14.5f, 12f, 14.5f); curveTo(15.6f, 14.5f, 18.5f, 16.2f, 19.5f, 20f)
    } }
    val Lock by lazy { strokeIcon("Lock") {
        roundRect(5f, 10.5f, 19f, 21f, 2.5f)
        moveTo(8f, 10.5f); lineTo(8f, 8f); curveTo(8f, 2.7f, 16f, 2.7f, 16f, 8f); lineTo(16f, 10.5f)
        moveTo(12f, 14.5f); lineTo(12f, 17f)
    } }
    val Eye by lazy { strokeIcon("Eye") { eye() } }
    val EyeOff by lazy { strokeIcon("EyeOff") { eye(); moveTo(4f, 4f); lineTo(20f, 20f) } }
    val Globe by lazy { strokeIcon("Globe") {
        circle(12f, 12f, 9f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f); curveTo(7.8f, 7.6f, 7.8f, 16.4f, 12f, 21f)
        moveTo(12f, 3f); curveTo(16.2f, 7.6f, 16.2f, 16.4f, 12f, 21f)
    } }
    val Plus by lazy { strokeIcon("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Close by lazy { strokeIcon("Close") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }
    val Check by lazy { strokeIcon("Check") { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7f) } }
    val Edit by lazy { strokeIcon("Edit") {
        moveTo(4f, 20f); lineTo(4.8f, 16.2f); lineTo(15.5f, 5.5f); quadTo(17f, 4f, 18.5f, 5.5f)
        quadTo(20f, 7f, 18.5f, 8.5f); lineTo(7.8f, 19.2f); close()
        moveTo(13.5f, 7.5f); lineTo(16.5f, 10.5f)
    } }
    val Type by lazy { strokeIcon("Type") {
        moveTo(5f, 7.5f); lineTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 7.5f)
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(9f, 19f); lineTo(15f, 19f)
    } }
    val Smile by lazy { strokeIcon("Smile") {
        circle(12f, 12f, 9f)
        moveTo(8.5f, 14.5f); curveTo(10f, 16.5f, 14f, 16.5f, 15.5f, 14.5f)
        dot(9f, 9.75f); dot(15f, 9.75f)
    } }
    val Route by lazy { strokeIcon("Route") {
        moveTo(6f, 3f); lineTo(6f, 15f); circle(6f, 18f, 2.5f)
        circle(18f, 6f, 2.5f)
        moveTo(18f, 8.5f); curveTo(18f, 13.5f, 6f, 10f, 6f, 15f)
    } }
    val Link by lazy { strokeIcon("Link") {
        moveTo(10f, 7.5f); lineTo(7.5f, 7.5f); arcTo(4.5f, 4.5f, 0f, false, false, 7.5f, 16.5f); lineTo(10f, 16.5f)
        moveTo(14f, 7.5f); lineTo(16.5f, 7.5f); arcTo(4.5f, 4.5f, 0f, false, true, 16.5f, 16.5f); lineTo(14f, 16.5f)
        moveTo(8.5f, 12f); lineTo(15.5f, 12f)
    } }
    val Key by lazy { strokeIcon("Key") {
        circle(8f, 15.5f, 4.5f)
        moveTo(11.2f, 12.3f); lineTo(20f, 3.5f); moveTo(16.5f, 7f); lineTo(19f, 9.5f); moveTo(14f, 9.5f); lineTo(16f, 11.5f)
    } }
    val Trash by lazy { strokeIcon("Trash") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 19.5f); quadTo(7.15f, 21f, 8.5f, 21f); lineTo(15.5f, 21f); quadTo(16.85f, 21f, 17f, 19.5f); lineTo(18f, 7f)
        moveTo(10f, 11f); lineTo(10f, 17f); moveTo(14f, 11f); lineTo(14f, 17f)
    } }
    val Sliders by lazy { strokeIcon("Sliders") {
        moveTo(4f, 7f); lineTo(7f, 7f); circle(9.5f, 7f, 2.5f); moveTo(12f, 7f); lineTo(20f, 7f)
        moveTo(4f, 17f); lineTo(12f, 17f); circle(14.5f, 17f, 2.5f); moveTo(17f, 17f); lineTo(20f, 17f)
    } }
    val Chevron by lazy { strokeIcon("Chevron") { moveTo(9.5f, 6.5f); lineTo(15f, 12f); lineTo(9.5f, 17.5f) } }
    val Hash by lazy { strokeIcon("Hash") { moveTo(9.5f, 4f); lineTo(7.5f, 20f); moveTo(16.5f, 4f); lineTo(14.5f, 20f); moveTo(4.5f, 9f); lineTo(20f, 9f); moveTo(4f, 15f); lineTo(19.5f, 15f) } }
    val Back by lazy { strokeIcon("Back") { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) } }
    val Play by lazy { strokeIcon("Play") { circle(12f, 12f, 9f); moveTo(10.2f, 8.6f); lineTo(15.4f, 12f); lineTo(10.2f, 15.4f); close() } }
    val Info by lazy { strokeIcon("Info") { circle(12f, 12f, 9f); moveTo(12f, 11f); lineTo(12f, 16.5f); dot(12f, 7.8f) } }
    val Palette by lazy { strokeIcon("Palette") {
        moveTo(12f, 3f); curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
        curveTo(13.4f, 21f, 14f, 20.1f, 14f, 19f); curveTo(14f, 17.6f, 12.8f, 17.1f, 12.8f, 15.8f)
        curveTo(12.8f, 14.6f, 13.8f, 14f, 15f, 14f); lineTo(17f, 14f); curveTo(19.2f, 14f, 21f, 12.2f, 21f, 10f)
        curveTo(21f, 6f, 17f, 3f, 12f, 3f); close()
        dot(7.6f, 11.6f); dot(9.6f, 7.6f); dot(14.4f, 7.4f)
    } }
    val Gesture by lazy { strokeIcon("Gesture") {
        moveTo(10.2f, 21f); lineTo(7.9f, 16.6f); curveTo(7.4f, 15.4f, 8.8f, 14.6f, 9.6f, 15.6f); lineTo(10.5f, 16.8f)
        lineTo(10.5f, 9.2f); curveTo(10.5f, 7.8f, 12.5f, 7.8f, 12.5f, 9.2f); lineTo(12.5f, 13f); lineTo(16.9f, 13.8f)
        curveTo(18.3f, 14f, 19f, 15.1f, 18.8f, 16.4f); lineTo(18.1f, 21f)
        moveTo(7.6f, 9.6f); curveTo(6.8f, 6.2f, 9.2f, 3.6f, 11.5f, 3.6f); curveTo(13.8f, 3.6f, 16.2f, 6.2f, 15.4f, 9.6f)
    } }
    val Archive by lazy { strokeIcon("Archive") {
        roundRect(3.5f, 4f, 20.5f, 9f, 1.6f)
        moveTo(5f, 9f); lineTo(5f, 18.5f); quadTo(5f, 20f, 6.5f, 20f); lineTo(17.5f, 20f); quadTo(19f, 20f, 19f, 18.5f); lineTo(19f, 9f)
        moveTo(10f, 13f); lineTo(14f, 13f)
    } }
    val Sun by lazy { strokeIcon("Sun") {
        circle(12f, 12f, 4f)
        listOf(0f to -1f, 0.7f to -0.7f, 1f to 0f, 0.7f to 0.7f, 0f to 1f, -0.7f to 0.7f, -1f to 0f, -0.7f to -0.7f).forEach { (x, y) ->
            moveTo(12f + x * 6.8f, 12f + y * 6.8f); lineTo(12f + x * 8.8f, 12f + y * 8.8f)
        }
    } }
    val Moon by lazy { strokeIcon("Moon") {
        moveTo(20f, 14.5f); curveTo(18.7f, 18.2f, 15.2f, 20.8f, 11.3f, 20.8f); curveTo(6.4f, 20.8f, 3f, 17.1f, 3f, 12.4f)
        curveTo(3f, 8.4f, 5.7f, 5f, 9.4f, 4f); curveTo(8.6f, 5.3f, 8.2f, 6.8f, 8.2f, 8.4f)
        curveTo(8.2f, 13f, 11.9f, 16.6f, 16.4f, 16.6f); curveTo(17.7f, 16.6f, 18.9f, 16.3f, 20f, 14.5f); close()
    } }
    val Device by lazy { strokeIcon("Device") { roundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.6f); moveTo(10.5f, 18.5f); lineTo(13.5f, 18.5f) } }
    val Pause by lazy { strokeIcon("Pause") { circle(12f, 12f, 9f); moveTo(10f, 9f); lineTo(10f, 15f); moveTo(14f, 9f); lineTo(14f, 15f) } }
    val PictureInPicture by lazy { strokeIcon("PictureInPicture") { roundRect(3f, 5f, 21f, 19f, 2.5f); roundRect(12f, 11.5f, 18.5f, 16.5f, 1.2f) } }
    val Chip by lazy { strokeIcon("Chip") {
        roundRect(7f, 7f, 17f, 17f, 2f)
        for (v in listOf(9.5f, 12f, 14.5f)) {
            moveTo(v, 4f); lineTo(v, 7f); moveTo(v, 17f); lineTo(v, 20f)
            moveTo(4f, v); lineTo(7f, v); moveTo(17f, v); lineTo(20f, v)
        }
    } }
    val Code by lazy { strokeIcon("Code") {
        moveTo(8.5f, 7f); lineTo(3.5f, 12f); lineTo(8.5f, 17f); moveTo(15.5f, 7f); lineTo(20.5f, 12f); lineTo(15.5f, 17f)
        moveTo(13.5f, 5f); lineTo(10.5f, 19f)
    } }
    val Copy by lazy { strokeIcon("Copy") {
        roundRect(8f, 8f, 20f, 20f, 2f)
        moveTo(16f, 8f); lineTo(16f, 5.5f); quadTo(16f, 4f, 14.5f, 4f); lineTo(5.5f, 4f); quadTo(4f, 4f, 4f, 5.5f)
        lineTo(4f, 14.5f); quadTo(4f, 16f, 5.5f, 16f); lineTo(8f, 16f)
    } }
    val Home by lazy { strokeIcon("Home") {
        moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f)
        moveTo(6f, 9f); lineTo(6f, 18.5f); quadTo(6f, 20f, 7.5f, 20f); lineTo(16.5f, 20f); quadTo(18f, 20f, 18f, 18.5f); lineTo(18f, 9f)
        moveTo(10f, 20f); lineTo(10f, 15f); quadTo(10f, 14f, 11f, 14f); lineTo(13f, 14f); quadTo(14f, 14f, 14f, 15f); lineTo(14f, 20f)
    } }
    val Search by lazy { strokeIcon("Search") { circle(11f, 11f, 6.5f); moveTo(16f, 16f); lineTo(20f, 20f) } }
    val Star by lazy { strokeIcon("Star") {
        moveTo(12f, 3.8f); lineTo(14.4f, 8.9f); lineTo(19.9f, 9.6f); lineTo(15.9f, 13.4f); lineTo(16.9f, 18.9f)
        lineTo(12f, 16.2f); lineTo(7.1f, 18.9f); lineTo(8.1f, 13.4f); lineTo(4.1f, 9.6f); lineTo(9.6f, 8.9f); close()
    } }
    val Download by lazy { strokeIcon("Download") { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f); moveTo(5f, 19.5f); lineTo(19f, 19.5f) } }
    val Tv by lazy { strokeIcon("Tv") { roundRect(3f, 6f, 21f, 18f, 2.5f); moveTo(8.5f, 2.5f); lineTo(12f, 6f); lineTo(15.5f, 2.5f); moveTo(8f, 21f); lineTo(16f, 21f) } }
    val ChevronDown by lazy { strokeIcon("ChevronDown") { moveTo(6.5f, 9.5f); lineTo(12f, 15f); lineTo(17.5f, 9.5f) } }
    val Calendar by lazy { strokeIcon("Calendar") { roundRect(3.5f, 5f, 20.5f, 20f, 2.5f); moveTo(3.5f, 10f); lineTo(20.5f, 10f); moveTo(8f, 3f); lineTo(8f, 7f); moveTo(16f, 3f); lineTo(16f, 7f) } }
    val SortAlpha by lazy { strokeIcon("SortAlpha") {
        moveTo(3.5f, 11f); lineTo(6.5f, 4f); lineTo(9.5f, 11f); moveTo(4.6f, 8.6f); lineTo(8.4f, 8.6f)
        moveTo(4f, 14f); lineTo(9f, 14f); lineTo(4f, 20f); lineTo(9f, 20f)
        moveTo(17f, 4f); lineTo(17f, 20f); moveTo(14f, 17f); lineTo(17f, 20f); lineTo(20f, 17f)
    } }
    val SortDescending by lazy { strokeIcon("SortDescending") {
        moveTo(6f, 4.5f); lineTo(6f, 19.5f); moveTo(3.5f, 17f); lineTo(6f, 19.5f); lineTo(8.5f, 17f)
        moveTo(11.5f, 6f); lineTo(20f, 6f); moveTo(11.5f, 12f); lineTo(17.5f, 12f); moveTo(11.5f, 18f); lineTo(15f, 18f)
    } }
    val SortAscending by lazy { strokeIcon("SortAscending") {
        moveTo(6f, 19.5f); lineTo(6f, 4.5f); moveTo(3.5f, 7f); lineTo(6f, 4.5f); lineTo(8.5f, 7f)
        moveTo(11.5f, 6f); lineTo(15f, 6f); moveTo(11.5f, 12f); lineTo(17.5f, 12f); moveTo(11.5f, 18f); lineTo(20f, 18f)
    } }
    /** 封面样式按当前布局显示：两张同比例卡片前后叠放，后一张只露出右上边，前卡底部一道片名线；竖幅为竖卡，横幅为宽卡。 */
    val LayoutPoster by lazy { strokeIcon("LayoutPoster") {
        // 外轮廓控制在 4.5–20，与筛选、横幅图标同高。
        roundRect(5.5f, 7.5f, 14.5f, 20f, 1.8f)
        moveTo(8.2f, 17f); lineTo(11.2f, 17f)
        moveTo(8.5f, 4.5f); lineTo(16.8f, 4.5f); quadTo(18.5f, 4.5f, 18.5f, 6.2f); lineTo(18.5f, 16.5f)
    } }
    val LayoutBackdrop by lazy { strokeIcon("LayoutBackdrop") {
        roundRect(2.5f, 9.5f, 18f, 19.5f, 2f)
        moveTo(5.5f, 16.5f); lineTo(9.5f, 16.5f)
        moveTo(6.5f, 5.5f); lineTo(19.5f, 5.5f); quadTo(21.5f, 5.5f, 21.5f, 7.5f); lineTo(21.5f, 15.5f)
    } }
    val Filter by lazy { strokeIcon("Filter") {
        moveTo(4.5f, 5.5f); lineTo(19.5f, 5.5f); lineTo(14f, 12f); lineTo(14f, 18f); lineTo(10f, 19.5f); lineTo(10f, 12f); close()
    } }
    /** 逆时针回转箭头：恢复默认 / 清除筛选。 */
    val Reset by lazy { strokeIcon("Reset") {
        moveTo(5f, 12f); arcToRelative(7f, 7f, 0f, true, false, 2.05f, -4.95f); lineTo(5f, 9f)
        moveTo(5f, 4.5f); lineTo(5f, 9f); lineTo(9.5f, 9f)
    } }
    val Swap by lazy { strokeIcon("Swap") { moveTo(8f, 20f); lineTo(8f, 4f); moveTo(4.5f, 7.5f); lineTo(8f, 4f); lineTo(11.5f, 7.5f); moveTo(16f, 4f); lineTo(16f, 20f); moveTo(12.5f, 16.5f); lineTo(16f, 20f); lineTo(19.5f, 16.5f) } }
    val Grid by lazy { strokeIcon("Grid") { roundRect(4f, 4f, 10.5f, 10.5f, 1.8f); roundRect(13.5f, 4f, 20f, 10.5f, 1.8f); roundRect(4f, 13.5f, 10.5f, 20f, 1.8f); roundRect(13.5f, 13.5f, 20f, 20f, 1.8f) } }
    val Folder by lazy { strokeIcon("Folder") {
        moveTo(3.5f, 7.5f); quadTo(3.5f, 5f, 6f, 5f); lineTo(9.5f, 5f); lineTo(11.5f, 7.5f); lineTo(18f, 7.5f); quadTo(20.5f, 7.5f, 20.5f, 10f)
        lineTo(20.5f, 17.5f); quadTo(20.5f, 20f, 18f, 20f); lineTo(6f, 20f); quadTo(3.5f, 20f, 3.5f, 17.5f); close()
    } }
    val Tag by lazy { strokeIcon("Tag") {
        moveTo(3.5f, 4.5f); quadTo(3.5f, 3.5f, 4.5f, 3.5f); lineTo(11f, 3.5f); lineTo(20.5f, 13f); lineTo(13f, 20.5f); lineTo(3.5f, 11f); close()
        dot(8f, 8f)
    } }
    val Shapes by lazy { strokeIcon("Shapes") { circle(7.5f, 7.5f, 3.5f); roundRect(13.5f, 4f, 20f, 10.5f, 1.5f); moveTo(7.5f, 13.5f); lineTo(11f, 20f); lineTo(4f, 20f); close(); circle(16.75f, 16.75f, 3.25f) } }
    val Clock by lazy { strokeIcon("Clock") { circle(12f, 12f, 9f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) } }
    val Alert by lazy { strokeIcon("Alert") {
        circle(12f, 12f, 9f); moveTo(12f, 7.5f); lineTo(12f, 12.5f); dot(12f, 16f)
    } }

    // 详情页与媒体工具使用的图标。
    val Heart by lazy { strokeIcon("Heart") { heart() } }
    val HeartFilled by lazy { strokeIcon("HeartFilled", filled = true) { heart() } }
    val CheckCircle by lazy { strokeIcon("CheckCircle") {
        circle(12f, 12f, 9f); moveTo(8f, 12.3f); lineTo(10.8f, 15f); lineTo(16f, 9.5f)
    } }
    /** 未观看：纯空心圆，与 [CheckCircleFilled] 成对；外沿与实心圆等大，切换时不跳动。 */
    val Unwatched by lazy { strokeIcon("Unwatched") { circle(12f, 12f, 9f) } }
    /** 已观看：实心圆里镂空一道勾（偶奇填充），与空心的 [Unwatched] 成对，和空心 / 实心心形同一规则。 */
    val CheckCircleFilled by lazy {
        ImageVector.Builder("CheckCircleFilled", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9.7f)
                moveTo(6.73f, 13.30f); lineTo(10.83f, 16.75f); lineTo(17.51f, 10.15f); lineTo(15.69f, 8.45f); lineTo(10.77f, 14.25f); lineTo(8.47f, 11.50f); close()
            }
        }.build()
    }
    val Cast by lazy { strokeIcon("Cast") { cast() } }
    val CastConnected by lazy { strokeIcon("CastConnected") {
        cast(); moveTo(6.5f, 8f); lineTo(17.5f, 8f); lineTo(17.5f, 16f); lineTo(14.5f, 16f)
    } }
    val Subtitles by lazy { strokeIcon("Subtitles") {
        roundRect(3f, 5f, 21f, 19f, 2.5f)
        moveTo(6.5f, 13.5f); lineTo(10f, 13.5f); moveTo(12.5f, 13.5f); lineTo(17.5f, 13.5f)
        moveTo(6.5f, 16.3f); lineTo(13f, 16.3f); moveTo(15f, 16.3f); lineTo(17.5f, 16.3f)
    } }
    val Audio by lazy { strokeIcon("Audio") {
        circle(6.5f, 17.5f, 2.5f); circle(16.5f, 15.5f, 2.5f)
        moveTo(9f, 17.5f); lineTo(9f, 5.5f); lineTo(19f, 3.5f); lineTo(19f, 15.5f); moveTo(9f, 9f); lineTo(19f, 7f)
    } }
    val Video by lazy { strokeIcon("Video") {
        roundRect(2.5f, 6f, 15.5f, 18f, 2.5f); moveTo(15.5f, 10.5f); lineTo(21.5f, 7f); lineTo(21.5f, 17f); lineTo(15.5f, 13.5f)
    } }
    val Image by lazy { strokeIcon("Image") {
        roundRect(3.5f, 4.5f, 20.5f, 19.5f, 2.5f); circle(9f, 9.5f, 1.6f)
        moveTo(3.5f, 16.5f); lineTo(8.5f, 12f); lineTo(12.5f, 15.5f); lineTo(15f, 13.5f); lineTo(20.5f, 18f)
    } }
    val ImageAdd by lazy { strokeIcon("ImageAdd") {
        moveTo(13f, 5f); lineTo(5.5f, 5f); quadTo(3f, 5f, 3f, 7.5f); lineTo(3f, 17f); quadTo(3f, 19.5f, 5.5f, 19.5f)
        lineTo(15f, 19.5f); quadTo(17.5f, 19.5f, 17.5f, 17f); lineTo(17.5f, 11.5f)
        circle(8f, 10f, 1.4f); moveTo(3f, 16f); lineTo(7.5f, 12f); lineTo(11f, 15f); lineTo(13f, 13.5f); lineTo(17.5f, 17f)
        moveTo(19.5f, 2.5f); lineTo(19.5f, 8.5f); moveTo(16.5f, 5.5f); lineTo(22.5f, 5.5f)
    } }
    val ImageSearch by lazy { strokeIcon("ImageSearch") {
        moveTo(15f, 9f); lineTo(15f, 6.5f); quadTo(15f, 4.5f, 13f, 4.5f); lineTo(5f, 4.5f); quadTo(3f, 4.5f, 3f, 6.5f)
        lineTo(3f, 14.5f); quadTo(3f, 16.5f, 5f, 16.5f); lineTo(10.5f, 16.5f)
        moveTo(3f, 13.5f); lineTo(6.5f, 10.5f); lineTo(9.5f, 13f)
        circle(16.5f, 16.5f, 3.2f); moveTo(18.8f, 18.8f); lineTo(21.5f, 21.5f)
    } }
    val Upload by lazy { strokeIcon("Upload") {
        moveTo(12f, 15.5f); lineTo(12f, 4f); moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
        moveTo(4.5f, 15f); lineTo(4.5f, 18f); quadTo(4.5f, 20f, 6.5f, 20f); lineTo(17.5f, 20f); quadTo(19.5f, 20f, 19.5f, 18f); lineTo(19.5f, 15f)
    } }
    /** 顺时针回转箭头：重新读取 / 刷新。 */
    val Refresh by lazy { strokeIcon("Refresh") {
        moveTo(19f, 12f); arcToRelative(7f, 7f, 0f, true, true, -2.05f, -4.95f); lineTo(19f, 9f)
        moveTo(19f, 4.5f); lineTo(19f, 9f); lineTo(14.5f, 9f)
    } }
    val List by lazy { strokeIcon("List") {
        moveTo(9.5f, 6.5f); lineTo(20f, 6.5f); moveTo(9.5f, 12f); lineTo(20f, 12f); moveTo(9.5f, 17.5f); lineTo(20f, 17.5f)
        dot(5f, 6.5f); dot(5f, 12f); dot(5f, 17.5f)
    } }
    val Stop by lazy { strokeIcon("Stop") { roundRect(6f, 6f, 18f, 18f, 2f) } }
    /** 电源：上方留缺口的圆弧与竖线，用于重启服务器。 */
    val Power by lazy { strokeIcon("Power") {
        moveTo(12f, 3.5f); lineTo(12f, 11.5f)
        moveTo(7.6f, 6.4f); arcToRelative(7.2f, 7.2f, 0f, true, false, 8.8f, 0f)
    } }

    // 播放器控制层使用的图标。
    /** 逆时针回转箭头，表示后退若干秒。 */
    val SeekBack by lazy { strokeIcon("SeekBack") {
        moveTo(4.5f, 12f); arcToRelative(7.5f, 7.5f, 0f, true, false, 2.2f, -5.3f); lineTo(4.5f, 9f)
        moveTo(4.5f, 4.5f); lineTo(4.5f, 9f); lineTo(9f, 9f)
        moveTo(10f, 10f); lineTo(10f, 15f); moveTo(13f, 10f); lineTo(15.5f, 10f); lineTo(15.5f, 15f); lineTo(13f, 15f); close()
    } }
    /** 顺时针回转箭头，表示前进若干秒。 */
    val SeekForward by lazy { strokeIcon("SeekForward") {
        moveTo(19.5f, 12f); arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f); lineTo(19.5f, 9f)
        moveTo(19.5f, 4.5f); lineTo(19.5f, 9f); lineTo(15f, 9f)
        moveTo(8.5f, 10f); lineTo(8.5f, 15f); moveTo(11.5f, 10f); lineTo(14f, 10f); lineTo(14f, 15f); lineTo(11.5f, 15f); close()
    } }
    val SkipPrevious by lazy { strokeIcon("SkipPrevious") {
        moveTo(6.5f, 5.5f); lineTo(6.5f, 18.5f); moveTo(18f, 6f); lineTo(9.5f, 12f); lineTo(18f, 18f); close()
    } }
    val SkipNext by lazy { strokeIcon("SkipNext") {
        moveTo(17.5f, 5.5f); lineTo(17.5f, 18.5f); moveTo(6f, 6f); lineTo(14.5f, 12f); lineTo(6f, 18f); close()
    } }
    val Camera by lazy { strokeIcon("Camera") {
        moveTo(3.5f, 9f); quadTo(3.5f, 7f, 5.5f, 7f); lineTo(8f, 7f); lineTo(9.5f, 4.8f); lineTo(14.5f, 4.8f); lineTo(16f, 7f)
        lineTo(18.5f, 7f); quadTo(20.5f, 7f, 20.5f, 9f); lineTo(20.5f, 17.5f); quadTo(20.5f, 19.5f, 18.5f, 19.5f)
        lineTo(5.5f, 19.5f); quadTo(3.5f, 19.5f, 3.5f, 17.5f); close(); circle(12f, 13f, 3.6f)
    } }
    val LockOpen by lazy { strokeIcon("LockOpen") {
        roundRect(5f, 10.5f, 19f, 21f, 2.5f)
        moveTo(8f, 10.5f); lineTo(8f, 8f); curveTo(8f, 3.2f, 15f, 2.6f, 15.9f, 6.5f)
        moveTo(12f, 14.5f); lineTo(12f, 17f)
    } }
    /** 旋转键按“点了会转到哪个方向”画：一部目标方向的手机，外加一道带箭头的旋转弧。 */
    val RotateToLandscape by lazy { strokeIcon("RotateToLandscape") {
        roundRect(2.5f, 10f, 16f, 19f, 1.8f); moveTo(13.5f, 13.5f); lineTo(13.5f, 15.5f)
        moveTo(8f, 6.5f); quadTo(15.5f, 2.5f, 20f, 8.5f); moveTo(20f, 8.5f); lineTo(17.2f, 8.2f); moveTo(20f, 8.5f); lineTo(20.6f, 5.7f)
    } }
    val RotateToPortrait by lazy { strokeIcon("RotateToPortrait") {
        roundRect(4f, 6f, 13f, 20.5f, 1.8f); moveTo(7.5f, 17.5f); lineTo(9.5f, 17.5f)
        moveTo(15f, 3.5f); quadTo(21f, 6f, 20.5f, 13f); moveTo(20.5f, 13f); lineTo(18.5f, 11f); moveTo(20.5f, 13f); lineTo(22f, 10.6f)
    } }
    val Battery by lazy { strokeIcon("Battery") { roundRect(2.5f, 7f, 19.5f, 17f, 2.2f); moveTo(21.5f, 10.5f); lineTo(21.5f, 13.5f) } }
    val AspectRatio by lazy { strokeIcon("AspectRatio") {
        roundRect(3f, 5f, 21f, 19f, 2.5f)
        moveTo(6.5f, 11f); lineTo(6.5f, 8.5f); lineTo(9f, 8.5f); moveTo(17.5f, 13f); lineTo(17.5f, 15.5f); lineTo(15f, 15.5f)
    } }
    /** 中央播放键用的实心三角与双竖条，比带圆圈的版本更醒目。 */
    val PlayGlyph by lazy { strokeIcon("PlayGlyph", filled = true) { moveTo(8.5f, 5.5f); lineTo(18.5f, 12f); lineTo(8.5f, 18.5f); close() } }
    val PauseGlyph by lazy { strokeIcon("PauseGlyph", filled = true) { roundRect(6.5f, 5f, 10f, 19f, 1.2f); roundRect(14f, 5f, 17.5f, 19f, 1.2f) } }
    val Volume by lazy { strokeIcon("Volume") {
        moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f); moveTo(18f, 6.5f); quadTo(22f, 12f, 18f, 17.5f)
    } }
    val VolumeOff by lazy { strokeIcon("VolumeOff") {
        moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(15.5f, 9.5f); lineTo(20.5f, 14.5f); moveTo(20.5f, 9.5f); lineTo(15.5f, 14.5f)
    } }
    val Minus by lazy { strokeIcon("Minus") { moveTo(5f, 12f); lineTo(19f, 12f) } }
    /** 播放质量：圆角屏框里一个细线“HD”。 */
    val Quality by lazy { strokeIcon("Quality") {
        roundRect(3f, 5.5f, 21f, 18.5f, 3f)
        moveTo(7.5f, 9.5f); lineTo(7.5f, 14.5f); moveTo(11f, 9.5f); lineTo(11f, 14.5f); moveTo(7.5f, 12f); lineTo(11f, 12f)
        moveTo(13.8f, 9.5f); lineTo(13.8f, 14.5f); lineTo(15.3f, 14.5f); curveTo(18.2f, 14.5f, 18.2f, 9.5f, 15.3f, 9.5f); close()
    } }
    /** 画质档位：原画是无损的钻石，高质量是闪光，标准是屏幕，流畅是顺滑的风线。 */
    val QualityOriginal by lazy { strokeIcon("QualityOriginal") {
        moveTo(6.5f, 4.5f); lineTo(17.5f, 4.5f); lineTo(21f, 9.5f); lineTo(12f, 20f); lineTo(3f, 9.5f); close()
        moveTo(3f, 9.5f); lineTo(21f, 9.5f)
        moveTo(9.5f, 9.5f); lineTo(12f, 4.5f); lineTo(14.5f, 9.5f); lineTo(12f, 20f); lineTo(9.5f, 9.5f)
    } }
    val QualityHigh by lazy { strokeIcon("QualityHigh") {
        moveTo(11f, 4.5f); quadTo(11.8f, 11.2f, 18.5f, 12.5f); quadTo(11.8f, 13.8f, 11f, 20.5f)
        quadTo(10.2f, 13.8f, 3.5f, 12.5f); quadTo(10.2f, 11.2f, 11f, 4.5f); close()
        moveTo(18.5f, 3.5f); lineTo(18.5f, 7.5f); moveTo(16.5f, 5.5f); lineTo(20.5f, 5.5f)
    } }
    val QualityStandard by lazy { strokeIcon("QualityStandard") {
        roundRect(3f, 4.5f, 21f, 16f, 2.2f); moveTo(12f, 16f); lineTo(12f, 19.5f); moveTo(8.5f, 19.5f); lineTo(15.5f, 19.5f)
    } }
    val QualitySaver by lazy { strokeIcon("QualitySaver") {
        moveTo(3f, 9f); lineTo(15f, 9f); curveTo(18.3f, 9f, 18.3f, 4f, 15f, 4f); curveTo(13.6f, 4f, 12.8f, 5f, 12.8f, 6f)
        moveTo(3f, 13f); lineTo(18f, 13f); curveTo(21.6f, 13f, 21.6f, 19f, 18f, 19f); curveTo(16.6f, 19f, 15.8f, 18f, 15.8f, 17f)
        moveTo(3f, 17f); lineTo(10.5f, 17f)
    } }
    val Speed by lazy { strokeIcon("Speed") {
        moveTo(4.5f, 16.5f); arcToRelative(8f, 8f, 0f, true, true, 15f, 0f); moveTo(12f, 15f); lineTo(15.5f, 10f); dot(12f, 15f)
    } }

    private fun PathBuilder.heart() {
        moveTo(12f, 20f); curveTo(5f, 15.5f, 3f, 12f, 3f, 8.8f); curveTo(3f, 6.1f, 5.1f, 4f, 7.7f, 4f)
        curveTo(9.5f, 4f, 11.1f, 5f, 12f, 6.5f); curveTo(12.9f, 5f, 14.5f, 4f, 16.3f, 4f)
        curveTo(18.9f, 4f, 21f, 6.1f, 21f, 8.8f); curveTo(21f, 12f, 19f, 15.5f, 12f, 20f); close()
    }

    private fun PathBuilder.cast() {
        moveTo(3f, 8.5f); lineTo(3f, 6.5f); quadTo(3f, 4.5f, 5f, 4.5f); lineTo(19f, 4.5f); quadTo(21f, 4.5f, 21f, 6.5f)
        lineTo(21f, 17.5f); quadTo(21f, 19.5f, 19f, 19.5f); lineTo(14f, 19.5f)
        moveTo(3f, 12f); arcToRelative(7.5f, 7.5f, 0f, false, true, 7.5f, 7.5f)
        moveTo(3f, 15.5f); arcToRelative(4f, 4f, 0f, false, true, 4f, 4f)
        dot(3.5f, 19.5f)
    }

    private fun PathBuilder.eye() {
        moveTo(2.5f, 12f); curveTo(4.8f, 7.4f, 8.2f, 5f, 12f, 5f); curveTo(15.8f, 5f, 19.2f, 7.4f, 21.5f, 12f)
        curveTo(19.2f, 16.6f, 15.8f, 19f, 12f, 19f); curveTo(8.2f, 19f, 4.8f, 16.6f, 2.5f, 12f); close()
        circle(12f, 12f, 3f)
    }
}
