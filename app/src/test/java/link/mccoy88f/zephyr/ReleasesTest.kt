package link.mccoy88f.zephyr

import link.mccoy88f.zephyr.Releases.State
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReleasesTest {
    private val today = LocalDate.parse("2026-10-29")

    /** A film as TMDB answers it: [dates] country to (type, day); [services] in IT, by kind. */
    private fun movie(vararg dates: Pair<String, Pair<Int, String>>, services: List<String> = emptyList(), primary: String? = null): JSONObject {
        val results = JSONArray()
        dates.groupBy { it.first }.forEach { (country, list) ->
            results.put(JSONObject().put("iso_3166_1", country).put("release_dates",
                JSONArray(list.map { (_, d) -> JSONObject().put("type", d.first).put("release_date", d.second + "T00:00:00.000Z") })))
        }
        val here = JSONObject().apply { services.forEach { put(it, JSONArray().put(JSONObject().put("provider_id", 8))) } }
        return JSONObject().put("release_date", primary ?: "").put("release_dates", JSONObject().put("results", results))
            .put("watch/providers", JSONObject().put("results", JSONObject().put("IT", here)))
    }

    @Test fun inCinemasHereNotYetAtHome() {
        assertEquals(State.CINEMA, Releases.state(movie("IT" to (3 to "2026-10-01"), "US" to (4 to "2026-10-20")), "IT", today))
    }

    @Test fun outOnceAServiceOrAHomeDateHasIt() {
        assertEquals(State.OUT, Releases.state(movie("IT" to (3 to "2026-10-01"), services = listOf("rent")), "IT", today))
        assertEquals(State.OUT, Releases.state(movie("IT" to (3 to "2026-08-01"), "IT" to (4 to "2026-10-15")), "IT", today))
    }

    @Test fun aHomeDateStillAheadKeepsItInCinemas() {
        assertEquals(State.CINEMA, Releases.state(movie("IT" to (3 to "2026-10-01"), "IT" to (4 to "2026-12-15")), "IT", today))
    }

    @Test fun toComeWhenEveryDateIsAhead() {
        assertEquals(State.COMING, Releases.state(movie("IT" to (3 to "2026-12-25")), "IT", today))
        assertEquals(State.COMING, Releases.state(movie("US" to (3 to "2027-03-01"), primary = "2027-03-01"), "IT", today))
    }

    @Test fun inDoubtItIsOut() {
        // In cinemas long ago, with no date at home: out (the dates at home are often missing).
        assertEquals(State.OUT, Releases.state(movie("IT" to (3 to "2024-01-10")), "IT", today))
        // Nothing for the country, but out elsewhere.
        assertEquals(State.OUT, Releases.state(movie("US" to (3 to "2026-10-10"), primary = "2026-10-10"), "IT", today))
        // Nothing at all.
        assertEquals(State.OUT, Releases.state(movie(), "IT", today))
    }
}
