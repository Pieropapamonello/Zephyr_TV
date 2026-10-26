package dev.piume

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktTest {
    @Test fun watchedFilmsAndEpisodesSinceARewatch() {
        val movies = JSONArray("""[{"plays":1,"movie":{"title":"Batman Begins","ids":{"trakt":1,"imdb":"tt0372784"}}},
            {"plays":1,"movie":{"title":"No IMDb","ids":{"trakt":2,"imdb":null}}}]""")
        val shows = JSONArray("""[{"reset_at":"2024-01-01T00:00:00.000Z","show":{"ids":{"imdb":"tt0903747"}},"seasons":[{"number":1,"episodes":[
            {"number":1,"last_watched_at":"2023-05-01T00:00:00.000Z"},{"number":2,"last_watched_at":"2024-02-01T00:00:00.000Z"}]}]}]""")
        assertEquals(listOf("tt0372784" to "tt0372784", "tt0903747:1:2" to "tt0903747"), Trakt.parseWatched(movies, shows))
    }

    @Test fun pausedFilmsAndEpisodes() {
        val list = JSONArray("""[{"progress":10.0,"paused_at":"2015-01-25T22:01:32.000Z","type":"movie",
            "movie":{"title":"Batman Begins","runtime":140,"ids":{"imdb":"tt0372784"}}},
            {"progress":65.5,"paused_at":"2015-01-25T22:01:32.000Z","type":"episode",
            "episode":{"season":1,"number":3,"title":"…And the Bag's in the River","runtime":48,"ids":{"imdb":""}},
            "show":{"title":"Breaking Bad","ids":{"imdb":"tt0903747"}}}]""")
        val p = Trakt.parsePaused(list)
        assertEquals(listOf("tt0372784", "tt0903747:1:3"), p.map { it.id })
        assertEquals("tt0903747", p[1].metaId)
        assertEquals(48, p[1].runtimeMin)
        assertEquals(1422223292000L, p[0].at)
    }
}
