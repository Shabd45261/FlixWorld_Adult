package com.flixworld.adult

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.util.UUID

/**
 * FikFap Shorts provider — direct port of fikfap_shorts.py.
 *
 * api.fikfap.com is a pure client-side app; anonymous auth is a bare UUID4 sent
 * as `Authorization-Anonymous`. The feed is an `afterId` cursor (page is
 * ignored). Kept stable across restarts via the ffk anon key.
 *
 * The CDN (vz-*.b-cdn.net) answers 403 unless the request carries
 * `Origin: https://fikfap.com`, so every media URL is emitted with those
 * headers. Tokens are minted for India (`token_countries=IN`).
 *
 * Ads / livestream promos are filtered out exactly like the app does.
 */
class FikfapShortsProvider : MainAPI() {
    override var mainUrl = "https://fikfap.com"
    override var name = "FikFap Shorts"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val api = "https://api.fikfap.com"

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val anon: String
        get() {
            val key = "flix_fikfap_anon"
            val cur = getKey<String>(key)
            if (!cur.isNullOrBlank()) return cur
            val v = UUID.randomUUID().toString()
            setKey(key, v)
            return v
        }

    private fun apiHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Authorization-Anonymous" to anon,
    )

    private fun mediaHeaders() = mapOf(
        "User-Agent" to UA,
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf("feed" to "Shorts")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = ArrayList<HomePageList>()
        val feed = feed(amount = 28).mapNotNull { it.toSearch() }
        if (feed.isNotEmpty()) rows.add(HomePageList("Shorts", feed, false))
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val body = app.get("$api/search", params = mapOf("q" to q), headers = apiHeaders()).text
        val d: FSearch = mapper.readValue(body)
        return (d.posts ?: emptyList()).filter { it.isPlayable() }.mapNotNull { it.toSearch() }
    }

    private suspend fun feed(amount: Int, after: Long? = null): List<FPost> {
        val params = LinkedHashMap<String, String>()
        params["amount"] = amount.coerceIn(1, 40).toString()
        if (after != null) params["afterId"] = after.toString()
        val body = app.get("$api/posts", params = params, headers = apiHeaders()).text
        val list: List<FPost> = mapper.readValue(body)
        return list.filter { it.isPlayable() }
    }

    private fun FPost.toSearch(): SearchResponse? {
        val id = postId ?: return null
        val title = label?.takeIf { it.isNotBlank() } ?: ("@" + (author?.username ?: "fikfap"))
        return newMovieSearchResponse(title, "$mainUrl/post/$id", TvType.NSFW) {
            this.posterUrl = thumbnailStreamUrl
            this.posterHeaders = mediaHeaders()
        }
    }

    private fun FPost.isPlayable(): Boolean {
        val v = videoStreamUrl ?: return false
        return bunnyVideoId != null &&
            deletedAt == null &&
            v.startsWith("http") &&
            v.contains(".m3u8")
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("/post/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val body = app.get("$api/posts/$id", headers = apiHeaders()).text
        val p: FPost = mapper.readValue(body)
        if (!p.isPlayable()) return null
        return newMovieLoadResponse(
            p.label?.takeIf { it.isNotBlank() } ?: "FikFap #$id",
            url,
            TvType.NSFW,
            p.videoStreamUrl ?: url,
        ) {
            this.posterUrl = p.thumbnailStreamUrl
            this.posterHeaders = mediaHeaders()
            this.plot = p.author?.description?.trim().orEmpty()
            this.tags = (p.hashtags ?: emptyList()).mapNotNull { it.label }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val master = data
        if (!master.startsWith("http") || !master.contains(".m3u8")) return false
        callback.invoke(
            newExtractorLink(name, "Auto", master, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = mediaHeaders()
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // ---- DTOs (fikfap /posts + /search) ----------------------------------
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
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
