package link.mccoy88f.zephyr

import android.content.Intent
import link.mccoy88f.zephyr.MainActivity.Prog

// Films and series from the Stremio add-ons on the home screen: the Films and Series tiles (or each add-on's own),
// their rows and cards, and films and episodes begun in Continue Playing. A title's page is in TitlePages, the
// search and Spotlight's titles in Library, the TV's language in Localize. Functions of MainActivity, like the
// other topics; the add-ons themselves are read by Stremio.

/** App.pkg of the Films and Series tiles (see addonsVod), and of the Continue Playing cards of films and episodes. */
internal const val MOVIES_PKG = STREMIO + "movies"
internal const val SERIES_PKG = STREMIO + "series"
private const val VOD_PKG = STREMIO + "vod"

/**
 * A film or series a card stands for: what to ask its page for ([type], [id]), its name and art (as the card shows
 * it, for its page and the player), the add-on it came from ([source]: its manifest's address, asked for the page
 * first), and, on a Continue Playing card, the film or episode to go on with ([video]).
 */
internal class TitleRef(val type: String, val id: String, val name: String, val poster: String?, val background: String?,
                        val source: String? = null, val video: String? = null, val logo: String? = null, val rating: String? = null)

/** Cinemeta's manifest: Stremio's catalog of films and series, always the library's first; its address can be changed. */
internal fun MainActivity.cinemetaUrl(): String = prefs.getString(K.CINEMETA_URL, null)?.let { Stremio.manifestUrl(it) } ?: Stremio.CINEMETA

/** Where films and series show: a Films tile and a Series tile, each add-on's own tile, or nowhere. */
internal fun MainActivity.addonsVod(): VodPlace = prefs.getString(K.ADDONS_VOD, null).let { k -> VodPlace.entries.firstOrNull { it.key == k } } ?: VodPlace.TILES

/** The add-ons films and series are listed from (Cinemeta first), as manifest addresses. */
internal fun MainActivity.libraryUrls(): List<String> = (listOf(cinemetaUrl()) + addonUrls()).distinct()

/** The add-ons as read, those with film or series catalogs or streams. Reads them when they're missing (in the background). */
internal fun MainActivity.vodAddons(): List<Stremio.Addon> = libraryUrls().mapNotNull { url ->
    Stremio.saved(filesDir, url)?.addon ?: null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
}

/** The add-ons that give streams for a film or episode of [type] with this [id] (address without /manifest.json, and name). */
internal fun MainActivity.streamSources(type: String, id: String): List<Pair<String, String>> =
    addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.provides("stream", type, id) }.map { it.base to it.name }

/** The tiles films and series add to the apps row: Films and Series, or nothing here (each add-on's own, see addonApps). */
internal fun MainActivity.vodApps(): List<MainActivity.App> {
    if (addonsVod() != VodPlace.TILES || libraryUrls().isEmpty()) return emptyList()
    return listOf(
        MainActivity.App(MOVIES_PKG, getString(R.string.vod_movies), Intent(), getDrawable(R.drawable.ic_movie)!!, picture = getDrawable(R.drawable.tile_films)),
        MainActivity.App(SERIES_PKG, getString(R.string.vod_series), Intent(), getDrawable(R.drawable.ic_series)!!, picture = getDrawable(R.drawable.tile_series)),
    )
}

/** The add-ons with film or series catalogs that get a tile of their own (films and series "apps"). */
internal fun MainActivity.vodTileAddons(): List<Stremio.Addon> =
    if (addonsVod() != VodPlace.APPS) emptyList() else vodAddons().filter { a -> a.vod.any { it.listed } }

/**
 * The rows of films or series under the tile [pkg]: under Films or Series, every add-on's catalogs of that type
 * ("Cinemeta · Popular"); under an add-on's own tile, its catalogs of both. A catalog read a few hours ago is read
 * again in the background, and the rows built again once it's in. Runs off the UI thread.
 */
internal fun MainActivity.titleSections(pkg: String, hidden: Set<String>): List<Section> {
    val mode = addonsVod()
    val type = when (pkg) { MOVIES_PKG -> "movie"; SERIES_PKG -> "series"; else -> null }
    val addons = when {
        mode == VodPlace.TILES && type != null -> vodAddons()
        mode == VodPlace.APPS && pkg.startsWith(STREMIO) -> vodAddons().filter { STREMIO + it.id == pkg }
        else -> return emptyList()
    }
    val missing = ArrayList<String>()
    val watched = VodWatched.all(vodDir())
    return addons.flatMap { addon ->
        if (Stremio.vodDue(filesDir, addon.url) && addon.vod.any { it.listed }) refreshShelves(addon, pkg)
        Stremio.shelves(filesDir, addon).filter { type == null || it.catalog.type == type }.mapNotNull { shelf ->
            val kind = getString(if (shelf.catalog.type == "series") R.string.vod_series else R.string.vod_movies)
            val name = if (type != null) "${addon.name} · ${shelf.catalog.name}" else "$kind · ${shelf.catalog.name}"
            // In the TV's language when TMDB has the title (those it hasn't been asked about yet: once it's answered).
            val cards = shelf.titles.map { t -> localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id } }
                .filter { hideKey(null, it.name) !in hidden }.map { titleCard(it, addon.url, pkg, it.id in watched) }
            if (cards.isEmpty()) null else Section("vod:${addon.id}:${shelf.catalog.type}:${shelf.catalog.id}", name, name, cards)
        }
    }.plus(
        // Last, the films and series watched: under Films and Series, or Cinemeta's own tile.
        if (mode == VodPlace.TILES || addons.any { it.url == cinemetaUrl() }) watchedSections(type, pkg, hidden, missing) else emptyList()
    ).also { if (missing.isNotEmpty()) localizeLater(missing.distinct(), pkg) }
}

/**
 * The Films watched and Series watched rows (films only for [type] "movie", series only for "series"), newest
 * first, a series by its last episode watched: Cinemeta's art for each (by its IMDb id), in the TV's language when
 * TMDB has it ([missing] gets those it wasn't asked about yet), when it was watched under it. Names missing from old
 * marks are looked up on Cinemeta, and the rows built again once they're in. Off the UI thread.
 */
private fun MainActivity.watchedSections(type: String?, pkg: String, hidden: Set<String>, missing: MutableList<String>): List<Section> {
    val (films, shows) = VodWatched.titles(vodDir())
    val nameless = (films.map { it to "movie" } + shows.map { it to "series" }).filter { it.first.name.isEmpty() }
    if (nameless.isNotEmpty()) nameLater(nameless, pkg)
    fun section(kind: String, title: Int, marks: List<VodWatched.Mark>): Section? {
        val cards = marks.filter { it.name.isNotEmpty() && it.metaId.startsWith("tt") }.take(40).map { m ->
            val t = Stremio.Title(kind, m.metaId, m.name, Stremio.art(m.metaId, "poster/small"), Stremio.art(m.metaId, "background/medium"),
                Stremio.art(m.metaId, "logo/medium"), null)
            val shown = localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id }
            titleCard(shown, cinemetaUrl(), pkg, watched = true, sub = listOfNotNull("✓", watchedOn(m.at)).joinToString(" · "))
        }.filter { hideKey(null, it.title) !in hidden }
        val name = getString(title)
        return if (cards.isEmpty()) null else Section("vod:watched:$kind", name, name, cards)
    }
    return listOfNotNull(
        if (type == "series") null else section("movie", R.string.vod_watched_movies, films),
        if (type == "movie") null else section("series", R.string.vod_watched_series, shows),
    )
}

private val naming = java.util.Collections.synchronizedSet(HashSet<String>()) // asked once while Zephyr TV runs

/** The names of [marks] (with their kind) from Cinemeta, kept with them; then the rows under [pkg] again, if it's still in focus. */
private fun MainActivity.nameLater(marks: List<Pair<VodWatched.Mark, String>>, pkg: String) {
    val todo = marks.filter { naming.add(it.first.metaId) }.take(40)
    if (todo.isEmpty()) return
    val dir = vodDir()
    val base = cinemetaUrl().removeSuffix("/manifest.json")
    netIo.execute {
        val names = parallel(todo, 6, 20) { (m, kind) -> Stremio.titleMeta(base, kind, m.metaId)?.title?.name?.let { m.metaId to it } }.toMap()
        runCatching { VodWatched.name(dir, names) }
        if (names.isNotEmpty()) ui.post { rowsAgain(pkg) }
    }
}

private val refreshing = java.util.Collections.synchronizedSet(HashSet<String>())

/** Reads [addon]'s film and series catalogs again; once they're in, the rows under [pkg] again if it's still in focus. */
private fun MainActivity.refreshShelves(addon: Stremio.Addon, pkg: String) {
    if (!refreshing.add(addon.url)) return
    netIo.execute {
        val ok = runCatching { Stremio.updateShelves(filesDir, addon) }.onFailure { warn("Stremio catalogs failed: ${android.net.Uri.parse(addon.url).host} ($it)") }.isSuccess
        refreshing.remove(addon.url)
        if (ok) ui.post { rowsAgain(pkg) }
    }
}

/**
 * A film's or series' card: its wide art (or its poster on a blur of itself), under it its year, genre and rating,
 * marked with the icon of the tile [icon] it's under (Films, Series, or the add-on's own), and ✓ once [watched]; or [sub].
 */
internal fun titleCard(t: Stremio.Title, source: String?, icon: String?, watched: Boolean = false, sub: String? = null) =
    Prog(t.name, sub ?: listOfNotNull("✓".takeIf { watched }, t.year, t.genres.firstOrNull(), t.rating?.let { "★ $it" }).joinToString(" · "), 0, t.background, null, null,
        logo = t.logo, poster = t.poster, icon = icon, vod = TitleRef(t.type, t.id, t.name, t.poster, t.background, source, logo = t.logo, rating = t.rating))

/** The tile a title of [type] from the add-on at [source] is under: Films or Series, or the add-on's own (null: none). */
internal fun MainActivity.titleTile(type: String, source: String?): String? = when (addonsVod()) {
    VodPlace.TILES -> if (type == "series") SERIES_PKG else MOVIES_PKG
    VodPlace.APPS -> source?.let { Stremio.saved(filesDir, it)?.addon }?.let { STREMIO + it.id }
    VodPlace.OFF -> null
}

// ---------- Continue Playing ----------

/** The films and episodes begun, as Continue Playing cards (with the time they were left, for its order). Off the UI thread. */
internal fun MainActivity.titleProgressCards(hidden: Set<String>): List<Pair<Long, Prog>> =
    // One card a series: its newest episode, begun or waiting next.
    VodProgress.all(vodDir()).filter { hideKey(VOD_PKG, it.name) !in hidden }.distinctBy { it.metaId }.take(10).map { e ->
        val left = ((e.duration - e.position) / 60_000).coerceAtLeast(1)
        val tile = if (addonsVod() == VodPlace.TILES) (if (e.type == "series") SERIES_PKG else MOVIES_PKG) else null
        val state = if (e.upNext) getString(R.string.title_up_next) else getString(R.string.time_left_min, left.toInt())
        e.at to Prog(e.name, listOf(e.sub, state).filter { it.isNotBlank() }.joinToString(" · "),
            e.percent, e.poster, null, VOD_PKG, poster = e.poster, icon = tile,
            vod = TitleRef(e.type, e.metaId, e.name.substringBefore(" · "), null, e.poster, video = e.id))
    }
