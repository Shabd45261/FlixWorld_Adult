package com.flixworld.adult

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * OK.XXX provider — direct port of scrapers.py (the ok.xxx scraper).
 *
 * KVS/Streamray CMS behind Cloudflare, so every request goes through
 * CloudflareKiller.
 *
 *   cards     .item.thumb-bl-video -> a[href^=/video/<id>/]
 *   thumbs    static.ok.xxx/.../<WxH>/<n>.jpg ; the list markup only hands out
 *             640x360, so it is upgraded to 960x540 (1280x720+ 404s).
 *             static.ok.xxx 403s a foreign Referer -> posterHeaders.
 *   playback  #my-video source[src] = /get_file/13/<token>/....mp4/ gate, which
 *             302s to a SIGNED multi-bitrate HLS master on cdn.phcdn16.com.
 *             The gate 403s any Referer but ok.xxx, so it is resolved
 *             server-side and the signed master is handed to the player.
 */
class OkxxxProvider : MainAPI() {
    override var mainUrl = "https://ok.xxx"
    override var name = "OK.XXX"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cf = CloudflareKiller()

    private val hdrs = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )

    // Mirrors the app: the home feed merges newest + popular.
    override val mainPage = mainPageOf(
        "new" to "Newest",
        "popular" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = when (request.data) {
            "popular" -> if (page == 1) "/popular/" else "/popular/$page/"
            else -> if (page == 1) "/videos/" else "/videos/$page/"
        }
        val doc = app.get(mainUrl + path, headers = hdrs, interceptor = cf).document
        val items = doc.parseCards()
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val url = "$mainUrl/search/${URLEncoder.encode(q, "UTF-8")}/"
        val doc = app.get(url, headers = hdrs, interceptor = cf).document
        return doc.parseCards()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Document.parseCards(): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (card in select(".item.thumb-bl-video")) {
            val a = card.selectFirst("a[href^=/video/]") ?: continue
            val id = Regex("/video/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue

            val title = a.attr("title").ifBlank { a.text() }.ifBlank { "Video $id" }
            val thumb = hiResThumb(
                card.selectFirst("img.thumb")?.let {
                    it.attr("data-original")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("src") }
                } ?: ""
            )

            out.add(
                newMovieSearchResponse(title, "$mainUrl/video/$id/", TvType.NSFW) {
                    this.posterUrl = thumb
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            )
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = hdrs, interceptor = cf).document
        val vid = Regex("/video/(\\d+)").find(url)?.groupValues?.get(1)

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifBlank { "Video $vid" }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        doc.selectFirst("#my-video")?.let {
            if (it.attr("poster").isNotBlank()) poster = it.attr("poster")
        }
        poster = hiResThumb(poster)

        var desc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
        if (desc == title) desc = ""

        val tags = doc.select(".video-tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length < 30 && !it.equals("tags:", true) }
            .take(12)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(data, headers = hdrs, interceptor = cf).document
        val gate = page.selectFirst("#my-video source[src]")?.attr("src").orEmpty()
            .ifBlank { page.selectFirst("video source[src]")?.attr("src").orEmpty() }
            .ifBlank { page.selectFirst("#my-video source")?.attr("src").orEmpty() }
        if (gate.isBlank()) return false

        // Resolve the /get_file/ gate (server-side only) -> signed CDN master.
        val master = app.get(
            if (gate.startsWith("http")) gate else mainUrl + gate,
            headers = mapOf("Referer" to "$mainUrl/", "Accept" to "*/*"),
            allowRedirects = true,
            interceptor = cf,
        ).url

        if (master.isBlank()) return false

        // "Auto" = the signed multi-bitrate master (media3 does ABR itself).
        callback.invoke(
            newExtractorLink(name, "Auto", master, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Also expose each variant so a quality can be pinned manually.
        try {
            val man = app.get(
                master,
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "*/*"),
                interceptor = cf,
            ).text
            Regex("#EXT-X-STREAM-INF:([^\\n]*)\\n(\\S+)").findAll(man).forEachIndexed { i, m ->
                val attrs = m.groupValues[1]
                val uri = m.groupValues[2].trim()
                val res = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)
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

    private fun hiResThumb(url: String): String {
        if (url.isBlank()) return ""
        for (size in listOf("320x180", "640x360")) {
            if (url.contains("/$size/")) return url.replace("/$size/", "/960x540/")
        }
        return url
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
