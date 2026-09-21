package eu.kanade.tachiyomi.animeextension.en.aniwatch

import android.util.Base64
import aniyomi.lib.omniembedextractor.OmniEmbedExtractor
import aniyomi.lib.rapidcloudextractor.RapidCloudExtractor
import aniyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class AniWatch : AnimeHttpLegacySource() {
    override val name = "AniWatch"
    override val baseUrl = "https://aniwatch.co.at"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val vidsrc by lazy { VidsrcExtractor(client, headers) }
    private val omni by lazy { OmniEmbedExtractor(client, headers) }
    private val rapidCloud by lazy { RapidCloudExtractor(client, headers, preferences) }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/most-popular-anime/" else "$baseUrl/most-popular-anime/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        response.asJsoup().parseListing()

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/recently-updated/" else "$baseUrl/recently-updated/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        response.asJsoup().parseListing()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return latestUpdatesRequest(page)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
                addPathSegment("")
            }
            addQueryParameter("s", query)
            addQueryParameter("post_type", "anime")
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        response.asJsoup().parseListing()

    override fun getFilterList() = AnimeFilterList()

    private fun Document.parseListing(): AnimesPage {
        val cards = select(".flw-item, main article").filter {
            it.selectFirst("a[href*='/anime/']") != null
        }
        val items = if (cards.isNotEmpty()) {
            cards.mapNotNull(::animeFromElement)
        } else {
            select("a[href*='/anime/']:has(img)").mapNotNull { link ->
                animeFromLink(link, link.selectFirst("img"), link.parent() ?: link)
            }
        }.distinctBy { it.url }

        val next = selectFirst("a[rel=next], .pagination a.next, a.next.page-numbers, li.page-item a[title=Next]") != null
        return AnimesPage(items, next)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val link = element.selectFirst("a[href*='/anime/']") ?: return null
        return animeFromLink(link, element.selectFirst("img"), element)
    }

    private fun animeFromLink(link: Element, image: Element?, scope: Element): SAnime? {
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null
        val title = scope.selectFirst(".film-name, h2, h3, h4")?.text()?.takeIf(String::isNotBlank)
            ?: link.attr("title").takeIf(String::isNotBlank)
            ?: link.attr("data-jname").takeIf(String::isNotBlank)
            ?: link.text().takeIf(String::isNotBlank)
            ?: image?.attr("alt")?.takeIf(String::isNotBlank)
            ?: return null
        val thumb = image?.let {
            it.attr("abs:data-src").ifBlank {
                it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } }
            }
        }
        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = thumb
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val info = doc.select(".anisc-info, .film-infor, .film-information, .anime-info").text()
        return SAnime.create().apply {
            title = doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            thumbnail_url = doc.selectFirst(".anisc-poster img, .film-poster img, .poster img")?.let {
                it.attr("abs:data-src").ifBlank {
                    it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } }
                }
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst(".film-description .text, .description, [itemprop=description]")?.text()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select("a[href*='/genre/']").map(Element::text).filter(String::isNotBlank).distinct().joinToString()
                .takeIf(String::isNotBlank)
            author = doc.select("a[href*='/studio/'], a[href*='/producer/']").map(Element::text).filter(String::isNotBlank).distinct().joinToString()
                .takeIf(String::isNotBlank)
            status = when {
                info.contains("Currently Airing", true) -> SAnime.ONGOING
                info.contains("Finished Airing", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("REST-backed episode list")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val details = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().asJsoup()
        val firstUrl = details.selectFirst("a[href*='episode-1'], a.btn-play[href], a[href*='episode-'][href]")
            ?.let { it.attr("abs:href").ifBlank { it.attr("href") } }
            ?: return emptyList()

        val episodeResponse = client.newCall(GET(absoluteUrl(firstUrl), headers)).awaitSuccess()
        val raw = episodeResponse.body.string()
        val pageEpisodes = parseEpisodes(Jsoup.parse(raw, baseUrl))
        if (pageEpisodes.isNotEmpty()) return pageEpisodes

        val animeId = extractAnimeId(raw) ?: return emptyList()
        val payload = client.newCall(
            GET(baseUrl + "/wp-json/hianime/v1/episode/list/" + animeId, ajaxHeaders(absoluteUrl(firstUrl))),
        ).awaitSuccess().parseAs<HtmlPayload>()
        return parseEpisodes(Jsoup.parse(payload.html, baseUrl))
    }

    private fun parseEpisodes(doc: Document): List<SEpisode> =
        doc.select("a.ep-item[data-id], a[data-id][data-number], .ss-list a[data-id]").mapNotNull { element ->
            val id = element.attr("data-id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            val number = element.attr("data-number").toFloatOrNull()
                ?: Regex("""(?:Episode|Ep\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(element.text())?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: return@mapNotNull null
            if (href.isBlank()) return@mapNotNull null
            val title = element.attr("title").takeIf(String::isNotBlank)
                ?: element.selectFirst(".ep-name, .film-name")?.text()
            SEpisode.create().apply {
                url = id + "::" + cleanUrlWithoutDomain(href)
                episode_number = number
                name = "Episode " + cleanNumber(number) + if (title.isNullOrBlank()) "" else ": " + title
            }
        }.distinctBy { it.url.substringBefore("::") }.sortedByDescending { it.episode_number }

    override fun getEpisodeUrl(episode: SEpisode): String =
        baseUrl + episode.url.substringAfter("::", episode.url)

    private fun extractAnimeId(html: String): String? =
        listOf(
            Regex("""["']anime_id["']\s*:\s*["']?(\d+)"""),
            Regex("""anime_id\s*[:=]\s*["']?(\d+)"""),
            Regex("""data-anime-id=["'](\d+)["']"""),
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }

    override fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("REST-backed video list")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val id = episode.url.substringBefore("::")
        val payload = client.newCall(
            GET(baseUrl + "/wp-json/hianime/v1/episode/servers/" + id, ajaxHeaders(getEpisodeUrl(episode))),
        ).awaitSuccess().parseAs<HtmlPayload>()

        val links = Jsoup.parse(payload.html, baseUrl).select("[data-hash]").mapNotNull { element ->
            val link = decodeHash(element.attr("data-hash")) ?: return@mapNotNull null
            val ancestry = element.parents().joinToString(" ") { it.className() }
            val type = when {
                ancestry.contains("dub", true) -> "Dub"
                ancestry.contains("raw", true) -> "Raw"
                else -> "Sub"
            }
            val name = element.attr("data-server").takeIf(String::isNotBlank)
                ?: element.attr("title").takeIf(String::isNotBlank)
                ?: element.text().trim().takeIf(String::isNotBlank)
                ?: "Server"
            ServerLink(name, type, link)
        }.distinctBy { it.link }

        return links.parallelCatchingFlatMap(::extractServer).distinctBy { it.videoUrl }
    }

    private suspend fun extractServer(source: ServerLink): List<Video> {
        val link = source.link
        val lower = link.lowercase()
        if (lower.contains(".m3u8") || lower.substringBefore("?").endsWith(".mp4")) {
            return listOf(Video(link, source.type + " - " + source.name, link, headers = headers))
        }
        val host = link.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return runCatching {
            when {
                "vidsrc" in host -> vidsrc.videosFromUrl(link, source.name, source.type)
                "rapid-cloud" in host -> rapidCloud.getVideosFromUrl(link, source.type, source.name)
                else -> omni.extractVideos(link, source.type + " - " + source.name, emptyList())
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeHash(hash: String): String? {
        if (hash.isBlank()) return null
        for (flag in listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)) {
            val value = runCatching { String(Base64.decode(hash, flag), Charsets.UTF_8).trim() }.getOrNull()
            if (value != null && (value.startsWith("http://") || value.startsWith("https://"))) return value
        }
        return null
    }

    private fun ajaxHeaders(referer: String): Headers = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Referer", referer)
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun absoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:" + url
        else -> baseUrl + "/" + url.trimStart('/')
    }

    private fun cleanUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig)
        uri.rawPath.orEmpty() +
            (uri.rawQuery?.let { "?" + it } ?: "") +
            (uri.rawFragment?.let { "#" + it } ?: "")
    } catch (_: Throwable) {
        orig
    }

    private fun cleanNumber(value: Float): String =
        if (value % 1F == 0F) value.toInt().toString() else value.toString()

    @Serializable
    private data class HtmlPayload(val html: String = "")

    private data class ServerLink(val name: String, val type: String, val link: String)
}
