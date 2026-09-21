package eu.kanade.tachiyomi.animeextension.id.nekopoi

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Adapted from the Apache-2.0 Dou Anime Extensions implementation,
 * with a smaller parser surface for Animetail testing.
 */
class Nekopoi : AnimeHttpLegacySource() {
    override val name = "Nekopoi"
    override val baseUrl = "https://nekopoi.care"
    override val lang = "id"
    override val supportsLatest = true

    private val dood by lazy { DoodExtractor(client) }
    private val streamWish by lazy { StreamWishExtractor(client, headers) }
    private val vidHide by lazy { VidHideExtractor(client, headers) }
    private val universal by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/hentai-list/?nk_page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val page = response.request.url.queryParameter("nk_page")?.toIntOrNull() ?: 1
        val all = doc.select(".nk-az-item a, a.nk-series-link")
            .mapNotNull(::animeFromElement)
            .distinctBy { it.url }

        val pageSize = 20
        val start = (page - 1) * pageSize
        if (start >= all.size) return AnimesPage(emptyList(), false)
        val end = minOf(start + pageSize, all.size)
        return AnimesPage(all.subList(start, end), end < all.size)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/?post_type=anime" else "$baseUrl/page/$page/?post_type=anime", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseListing(response.asJsoup())

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
        parseListing(response.asJsoup())

    override fun getFilterList() = AnimeFilterList()

    private fun parseListing(doc: Document): AnimesPage {
        val elements = doc.select(
            ".nk-post-card, div.nk-search-results ul li a.nk-search-item, a.nk-search-item, " +
                "div.nk-episode-grid ul li a.nk-episode-card, a.nk-episode-card",
        )
        val items = elements.mapNotNull(::animeFromElement).distinctBy { it.url }
        val next = doc.selectFirst(
            "nav.pagination .nav-links a.next.page-numbers, .pagination a.next, .page-numbers.next",
        ) != null
        return AnimesPage(items, next)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val link = if (element.tagName() == "a") {
            element
        } else {
            element.selectFirst("h2 a, .nk-post-meta h2 a, a") ?: return null
        }
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null

        val rawTitle = element.selectFirst("h2, .nk-episode-card-title")?.text()
            ?: link.text().takeIf(String::isNotBlank)
            ?: link.attr("title").takeIf(String::isNotBlank)
            ?: return null
        if (!validEntry(rawTitle, href)) return null

        return SAnime.create().apply {
            url = cleanUrlWithoutDomain(href)
            title = cleanTitle(rawTitle)
            thumbnail_url = extractThumbnail(element)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val rawTitle = doc.selectFirst(".nk-series-synopsis > b")?.text()
            ?: doc.selectFirst(".nk-post-header h1")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: "Unknown Title"

        val synopsis = mutableListOf<String>()
        doc.selectFirst(".nk-series-synopsis p")?.text()?.takeIf(String::isNotBlank)?.let(synopsis::add)
        doc.select(".konten p, .nk-post-body p, .entry-content p").forEach {
            val text = it.text().trim()
            if (text.isNotBlank() &&
                !text.startsWith("genre", true) &&
                !text.startsWith("size", true) &&
                !text.startsWith("duration", true) &&
                !text.startsWith("durasi", true)
            ) {
                synopsis.add(text)
            }
        }

        val metaText = doc.select(".nk-series-meta-list li").text()
        val genres = doc.select(".nk-series-meta-list li:contains(Genre) a, a[href*='/genres/']")
            .map(Element::text)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString()
        val producers = metadataValue(doc, "Produser", "Producers", "Producer")
        val actresses = metadataValue(doc, "Actress", "Actresses", "Artist", "Artis")

        return SAnime.create().apply {
            title = cleanTitle(rawTitle)
            thumbnail_url = extractBgUrl(doc.selectFirst(".nk-series-poster")?.attr("style").orEmpty())
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".nk-featured-img img, .featured-image img, .nk-post-thumb img")
                    ?.let { it.attr("abs:src").ifBlank { it.attr("src") } }
            description = synopsis.distinct().joinToString("\n\n").takeIf(String::isNotBlank)
            genre = genres.takeIf(String::isNotBlank)
            author = producers
            artist = actresses
            status = when {
                metaText.contains("completed", true) || metaText.contains("tamat", true) -> SAnime.COMPLETED
                metaText.contains("ongoing", true) || metaText.contains("berjalan", true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val cards = doc.select(".nk-episode-grid ul li a.nk-episode-card, .nk-episode-grid a.nk-episode-card")
        if (cards.isNotEmpty()) {
            return cards.mapIndexed { index, card ->
                val href = card.attr("abs:href").ifBlank { card.attr("href") }
                val title = card.selectFirst(".nk-episode-card-title")?.text().orEmpty()
                val badge = card.selectFirst(".nk-episode-badge")?.text().orEmpty()
                val number = episodeNumber(badge, title, (index + 1).toFloat())
                SEpisode.create().apply {
                    url = cleanUrlWithoutDomain(href)
                    episode_number = number
                    name = "Episode " + cleanNumber(number)
                }
            }.sortedByDescending { it.episode_number }
        }

        val current = response.request.url.toString()
        val title = doc.selectFirst(".nk-post-header h1, h1")?.text().orEmpty()
        val number = episodeNumber("", title, 1F)
        return listOf(
            SEpisode.create().apply {
                url = cleanUrlWithoutDomain(current)
                episode_number = number
                name = "Episode " + cleanNumber(number)
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val streamingServers = doc.select(
            "#nk-player .nk-player-frame iframe[src], .nk-player-frame iframe[src]",
        ).mapNotNull { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf(String::isNotBlank)
                ?.let { if (it.startsWith("//")) "https:$it" else it }
        }.filterNot(::isAdIframe)
            .distinct()

        val selected = streamingServers.getOrNull(2)
            ?: streamingServers.lastOrNull()
            ?: return emptyList()

        return listOf(selected).parallelCatchingFlatMapBlocking { extractVideo(it) }
            .distinctBy { it.videoUrl }
    }

    private fun isAdIframe(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host.contains("a-ads.") ||
            host.contains("doubleclick.") ||
            host.contains("googlesyndication.") ||
            host.contains("adservice.")
    }

    private suspend fun extractVideo(url: String): List<Video> {
        val lower = url.lowercase()

        if (lower.substringBefore("?").endsWith(".mp4") || lower.contains(".m3u8")) {
            return listOf(Video(url, "Direct", url, headers = headers))
        }

        if (
            "discordapp.com" in lower ||
            "discord.com" in lower ||
            "discordapp.net" in lower ||
            "discordcdn.com" in lower
        ) {
            val direct = extractDiscordServer(url)
            if (direct.isNotEmpty()) return direct
            return webViewFallback(url)
        }

        if ("playmogo" in lower) {
            val direct = extractPlaymogo(url)
            if (direct.isNotEmpty()) return direct

            val id = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
            if (id.isNotBlank()) {
                val mirrored = dood.videosFromUrl("https://d000d.com/e/$id")
                if (mirrored.isNotEmpty()) return mirrored
            }
            return webViewFallback(url)
        }

        if ("dood" in lower || "d000d" in lower || "ds2play" in lower) {
            val direct = dood.videosFromUrl(url)
            return direct.ifEmpty { webViewFallback(url) }
        }

        if ("streampoi" in lower) {
            val direct = extractStreampoi(url)
            if (direct.isNotEmpty()) return direct
            val generic = streamWish.videosFromUrl(url)
            return generic.ifEmpty { webViewFallback(url) }
        }

        if ("streamwish" in lower || "wishembed" in lower || "awish" in lower) {
            val direct = streamWish.videosFromUrl(url)
            return direct.ifEmpty { webViewFallback(url) }
        }

        if ("vidhide" in lower || "vidhided" in lower || "embedwish" in lower) {
            val direct = vidHide.videosFromUrl(url)
            return direct.ifEmpty { webViewFallback(url) }
        }

        return webViewFallback(url)
    }

    private suspend fun extractDiscordServer(url: String): List<Video> = runCatching {
        val requestHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val response = client.newCall(GET(url, requestHeaders)).awaitSuccess()
        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type").orEmpty().lowercase()

        if (
            contentType.startsWith("video/") ||
            finalUrl.substringBefore("?").endsWith(".mp4", true) ||
            finalUrl.substringBefore("?").endsWith(".webm", true)
        ) {
            return listOf(Video(finalUrl, "Server 3", finalUrl, headers = requestHeaders))
        }

        val html = response.body.string()
        val document = Jsoup.parse(html, finalUrl)
        val directUrl = document.selectFirst("video[src], source[src]")?.let {
            it.attr("abs:src").ifBlank { it.attr("src") }
        }?.takeIf(String::isNotBlank)
            ?: DISCORD_MEDIA_REGEX.find(html)?.value

        if (directUrl.isNullOrBlank()) return emptyList()

        listOf(Video(directUrl, "Server 3", directUrl, headers = requestHeaders))
    }.getOrDefault(emptyList())

    private suspend fun extractPlaymogo(url: String): List<Video> = runCatching {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.host}"
        val pageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val html = client.newCall(GET(url, pageHeaders)).awaitSuccess().body.string()
        val passPath = PASS_MD5_REGEX.find(html)?.value ?: return emptyList()
        val token = passPath.substringAfterLast("/")
        if (token.isBlank()) return emptyList()

        val passHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val mediaBase = client.newCall(GET(origin + passPath, passHeaders))
            .awaitSuccess().body.string().trim()
        if (mediaBase.isBlank()) return emptyList()

        val nonce = buildString {
            repeat(10) { append(NONCE_CHARS.random()) }
        }
        val expiry = System.currentTimeMillis()
        val videoUrl = "$mediaBase$nonce?token=$token&expiry=$expiry"
        val mediaHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        listOf(Video(videoUrl, "Playmogo", videoUrl, headers = mediaHeaders))
    }.getOrDefault(emptyList())

    private suspend fun extractStreampoi(url: String): List<Video> = runCatching {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.host}"
        val pageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val html = client.newCall(GET(url, pageHeaders)).awaitSuccess().body.string()

        var mediaUrl = FILE_URL_REGEX.find(html)?.groupValues?.getOrNull(1)
        if (mediaUrl.isNullOrBlank()) {
            val unpacked = JsUnpacker.unpackAndCombine(html)
            mediaUrl = unpacked?.let { FILE_URL_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?: unpacked?.let { M3U8_REGEX.find(it)?.value }
        }
        if (mediaUrl.isNullOrBlank()) {
            mediaUrl = M3U8_REGEX.find(html)?.value
        }
        val streamUrl = mediaUrl?.replace("\\/", "/") ?: return emptyList()

        val mediaHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()

        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                videoNameGen = { quality -> "Streampoi - $quality" },
                referer = url,
                masterHeaders = mediaHeaders,
                videoHeaders = mediaHeaders,
            )
        } else {
            listOf(Video(streamUrl, "Streampoi", streamUrl, headers = mediaHeaders))
        }
    }.getOrDefault(emptyList())

    private fun webViewFallback(url: String): List<Video> {
        val fallbackHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return runCatching {
            universal.videosFromUrl(
                origRequestUrl = url,
                origRequestHeader = fallbackHeaders,
                prefix = "Nekopoi",
            )
        }.getOrDefault(emptyList())
    }

    private fun cleanTitle(input: String): String =
        input.replace(Regex("""(?i)\[NEW\s+RELEASE\]\s*|\bSUB\s*[-_]?\s*INDO(?:NESIA)?\b|\bSubtitle\s+Indonesia\b"""), "")
            .replace(Regex("""\[\s*]|\(\s*\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—')

    private fun validEntry(title: String, url: String): Boolean {
        val t = title.lowercase()
        val u = url.lowercase()
        return listOf("anniversary", "selamat tahun baru", "ulang tahun", "nekopoi mengucapkan", "[batch]")
            .none { it in t || it.replace(" ", "-") in u }
    }

    private fun episodeNumber(badge: String, title: String, fallback: Float): Float {
        val regex = Regex("""(?:Ep|Episode)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        return regex.find(badge)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: regex.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: fallback
    }

    private fun extractBgUrl(style: String): String? =
        Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.getOrNull(1)

    private fun extractThumbnail(element: Element): String? {
        val tooltip = element.attr("original-title")
            .ifBlank { element.attr("data-original-title") }
            .ifBlank { element.attr("data-bs-original-title") }
        TOOLTIP_IMG_REGEX.find(tooltip)?.groupValues?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?.let { return absoluteUrl(it) }

        element.selectFirst("[style*='url(']")?.attr("style")?.let(::extractBgUrl)?.let { return absoluteUrl(it) }
        val image = element.selectFirst("img") ?: return null
        return image.attr("abs:data-src").ifBlank {
            image.attr("data-src").ifBlank { image.attr("abs:src").ifBlank { image.attr("src") } }
        }.takeIf(String::isNotBlank)?.let(::absoluteUrl)
    }

    private fun metadataValue(doc: Document, vararg labels: String): String? {
        val candidates = doc.select(".nk-series-meta-list li, .konten p, .nk-post-body p, .entry-content p")
        for (element in candidates) {
            val text = element.text().trim()
            val label = labels.firstOrNull { text.startsWith(it, true) } ?: continue
            val links = element.select("a").map(Element::text).filter(String::isNotBlank).distinct()
            val value = if (links.isNotEmpty()) {
                links.joinToString()
            } else {
                text.substringAfter(":", "").ifBlank {
                    text.removePrefix(label).trim(' ', ':', '-')
                }.trim()
            }
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun absoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$baseUrl/" + url.trimStart('/')
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

    companion object {
        private val PASS_MD5_REGEX = Regex("""/pass_md5/[^'"\s]+""", RegexOption.IGNORE_CASE)
        private val FILE_URL_REGEX = Regex("""(?i)["']?file["']?\s*:\s*["'](https?://[^"']+)["']""")
        private val M3U8_REGEX = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""", RegexOption.IGNORE_CASE)
        private val TOOLTIP_IMG_REGEX = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val DISCORD_MEDIA_REGEX = Regex(
            """https?://[^"'\\\s<>]+(?:discordapp\.com|discordapp\.net|discord\.com|discordcdn\.com)[^"'\\\s<>]+\.(?:mp4|webm)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
