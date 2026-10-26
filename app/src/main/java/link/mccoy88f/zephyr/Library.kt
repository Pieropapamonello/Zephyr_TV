package link.mccoy88f.zephyr

// Finding films and series: the search (words, filters, or both) in Cinemeta and the add-ons' catalogs, with TMDB
// for names in the TV's language, and Spotlight's titles. Functions of MainActivity; see Titles for the rows.

/** The films and series the add-ons' searchable catalogs find for [query] (Cinemeta first), each once. Off the UI thread. */
internal fun MainActivity.findTitles(query: String): List<Pair<Stremio.Title, String>> {
    val asked = vodAddons().flatMap { a -> a.vod.filter { it.search }.map { c -> a to c } }
    if (asked.isEmpty()) return emptyList()
    return parallel(asked, 4, 15) { (a, c) -> Stremio.search(a, c, query).map { it to a.url } }.flatten().distinctBy { it.first.type + it.first.id }
}

/**
 * The films and series for a search of [query], each once: with a TMDB key, those TMDB finds first (it knows their
 * names in the TV's language: "Il padrino" finds The Godfather), as Cinemeta's titles by their IMDb ids (those without
 * one left out); then those the add-ons' searchable catalogs find (Cinemeta's first). In the TV's language, as the
 * Films and Series rows are. Off the UI thread.
 */
internal fun MainActivity.searchLibrary(query: String): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    val viaTmdb = if (key == null) emptyList() else {
        parallel(TmdbClient.search(key, query, tmdbLang()), 6, 15) { item ->
            val imdb = TmdbClient.imdbIdOf(key, item) ?: return@parallel null
            Stremio.Title(if (item.mediaType == "tv") "series" else "movie", imdb, item.title,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, TmdbClient.cardUrl(item.backdropPath), null,
                item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres,
                item.voteAverage.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }, item.overview, item.id) to cinemetaUrl()
        }
    }
    return localizeAll((viaTmdb + findTitles(query)).distinctBy { it.first.type + it.first.id })
}

/** [all] in the TV's language, as the Films and Series rows are (TMDB asked about those it wasn't yet). Off the UI thread. */
private fun MainActivity.localizeAll(all: List<Pair<Stremio.Title, String>>): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = all.map { it.first }.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    return all.map { (t, source) -> (localize(t) ?: t) to source }
}

/**
 * Search filters: films, series or both ([type] "movie", "series", "all"), a [year] and a [genre] (Cinemeta's
 * names, English: "Sci-Fi"), each null for any.
 */
internal class SearchFilter(val type: String = "all", val year: String? = null, val genre: String? = null) {
    val any get() = type != "all" || year != null || genre != null

    /** Whether [t] passes, its year and genres as Cinemeta has them (a series' years "2008–2013" span), or [local] names. */
    fun accepts(t: Stremio.Title, local: (String) -> String = { it }): Boolean {
        if (type != "all" && t.type != type) return false
        if (year != null && !spans(t.year, year.toInt())) return false
        return genre == null || t.genres.any { it.equals(genre, ignoreCase = true) || it.equals(local(genre), ignoreCase = true) }
    }

    private fun spans(years: String?, y: Int): Boolean {
        val from = years?.take(4)?.toIntOrNull() ?: return false
        if (y == from) return true
        val rest = years.substringAfter('–', "").takeIf { '–' in years } ?: return false
        val to = rest.take(4).toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) // running: to this year
        return y in from..to
    }
}

/**
 * The films and series for [filter] without words: Cinemeta's catalogs by year ("New", filtered by genre here) or by
 * genre ("Popular"), else its popular ones; two pages of each type. In the TV's language. Off the UI thread.
 */
internal fun MainActivity.browseLibrary(filter: SearchFilter): List<Pair<Stremio.Title, String>> {
    val url = cinemetaUrl()
    val base = url.removeSuffix("/manifest.json")
    val kinds = if (filter.type == "all") listOf("movie", "series") else listOf(filter.type)
    val catalog = if (filter.year != null) "year/genre=${filter.year}" else filter.genre?.let { "top/genre=${Stremio.enc(it)}" } ?: "top"
    val found = parallel(kinds.flatMap { kind -> (0 until 2).map { page -> kind to page } }, 4, 20) { (kind, page) ->
        val skip = if (page == 0) "" else (if ('=' in catalog) "&" else "/") + "skip=${page * 50}"
        Stremio.titles(Http.json("$base/catalog/$kind/$catalog$skip.json"))
    }.flatten()
    return localizeAll(found.filter { filter.accepts(it) }.distinctBy { it.type + it.id }.map { it to url })
}

/** Cinemeta's genres, for the search filter (its films' and series' catalogs' options), as its manifest lists them. */
internal fun MainActivity.libraryGenres(): List<String> =
    Stremio.saved(filesDir, cinemetaUrl())?.addon?.vod.orEmpty().filter { it.id == "top" }.flatMap { it.genres }.distinct()
        .ifEmpty { GENRES.keys.toList() }

// ---------- Spotlight ----------

/**
 * Spotlight's titles from Cinemeta, which needs no key: its popular films, series or both ([type]: "movie", "tv",
 * "all", taken in turn), those with a background. With a TMDB key, in the TV's language as the Films and Series rows
 * are (see localize), with their TMDB ids for the services; their background stays Cinemeta's, the largest.
 * Empty when Cinemeta can't be asked. Network: off the UI thread.
 */
internal fun MainActivity.spotlightTitles(type: String): List<TmdbItem> {
    val url = cinemetaUrl()
    val addon = Stremio.saved(filesDir, url)?.addon
    val kinds = when (type) { "movie" -> listOf("movie"); "tv" -> listOf("series"); else -> listOf("movie", "series") }
    val lists = kinds.map { k ->
        val catalog = addon?.vod?.firstOrNull { it.type == k && it.listed }?.id ?: "top"
        runCatching { Stremio.titles(Http.json("${url.removeSuffix("/manifest.json")}/catalog/$k/${Stremio.enc(catalog)}.json")) }
            .onFailure { warn("Cinemeta for Spotlight failed ($it)") }.getOrDefault(emptyList())
    }
    val titles = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        .filter { it.background != null }.distinctBy { it.id }.take(40)
    val key = tmdbKey()
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = titles.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    return titles.map { t ->
        val local = localize(t)
        val l = if (key != null && t.id.startsWith("tt")) Local.get(t.id) else null
        val name = local?.name ?: t.name
        TmdbItem(l?.tmdbId ?: t.tmdbId ?: 0, name, l?.overview?.ifBlank { null } ?: t.description.orEmpty(),
            t.background?.replace("/background/medium/", "/background/large/"), t.rating?.toDoubleOrNull() ?: l?.vote ?: 0.0,
            if (t.type == "series") "tv" else "movie", local?.genres ?: t.genres, t.year, imdbId = t.id)
            .also { if (it.id > 0) TmdbClient.knowImdbId(it, t.id) }
    }
}
