package dev.piume

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.widget.LinearLayout
import android.widget.Toast
import dev.piume.MainActivity.Prog
import dev.piume.MainActivity.Companion.ADDONS_TV_ROW
import dev.piume.MainActivity.Companion.ADDON_ADD_ROW
import dev.piume.MainActivity.Companion.ADDON_ROWS
import dev.piume.MainActivity.Companion.SUBMENU

// Stremio add-ons' TV channels on the home screen: where they show (the TV app, one IPTV app, or an app per add-on),
// their rows, and adding and removing add-ons. Functions of MainActivity, like the other topics; the add-ons
// themselves are read by Stremio.

/** App.pkg prefix of the add-ons' tiles: "stremio:iptv" for the IPTV app, "stremio:<add-on id>" for an add-on's own. */
internal const val STREMIO = "stremio:"
internal const val IPTV_PKG = STREMIO + "iptv"

/** A card that's an add-on's channel: the add-on (its manifest's address) and the channel in it. */
internal class AddonChannel(val addon: String, val item: Stremio.Item)

/** The add-ons' manifest addresses, in the order they were added. */
internal fun MainActivity.addonUrls(): List<String> = prefs.getString(K.ADDONS, "")!!.lines().filter { it.isNotBlank() }

private fun MainActivity.saveAddonUrls(urls: List<String>) = prefs.edit().putString(K.ADDONS, urls.distinct().joinToString("\n")).apply()

/** Whether there's a TV app here: the one Android opens a channel link with (none on most boxes and sticks). */
internal fun MainActivity.hasTvApp(): Boolean = listOf(
    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(1)),
    Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE),
).any { packageManager.queryIntentActivities(it, 0).any { r -> r.activityInfo.packageName != packageName } }

/** Where the add-ons' TV channels show, as chosen: in the TV app (if there's one, else the IPTV app), the IPTV app, an app each. */
internal fun MainActivity.addonsTv(): String {
    val mode = prefs.getString(K.ADDONS_TV, null)?.takeIf { it in listOf("tv", "iptv", "apps") } ?: "tv"
    return if (mode == "tv" && !hasTvApp()) "iptv" else mode
}

/** The tiles the add-ons add to the apps row: one IPTV app, or one per add-on, or none (in the TV app). Runs on the io thread. */
internal fun MainActivity.addonApps(): List<MainActivity.App> {
    val urls = addonUrls()
    if (urls.isEmpty()) return emptyList()
    fun icon() = getDrawable(R.drawable.ic_live_tv)!!
    return when (addonsTv()) {
        "iptv" -> listOf(MainActivity.App(IPTV_PKG, getString(R.string.iptv_app), Intent(), icon()))
        "apps" -> urls.mapNotNull { url ->
            // Not read yet (just restored from a backup): its tile comes once it is.
            val addon = Stremio.saved(filesDir, url)?.addon ?: return@mapNotNull null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
            // Its logo once it's on disk (downloaded with its channels, see updateAddon), else the TV mark.
            val logo = addon.logo?.takeIf { channelLogoFiles.has(it) }?.let { uri ->
                runCatching { BitmapFactory.decodeFile(channelLogoFiles.get(uri) { openStream(it) }.path) }.getOrNull()
            }
            MainActivity.App(STREMIO + addon.id, addon.name, Intent(), logo?.let { BitmapDrawable(resources, it) } ?: icon())
        }.distinctBy { it.pkg }
        else -> emptyList()
    }
}

/** The add-ons whose channels show under the tile [pkg]: all of them under the TV app or the IPTV app, one under its own. */
internal fun MainActivity.addonsUnder(pkg: String): List<String> {
    val urls = addonUrls()
    val mode = addonsTv()
    return when {
        pkg == IPTV_PKG -> if (mode == "iptv") urls else emptyList()
        pkg.startsWith(STREMIO) -> if (mode == "apps") urls.filter { Stremio.saved(filesDir, it)?.addon?.let { a -> STREMIO + a.id } == pkg } else emptyList()
        mode == "tv" && isTvApp(pkg) -> urls
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
    return addonsUnder(pkg).flatMap { url ->
        if (Stremio.due(filesDir, url)) updateAddon(url, pkg)
        val listing = Stremio.saved(filesDir, url) ?: return@flatMap emptyList()
        val catalogs = listing.addon.catalogs.size
        listing.rows.mapNotNull { row ->
            val name = when {
                row.genre == null -> row.catalog.name
                own && catalogs == 1 -> row.genre
                else -> "${row.catalog.name} · ${row.genre}"
            }
            // Hidden as hideWatch keys it: the card has no app.
            val items = row.items.filter { hideKey(null, it.name) !in hidden }.map { item ->
                Prog(item.name, item.now ?: "", 0, item.logo, null, null, badge = "", addon = AddonChannel(url, item))
            }
            if (items.isEmpty()) null
            else Section("stremio:${listing.addon.id}:${row.catalog.id}:${row.genre.orEmpty()}", name, name, items)
        }
    }
}

/**
 * Reads the add-on at [url] again in the background; once it's in, the rows under [pkg] are built again if it's
 * still the tile in focus, and the apps row if the add-on has a tile there. [done] hears the add-on (null: it failed).
 */
internal fun MainActivity.updateAddon(url: String, pkg: String?, done: ((Stremio.Listing?) -> Unit)? = null) = netIo.execute {
    val first = Stremio.saved(filesDir, url) == null
    val listing = Stremio.update(filesDir, url)
    // Its logo, for its tile: downloaded here, not while the apps row loads.
    val tiles = addonsTv() == "apps"
    val logo = listing?.addon?.logo?.takeIf { tiles && !channelLogoFiles.has(it) }
        ?.let { runCatching { channelLogoFiles.get(it) { u -> openStream(u) } }.isSuccess } == true
    ui.post {
        done?.invoke(listing)
        if (tiles && listing != null && (logo || first)) refreshApps()
        if (listing != null && pkg != null && tmdbLastFocusApp == pkg) {
            watchApp = null
            queryAppChannels(pkg)
        }
    }
}

/** Adds the add-on at [input] (typed or sent from a phone) once it answers, telling how it went. */
internal fun MainActivity.addAddon(input: String, done: () -> Unit = {}) {
    val url = Stremio.manifestUrl(input) ?: return Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
    updateAddon(url, null) { listing ->
        when {
            listing == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
            listing.rows.isEmpty() -> {
                Stremio.forget(filesDir, url)
                Toast.makeText(this, getString(R.string.toast_addon_no_tv), Toast.LENGTH_LONG).show()
            }
            else -> {
                saveAddonUrls(addonUrls() + url)
                addonsChanged()
                val channels = listing.rows.filter { it.genre == null }.sumOf { it.items.size }
                Toast.makeText(this, getString(R.string.toast_addon_added, listing.addon.name, channels), Toast.LENGTH_LONG).show()
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

/** OK on an add-on's channel: Piume's player plays it (see PlayerActivity). */
internal fun MainActivity.playAddonChannel(p: Prog) {
    val c = p.addon ?: return
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: c.addon.removeSuffix("/manifest.json")
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.BASE, base)
            .putExtra(PlayerActivity.TYPE, c.item.type)
            .putExtra(PlayerActivity.ID, c.item.id)
            .putExtra(PlayerActivity.NAME, c.item.name))
    } catch (e: Exception) {
        warn("player didn't open ($e)")
    }
}

/** Whether Piume may read the TV listings (the TV app's channels and other apps' rows): add-on tiles don't need to. */
internal fun MainActivity.readsListings() = checkSelfPermission(MainActivity.TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

// ---------- settings: the Stremio add-ons section ----------

/** Where the add-ons' channels show, then Add an add-on, then each add-on (its page: update, remove). */
internal fun MainActivity.addonRows(): List<Triple<String, String, () -> Unit>> {
    val mode = addonsTv()
    val modes = listOfNotNull("tv".takeIf { hasTvApp() }, "iptv", "apps")
    return listOf(
        page(getString(R.string.setting_addons_tv), addonsTvName(mode)) {
            val next = modes[(modes.indexOf(mode) + 1) % modes.size]
            prefs.edit().putString(K.ADDONS_TV, next).apply()
            addonsChanged()
            // An app each: their logos, for the tiles, come with their channels.
            if (next == "apps") addonUrls().forEach { updateAddon(it, null) }
            renderSettings(ADDONS_TV_ROW)
        },
        page(getString(R.string.setting_addon_add), SUBMENU) { configureAddon() },
    ) + addonUrls().mapIndexed { i, url ->
        page(Stremio.saved(filesDir, url)?.addon?.name ?: url, SUBMENU) { addonPage(url, ADDON_ROWS + i) }
    }
}

internal fun MainActivity.addonsTvName(mode: String) = getString(when (mode) {
    "tv" -> R.string.addons_tv_tv
    "iptv" -> R.string.addons_tv_iptv
    else -> R.string.addons_tv_apps
})

/** An add-on's manifest address: typed with the remote or sent from a phone. Saving adds it once it answers. */
internal fun MainActivity.configureAddon() {
    val input = urlField("", getString(R.string.addon_hint))
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(input)
        addView(label(getString(R.string.addon_intro), 12f).apply { alpha = 0.6f; maxLines = 4; setPadding(dp(4), dp(10), dp(4), 0) })
    }
    val rows = listOf(
        page(getString(R.string.setting_epg_phone)) { addonFromPhone() },
        page(getString(R.string.dialog_save)) {
            addAddon(input.text?.toString().orEmpty()) { refreshSettings(ADDON_ADD_ROW) }
            renderSettings(ADDON_ADD_ROW)
        },
    )
    showPage(getString(R.string.setting_addon_add), rows, top = box, back = { renderSettings(ADDON_ADD_ROW) })
    downFrom(input)
}

/** The add-on's address pasted on a phone: added like the Save row does. */
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
    }, status) { configureAddon() }
}

/** One add-on's page, from its row ([row]) in the section: read it again now, or remove it. */
internal fun MainActivity.addonPage(url: String, row: Int) {
    val name = Stremio.saved(filesDir, url)?.addon?.name ?: url
    showPage(name, listOf(
        page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            updateAddon(url, null) { listing ->
                val channels = listing?.rows?.filter { it.genre == null }?.sumOf { it.items.size }
                Toast.makeText(this, if (channels == null) getString(R.string.toast_addon_failed)
                    else getString(R.string.toast_addon_updated, channels), Toast.LENGTH_LONG).show()
                watchKey = null
            }
        },
        page(getString(R.string.addon_remove)) {
            removeAddon(url)
            renderSettings(ADDON_ADD_ROW)
        },
    ), top = label(url, 12f).apply { alpha = 0.6f; setPadding(dp(4), dp(8), dp(4), dp(8)) }, back = { renderSettings(row) })
}
