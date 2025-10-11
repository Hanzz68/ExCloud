package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

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
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item, div.gmr-item, div.item-movie, div.item-series")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href][title], h2.entry-title > a") ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = linkElement.attr("title").removePrefix("Permalink to: ").ifBlank { linkElement.text() }.trim()
        if (title.isBlank()) return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("abs:data-src") ?: it.attr("abs:data-lazy-src") ?: it.attr("abs:src") ?: it.attr("abs:srcset")?.split(",")?.firstOrNull()
        }?.fixImageQuality()
        val quality = this.select("span.gmr-quality-item, div.gmr-qual > a").text().trim().replace("-", "")
        val typeText = this.selectFirst(".gmr-posttype-item")?.text()?.trim()
        val isSeries = typeText.equals("TV Show", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotEmpty()) addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item, div.gmr-item, div.item-movie, div.item-series").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("abs:data-src") ?: it.attr("abs:data-lazy-src") ?: it.attr("abs:src") ?: it.attr("abs:srcset")?.split(",")?.firstOrNull()
        }?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title, div.mvic-desc h3")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.substringBefore("(")?.trim().orEmpty()
        val poster = document.selectFirst("figure.pull-left > img")?.let {
            it.attr("abs:data-src") ?: it.attr("abs:data-lazy-src") ?: it.attr("abs:src") ?: it.attr("abs:srcset")?.split(",")?.firstOrNull()
        }?.fixImageQuality()
        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata strong:contains(Genre:) > a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().toIntOrNull()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text() }.takeIf { it.isNotEmpty() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()
        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)").find(seasonTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val eps = block.select("div.gmr-season-episodes a").filter { a -> !a.text().lowercase().contains("view all") && !a.text().lowercase().contains("batch") }
                .mapIndexedNotNull { index, epLink ->
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
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; if(rating != null) addScore(rating.toString(),10); addActors(actors); this.recommendations = recommendations }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year; addActors(actors); addTrailer(trailer); if(rating != null) addScore(rating.toString(),10); this.recommendations = recommendations }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if(postId.isNullOrBlank()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val iframe = app.get(fixUrl(ele.attr("href"))).document.selectFirst("div.gmr-embed-responsive iframe")?.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").forEach { ele ->
                val server = app.post("$directUrl/wp-admin/admin-ajax.php", data = mapOf("action" to "muvipro_player_content", "tab" to ele.attr("id"), "post_id" to "$postId")).document.select("iframe").attr("src").let { httpsify(it) }
                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }
        return true
    }

    private fun String?.fixImageQuality(): String? { if(this==null) return null; val regex=Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this; return this.replace(regex,"") }
    private fun getBaseUrl(url: String): String { return URI(url).let { "${it.scheme}://${it.host}" } }
    private fun Element?.getIframeAttr(): String? { return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty()==true } ?: this?.attr("src") }

}
