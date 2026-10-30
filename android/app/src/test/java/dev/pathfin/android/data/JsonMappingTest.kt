package dev.pathfin.android.data

import dev.pathfin.android.domain.ServerConfig
import dev.pathfin.android.domain.MediaItem
import dev.pathfin.android.domain.MediaUserData
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonMappingTest {
    @Test fun defaultSubtitleSelectionDistinguishesServerOffFromMissingOrInvalidIndex() {
        val sources = JsonMapping.mediaSources(org.json.JSONArray("""[
            {"Id":"off","DefaultSubtitleStreamIndex":-1,"DefaultAudioStreamIndex":-1},
            {"Id":"first","DefaultSubtitleStreamIndex":0},
            {"Id":"selected","DefaultSubtitleStreamIndex":7},
            {"Id":"missing"},
            {"Id":"invalid-negative","DefaultSubtitleStreamIndex":-2},
            {"Id":"fraction","DefaultSubtitleStreamIndex":1.5},
            {"Id":"overflow","DefaultSubtitleStreamIndex":2147483648}]"""))
        assertEquals(listOf(-1, 0, 7, null, null, null, null), sources.map { it.defaultSubtitleStreamIndex })
        assertNull(sources.first().defaultAudioStreamIndex)
    }

    @Test fun strmSourceSizeIsTheShortcutFileAndIsDropped() {
        val sources = JsonMapping.mediaSources(org.json.JSONArray("""[
            {"Id":"strm","IsRemote":true,"Path":"http://cdn/film.mkv","Size":270},
            {"Id":"local","IsRemote":false,"Size":53687091200},
            {"Id":"plain","Size":1024},
            {"Id":"invalid","Size":-1}]"""))
        assertEquals(listOf(null, 53_687_091_200L, 1024L, null), sources.map { it.size })
    }

    @Test fun mapsSparseItemsAndIgnoresInvalidListEntries() {
        val page = JsonMapping.mediaPage(JSONObject("""{"Items":[null,{}, {"Id":"film","Name":null,"Type":"Movie","Overview":null}],"TotalRecordCount":1}"""))
        assertEquals(1, page.items.size)
        assertEquals("未命名媒体", page.items.single().name)
        assertEquals("", page.items.single().overview)
        assertNull(page.items.single().imageTag)
        assertNull(page.items.single().primaryImageItemId)
        assertNull(page.items.single().backdropTag)
        assertNull(page.items.single().backdropItemId)
        assertNull(page.items.single().communityRating)
        assertNull(page.items.single().officialRating)
        assertTrue(page.items.single().genres.isEmpty())
        assertNull(page.items.single().childCount)
        assertEquals(1, page.totalCount)
    }

    @Test fun mapsImageSeriesProgressAnd64BitTime() {
        val item = JsonMapping.mediaItem(JSONObject("""{"Id":"episode","Name":"Episode 1","Type":"Episode","RunTimeTicks":90000000000,"ProductionYear":2026,"SeriesName":"Series","SeriesId":"series","IndexNumber":1,"ParentIndexNumber":2,"ImageTags":{"Primary":"tag"},"UserData":{"PlaybackPositionTicks":30000000000}}"""))
        assertEquals(90_000_000_000L, item.runTimeTicks)
        assertEquals(30_000_000_000L, item.positionTicks)
        assertEquals("tag", item.imageTag)
        assertEquals("episode", item.primaryImageItemId)
        assertEquals(2, item.parentIndexNumber)
    }

    @Test fun personBiographyKeepsTheServerNamesDatesAndBirthplaces() {
        val person = JsonMapping.mediaItem(JSONObject("""{
            "Id":"person","Type":"Person","Name":"合成人物","OriginalTitle":"Synthetic Person",
            "PremiereDate":"1976-02-25T00:00:00Z","EndDate":"2020-01-02T00:00:00Z",
            "ProductionLocations":["USA, Los Angeles"," Los Angeles, California ","USA, Los Angeles",null,""]
        }"""))
        assertEquals("Synthetic Person", person.originalTitle)
        assertEquals("1976-02-25T00:00:00Z", person.premiereDate)
        assertEquals("2020-01-02T00:00:00Z", person.endDate)
        assertEquals(listOf("USA, Los Angeles", "Los Angeles, California"), person.productionLocations)

        val unknown = JsonMapping.mediaItem(JSONObject("""{"Id":"unknown","Type":"Person","OriginalTitle":null,"EndDate":null}"""))
        assertNull(unknown.originalTitle)
        assertNull(unknown.premiereDate)
        assertNull(unknown.endDate)
        assertTrue(unknown.productionLocations.isEmpty())
    }

    @Test fun primaryArtworkPrefersOwnThenPairedParentThenPairedSeries() {
        val json = JSONObject("""{
            "Id":"episode","Type":"Episode","ImageTags":{"Primary":"own-poster"},
            "ParentPrimaryImageItemId":"season","ParentPrimaryImageTag":"parent-poster",
            "SeriesId":"series","SeriesPrimaryImageTag":"series-poster"
        }""")
        val own = JsonMapping.mediaItem(json)
        assertEquals("own-poster", own.imageTag)
        assertEquals("episode", own.primaryImageItemId)

        json.put("ImageTags", JSONObject().put("Primary", " "))
        val inherited = JsonMapping.mediaItem(json)
        assertEquals("parent-poster", inherited.imageTag)
        assertEquals("season", inherited.primaryImageItemId)

        json.remove("ParentPrimaryImageItemId")
        val series = JsonMapping.mediaItem(json)
        assertEquals("series-poster", series.imageTag)
        assertEquals("series", series.primaryImageItemId)
    }

    @Test fun primaryArtworkDoesNotGuessMissingIdsOrTags() {
        val incomplete = listOf(
            """{"ParentPrimaryImageItemId":"parent"}""",
            """{"ParentPrimaryImageTag":"poster"}""",
            """{"ParentPrimaryImageItemId":" ","ParentPrimaryImageTag":"poster"}""",
            """{"ParentPrimaryImageItemId":"parent","ParentPrimaryImageTag":null}""",
            """{"ParentPrimaryImageItemId":"parent","ParentPrimaryImageTag":" "}""",
            """{"ParentPrimaryImageItemId":"parent","ParentPrimaryImageTag":7}""",
            """{"SeriesId":"series"}""",
            """{"SeriesPrimaryImageTag":"poster"}""",
            """{"SeriesId":"","SeriesPrimaryImageTag":"poster"}""",
            """{"SeriesId":"series","SeriesPrimaryImageTag":" "}""",
            """{"SeriesId":"series","SeriesPrimaryImageTag":null}""",
        )
        for (fields in incomplete) {
            val item = JsonMapping.mediaItem(JSONObject(fields).put("Id", "episode").put("Type", "Episode"))
            assertNull(item.imageTag)
            assertNull(item.primaryImageItemId)
        }
    }

    @Test fun mapsCollectionTypeForKnownAndUnknownLibraries() {
        for (collectionType in listOf("tvshows", "movies", "unknown")) {
            val item = JsonMapping.mediaItem(JSONObject()
                .put("Id", "library")
                .put("Type", "CollectionFolder")
                .put("CollectionType", collectionType))
            assertEquals(collectionType, item.collectionType)
            assertEquals("CollectionFolder", item.type)
        }
        val boxSet = JsonMapping.mediaItem(JSONObject("""{"Id":"collection","Type":"BoxSet"}"""))
        assertEquals("BoxSet", boxSet.type)
        assertNull(boxSet.collectionType)
    }

    @Test fun missingNullBlankAndInvalidCollectionTypesRemainAbsent() {
        assertNull(MediaItem("library", "Library", "CollectionFolder").collectionType)
        assertNull(JsonMapping.mediaItem(JSONObject("""{"Id":"library"}""")).collectionType)
        for (value in listOf(JSONObject.NULL, "", " ", 7, true)) {
            val item = JsonMapping.mediaItem(JSONObject().put("Id", "library").put("CollectionType", value))
            assertNull(item.collectionType)
        }
    }

    @Test fun mapsPlaybackWithoutAssumingMediaStreamsOrContainer() {
        val info = JsonMapping.playbackInfo(JSONObject("""{"PlaySessionId":"play","MediaSources":[{"Id":"source","Path":"https://cdn.example/movie?x=1&x=2","Protocol":"Http","RequiredHttpHeaders":{"User-Agent":"Player","Missing":null},"SupportsDirectPlay":true}]}"""))
        assertEquals("play", info.playSessionId)
        assertEquals(1, info.sources.size)
        assertNull(info.sources.single().container)
        assertEquals(mapOf("User-Agent" to "Player"), info.sources.single().requiredHttpHeaders)
        assertTrue(info.sources.single().supportsDirectPlay)
    }

    @Test fun mapsMediaFluxPlaybackWithoutChangingTheProxyPathOrProtocol() {
        val url = "https://mf.example/playgy/account/share/file/movie.mkv?v=1&sig=${"a".repeat(64)}&name=a%2Fb&name=two"
        val json = JSONObject().put("Id", "source").put("Path", "/proxy/movie.strm").put("Protocol", "File")
            .put("MediaFluxPlayback", JSONObject().put("protocol", "mediaflux.playback.v1").put("source_url", url))
        val payload = JSONObject().put("Id", "item").put("MediaSources", org.json.JSONArray().put(json))
        val source = JsonMapping.playbackInfo(payload).sources.single()

        assertEquals(url, source.cloudPlaybackUrl)
        assertEquals("/proxy/movie.strm", source.path)
        assertEquals("File", source.protocol)
        assertEquals(source, JsonMapping.mediaItem(payload).mediaSources.single())
    }

    @Test fun malformedOrUnknownMediaFluxExtensionsLeaveNormalPlaybackMappingIntact() {
        val url = "https://mf.example/playgy/account/share/file/movie.mkv?v=1&sig=${"a".repeat(64)}"
        val valid = JSONObject().put("protocol", "mediaflux.playback.v1").put("source_url", url)
        val invalid = listOf(JSONObject.NULL, "mediaflux.playback.v1", org.json.JSONArray(),
            JSONObject().put("source_url", url), JSONObject(valid.toString()).put("protocol", "mediaflux.playback.v2"),
            JSONObject(valid.toString()).put("protocol", "MEDIAFLUX.PLAYBACK.V1")) +
            listOf(JSONObject.NULL, "", " ", 7, true).map { JSONObject(valid.toString()).put("source_url", it) }
        for (extension in listOf(null) + invalid) {
            val json = JSONObject().put("Id", "source").put("Path", "/local/movie.mkv").put("Protocol", "File")
                .put("MediaFluxPlayback", extension)
            val source = JsonMapping.mediaSources(org.json.JSONArray().put(json)).single()
            assertNull(source.cloudPlaybackUrl)
            assertEquals("/local/movie.mkv", source.path)
            assertEquals("File", source.protocol)
        }
    }

    @Test fun mapsOwnFirstBackdropAndRealMetadataWithoutLosingDecimalRating() {
        val item = JsonMapping.mediaItem(JSONObject("""{
            "Id":"series","Type":"Series","BackdropImageTags":["own-first","own-second"],
            "ParentBackdropItemId":"parent","ParentBackdropImageTags":["parent-tag"],
            "CommunityRating":8.7,"OfficialRating":"TV-MA","Genres":["Drama","Science Fiction"],"ChildCount":3
        }"""))
        assertEquals("own-first", item.backdropTag)
        assertEquals("series", item.backdropItemId)
        assertEquals(8.7, item.communityRating!!, 0.0001)
        assertEquals("TV-MA", item.officialRating)
        assertEquals(listOf("Drama", "Science Fiction"), item.genres)
        assertEquals(3, item.childCount)
    }

    @Test fun inheritsParentBackdropOnlyWhenItsIdAndFirstTagAreBothPresent() {
        val item = JsonMapping.mediaItem(JSONObject("""{
            "Id":"episode","Type":"Episode","BackdropImageTags":[],
            "ParentBackdropItemId":"series","ParentBackdropImageTags":["series-first","series-second"]
        }"""))
        assertEquals("series-first", item.backdropTag)
        assertEquals("series", item.backdropItemId)

        val missingParentId = JsonMapping.mediaItem(JSONObject("""{"Id":"episode","ParentBackdropImageTags":["series-first"]}"""))
        assertNull(missingParentId.backdropTag)
        assertNull(missingParentId.backdropItemId)

        val missingFirstTag = JsonMapping.mediaItem(JSONObject("""{
            "Id":"episode","BackdropImageTags":[null,"own-second"],
            "ParentBackdropItemId":"series","ParentBackdropImageTags":["","series-second"]
        }"""))
        assertNull(missingFirstTag.backdropTag)
        assertNull(missingFirstTag.backdropItemId)
    }

    @Test fun optionalMetadataRejectsInvalidValuesAndPreservesZeroCounts() {
        val item = JsonMapping.mediaItem(JSONObject("""{
            "Id":"film","CommunityRating":"NaN","OfficialRating":" ",
            "Genres":[null,7,"", "Drama"],"ChildCount":-1
        }"""))
        assertNull(item.communityRating)
        assertNull(item.officialRating)
        assertEquals(listOf("Drama"), item.genres)
        assertNull(item.childCount)
        assertEquals(0, JsonMapping.mediaItem(JSONObject("""{"Id":"empty","ChildCount":0}""")).childCount)
        assertNull(JsonMapping.mediaItem(JSONObject("""{"Id":"fraction","ChildCount":2.5}""")).childCount)
        assertNull(JsonMapping.mediaItem(JSONObject("""{"Id":"overflow","ChildCount":2147483648}""")).childCount)
    }

    @Test fun authCannotCreateSessionFromMissingTokenOrUser() {
        assertThrows(ApiException::class.java) { JsonMapping.session(JSONObject("""{"User":{"Id":"user"}}"""), ServerConfig("https://media.example"), "device") }
        val session = JsonMapping.session(JSONObject("""{"AccessToken":"secret","User":{"Id":"user","Name":"Alice"},"ServerId":"server"}"""), ServerConfig("https://media.example"), "device")
        assertEquals("Alice", session.userName)
        assertEquals("server", session.serverId)
    }

    @Test fun sessionMapsCurrentAvatarAndStrictUserPolicy() {
        val session = JsonMapping.session(JSONObject("""{
            "AccessToken":"secret","User":{"Id":"user","Name":"Alice","PrimaryImageTag":"avatar",
            "Policy":{"IsAdministrator":true,"EnableContentDownloading":true,"EnableContentDeletion":false,"EnableContentDeletionFromFolders":["folder"]}}
        }"""), ServerConfig("https://media.example"), "device")
        assertEquals("avatar", session.userImageTag)
        assertTrue(session.isAdministrator)
        assertTrue(session.canDownload)
        assertTrue(session.canDelete)

        val changed = JsonMapping.sessionUser(session, JSONObject("""{"Id":"user","Name":"Changed","Policy":{}}"""))
        assertEquals("Changed", changed.userName)
        assertNull(changed.userImageTag)
        assertFalse(changed.isAdministrator)
        assertFalse(changed.canDownload)
        assertFalse(changed.canDelete)
    }

    @Test fun missingMalformedOrDisabledPolicyCannotGrantManagementPermissions() {
        for (policy in listOf("null", "{}", """{"IsAdministrator":"true","EnableContentDownloading":1,"EnableContentDeletion":"true","EnableContentDeletionFromFolders":[null,"",7]}""",
            """{"IsDisabled":true,"IsAdministrator":true,"EnableContentDownloading":true,"EnableContentDeletion":true}""")) {
            val session = JsonMapping.session(JSONObject("""{"AccessToken":"secret","User":{"Id":"user","PrimaryImageTag":" ","Policy":$policy}}"""),
                ServerConfig("https://media.example"), "device")
            assertNull(session.userImageTag)
            assertFalse(session.isAdministrator)
            assertFalse(session.canDownload)
            assertFalse(session.canDelete)
        }
    }

    @Test fun itemManagementCapabilitiesRemainNullableUntilReturnedAsBooleans() {
        val item = JsonMapping.mediaItem(JSONObject("""{"Id":"film","CanDownload":true,"CanDelete":false}"""))
        assertEquals(true, item.canDownload)
        assertEquals(false, item.canDelete)
        val malformed = JsonMapping.mediaItem(JSONObject("""{"Id":"film","CanDownload":"true","CanDelete":1}"""))
        assertNull(malformed.canDownload)
        assertNull(malformed.canDelete)
        assertNull(JsonMapping.mediaItem(JSONObject("""{"Id":"film"}""")).canDownload)
    }

    @Test fun mapsRealUserDataPeopleStudiosExternalLinksAndInheritedArtwork() {
        val item = JsonMapping.mediaItem(JSONObject("""{
            "Id":"episode","Name":"Episode","Type":"Episode","SeasonId":"season","CriticRating":91,"PremiereDate":"2026-01-01T00:00:00Z",
            "ImageTags":{"Primary":"poster","Banner":"banner"},
            "ParentLogoItemId":"series","ParentLogoImageTag":"logo",
            "ParentBackdropItemId":"series","ParentBackdropImageTags":["backdrop0","backdrop1"],
            "People":[{"Id":"person","Name":"Actor","Role":"Lead","Type":"Actor","PrimaryImageTag":"head"},{}],
            "Studios":[{"Id":"studio","Name":"Studio"},{}],
            "ExternalUrls":[{"Name":"Official","Url":"https://example.com/title"},{"Name":"Unsafe","Url":"javascript:alert(1)"},{"Name":"Credentials","Url":"https://user:password@example.com/"}],
            "Tags":["4K"],"Status":"Continuing",
            "UserData":{"IsFavorite":true,"Played":false,"PlaybackPositionTicks":50000000,"PlayedPercentage":12.5,"UnplayedItemCount":3,"PlayCount":2}
        }"""))
        assertTrue(item.isFavorite)
        assertFalse(item.played)
        assertEquals(50_000_000L, item.positionTicks)
        assertEquals(12.5, item.playedPercentage!!, 0.0001)
        assertEquals(3, item.unplayedItemCount)
        assertEquals(2, item.playCount)
        assertEquals("banner", item.bannerTag)
        assertEquals("logo", item.logoTag)
        assertEquals("series", item.logoItemId)
        assertEquals(listOf("backdrop0", "backdrop1"), item.backdropTags)
        assertEquals("person", item.people.single().id)
        assertEquals("Lead", item.people.single().role)
        assertEquals("head", item.people.single().imageTag)
        assertEquals("studio", item.studios.single().id)
        assertEquals("https://example.com/title", item.externalUrls.single().url)
        assertEquals(listOf("4K"), item.tags)
        assertEquals("Continuing", item.status)
        assertEquals("season", item.seasonId)
        assertEquals(91.0, item.criticRating!!, 0.0001)
    }

    @Test fun sparseMutationDataPreservesUnreturnedStateButAppliesFalseAndZero() {
        val item = MediaItem("film", "Movie", "Movie", positionTicks = 800L, isFavorite = true, played = true, playCount = 2)
        assertEquals(item, MediaUserData().applyTo(item))
        val changed = JsonMapping.userData(JSONObject("""{"IsFavorite":false,"Played":false,"PlaybackPositionTicks":0,"PlayCount":0}""")).applyTo(item)
        assertFalse(changed.isFavorite)
        assertFalse(changed.played)
        assertEquals(0L, changed.positionTicks)
        assertEquals(0, changed.playCount)
    }

    @Test fun userDataRejectsMalformedBooleansCountsAndPercentages() {
        val data = JsonMapping.userData(JSONObject("""{"IsFavorite":"true","Played":1,"PlayCount":2.5,"UnplayedItemCount":-1,"PlayedPercentage":150}"""))
        assertNull(data.isFavorite)
        assertNull(data.played)
        assertNull(data.playCount)
        assertNull(data.unplayedItemCount)
        assertNull(data.playedPercentage)
    }
}
