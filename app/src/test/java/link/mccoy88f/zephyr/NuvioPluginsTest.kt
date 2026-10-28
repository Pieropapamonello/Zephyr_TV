package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NuvioPluginsTest {
    /** A fetch that answers from a table, by address: what the plugins under test ask, without the network. */
    private fun fake(pages: Map<String, String>, seen: MutableList<JSONObject> = ArrayList()): (JSONObject) -> Nuvio.Fetched = { req ->
        seen += req
        val body = pages[req.getString("url")]
        if (body == null) Nuvio.Fetched(404, "Not Found", req.getString("url"), emptyList(), ByteArray(0), null)
        else Nuvio.Fetched(200, "OK", req.getString("url"), listOf("set-cookie" to "a=1", "set-cookie" to "b=2"), body.toByteArray(), null)
    }

    @Test fun runsAPluginWithFetchTimersAndBase64() {
        val seen = ArrayList<JSONObject>()
        val script = """
            function getStreams(id, type, season, episode) {
              return new Promise(function (done) { setTimeout(done, 5); }).then(function () {
                return fetch("https://example.org/" + id, { method: "POST", headers: { Referer: "https://example.org" }, body: "q=" + id });
              }).then(function (res) {
                var cookies = res.headers.get("set-cookie");
                return res.json().then(function (j) {
                  return [{ name: "Test " + atob(btoa("ok")), title: j.title + "\n" + cookies, url: j.url, quality: "1080p",
                            headers: { Referer: "https://example.org/" } }];
                });
              });
            }
            if (typeof module !== "undefined") module.exports = { getStreams: getStreams };
        """
        val out = Nuvio.run(script, "getStreams(\"tt1\", \"movie\", null, null)",
            fetch = fake(mapOf("https://example.org/tt1" to """{"title":"Film","url":"https://cdn.example.org/a.m3u8"}"""), seen))!!
        assertEquals(1, out.length())
        val s = out.getJSONObject(0)
        assertEquals("https://cdn.example.org/a.m3u8", s.getString("url"))
        assertEquals("Test ok", s.getString("name"))
        assertTrue(s.getString("title").endsWith("a=1, b=2"))
        assertEquals("POST", seen.single().getString("method"))
        assertEquals("q=tt1", seen.single().getString("body"))
        assertEquals("https://example.org", seen.single().getJSONObject("headers").getString("Referer"))
    }

    @Test fun pluginStreamsReadAsAnAddOnsWithTheirHeaders() {
        val found = org.json.JSONArray("""[{"name":"​‌💎 VixSrc","quality":"1080p","title":"Film\n🎬 HLS","url":"https://cdn/x.m3u8",
            "headers":{"Referer":"https://vixsrc.to/"}}, {"name":"no address"}]""")
        val streams = Stremio.streams(Nuvio.asStremio(found), vod = true)
        assertEquals(1, streams.size)
        assertEquals("💎 VixSrc 1080p · Film", streams[0].title)
        assertEquals("https://vixsrc.to/", streams[0].headers["Referer"])
    }

    @Test fun repositoryAndWhatEachPluginServes() {
        val repo = Nuvio.repo("https://raw.githubusercontent.com/a/b/main/manifest.json", JSONObject("""
            {"name":"Repo","version":"1","scrapers":[
              {"id":"p1","name":"P1","filename":"providers/p1.js","supportedTypes":["movie","tv"],"idPrefixes":["tmdb:","tt"]},
              {"id":"bad","filename":"../escape.js"},
              {"id":"live","name":"Live","filename":"live.js","supportedTypes":["tv"],"idPrefixes":["tv:"]}]}"""))
        assertEquals(listOf("p1", "live"), repo.scrapers.map { it.id })
        val p1 = repo.scrapers[0]
        assertTrue(Nuvio.provides(p1, "movie", "tt0111161"))
        assertTrue(Nuvio.provides(p1, "series", "tt2531336:1:1"))
        assertTrue(!Nuvio.provides(p1, "tv", "tv:rai1")) // its "tv" is series; a channel's id isn't one of its
        assertTrue(Nuvio.provides(repo.scrapers[1], "tv", "tv:rai1"))
        val anyId = Nuvio.Scraper("y", "Y", "", "1", "y.js", listOf("movie", "tv"), null, true, emptyList())
        assertTrue(Nuvio.provides(anyId, "series", "tt0944947:1:1"))
        assertTrue(!Nuvio.provides(anyId, "tv", "vavoo_SKY|group:it")) // "tv" without ids of its own: series, not channels
        assertTrue(!Nuvio.provides(anyId, "movie", "jstrm:abc")) // an add-on's own id: no TMDB id to give it
        assertEquals("https://h/x/manifest.json", Nuvio.manifestUrl("https://h/x/"))
        assertEquals("https://h/x/repo.json", Nuvio.manifestUrl(" https://h/x/repo.json "))
    }

    init {
        // The modules as the app has them (assets/nuvio), read from the source tree.
        Nuvio.modules = { file -> java.io.File("src/main/assets/nuvio/$file").takeIf { it.exists() }?.readText() }
    }

    @Test fun urlsModulesAndExports() {
        val script = """
            var cheerio = require("cheerio-without-node-native");
            var CryptoJS = require("crypto-js");
            function getStreams() {
              var u = new URL("../b/c.m3u8?x=1&y=a+b#t", "https://Host.example:443/a/p/index.html");
              u.searchParams.set("x", "2");
              var q = new URLSearchParams({ q: "il padrino", n: 1 });
              var ${'$'} = cheerio.load('<div><a class="s" href="/v/1">Uno</a><a class="s" href="/v/2">Due</a></div>');
              var links = ${'$'}("a.s").map(function (i, el) { return ${'$'}(el).attr("href"); }).get();
              var key = CryptoJS.enc.Utf8.parse("0123456789abcdef");
              var sealed = CryptoJS.AES.encrypt("segreto", key, { iv: key, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7 }).toString();
              var open = CryptoJS.AES.decrypt(sealed, key, { iv: key, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7 }).toString(CryptoJS.enc.Utf8);
              return Promise.resolve([{ url: u.href, name: q.toString(), title: links.join(",") + "|" + open + "|" + u.origin + "|" + u.searchParams.get("y") }]);
            }
            module.exports = { getStreams };
        """
        val s = Nuvio.run(script, "(typeof getStreams === \"function\" ? getStreams : module.exports.getStreams)()",
            fetch = fake(emptyMap()))!!.getJSONObject(0)
        assertEquals("https://host.example/a/b/c.m3u8?x=2&y=a+b#t", s.getString("url"))
        assertEquals("q=il+padrino&n=1", s.getString("name"))
        assertEquals("/v/1,/v/2|segreto|https://host.example|a b", s.getString("title"))
    }

    /**
     * Every plugin of a repository on a film and an episode, over the network: run with NUVIO_LIVE=1 and, for another
     * repository than yoruix's, NUVIO_REPO=<its manifest>. Prints what each found.
     */
    @Test fun repositoryOverTheNetwork() {
        assumeTrue(System.getenv("NUVIO_LIVE") == "1")
        val url = System.getenv("NUVIO_REPO") ?: "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json"
        val dir = java.nio.file.Files.createTempDirectory("nuvio").toFile()
        val repo = Nuvio.update(dir, url)!!
        val only = System.getenv("NUVIO_ONLY")?.split(',')?.toSet()
        if (only != null) Nuvio.console = { println("  js: $it") }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val jobs = repo.scrapers.filter { only == null || it.id in only }.map { s ->
            s to pool.submit(java.util.concurrent.Callable {
                val film = if (Nuvio.provides(s, "movie", "tt0137523")) Nuvio.streams(dir, url, s, s.name, "movie", "tt0137523", null).size.toString() else "-"
                val episode = if (Nuvio.provides(s, "series", "tt0944947:1:1")) Nuvio.streams(dir, url, s, s.name, "series", "tt0944947:1:1", null).size.toString() else "-"
                film to episode
            })
        }
        println("plugin                film(Fight Club)  episode(GoT 1x1)")
        for ((s, job) in jobs) {
            val (film, episode) = runCatching { job.get(90, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "err" to "err" }
            println("%-22s %-17s %s".format(s.id, film, episode))
        }
        pool.shutdownNow()
    }

    /** The real PriSynx plugins, over the network: run with NUVIO_LIVE=1 (not in CI, where sites may block or change). */
    @Test fun prisynxFindsStreams() {
        assumeTrue(System.getenv("NUVIO_LIVE") == "1")
        val base = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/providers/"
        for (file in listOf("vixsrc.js", "raiplay.js")) {
            val script = Nuvio.httpFetch(JSONObject().put("url", base + file)).text()
            val movie = Nuvio.run(script, "getStreams(\"603\", \"movie\", null, null)") // The Matrix
            println("$file movie: ${movie?.length()} ${movie?.optJSONObject(0)?.optString("url")}")
        }
        val script = Nuvio.httpFetch(JSONObject().put("url", base + "vixsrc.js")).text()
        val episode = Nuvio.run(script, "getStreams(\"1399\", \"tv\", 1, 1)") // Game of Thrones 1x1
        println("vixsrc episode: ${episode?.length()} ${episode?.optJSONObject(0)?.optString("url")}")
        assertTrue((episode?.length() ?: 0) > 0)
    }
}
