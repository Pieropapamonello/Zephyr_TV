package dev.piume

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbLocalizedTest {
    // As TMDB's /find answers for an IMDb id, in Italian.
    @Test fun findsAFilmInTheTvsLanguage() {
        val l = TmdbClient.localized(JSONObject("""{"movie_results":[{"id":10378,"title":"Big Buck Bunny","overview":"Un coniglio gigante.",
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg","genre_ids":[16,35],"release_date":"2008-05-30"}],"tv_results":[]}"""),
            mapOf(16 to "Animazione", 35 to "Commedia"))!!
        assertEquals(10378L, l.tmdbId)
        assertEquals("Un coniglio gigante.", l.overview)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", l.poster)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", l.backdrop)
        assertEquals(listOf("Animazione", "Commedia"), l.genres)
        assertEquals("2008", l.year)
    }

    @Test fun findsASeriesAndToleratesMissingArt() {
        val l = TmdbClient.localized(JSONObject("""{"movie_results":[],"tv_results":[{"id":1418,"name":"The Big Bang Theory",
            "overview":"","poster_path":null,"first_air_date":"2007-09-24"}]}"""), emptyMap())!!
        assertTrue(l.tv)
        assertEquals("", l.overview) // none in that language: Cinemeta's stays
        assertNull(l.poster)
        assertNull(l.backdrop)
        assertNull(TmdbClient.localized(JSONObject("""{"movie_results":[],"tv_results":[]}"""), emptyMap()))
    }

    @Test fun readsASeasonsEpisodes() {
        val eps = TmdbClient.seasonEpisodes(JSONObject("""{"episodes":[
            {"episode_number":1,"name":"Episodio pilota","overview":"Leonard e Sheldon.","still_path":"/s1.jpg"},
            {"episode_number":2,"name":"","overview":"","still_path":null}]}"""))
        assertEquals("Episodio pilota", eps[1]!!.name)
        assertEquals("https://image.tmdb.org/t/p/w300/s1.jpg", eps[1]!!.still)
        assertNull(eps[2]!!.still)
    }
}
