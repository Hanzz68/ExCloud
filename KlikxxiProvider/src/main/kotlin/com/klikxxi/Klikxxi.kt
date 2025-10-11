package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

open class Klikxxi : MainAPI() {

    override var mainUrl = "https://www.klikxxi.com"
    private var directUrl: String? = null
    override var name = "Klikxxi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/?s&search=advanced&post_type=movie" to "All Movies",
        "$mainUrl/?s=&search=advanced&post_type=movie&orderby=&genre=&movieyear=&country=&quality=&paged=%d" to "All Movies (Alt)",
        "$mainUrl/series/page/%d/" to "All Series",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/adventure/page/%d/" to "Adventure",
        "$mainUrl/genre/animation/page/%d/" to "Animation",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/drama/page/%d/" to "Drama",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/family/page/%d/" to "Family",
        "$mainUrl/genre/horror/page/%d/" to "Horror",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/sci-fi/page/%d/" to "Science Fiction",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/country/asia/page/%d/" to "Asia",
        "$mainUrl/country/india/page/%d/" to "India",
        "$mainUrl/country/korea/page/%d/" to "Korea",
        "$mainUrl/country/china/page/%d/" to "China",
        "$mainUrl/country/europe/page/%d/" to "Europe",
        "$mainUrl/western/page/%d/" to "Western Movies",
        "$mainUrl/asian/page/%d/" to "Asian Movies",
        "$mainUrl/western-series/page/%d/" to "Western Series",
        "$mainUrl/korean-series/page/%d/" to "Korean Series",
        "$mainUrl/india-series/page/%d/" to "India Series",
        "$mainUrl/asia-series/page/%d/" to "Asia Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val document = app.get(fixUrl(url)).document
        val list = document.select("article.item, div.gmr-item, div.item-movie, div.item-series").mapNotNull { it.toSearchResult() }
        return HomePageResponse(listOf(HomePageList(request.name, list, isHorizontalImages = false)))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("h2.entry-title > a, a[title], a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val rawTitle = link.attr("title").ifBlank { link.text() }.trim()
        val title = rawTitle.removePrefix("Permalink to: ").trim()
        if (title.isBlank()) return null
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")
                ?.ifBlank { img.attr("data-lazy-src") }
                ?.ifBlank { img.attr("src") }
                ?: this.selectFirst("a > img")?.attr("src")
        )?.fixImageQuality()
        val quality = this.selectFirst("span.gmr-quality-item")?.text()?.trim()
            ?: this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        val isSeries = this.selectFirst(".gmr-posttype-item")?.text()?.contains("TV", true) == true || href.contains("/series/") || href.contains("/tv/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.otherInfo = if (quality.isNullOrBlank()) null else quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: link.attr("title").ifBlank { link.text() }.trim()
        val href = fixUrl(link.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.attr("data-src") ?: this.selectFirst("a > img")?.attr("src"))?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item, div.gmr-item, div.item-movie, div.item-series").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title, div.mvic-desc h3")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.substringBefore("(")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.attr("data-src") ?: document.selectFirst("figure.pull-left > img")?.attr("src"))?.fixImageQuality()
        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p")?.text()?.trim()
        val tags = document.select("div.gmr-moviedata strong:contains(Genre:) > a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()?.toInt()
        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a").map { it.text() }.takeIf { it.isNotEmpty() }
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()
        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)").find(seasonTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val eps = block.select("div.gmr-season-episodes a").filter { a ->
                val t = a.text().lowercase()
                !t.contains("view all") && !t.contains("batch")
            }.mapIndexedNotNull { index, epLink ->
                val href = epLink.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapIndexedNotNull null
                val name = epLink.text().trim()
                val episodeNum = Regex("E(p|ps)?(\\d+)").find(name)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (index + 1)
                newEpisode(href) {
                    this.name = name
                    this.season = seasonNumber
                    this.episode = episodeNum
                }
            }
            allEpisodes.addAll(eps)
        }

        val episodes = allEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val tvType = if (episodes.isNotEmpty() || url.contains("/series/") || url.contains("/tv/")) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating
                addActors(actors)
                addTrailer(trailer)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (postId.isNullOrBlank()) {
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

    private fun Element.getImageAttr(): String? {
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
