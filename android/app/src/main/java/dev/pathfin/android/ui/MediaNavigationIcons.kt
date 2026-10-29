package dev.pathfin.android.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dev.pathfin.android.domain.MediaItem
import java.util.Locale

/** 导航图标使用同一24dp画布与圆角描边；由Icon的主题前景色统一着色。 */
internal object MediaNavigationIcons {
    val More by lazy { strokeIcon("MediaMore") {
        for (x in listOf(5f, 12f, 19f)) {
            moveTo(x + 1f, 12f)
            arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
            close()
        }
    } }
    val Console by lazy { strokeIcon("MediaConsole") {
        moveTo(5f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f)
        lineTo(21f, 14f); quadTo(21f, 16f, 19f, 16f); lineTo(5f, 16f)
        quadTo(3f, 16f, 3f, 14f); lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f); close()
        moveTo(10f, 6.5f); lineTo(15f, 9.5f); lineTo(10f, 12.5f); close()
        moveTo(3f, 20f); lineTo(10f, 20f); moveTo(6f, 18.5f); lineTo(6f, 21.5f)
        moveTo(14f, 20f); lineTo(21f, 20f); moveTo(18f, 18.5f); lineTo(18f, 21.5f)
    } }
    val Movies by lazy { strokeIcon("LibraryMovies") {
        moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f)
        lineTo(21f, 18f); quadTo(21f, 20f, 19f, 20f); lineTo(5f, 20f)
        quadTo(3f, 20f, 3f, 18f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f); close()
        moveTo(7f, 4f); lineTo(7f, 20f); moveTo(17f, 4f); lineTo(17f, 20f)
        for (y in listOf(8f, 12f, 16f)) {
            moveTo(3f, y); lineTo(7f, y); moveTo(17f, y); lineTo(21f, y)
        }
    } }
    val Series by lazy { strokeIcon("LibrarySeries") {
        moveTo(5f, 5f); lineTo(19f, 5f); quadTo(21f, 5f, 21f, 7f)
        lineTo(21f, 16f); quadTo(21f, 18f, 19f, 18f); lineTo(5f, 18f)
        quadTo(3f, 18f, 3f, 16f); lineTo(3f, 7f); quadTo(3f, 5f, 5f, 5f); close()
        moveTo(12f, 18f); lineTo(12f, 21f); moveTo(8f, 21f); lineTo(16f, 21f)
        moveTo(9f, 2f); lineTo(12f, 5f); lineTo(15f, 2f)
    } }
    val Music by lazy { strokeIcon("LibraryMusic") {
        moveTo(10f, 17f); lineTo(10f, 6f); lineTo(20f, 3f); lineTo(20f, 14f)
        moveTo(10f, 17f); curveTo(10f, 20.5f, 3f, 21.5f, 3f, 18f)
        curveTo(3f, 14.5f, 10f, 13.5f, 10f, 17f); close()
        moveTo(20f, 14f); curveTo(20f, 17.5f, 13f, 18.5f, 13f, 15f)
        curveTo(13f, 11.5f, 20f, 10.5f, 20f, 14f); close()
    } }
    val Mixed by lazy { strokeIcon("LibraryMixed") {
        moveTo(4f, 16f); lineTo(3f, 16f); lineTo(3f, 4f); lineTo(16f, 4f)
        moveTo(8f, 7f); lineTo(19f, 7f); quadTo(21f, 7f, 21f, 9f)
        lineTo(21f, 19f); quadTo(21f, 21f, 19f, 21f); lineTo(8f, 21f)
        quadTo(6f, 21f, 6f, 19f); lineTo(6f, 9f); quadTo(6f, 7f, 8f, 7f); close()
        moveTo(11f, 11f); lineTo(17f, 14f); lineTo(11f, 17f); close()
    } }
    val Photos by lazy { strokeIcon("LibraryPhotos") {
        moveTo(5f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f)
        lineTo(21f, 19f); quadTo(21f, 21f, 19f, 21f); lineTo(5f, 21f)
        quadTo(3f, 21f, 3f, 19f); lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f); close()
        moveTo(3f, 17f); lineTo(8f, 12f); lineTo(12f, 16f); lineTo(16f, 11f); lineTo(21f, 17f)
        moveTo(9f, 7f); arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
        arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f); close()
    } }
    val Books by lazy { strokeIcon("LibraryBooks") {
        moveTo(12f, 5f); curveTo(9f, 3f, 6f, 3f, 3f, 4f); lineTo(3f, 19f)
        curveTo(6f, 18f, 9f, 18f, 12f, 20f); curveTo(15f, 18f, 18f, 18f, 21f, 19f)
        lineTo(21f, 4f); curveTo(18f, 3f, 15f, 3f, 12f, 5f); lineTo(12f, 20f)
    } }
}

internal fun libraryTypeIcon(library: MediaItem): ImageVector = when (library.collectionType?.lowercase(Locale.ROOT)) {
    "movies" -> MediaNavigationIcons.Movies
    "tvshows", "livetv" -> MediaNavigationIcons.Series
    "music", "musicvideos" -> MediaNavigationIcons.Music
    "photos", "homevideos" -> MediaNavigationIcons.Photos
    "books" -> MediaNavigationIcons.Books
    else -> MediaNavigationIcons.Mixed
}
