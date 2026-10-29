package dev.pathfin.android.data

import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.Session
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageUrlTest {
    private val api = JellyfinApi()
    private val session = Session(ServerConfig("https://media.example/proxy"), "fixture-token", "user", "Alice", "device")
    private val film = MediaItem("film", "Movie", "Movie", imageTag = "primary-tag")

    @Test fun primaryKeepsProxyPrefixAndAuthenticationOutOfUrl() {
        val url = api.imageUrl(session, film)!!.toHttpUrl()
        assertEquals("/proxy/Items/film/Images/Primary", url.encodedPath)
        assertEquals("420", url.queryParameter("maxWidth"))
        assertEquals("80", url.queryParameter("quality"))
        assertEquals("Webp", url.queryParameter("format"))
        assertEquals("primary-tag", url.queryParameter("tag"))
        assertNull(url.queryParameter("api_key"))
        assertFalse(url.toString().contains(session.accessToken))
    }

    @Test fun userAvatarUsesCurrentUserTagAndKeepsCredentialsInHeaders() {
        val withAvatar = session.copy(userImageTag = "avatar & tag/#")
        val url = api.userImageUrl(withAvatar, width = 128)!!.toHttpUrl()
        assertEquals("/proxy/Users/user/Images/Primary", url.encodedPath)
        assertEquals("avatar & tag/#", url.queryParameter("tag"))
        assertEquals("128", url.queryParameter("maxWidth"))
        assertEquals("Webp", url.queryParameter("format"))
        assertFalse(url.toString().contains(session.accessToken))
        assertNull(api.userImageUrl(session))
        assertNull(api.userImageUrl(withAvatar.copy(userId = "../capture")))
        assertEquals("1024", api.userImageUrl(withAvatar, Int.MAX_VALUE)!!.toHttpUrl().queryParameter("maxWidth"))
    }

    @Test fun inheritedPrimaryAndMissingWideArtUseTheMappedSourceId() {
        val episode = JsonMapping.mediaItem(JSONObject("""{
            "Id":"episode","Type":"Episode","ParentPrimaryImageItemId":"season","ParentPrimaryImageTag":"season-poster"
        }"""))
        for (type in listOf("Primary", "Backdrop", "Banner")) {
            val url = api.imageUrl(session, episode, type = type)!!.toHttpUrl()
            assertEquals("/proxy/Items/season/Images/Primary", url.encodedPath)
            assertEquals("season-poster", url.queryParameter("tag"))
            assertFalse(url.toString().contains(session.accessToken))
        }
        assertNull(api.imageUrl(session, episode, type = "Logo"))

        val seriesFallback = JsonMapping.mediaItem(JSONObject("""{
            "Id":"episode","Type":"Episode","SeriesId":"series","SeriesPrimaryImageTag":"series-poster"
        }"""))
        assertEquals("/proxy/Items/series/Images/Primary", api.imageUrl(session, seriesFallback)!!.toHttpUrl().encodedPath)
    }

    @Test fun validNonPrimaryArtworkKeepsItsOwnSourceEvenWithInheritedPrimary() {
        val episode = film.copy(id = "episode", imageTag = "parent-poster", primaryImageItemId = "parent-primary",
            backdropTag = "episode-wide", bannerTag = "episode-banner", logoTag = "episode-logo")
        val expected = mapOf("Backdrop" to ("Backdrop/0" to "episode-wide"),
            "Banner" to ("Banner" to "episode-banner"), "Logo" to ("Logo" to "episode-logo"))
        for ((type, art) in expected) {
            val url = api.imageUrl(session, episode, type = type)!!.toHttpUrl()
            assertEquals("/proxy/Items/episode/Images/${art.first}", url.encodedPath)
            assertEquals(art.second, url.queryParameter("tag"))
        }
        val otherParents = episode.copy(backdropItemId = "parent-backdrop", logoItemId = "parent-logo")
        assertEquals("/proxy/Items/parent-backdrop/Images/Backdrop/0", api.imageUrl(session, otherParents, type = "Backdrop")!!.toHttpUrl().encodedPath)
        assertEquals("/proxy/Items/parent-logo/Images/Logo", api.imageUrl(session, otherParents, type = "Logo")!!.toHttpUrl().encodedPath)
    }

    @Test fun inheritedPrimaryIdsAreValidatedAndCannotAffectOtherImageTypes() {
        for (id in listOf("", " ", "../capture", "item?api_key=value", "item%2Fcapture", "x".repeat(129))) {
            val inherited = film.copy(primaryImageItemId = id, bannerTag = "own-banner")
            assertNull(api.imageUrl(session, inherited))
            assertNull(api.imageUrl(session, inherited, type = "Backdrop"))
            assertEquals("/proxy/Items/film/Images/Banner", api.imageUrl(session, inherited, type = "Banner")!!.toHttpUrl().encodedPath)
        }
        val unpaired = JsonMapping.mediaItem(JSONObject("""{"Id":"episode","SeriesId":"series","ParentPrimaryImageTag":"poster"}"""))
        assertNull(api.imageUrl(session, unpaired))
    }

    @Test fun backdropUsesIndexZeroAndMayBelongToParent() {
        val own = film.copy(backdropTag = "own-wide")
        val ownUrl = api.imageUrl(session, own, width = 900, type = "Backdrop")!!.toHttpUrl()
        assertEquals("/proxy/Items/film/Images/Backdrop/0", ownUrl.encodedPath)
        assertEquals("own-wide", ownUrl.queryParameter("tag"))
        assertEquals("900", ownUrl.queryParameter("maxWidth"))
        assertEquals("/proxy/Items/film/Images/Backdrop/0", api.imageUrl(session, own.copy(imageTag = null), type = "Backdrop")!!.toHttpUrl().encodedPath)

        val inherited = own.copy(backdropItemId = "parent-series", backdropTag = "parent-wide")
        val parentUrl = api.imageUrl(session, inherited, type = "Backdrop")!!.toHttpUrl()
        assertEquals("/proxy/Items/parent-series/Images/Backdrop/0", parentUrl.encodedPath)
        assertEquals("parent-wide", parentUrl.queryParameter("tag"))
        assertEquals("/proxy/Items/film/Images/Primary", api.imageUrl(session, inherited)!!.toHttpUrl().encodedPath)
    }

    @Test fun missingBackdropFallsBackToPrimaryAndMissingAllImagesReturnsNull() {
        assertEquals("/proxy/Items/film/Images/Primary", api.imageUrl(session, film, type = "Backdrop")!!.toHttpUrl().encodedPath)
        assertEquals("primary-tag", api.imageUrl(session, film.copy(backdropTag = " "), type = "Backdrop")!!.toHttpUrl().queryParameter("tag"))
        assertNull(api.imageUrl(session, film.copy(imageTag = null), type = "Backdrop"))
        assertNull(api.imageUrl(session, film.copy(imageTag = "")))
    }

    @Test fun invalidImageTypesAndIdentifiersCannotChangeThePath() {
        for (type in listOf("backdrop", "Backdrop/../../capture", "Primary?api_key=fixture-token")) {
            assertNull(api.imageUrl(session, film, type = type))
        }
        for (id in listOf("", "../capture", "item?api_key=value", "item%2Fcapture", "x".repeat(129))) {
            assertNull(api.imageUrl(session, film.copy(id = id)))
            assertNull(api.imageUrl(session, film.copy(backdropTag = "wide", backdropItemId = id), type = "Backdrop"))
        }
    }

    @Test fun imageTagStaysAnEncodedValueAndWidthIsBounded() {
        val tag = "wide & injected=value/#"
        val url = api.imageUrl(session, film.copy(backdropTag = tag), width = Int.MAX_VALUE, type = "Backdrop")!!.toHttpUrl()
        assertEquals("/proxy/Items/film/Images/Backdrop/0", url.encodedPath)
        assertEquals(tag, url.queryParameter("tag"))
        assertEquals(setOf("maxWidth", "quality", "format", "tag"), url.queryParameterNames)
        assertEquals("2048", url.queryParameter("maxWidth"))
        assertEquals("48", api.imageUrl(session, film, width = -1)!!.toHttpUrl().queryParameter("maxWidth"))
    }

    @Test fun bannerAndInheritedLogoUseTheirActualTagsAndMissingLogoStaysAbsent() {
        val withArt = film.copy(bannerTag = "banner", logoTag = "logo", logoItemId = "series")
        val banner = api.imageUrl(session, withArt, type = "Banner")!!.toHttpUrl()
        assertEquals("/proxy/Items/film/Images/Banner", banner.encodedPath)
        assertEquals("banner", banner.queryParameter("tag"))
        val logo = api.imageUrl(session, withArt, type = "Logo")!!.toHttpUrl()
        assertEquals("/proxy/Items/series/Images/Logo", logo.encodedPath)
        assertEquals("logo", logo.queryParameter("tag"))
        assertNull(api.imageUrl(session, film, type = "Logo"))
        assertEquals("/proxy/Items/film/Images/Primary", api.imageUrl(session, film, type = "Banner")!!.toHttpUrl().encodedPath)
    }

    @Test fun additionalBackdropsKeepTheirRealArrayIndex() {
        val art = film.copy(backdropTag = "first", backdropTags = listOf("first", "", "third"), backdropItemId = "series")
        val third = api.imageUrl(session, art, type = "Backdrop", imageIndex = 2)!!.toHttpUrl()
        assertEquals("/proxy/Items/series/Images/Backdrop/2", third.encodedPath)
        assertEquals("third", third.queryParameter("tag"))
        assertEquals("/proxy/Items/film/Images/Primary", api.imageUrl(session, art, type = "Backdrop", imageIndex = 1)!!.toHttpUrl().encodedPath)
        assertNull(api.imageUrl(session, art, type = "Backdrop", imageIndex = -1))
    }
}
