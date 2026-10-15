package com.flixworld.adult

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.UUID

/**
 * Flix World — Adult. ONE provider combining:
 *   - OK.XXX   (ok.xxx, KVS/Streamray behind Cloudflare)  -> "Newest" / "Popular"
 *   - FikFap   (api.fikfap.com)                            -> "Shorts"
 *
 * Requests are routed by URL: ok.xxx <-> /video/, fikfap <-> /post/.
 * The loadLinks `data` string is tagged ("ok|..." / "fik|...") so playback
 * dispatches to the right resolver.
 */
class FlixWorldAdult : MainAPI() {
    override var mainUrl = "https://ok.xxx"
    override var name = "FlixWorld Adult"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    // ---- OK.XXX ----------------------------------------------------------
    private val cf = CloudflareKiller()
    private val okHeaders = mapOf(
        "User-Agent" to OK_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    // ---- FikFap ----------------------------------------------------------
    private val fikApi = "https://api.fikfap.com"
    private val fikWeb = "https://fikfap.com"
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private val anon: String by lazy { UUID.randomUUID().toString() }

    private fun fikApiHeaders() = mapOf(
        "User-Agent" to FFK_UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to fikWeb,
        "Referer" to "$fikWeb/",
        "Authorization-Anonymous" to anon,
    )

    private fun fikMediaHeaders() = mapOf(
        "User-Agent" to FFK_UA,
        "Origin" to fikWeb,
        "Referer" to "$fikWeb/",
    )

    override val mainPage = mainPageOf("home" to "Home")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = ArrayList<HomePageList>()

        try {
            val items = app.get("$mainUrl/videos/", headers = okHeaders, interceptor = cf)
                .document.parseOkCards()
            if (items.isNotEmpty()) rows.add(HomePageList("Newest", items, true))
        } catch (_: Exception) {
        }

        try {
            val items = app.get("$mainUrl/popular/", headers = okHeaders, interceptor = cf)
                .document.parseOkCards()
            if (items.isNotEmpty()) rows.add(HomePageList("Popular", items, true))
        } catch (_: Exception) {
        }

        try {
            val items = fikFeed(30).mapNotNull { it.toSearch() }
            if (items.isNotEmpty()) rows.add(HomePageList("Shorts", items, false))
        } catch (_: Exception) {
        }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val out = ArrayList<SearchResponse>()
        try {
            out.addAll(okSearch(q))
        } catch (_: Exception) {
        }
        try {
            out.addAll(fikSearch(q))
        } catch (_: Exception) {
        }
        return out
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---- OK.XXX ----------------------------------------------------------

    private suspend fun okSearch(q: String): List<SearchResponse> {
        val url = "$mainUrl/search/${URLEncoder.encode(q, "UTF-8")}/"
        return app.get(url, headers = okHeaders, interceptor = cf).document.parseOkCards()
    }

    private fun Document.parseOkCards(): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (card in select(".item.thumb-bl-video")) {
            val a = card.selectFirst("a[href^=/video/]") ?: continue
            val id = Regex("/video/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue

            val title = a.attr("title").ifBlank { a.text() }.ifBlank { "Video $id" }
            val thumb = okHiRes(
                card.selectFirst("img.thumb")?.let {
                    it.attr("data-original")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("src") }
                } ?: ""
            )
            out.add(newMovieSearchResponse(title, "$mainUrl/video/$id/", TvType.NSFW) {
                this.posterUrl = thumb
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            })
        }
        return out
    }

    private suspend fun loadOk(url: String): LoadResponse? {
        val doc = app.get(url, headers = okHeaders, interceptor = cf).document
        val vid = Regex("/video/(\\d+)").find(url)?.groupValues?.get(1)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifBlank { "OK.XXX $vid" }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        doc.selectFirst("#my-video")?.let { if (it.attr("poster").isNotBlank()) poster = it.attr("poster") }
        poster = okHiRes(poster)

        var desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
        if (desc == title) desc = ""

        val tags = doc.select(".video-tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length < 30 && !it.equals("tags:", true) }
            .take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, "ok|$url") {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = desc
            this.tags = tags
        }
    }

    private suspend fun okLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(url, headers = okHeaders, interceptor = cf).document
        val gate = page.selectFirst("#my-video source[src]")?.attr("src").orEmpty()
            .ifBlank { page.selectFirst("video source[src]")?.attr("src").orEmpty() }
        if (gate.isBlank()) return false

        val master = app.get(
            if (gate.startsWith("http")) gate else mainUrl + gate,
            headers = mapOf("Referer" to "$mainUrl/", "Accept" to "*/*"),
            allowRedirects = true,
            interceptor = cf,
        ).url
        if (master.isBlank()) return false

        callback.invoke(newExtractorLink(name, "Auto", master, ExtractorLinkType.M3U8) {
            this.referer = "$mainUrl/"
            this.quality = Qualities.Unknown.value
        })

        try {
            val man = app.get(
                master,
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "*/*"),
                interceptor = cf,
            ).text
            Regex("#EXT-X-STREAM-INF:([^\\n]*)\\n(\\S+)").findAll(man).forEachIndexed { i, m ->
                val res = Regex("RESOLUTION=\\d+x(\\d+)").find(m.groupValues[1])?.groupValues?.get(1)
                val uri = m.groupValues[2].trim()
                val vu = if (uri.startsWith("http")) uri else master.substringBeforeLast("/") + "/" + uri
                callback.invoke(
                    newExtractorLink(
                        name,
                        if (res != null) "${res}p" else "Auto ${i + 1}",
                        vu,
                        ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = res?.toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {
        }

        return true
    }

    private fun okHiRes(url: String): String {
        if (url.isBlank()) return ""
        for (size in listOf("320x180", "640x360")) {
            if (url.contains("/$size/")) return url.replace("/$size/", "/960x540/")
        }
        return url
    }

    // ---- FikFap ----------------------------------------------------------

    private suspend fun fikFeed(amount: Int): List<FPost> {
        val body = app.get(
            "$fikApi/posts",
            params = mapOf("amount" to amount.coerceIn(1, 40).toString()),
            headers = fikApiHeaders(),
        ).text
        val list: List<FPost> = mapper.readValue(body)
        return list.filter { it.isPlayable() }
    }

    private suspend fun fikSearch(q: String): List<SearchResponse> {
        val body = app.get("$fikApi/search", params = mapOf("q" to q), headers = fikApiHeaders()).text
        val d: FSearch = mapper.readValue(body)
        return (d.posts ?: emptyList()).filter { it.isPlayable() }.mapNotNull { it.toSearch() }
    }

    private fun FPost.toSearch(): SearchResponse? {
        val id = postId ?: return null
        val title = label?.takeIf { it.isNotBlank() } ?: ("@" + (author?.username ?: "fikfap"))
        return newMovieSearchResponse(title, "$fikWeb/post/$id", TvType.NSFW) {
            this.posterUrl = thumbnailStreamUrl
            this.posterHeaders = fikMediaHeaders()
        }
    }

    private fun FPost.isPlayable(): Boolean {
        val v = videoStreamUrl ?: return false
        return bunnyVideoId != null && deletedAt == null && v.startsWith("http") && v.contains(".m3u8")
    }

    private suspend fun loadFik(url: String): LoadResponse? {
        val id = Regex("/post/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val body = app.get("$fikApi/posts/$id", headers = fikApiHeaders()).text
        val p: FPost = mapper.readValue(body)
        if (!p.isPlayable()) return null
        return newMovieLoadResponse(
            p.label?.takeIf { it.isNotBlank() } ?: "FikFap #$id",
            url,
            TvType.NSFW,
            "fik|$url",
        ) {
            this.posterUrl = p.thumbnailStreamUrl
            this.posterHeaders = fikMediaHeaders()
            this.plot = p.author?.description?.trim().orEmpty()
            this.tags = (p.hashtags ?: emptyList()).mapNotNull { it.label }
        }
    }

    private suspend fun fikLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("/post/(\\d+)").find(url)?.groupValues?.get(1) ?: return false
        val body = app.get("$fikApi/posts/$id", headers = fikApiHeaders()).text
        val p: FPost = mapper.readValue(body)
        val master = p.videoStreamUrl ?: return false
        callback.invoke(newExtractorLink(name, "Auto", master, ExtractorLinkType.M3U8) {
            this.referer = "$fikWeb/"
            this.headers = fikMediaHeaders()
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    // ---- router ----------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("fikfap.com")) loadFik(url) else loadOk(url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sep = data.indexOf('|')
        val src = if (sep > 0) data.substring(0, sep) else ""
        val rest = if (sep > 0) data.substring(sep + 1) else data
        return when {
            src == "fik" -> fikLinks(rest, callback)
            src == "ok" -> okLinks(rest, callback)
            rest.contains("fikfap.com") -> fikLinks(rest, callback)
            else -> okLinks(rest, callback)
        }
    }

    // ---- DTOs ------------------------------------------------------------

    data class FAuthor(
        val username: String? = null,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val isVerified: Boolean? = null,
    )

    data class FHashtag(val label: String? = null)

    data class FPost(
        val postId: Long? = null,
        val label: String? = null,
        val thumbnailStreamUrl: String? = null,
        val videoStreamUrl: String? = null,
        val bunnyVideoId: String? = null,
        val deletedAt: String? = null,
        val likesCount: Int? = null,
        val viewsCount: Int? = null,
        val author: FAuthor? = null,
        val hashtags: List<FHashtag>? = null,
    )

    data class FSearch(val posts: List<FPost>? = null)

    companion object {
        const val OK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val FFK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
