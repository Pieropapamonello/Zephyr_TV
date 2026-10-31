package link.mccoy88f.zephyr

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Only real, unfinished on-demand playback from the requested apps. Never a list of app launches. */
internal object ExternalResume {
    data class Entry(val pkg: String, val title: String, val sub: String, val position: Long,
        val duration: Long, val at: Long, val image: String?, val uri: String?) {
        val key get() = "$pkg/$title/$sub"
        val percent get() = (position*100/duration.coerceAtLeast(1)).toInt().coerceIn(1,99)
    }
    fun allowed(pkg: String?) = pkg in setOf("com.nuvio.tv","com.nuvio.tv.debug","org.smarttube.stable","org.smarttube.beta","org.smarttube.fdroid")
    fun eligible(pkg: String?, position: Long, duration: Long) = allowed(pkg) && duration>0 && position>=10_000 && position<duration*95/100
    @Synchronized fun all(dir: File): List<Entry> = runCatching {
        val a=JSONArray(File(dir,"external_resume.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Entry(o.getString("pkg"),o.getString("title"),o.optString("sub"),o.getLong("position"),o.getLong("duration"),
                o.getLong("at"),o.optString("image").ifEmpty { null },o.optString("uri").ifEmpty { null })
        }.filter { eligible(it.pkg,it.position,it.duration) }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())
    @Synchronized fun put(dir: File, e: Entry) {
        if(!allowed(e.pkg) || e.duration<=0 || e.position<10_000)return
        val list=(if(eligible(e.pkg,e.position,e.duration))listOf(e) else emptyList())+all(dir).filter { it.key!=e.key }
        dir.mkdirs()
        val a=JSONArray(list.take(40).map { v -> JSONObject().put("pkg",v.pkg).put("title",v.title).put("sub",v.sub)
            .put("position",v.position).put("duration",v.duration).put("at",v.at).put("image",v.image.orEmpty()).put("uri",v.uri.orEmpty()) })
        val part=File(dir,"external_resume.json.part");part.writeText(a.toString())
        java.nio.file.Files.move(part.toPath(),File(dir,"external_resume.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun rememberLink(dir: File, title: String, uri: String) {
        val f=File(dir,"external_resume_links.json")
        val o=runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        o.put(title.trim().lowercase(),uri)
        while(o.length()>100)o.remove(o.keys().next())
        f.writeText(o.toString())
    }
    @Synchronized fun knownLink(dir: File,title: String): String? = runCatching {
        JSONObject(File(dir,"external_resume_links.json").readText()).optString(title.trim().lowercase()).ifEmpty { null }
    }.getOrNull()
    @Synchronized fun knownTitle(dir: File,uri: String): String? = runCatching {
        val o=JSONObject(File(dir,"external_resume_links.json").readText())
        o.keys().asSequence().firstOrNull { o.optString(it).substringBefore('&')==uri.substringBefore('&') }
    }.getOrNull()
}

internal object PreferredWatch {
    fun link(type: String,id: String) = "nuvio://detail/${if(type=="series" || type=="tv")"series" else "movie"}/"+
        java.net.URLEncoder.encode(id,"UTF-8")
}
