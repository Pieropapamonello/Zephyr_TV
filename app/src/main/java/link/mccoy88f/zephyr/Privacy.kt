package link.mccoy88f.zephyr

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

// Hiding the device's IP from the sites add-ons, plugins and extensions read, and from the servers of the videos:
// Cloudflare WARP, the free 1.1.1.1 app, as the device's VPN. Zephyr TV can't be one itself; it says whether one is on,
// and opens WARP or its Play Store page. Functions of MainActivity, kept here by topic.

/** Cloudflare's 1.1.1.1 app, with WARP. */
private const val WARP = "com.cloudflare.onedotonedotonedotone"

/** Whether the device's traffic goes through a VPN now (WARP, or another). */
internal fun MainActivity.vpnOn(): Boolean = runCatching {
    val cm = getSystemService(ConnectivityManager::class.java)
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}.getOrDefault(false)

internal fun MainActivity.warpInstalled() = runCatching { packageManager.getPackageInfo(WARP, 0) }.isSuccess

/** What hiding the IP is about, whether a VPN is on, and WARP opened or got. [back]: the page before. */
internal fun MainActivity.privacyPage(back: () -> Unit) {
    val intro = label(getString(R.string.warp_intro), 13f).apply {
        alpha = 0.7f
        maxLines = 12
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    val installed = warpInstalled()
    showPage(getString(R.string.warp_title), listOf(
        page(getString(R.string.warp_state), getString(if (vpnOn()) R.string.warp_vpn_on else R.string.state_off)) { privacyPage(back) },
        if (installed) page(getString(R.string.warp_open)) {
            val launch = packageManager.getLeanbackLaunchIntentForPackage(WARP) ?: packageManager.getLaunchIntentForPackage(WARP)
            launch?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        } else page(getString(R.string.warp_get)) {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WARP")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(store) }.onFailure {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://one.one.one.one/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
    ), top = intro, back = back)
}
