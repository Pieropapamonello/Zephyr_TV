package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Prog
import link.mccoy88f.zephyr.MainActivity.Companion.ADDONS_TV_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDONS_VOD_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.CINEMETA_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDON_ADD_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDON_ROWS
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU

// Stremio add-ons' TV channels on the home screen: where they show (the TV app, one IPTV app, or an app per add-on),
// their rows, and adding and removing add-ons. Functions of MainActivity, like the other topics; the add-ons
// themselves are read by Stremio.

/** App.pkg prefix of the add-ons' tiles: "stremio:iptv" for the IPTV app, "stremio:<add-on id>" for an add-on's own. */
internal const val STREMIO = "stremio:"
internal const val IPTV_PKG = STREMIO + "iptv"
/** The Continue Playing card of the channel last played: hiding it there keeps the channel in its add-on's rows. */
private const val LAST_PKG = STREMIO + "last"

/**
 * The add-ons' own thread, for building their rows: an IPTV add-on has thousands of channels, and the apps' rows
 * (on MainActivity.io) mustn't wait behind them.
 */
internal val addonIo: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

/** The rows last built under each tile, and what they were built from: built again only when that changes. */
private class Built(val from: String, val sections: List<Section>)
@Volatile private var built: Map<String, Built> = emptyMap()

/** What's on is worked out again this often (the progress under the cards moves on). */
private const val GUIDE_MS = 5 * 60_000L

/** A card that's an add-on's channel: the add-on (its manifest's address) and the channel in it. */
internal class AddonChannel(val addon: String, val item: Stremio.Item)

/**
 * The add-ons' pages of the channels whose cards show, and when each came: what's on with its times, as the player
 * shows it (the catalog says only the title). Asked on a thread of their own, a few at a time.
 */
private val channelPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Stremio.Meta>>()
private val pageIo: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3)

/**
 * What's on [c] as the player shows it, for its card: the add-on's page for the channel, its program and how far
 * in it is. A page from the last few minutes is used while its program is on; else it's asked in the background.
 * [show] hears it on the main thread, only when the page has a program on now.
 */
internal fun MainActivity.addonOnNow(c: AddonChannel, show: (String, Int) -> Unit) {
    val key = "${c.addon}|${c.item.id}"
    fun use(meta: Stremio.Meta): Boolean {
        val progress = Stremio.progress(meta.description) ?: return false
        show(meta.now ?: return false, progress)
        return true
    }
    channelPages[key]?.let { (at, meta) ->
        if (System.currentTimeMillis() - at < GUIDE_MS && (meta.description == null || use(meta))) return
    }
    pageIo.execute {
        val meta = runCatching { Stremio.meta(c.addon.removeSuffix("/manifest.json"), c.item.type, c.item.id) }.getOrNull() ?: return@execute
        channelPages[key] = System.currentTimeMillis() to meta
        ui.post { use(meta) }
    }
}

/** The add-ons' manifest addresses, in the order they were added. */
internal fun MainActivity.addonUrls(): List<String> = prefs.getString(K.ADDONS, "")!!.lines().filter { it.isNotBlank() }

private fun MainActivity.saveAddonUrls(urls: List<String>) = prefs.edit().putString(K.ADDONS, urls.distinct().joinToString("\n")).apply()

/** Whether there's a TV app here: the one Android opens a channel link with (none on most boxes and sticks). */
internal fun MainActivity.hasTvApp(): Boolean = listOf(
    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(1)),
    Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE),
).any { packageManager.queryIntentActivities(it, 0).any { r -> r.activityInfo.packageName != packageName } }

/** Where the add-ons' TV channels show: in the TV app, in one IPTV app, an app each; kept by [key] in the preferences. */
internal enum class TvPlace(val key: String) { TV("tv"), IPTV("iptv"), APPS("apps") }

/** Where the add-ons' films and series show: the Films and Series tiles, an app each, nowhere; kept by [key]. */
internal enum class VodPlace(val key: String) { TILES("tiles"), APPS("apps"), OFF("off") }

/** Where the add-ons' TV channels show, as chosen: in the TV app (if there's one, else the IPTV app), the IPTV app, an app each. */
internal fun MainActivity.addonsTv(): TvPlace {
    val mode = prefs.getString(K.ADDONS_TV, null).let { k -> TvPlace.entries.firstOrNull { it.key == k } } ?: TvPlace.TV
    return if (mode == TvPlace.TV && !hasTvApp()) TvPlace.IPTV else mode
}

/**
 * The tiles the add-ons add to the apps row: for TV channels one IPTV app, or one per add-on with channels, or none
 * (in the TV app); for films and series the Films and Series tiles, or one per add-on with catalogs of them (see
 * Titles). An add-on with both has one tile, with both. Runs on the io thread.
 */
internal fun MainActivity.addonApps(): List<MainActivity.App> {
    val urls = addonUrls()
    fun icon(res: Int) = getDrawable(res)!!
    // Its logo once it's on disk (downloaded with its channels, see updateAddon), else the [res] mark.
    fun tile(addon: Stremio.Addon, res: Int): MainActivity.App {
        val logo = addon.logo?.takeIf { channelLogoFiles.has(it) }?.let { uri ->
            runCatching { BitmapFactory.decodeFile(channelLogoFiles.get(uri) { openStream(it) }.path) }.getOrNull()
        }
        return MainActivity.App(STREMIO + addon.id, addon.name, Intent(), logo?.let { BitmapDrawable(resources, it) } ?: icon(res))
    }
    val tv = if (urls.isEmpty()) emptyList() else when (addonsTv()) {
        TvPlace.IPTV -> listOf(MainActivity.App(IPTV_PKG, getString(R.string.iptv_app), Intent(), icon(R.drawable.ic_live_tv)))
        TvPlace.APPS -> urls.mapNotNull { url ->
            // Not read yet (just restored from a backup): its tile comes once it is.
            val addon = Stremio.saved(filesDir, url)?.addon ?: return@mapNotNull null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
            if (addon.catalogs.isEmpty()) null else tile(addon, R.drawable.ic_live_tv) // no channels, no TV tile
        }
        TvPlace.TV -> emptyList()
    }
    return (tv + vodApps() + vodTileAddons().map { tile(it, R.drawable.ic_movie) }).distinctBy { it.pkg }
}

/** The add-ons whose channels show under the tile [pkg]: all of them under the TV app or the IPTV app, one under its own. */
internal fun MainActivity.addonsUnder(pkg: String): List<String> {
    val urls = addonUrls()
    val mode = addonsTv()
    return when {
        pkg == IPTV_PKG -> if (mode == TvPlace.IPTV) urls else emptyList()
        pkg.startsWith(STREMIO) -> if (mode == TvPlace.APPS) urls.filter { Stremio.saved(filesDir, it)?.addon?.let { a -> STREMIO + a.id } == pkg } else emptyList()
        mode == TvPlace.TV && isTvApp(pkg) -> urls
        else -> emptyList()
    }
}

/**
 * The rows of the add-ons under the tile [pkg] (see addonsUnder): per add-on, each TV catalog whole and then by
 * category, as the add-on lists them. Under an add-on's own tile a row is called by its category alone; under
 * the TV or IPTV app, where add-ons share the rows, by its catalog too ("OMG+ TV - SAT · Sport"). An add-on's
 * channels are read again when due, and the rows built again once they're in. Runs on the io thread.
 */
internal fun MainActivity.addonSections(pkg: String, hidden: Set<String>): List<Section> {
    val own = pkg.startsWith(STREMIO) && pkg != IPTV_PKG
    val urls = addonsUnder(pkg)
    if (urls.isEmpty()) return emptyList()
    // What's on: the add-on's own, else the guide set in Zephyr TV settings (by the channel's name).
    val guide = epgUrl()?.let { url ->
        if (Epg.due(filesDir, url)) updateEpg(pkg)
        Epg.saved(filesDir, url)
    }
    urls.forEach { if (Stremio.due(filesDir, it)) updateAddon(it, pkg) }
    val listings = urls.mapNotNull { url -> Stremio.saved(filesDir, url)?.let { url to it } }
    // The same add-ons, guide and hidden channels as last time, in the same few minutes: the same rows (the same
    // cards, so showSections keeps them on screen as they are).
    val from = listOf(pkg, addonsTv(), listings.joinToString { (u, l) -> "$u@${l.at}" }, System.identityHashCode(guide),
        hidden.hashCode(), System.currentTimeMillis() / GUIDE_MS).joinToString("|")
    built[pkg]?.takeIf { it.from == from }?.let { return it.sections }
    return listings.flatMap { (url, listing) ->
        val catalogs = listing.addon.catalogs.size
        listing.rows.mapNotNull { row ->
            val name = when {
                row.genre == null -> row.catalog.name
                own && catalogs == 1 -> row.genre
                else -> "${row.catalog.name} · ${row.genre}"
            }
            // Hidden as hideWatch keys it: the card has no app.
            val items = row.items.filter { hideKey(null, it.name) !in hidden }.map { item ->
                val now = if (item.now == null) guide?.let { Epg.now(it, item.name) } else null
                Prog(item.name, item.now ?: now?.first ?: "", now?.second ?: 0, item.logo, null, null, badge = "", addon = AddonChannel(url, item))
            }
            if (items.isEmpty()) null
            else Section("stremio:${listing.addon.id}:${row.catalog.id}:${row.genre.orEmpty()}", name, name, items, stamp = "$from/${items.size}")
        }
    }.also { built = built + (pkg to Built(from, it)) }
}

/**
 * Reads the add-on at [url] again in the background; once it's in, the rows under [pkg] are built again if it's
 * still the tile in focus, and the apps row if the add-on has a tile there. [done] hears the add-on (null: it failed).
 */
internal fun MainActivity.updateAddon(url: String, pkg: String?, done: ((Stremio.Listing?) -> Unit)? = null) = netIo.execute {
    val first = Stremio.saved(filesDir, url) == null
    val listing = Stremio.update(filesDir, url)
    // Its logo, for its tile: downloaded here, not while the apps row loads.
    val tiles = addonsTv() == TvPlace.APPS
    val logo = listing?.addon?.logo?.takeIf { tiles && !channelLogoFiles.has(it) }
        ?.let { runCatching { channelLogoFiles.get(it) { u -> openStream(u) } }.isSuccess } == true
    ui.post {
        done?.invoke(listing)
        if (tiles && listing != null && (logo || first)) refreshApps()
        if (listing != null) rowsAgain(pkg)
    }
}

/** Adds the add-on at [input] (typed or sent from a phone) once it answers, telling how it went. */
internal fun MainActivity.addAddon(input: String, done: () -> Unit = {}) {
    val url = Stremio.manifestUrl(input) ?: return Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
    updateAddon(url, null) { listing ->
        val a = listing?.addon
        when {
            listing == null || a == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
            // Neither channels nor films and series (catalogs or streams of them): nothing Zephyr TV can show.
            listing.rows.isEmpty() && !a.streamsVod && a.vod.isEmpty() -> {
                Stremio.forget(filesDir, url)
                Toast.makeText(this, getString(R.string.toast_addon_no_tv), Toast.LENGTH_LONG).show()
            }
            else -> {
                saveAddonUrls(addonUrls() + url)
                addonsChanged()
                val channels = listing.rows.filter { it.genre == null }.sumOf { it.items.size }
                Toast.makeText(this, if (channels > 0) getString(R.string.toast_addon_added, a.name, channels)
                    else getString(R.string.toast_addon_added_vod, a.name), Toast.LENGTH_LONG).show()
            }
        }
        done()
    }
}

internal fun MainActivity.removeAddon(url: String) {
    saveAddonUrls(addonUrls() - url)
    Stremio.forget(filesDir, url)
    addonsChanged()
    Toast.makeText(this, getString(R.string.toast_addon_removed), Toast.LENGTH_SHORT).show()
}

/** Add-ons, or where they show, changed: their tiles and the rows under the tile in focus come again. */
internal fun MainActivity.addonsChanged() {
    watchKey = null
    watchApp = null
    sectionCache = null
    refreshApps()
}

/**
 * The channels to zap through from [c]: those of the row it was picked in ([row], when there's one), else the
 * whole catalog it's in, else [c] alone.
 */
internal fun MainActivity.zapList(c: AddonChannel, row: List<AddonChannel>?): List<AddonChannel> {
    row?.takeIf { list -> list.any { it.item.id == c.item.id } }?.let { return it }
    val rows = Stremio.saved(filesDir, c.addon)?.rows.orEmpty().filter { it.genre == null }
    return rows.firstOrNull { r -> r.items.any { it.id == c.item.id } }?.items?.map { AddonChannel(c.addon, it) } ?: listOf(c)
}

/**
 * OK on an add-on's channel: Zephyr TV's player plays it (see PlayerActivity), with the channels around it ([row]: the
 * cards of the row it's in) for CH+ and CH-. Each channel's logo: the add-on's, then Zephyr TV's own by name.
 */
internal fun MainActivity.playAddonChannel(p: Prog, row: List<AddonChannel>? = null) {
    val c = p.addon ?: return
    val list = zapList(c, row)
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: c.addon.removeSuffix("/manifest.json")
    val logos = channelLogoTable()
    fun strings(f: (AddonChannel) -> String?) = ArrayList(list.map { f(it).orEmpty() })
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.ADDON, c.addon)
            .putExtra(PlayerActivity.BASE, base)
            .putExtra(PlayerActivity.INDEX, list.indexOfFirst { it.item.id == c.item.id }.coerceAtLeast(0))
            .putStringArrayListExtra(PlayerActivity.TYPES, strings { it.item.type })
            .putStringArrayListExtra(PlayerActivity.IDS, strings { it.item.id })
            .putStringArrayListExtra(PlayerActivity.NAMES, strings { it.item.name })
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.item.now })
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { it.item.logo })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { ChannelLogos.match(logos, it.item.name) }))
    } catch (e: Exception) {
        warn("player didn't open ($e)")
    }
}

/** The channel last played (written by PlayerActivity, in its own process: a file, not the preferences). */
internal fun MainActivity.lastAddonChannel(): Pair<Long, AddonChannel>? = runCatching {
    val o = org.json.JSONObject(java.io.File(filesDir, PlayerActivity.LAST_FILE).readText())
    val addon = o.getString("addon").takeIf { it in addonUrls() } ?: return null
    o.getLong("at") to AddonChannel(addon, Stremio.Item(o.getString("type"), o.getString("id"), o.getString("name"), o.optString("logo").ifEmpty { null }, null))
}.getOrNull()

/** The channel last played as a Continue Playing card: the add-on's name under it. Off the UI thread. */
internal fun MainActivity.lastChannelCard(hidden: Set<String>): Pair<Long, Prog>? {
    val (at, c) = lastAddonChannel() ?: return null
    if (hideKey(LAST_PKG, c.item.name) in hidden) return null
    val addon = Stremio.saved(filesDir, c.addon)?.addon
    // Marked with the tile it plays from, as the other cards are with their app.
    return at to Prog(c.item.name, addon?.name.orEmpty(), 0, c.item.logo, null, LAST_PKG, badge = "", addon = c, icon = addonTile(c)?.pkg)
}

/** The tile [c]'s channels show under, as addonsTv puts them: the add-on's own app, the IPTV app, or the TV app. */
internal fun MainActivity.addonTile(c: AddonChannel): MainActivity.App? {
    val pkg = when (addonsTv()) {
        TvPlace.APPS -> Stremio.saved(filesDir, c.addon)?.addon?.let { STREMIO + it.id }
        TvPlace.IPTV -> IPTV_PKG
        TvPlace.TV -> return apps.firstOrNull { !it.pkg.startsWith(STREMIO) && isTvApp(it.pkg) }
    }
    return apps.firstOrNull { it.pkg == pkg }
}

/** OK on an add-on's tile (or the IPTV app's): the channel last played among its add-ons, if any. */
internal fun MainActivity.playLastChannel(pkg: String): Boolean {
    val (_, c) = lastAddonChannel() ?: return false
    if (c.addon !in addonsUnder(pkg)) return false
    playAddonChannel(Prog(c.item.name, "", 0, c.item.logo, null, null, badge = "", addon = c))
    return true
}

/** The add-ons' channels whose name has [words] (normalized, see ChannelLogos) in it, for the search. Off the UI thread. */
internal fun MainActivity.findAddonChannels(words: String): List<ChannelDetail> {
    val logos = channelLogoTable()
    val guide = epgUrl()?.let { Epg.saved(filesDir, it) }
    return addonUrls().flatMap { url ->
        Stremio.saved(filesDir, url)?.rows.orEmpty().filter { it.genre == null }.flatMap { r -> r.items.map { url to it } }
    }.filter { (_, item) -> ChannelLogos.normalize(item.name).contains(words) }.distinctBy { it.second.name }.map { (url, item) ->
        addonDetail(AddonChannel(url, item), logos, guide, null)
    }
}

/** An add-on's channel as its page shows it: the add-on's logo and guide first, Zephyr TV's own after ([meta]: its page from the add-on). */
internal fun addonDetail(c: AddonChannel, logos: Map<String, String>, guide: Epg.Guide?, meta: Stremio.Meta?): ChannelDetail {
    val epg = guide?.let { Epg.nowAndNext(it, c.item.name) }
    val own = (meta?.now ?: c.item.now)?.let { Epg.Show(0, 0, it, meta?.description.orEmpty()) }
    return ChannelDetail(c.item.name, "", c.item.logo ?: ChannelLogos.match(logos, c.item.name), "", null,
        own ?: epg?.first, if (own == null) epg?.second else null, addon = c)
}

/** Hold OK on an add-on's channel, Details: its page, with what the add-on tells about it. */
internal fun MainActivity.showAddonChannelDetails(c: AddonChannel) = netIo.execute {
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: return@execute
    val meta = runCatching { Stremio.meta(base, c.item.type, c.item.id) }.getOrNull()
    val d = addonDetail(c, channelLogoTable(), epgUrl()?.let { Epg.saved(filesDir, it) }, meta)
    ui.post { showChannel(d) }
}

/** Whether Zephyr TV may read the TV listings (the TV app's channels and other apps' rows): add-on tiles don't need to. */
internal fun MainActivity.readsListings() = checkSelfPermission(MainActivity.TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

// ---------- settings: the Stremio add-ons section ----------

/** Where the add-ons' channels show, then Add an add-on, then each add-on (its page: update, remove). */
internal fun MainActivity.addonRows(): List<Triple<String, String, () -> Unit>> {
    val mode = addonsTv()
    val modes = TvPlace.entries.filter { it != TvPlace.TV || hasTvApp() }
    return listOf(
        page(getString(R.string.setting_addons_tv), addonsTvName(mode)) {
            val next = modes[(modes.indexOf(mode) + 1) % modes.size]
            prefs.edit().putString(K.ADDONS_TV, next.key).apply()
            addonsChanged()
            // An app each: their logos, for the tiles, come with their channels.
            if (next == TvPlace.APPS) addonUrls().forEach { updateAddon(it, null) }
            renderSettings(ADDONS_TV_ROW)
        },
        // Films and series: the Films and Series tiles, an app per add-on, or none; and Cinemeta, their library
        // (always there: only its address can change).
        page(getString(R.string.setting_addons_vod), addonsVodName(addonsVod())) {
            val modes = VodPlace.entries
            prefs.edit().putString(K.ADDONS_VOD, modes[(modes.indexOf(addonsVod()) + 1) % modes.size].key).apply()
            addonsChanged()
            renderSettings(ADDONS_VOD_ROW)
        },
        page(getString(R.string.setting_cinemeta), android.net.Uri.parse(cinemetaUrl()).host.orEmpty()) { configureCinemeta() },
        page(getString(R.string.setting_addon_add), SUBMENU) { addonFromPhone() },
    ) + addonUrls().mapIndexed { i, url ->
        page(Stremio.saved(filesDir, url)?.addon?.name ?: url, SUBMENU) { addonPage(url) }
    }
}

/** Cinemeta's address: another instance's, typed with the remote, or Stremio's own again. Saving reads it at once. */
internal fun MainActivity.configureCinemeta() {
    fun use(url: String?) {
        if (url == null || url == Stremio.CINEMETA) prefs.edit().remove(K.CINEMETA_URL).apply() else prefs.edit().putString(K.CINEMETA_URL, url).apply()
        Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
        updateAddon(cinemetaUrl(), null) { listing ->
            if (listing?.addon == null) Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
            addonsChanged()
        }
        renderSettings(CINEMETA_ROW)
    }
    textPage(getString(R.string.setting_cinemeta), cinemetaUrl(), Stremio.CINEMETA, getString(R.string.cinemeta_intro), back = { renderSettings(CINEMETA_ROW) }) { typed ->
        listOf(
            page(getString(R.string.dialog_save)) {
                val url = Stremio.manifestUrl(typed()) ?: return@page Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
                use(url)
            },
            page(getString(R.string.cinemeta_default)) { use(null) },
        )
    }
}

internal fun MainActivity.addonsVodName(mode: VodPlace) = getString(when (mode) {
    VodPlace.TILES -> R.string.addons_vod_tiles
    VodPlace.APPS -> R.string.addons_tv_apps
    VodPlace.OFF -> R.string.state_off
})

internal fun MainActivity.addonsTvName(mode: TvPlace) = getString(when (mode) {
    TvPlace.TV -> R.string.addons_tv_tv
    TvPlace.IPTV -> R.string.addons_tv_iptv
    TvPlace.APPS -> R.string.addons_tv_apps
})

/** An add-on's manifest address typed with the remote (see addonFromPhone). Saving adds it once it answers. */
internal fun MainActivity.configureAddon() =
    textPage(getString(R.string.setting_addon_add), "", getString(R.string.addon_hint), getString(R.string.addon_intro), back = { addonFromPhone() }) { typed ->
        listOf(page(getString(R.string.dialog_save)) {
            addAddon(typed()) { refreshSettings(ADDON_ADD_ROW) }
            renderSettings(ADDON_ADD_ROW)
        })
    }

/**
 * Add an add-on: a QR code first, as the other addresses and keys, since a long address is easier pasted on a
 * phone; typing it with the remote is the row under it. Added like the Save row does.
 */
internal fun MainActivity.addonFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.phone_addon_title, R.string.phone_addon_intro), getString(R.string.addon_hint))
    showPhoneLink(getString(R.string.setting_addon_add), PhoneTask(page, 4096) { file ->
        val url = Stremio.manifestUrl(file.readText()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
        ui.post {
            status.text = getString(R.string.addon_checking)
            addAddon(url)
        }
        200 to getString(R.string.phone_addon_saved)
    }, status, listOf(page(getString(R.string.addon_type)) { configureAddon() })) { renderSettings(ADDON_ADD_ROW) }
}

/**
 * Moves the add-on at [url] [step] places in the list: where several show together (the TV app, the IPTV app),
 * their rows come in this order.
 */
internal fun MainActivity.moveAddon(url: String, step: Int) {
    val urls = addonUrls().toMutableList()
    val from = urls.indexOf(url)
    val to = from + step
    if (from < 0 || to !in urls.indices) return
    urls.removeAt(from)
    urls.add(to, url)
    saveAddonUrls(urls)
    addonsChanged()
}

/**
 * One add-on's page, from its row in the section: read it again now, move it up or down the list (the order its
 * rows come in), or remove it. [focus] is the row to focus by its name (after a move, the same move again).
 */
internal fun MainActivity.addonPage(url: String, focus: Int? = null) {
    val name = Stremio.saved(filesDir, url)?.addon?.name ?: url
    val urls = addonUrls()
    val at = urls.indexOf(url)
    val row = ADDON_ROWS + at
    val rows = listOfNotNull(
        page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            updateAddon(url, null) { listing ->
                val channels = listing?.rows?.filter { it.genre == null }?.sumOf { it.items.size }
                Toast.makeText(this, if (channels == null) getString(R.string.toast_addon_failed)
                    else getString(R.string.toast_addon_updated, channels), Toast.LENGTH_LONG).show()
                watchKey = null
            }
        },
        if (at > 0) page(getString(R.string.addon_move_up)) { moveAddon(url, -1); addonPage(url, R.string.addon_move_up) } else null,
        if (at < urls.size - 1) page(getString(R.string.addon_move_down)) { moveAddon(url, +1); addonPage(url, R.string.addon_move_down) } else null,
        page(getString(R.string.addon_remove)) {
            removeAddon(url)
            renderSettings(ADDON_ADD_ROW)
        },
    )
    // After a move, the same move again (or the other one, at the top or bottom of the list).
    val shown = rows.map { it.first }
    val index = focus?.let { f -> shown.indexOf(getString(f)).takeIf { it >= 0 }
        ?: shown.indexOfFirst { it == getString(R.string.addon_move_up) || it == getString(R.string.addon_move_down) } } ?: 0
    showPage(name, rows, index.coerceAtLeast(0), top = label(url, 12f).apply { alpha = 0.6f; setPadding(dp(4), dp(8), dp(4), dp(8)) }, back = { renderSettings(row) })
}
