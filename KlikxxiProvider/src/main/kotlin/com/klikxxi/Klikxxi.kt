package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element

class Klikxxi : MainAPI() {
    override var mainUrl = "https://www.klikxxi.com"
    private var directUrl: String? = null
    override var name = "Klikxxi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "page/%d/?s&search=advanced&post_type=movie" to "All Movies",
        "asian/page/%d/" to "Asian Movies",
        "western/page/%d/" to "Western Movies",
        "india/page/%d/" to "India Movies",
        "korean/page/%d/" to "Korean Movies",
        "series/page/%d/" to "All Series",
        "western-series/page/%d/" to "Western Series",
        "korean-series/page/%d/" to "Korean Series",
        "india-series/page/%d/" to "India Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = if (page == 1) request.data.replace("/page/%d/", "") else request.data.format(page)
        val url = "$mainUrl/$data"
        val document = app.get(url).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href][title]") ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = linkElement.attr("title").removePrefix("Permalink to: ").ifBlank { linkElement.text() }.trim()
        if (title.isBlank()) return null
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.selectFirst("span.gmr-quality-item")?.text()?.trim()
        val typeText = this.selectFirst(".gmr-posttype-item")?.text()?.trim()
        val isSeries = typeText.equals("TV Show", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl; addQuality(quality ?: "") }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl; addQuality(quality ?: "") }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title, div.mvic-desc h3")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.substringBefore("(")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata strong:contains(Genre:) > a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().toIntOrNull()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()?.toInt()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text() }.takeIf { it.isNotEmpty() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toSearchResult() }

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()
        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)").find(seasonTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val eps = block.select("div.gmr-season-episodes a").filter { a -> val t = a.text().lowercase(); !t.contains("view all") && !t.contains("batch") }.mapIndexedNotNull { index, epLink ->
                val href = epLink.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapIndexedNotNull null
                val name = epLink.text().trim()
                val episodeNum = Regex("E(p|ps)?(\\d+)").find(name)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (index + 1)
                newEpisode(href) { this.name = name; this.season = seasonNumber; this.episode = episodeNum }
            }
            allEpisodes.addAll(eps)
        }

        val episodes = allEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; this.rating = rating; addActors(actors); this.recommendations = recommendations }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; this.rating = rating; addActors(actors); addTrailer(trailer); this.recommendations = recommendations }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (postId.isNullOrBlank()) return false
        document.select("div.tab-content-ajax").forEach { tab ->
            val tabId = tab.attr("id")
            if (tabId.isNullOrBlank()) return@forEach
            val response = app.post("$mainUrl/wp-admin/admin-ajax.php", data = mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to postId)).document
            val iframe = response.selectFirst("iframe")?.attr("src") ?: return@forEach
            val link = httpsify(iframe)
            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    private fun Element?.fixPoster(): String? {
        if (this == null) return null
        var link = when {
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("data-lazy-srcset") -> this.attr("abs:data-lazy-srcset").split(",").map { it.trim().split(" ")[0] }.lastOrNull()
            this.hasAttr("srcset") -> this.attr("abs:srcset").split(",").map { it.trim().split(" ")[0] }.lastOrNull()
            else -> this.attr("abs:src")
        }
        if (!link.isNullOrBlank()) {
            link = link.replace(Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))"), "")
            if (link.startsWith("//")) link = "https:$link"
        }
        return link
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true } ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
