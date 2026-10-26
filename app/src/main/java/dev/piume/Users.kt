package dev.piume

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import dev.piume.MainActivity.Companion.CHOSEN
import java.io.File

// Who's watching: Piume's users, each with what they watched and where they got to (VodProgress, VodWatched, in a
// folder of their own) and, if they like, their Trakt account (see Trakt). The one in use is picked from the top bar,
// left of search. The first user's files are the ones Piume kept before there were users, so nothing is lost.
// Functions of MainActivity, kept here by topic; the users themselves are in the preferences.

/** A Piume user: [id] names their folder ("" for the first one: Piume's own files), [name] is what they're called. */
internal class User(val id: String, val name: String)

/** Piume's users, in the order they were added; the first one always there. */
internal fun MainActivity.users(): List<User> {
    val saved = runCatching {
        val a = org.json.JSONArray(prefs.getString(K.USERS, null) ?: return@runCatching emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { User(it.optString("id"), it.optString("name")) }
    }.getOrDefault(emptyList())
    val first = saved.firstOrNull { it.id == "" } ?: User("", getString(R.string.user_default))
    return listOf(first) + saved.filter { it.id != "" }
}

private fun MainActivity.saveUsers(list: List<User>) {
    prefs.edit().putString(K.USERS, org.json.JSONArray(list.map { org.json.JSONObject().put("id", it.id).put("name", it.name) }).toString()).apply()
}

/** The user in use: their progress, watched titles and Trakt account are the ones Piume shows and keeps. */
internal fun MainActivity.currentUser(): User = users().let { list -> list.firstOrNull { it.id == prefs.getString(K.USER, "") } ?: list.first() }

/** [user]'s folder: what they watched and where they got to, and their Trakt account. */
internal fun MainActivity.vodDir(user: String = currentUser().id): File = if (user == "") filesDir else File(filesDir, "user_$user").apply { mkdirs() }

/** Top bar, the users' icon: the users, the one in use first marked, and Add user. */
internal fun MainActivity.showUsers(focus: Int = 0) {
    showPanel()
    val current = currentUser()
    val list = users()
    showPage(getString(R.string.users_title), list.map { u ->
        val trakt = Trakt.account(vodDir(u.id))?.user?.let { "Trakt: $it" }
        page(u.name, listOfNotNull(trakt, if (u.id == current.id) CHOSEN else null).joinToString("  ")) { userPage(u) }
    } + page(getString(R.string.user_add)) { userName(null) }, focus) { closeLauncherSettings() }
}

/** A user's page: use this profile, their Trakt account (connect, sync, disconnect), rename, remove. */
internal fun MainActivity.userPage(u: User, focus: Int = 0) {
    val dir = vodDir(u.id)
    val account = Trakt.account(dir)
    val inUse = currentUser().id == u.id
    val back = { showUsers(users().indexOfFirst { it.id == u.id }.coerceAtLeast(0)) }
    showPage(u.name, listOfNotNull(
        if (inUse) page(getString(R.string.user_in_use), CHOSEN) { } else page(getString(R.string.user_use)) { useUser(u); back() },
        if (account == null) page(getString(R.string.trakt_connect), getString(R.string.trakt_not_connected)) { traktSignIn(u) }
        else page(getString(R.string.trakt_sync), account.user) { syncTrakt(u.id, tell = true) },
        account?.let { page(getString(R.string.trakt_disconnect)) { Trakt.forget(dir); userPage(u, 1) } },
        page(getString(R.string.user_rename)) { userName(u) },
        // The first user stays: their files are Piume's own.
        if (u.id == "") null else page(getString(R.string.user_remove)) {
            saveUsers(users().filter { it.id != u.id })
            if (inUse) useUser(users().first())
            dir.deleteRecursively()
            showUsers()
        },
    ), focus, back = back)
}

/** [u] in use from now on: Continue Playing and the ✓ marks are theirs; synced with Trakt when they have it. */
internal fun MainActivity.useUser(u: User) {
    prefs.edit().putString(K.USER, u.id).apply()
    Toast.makeText(this, getString(R.string.user_switched, u.name), Toast.LENGTH_SHORT).show()
    usersChanged()
    syncTrakt(u.id)
}

/** What shows what the user watched again: Continue Playing, the rows with their ✓ marks, the users' icon. */
internal fun MainActivity.usersChanged() {
    watchKey = null
    watchApp = null
    findViewById<ImageView>(R.id.usersButton).contentDescription = getString(R.string.users_title) + ": " + currentUser().name
    refreshWatchNext()
}

/** A new user's name ([u] null), or [u]'s new one, typed with the remote. */
private fun MainActivity.userName(u: User?) {
    val input = urlField(u?.name.orEmpty(), getString(R.string.user_name_hint)).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(input)
    }
    val back = { if (u == null) showUsers(users().size) else userPage(u, 3) }
    showPage(getString(if (u == null) R.string.user_add else R.string.user_rename), listOf(page(getString(R.string.dialog_save)) {
        val name = input.text?.toString()?.trim().orEmpty().ifEmpty { return@page }
        val list = users()
        if (u == null) {
            val added = User(java.util.UUID.randomUUID().toString().take(8), name)
            saveUsers(list + added)
            userPage(added)
        } else {
            saveUsers(list.map { if (it.id == u.id) User(it.id, name) else it })
            if (currentUser().id == u.id) usersChanged()
            userPage(User(u.id, name), 3)
        }
    }), top = box, back = back)
    downFrom(input)
}

// ---------- Trakt ----------

/**
 * Connect [u] to Trakt from a phone: a QR code of the sign-in (Trakt's own page, through mccoy88f.link), and the TV
 * asking every few seconds whether it's done, while the page is open (ten minutes at most).
 */
private fun MainActivity.traktSignIn(u: User) {
    val state = Trakt.newState()
    val url = Trakt.signInUrl(state)
    val status = label(getString(R.string.trakt_waiting), 14f)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(12))
        addView(ImageView(this@traktSignIn).apply { setImageBitmap(qrBitmap(url, dp(200))) })
        addView(label(getString(R.string.trakt_sign_in_intro), 12f).apply { gravity = Gravity.CENTER; alpha = 0.6f; maxLines = 4; setPadding(0, dp(12), 0, 0) })
        addView(status.apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(12), 0, 0) })
    }
    val title = getString(R.string.trakt_connect) + " · " + u.name
    showPage(title, listOf(page(getString(R.string.dialog_cancel)) { userPage(u, 1) }), top = box, back = { userPage(u, 1) })
    val until = System.currentTimeMillis() + 10 * 60_000L
    val dir = vodDir(u.id)
    fun ask() {
        if (!pageShowing(title)) return // left: no more asking
        if (System.currentTimeMillis() > until) return run { status.text = getString(R.string.trakt_expired) }
        netIo.execute {
            val done = runCatching { Trakt.poll(dir, state) }.getOrElse { null } // offline for a moment: ask again
            ui.post {
                when (done) {
                    true -> {
                        Toast.makeText(this, getString(R.string.trakt_connected, Trakt.account(dir)?.user.orEmpty()), Toast.LENGTH_LONG).show()
                        if (pageShowing(title)) userPage(u, 1)
                        syncTrakt(u.id, tell = true)
                    }
                    false -> status.text = getString(R.string.trakt_expired)
                    null -> ui.postDelayed({ ask() }, 5000)
                }
            }
        }
    }
    ui.postDelayed({ ask() }, 5000)
}

/** Whether the page titled [title] is still the one in the panel. */
private fun MainActivity.pageShowing(title: String) =
    settingsScrim.visibility == android.view.View.VISIBLE && findViewById<android.widget.TextView>(R.id.settingsTitle).text.toString() == title

private val syncing = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * Brings [user]'s Trakt history and pauses into Piume: what they watched gets its ✓, where they paused elsewhere
 * goes into Continue Playing (when newer than Piume's own). [tell]: say how it went. Without an account, nothing.
 */
internal fun MainActivity.syncTrakt(user: String = currentUser().id, tell: Boolean = false) {
    val dir = vodDir(user)
    if (Trakt.account(dir) == null || !syncing.add(user)) return
    if (tell) Toast.makeText(this, getString(R.string.trakt_syncing), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val result = runCatching {
            val watched = Trakt.watched(dir) ?: return@runCatching null
            val known = VodWatched.all(dir)
            VodWatched.set(dir, watched.filter { it.first !in known }, true, 0) // Trakt's own dates aren't kept: older than Piume's marks
            val paused = Trakt.paused(dir).orEmpty().filter { it.runtimeMin > 0 }
            val mine = VodProgress.all(dir).associateBy { it.id }
            var resumed = 0
            for (p in paused) {
                if ((mine[p.id]?.at ?: 0L) >= p.at || p.id in VodWatched.all(dir)) continue
                val duration = p.runtimeMin * 60_000L
                val sub = if (!p.series) "" else listOf(getString(R.string.title_season_episode, p.season, p.number), p.episodeTitle)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                // Cinemeta's background for the title, as its card in the Films and Series rows has.
                VodProgress.put(dir, VodProgress.Entry(p.id, if (p.series) "series" else "movie", p.metaId, p.title, sub,
                    "https://images.metahub.space/background/medium/${p.metaId}/img", (duration * p.progress / 100).toLong(), duration, p.at))
                resumed++
            }
            Trakt.synced(dir, System.currentTimeMillis())
            watched.size to resumed
        }.getOrNull()
        syncing.remove(user)
        ui.post {
            if (user == currentUser().id) usersChanged()
            if (!tell) return@post
            Toast.makeText(this, if (result == null) getString(R.string.trakt_failed) else getString(R.string.trakt_synced, result.first, result.second),
                Toast.LENGTH_LONG).show()
        }
    }
}

/** Back on the home screen: the user in use synced with Trakt now and then (every quarter of an hour at most). */
internal fun MainActivity.syncTraktNow() {
    val a = Trakt.account(vodDir()) ?: return
    if (System.currentTimeMillis() - a.synced > 15 * 60_000L) syncTrakt()
}

/** Films and episodes marked watched or not on a page: the same in the user's Trakt history. */
internal fun MainActivity.traktHistory(items: List<Pair<String, String>>, on: Boolean) {
    val dir = vodDir()
    if (Trakt.account(dir) != null) netIo.execute { Trakt.history(dir, items, on) }
}
