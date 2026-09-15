package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URLDecoder

class LoveFilm : MainAPI() {
    override var mainUrl = "https://lovefilmizle.net"
    override var name = "LoveFilm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Filmler",
        "$mainUrl/yerli-film/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj/" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yabanci-dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/netflix-dizileri/" to "Netflix Dizileri",
        "$mainUrl/boxset-filmler-3/" to "Seri Filmler",
        "$mainUrl/yapim/2026/" to "2026 Filmleri"
    )

    // ============================================================
    // ANA SAYFA / KATALOG
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val pageUrl = if (page == 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }

        val document = app.get(pageUrl).document
        val home = document.select("div.poster").mapNotNull { it.toSearchResult() }
        val hasNext = document.select(".wp-pagenavi a.nextpostslink").isNotEmpty()

        Log.d(name, "getMainPage - ${home.size} içerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = this.selectFirst(".title")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val imdbText = this.selectFirst(".poster-imdb")?.text()?.trim()
        val imdbScore = imdbText?.let {
            Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()
        }

        val year = this.selectFirst(".icon-year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(this.text())?.value?.toIntOrNull()

        val langText = this.selectFirst(".poster-lang")?.text()?.trim() ?: ""
        val isSeries = langText.contains("Yabancı Dizi", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (imdbScore != null) this.score = Score.from10(imdbScore)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (imdbScore != null) this.score = Score.from10(imdbScore)
            }
        }
    }

    // ============================================================
    // ARAMA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search - Sorgu: $query")
        return try {
            val document = app.get("$mainUrl/?s=$query").document
            val results = document.select("div.poster").mapNotNull { it.toSearchResult() }
            Log.d(name, "search - ${results.size} sonuç bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================
    // DETAY
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")

        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        val poster = fixUrlNull(
            document.selectFirst(".block-poster-left img")?.attr("data-src")
                ?: document.selectFirst(".block-poster-left img")?.attr("src")
                ?: document.selectFirst(".poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img")?.attr("src")
        )

        val description = document.selectFirst(".block-post")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.select("div.block-item:contains(Yıl) a")
            .firstOrNull()?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val imdbText = document.select("div.block-item:contains(IMDB Puanı)")
            .firstOrNull()?.text()?.trim()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()
        }

        val genres = document.select("div.block-item:contains(Kategori) a")
            .map { it.text().trim() }.distinct()

        val actorElements = document.select("div.block-item:contains(Oyuncular) a")
        val actors: List<Pair<Actor, String?>> = actorElements.map { a ->
            Pair(Actor(a.text().trim(), null), null)
        }

        val duration = document.select("div.block-item:contains(Süre)")
            .firstOrNull()?.text()?.let {
                Regex("""(\d+)\s*dakika""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val trailerRaw = document.selectFirst(".btn.btn-black a[href*='youtube']")?.attr("href")
            ?: document.selectFirst("a[href*='youtube']")?.attr("href")
            ?: document.selectFirst("a[href*='youtu.be']")?.attr("href")
        val trailer = when {
            trailerRaw.isNullOrBlank() -> ""
            trailerRaw.contains("youtu.be/") -> {
                val videoId = trailerRaw.substringAfterLast("/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            }
            trailerRaw.contains("youtube.com/watch?v=") -> trailerRaw
            else -> trailerRaw
        }

        // Dizi mi film mi?
        val parts = document.select("ul.hdc-parts li a")
        val isSeries = parts.text().contains("Bölüm") ||
                document.select(".poster-lang").text().contains("Yabancı Dizi")

        if (isSeries) {
            val episodeList = mutableListOf<Episode>()
            val seasonNumber = Regex("""(\d+)\.?\s*Sezon""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""sezon-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            parts.forEach { part ->
                val epHref = fixUrlNull(part.attr("href")) ?: return@forEach
                val epText = part.text().trim()
                val epNumber = Regex("""(\d+)\.?\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                if (epNumber != null) {
                    episodeList.add(
                        newEpisode(epHref) {
                            this.name = epText
                            this.season = seasonNumber
                            this.episode = epNumber
                        }
                    )
                }
            }

            val sortedEpisodes = episodeList.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (rating != null) this.score = Score.from10(rating)
                if (duration != null) this.duration = duration
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (rating != null) this.score = Score.from10(rating)
                if (duration != null) this.duration = duration
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }
    }

    // ============================================================
    // LINKLER
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")

        val document = app.get(data).document

        // Part linkleri (dizi bölümleri)
        val partLinks = document.select("ul.hdc-parts li a")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()
        Log.d(name, "Part linkleri: ${partLinks.size}")

        // Doğrudan sayfadaki iframe'ler
        val directIframes = document.select("iframe").mapNotNull { iframe ->
            val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (raw.isBlank() || raw == "about:blank") null else raw
        }.distinct()
        Log.d(name, "Doğrudan iframe sayısı: ${directIframes.size}")

        var found = false

        suspend fun processDocument(doc: Document, sourceUrl: String) {
            val iframes = doc.select("iframe").mapNotNull { iframe ->
                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                if (raw.isBlank() || raw == "about:blank") null else raw
            }.distinct()

            for (raw in iframes) {
                val resolved = resolveEmbed(raw, sourceUrl) ?: continue
                Log.d(name, "Embed bulundu: $resolved")
                try {
                    loadExtractor(resolved, sourceUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    Log.e(name, "loadExtractor hatası: ${e.message}")
                }
            }

            // iframe yoksa HTML içinden regex ile embed ara
            if (iframes.isEmpty()) {
                val html = doc.html()
                val regexes = listOf(
                    Regex("""bemoly\.php\?url=([^&"']+)"""),
                    Regex("""//(?:ok\.ru|odnoklassniki\.ru)/videoembed/\d+[^"'\s]*"""),
                    Regex("""https?://vidmoly\.(?:net|biz|to)/embed-[a-zA-Z0-9]+\.html"""),
                    Regex("""//vk\.com/video_ext\.php\?[^"'\s]+"""),
                    Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                )
                for (rx in regexes) {
                    val m = rx.find(html) ?: continue
                    val value = m.value
                    val resolved = when {
                        value.contains("bemoly.php?url=") -> decodeBemoly(value) ?: continue
                        value.startsWith("//") -> "https:$value"
                        else -> value
                    }
                    if (resolved.isBlank()) continue
                    Log.d(name, "Regex embed: $resolved")
                    try {
                        loadExtractor(resolved, sourceUrl, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        Log.e(name, "loadExtractor hatası: ${e.message}")
                    }
                }
            }
        }

        if (partLinks.isNotEmpty()) {
            for (partUrl in partLinks) {
                try {
                    Log.d(name, "Part işleniyor: $partUrl")
                    val partDoc = app.get(partUrl).document
                    processDocument(partDoc, partUrl)
                } catch (e: Exception) {
                    Log.e(name, "Part çözümleme hatası: $partUrl - ${e.message}")
                }
            }
        } else {
            processDocument(document, data)
        }

        // Doğrudan iframe'ler (part linklerinden bağımsız) da denenir
        for (raw in directIframes) {
            val resolved = resolveEmbed(raw, data) ?: continue
            Log.d(name, "Direct iframe embed: $resolved")
            try {
                loadExtractor(resolved, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                Log.e(name, "Direct loadExtractor hatası: ${e.message}")
            }
        }

        Log.d(name, "loadLinks bitti - found=$found")
        return true
    }

    /** Embed URL'sini çözer (bemoly, protokol-relative, vs.) */
    private fun resolveEmbed(raw: String, sourceUrl: String): String? {
        if (raw.isBlank() || raw == "about:blank") return null

        if (raw.contains("bemoly.php?url=")) {
            return decodeBemoly(raw)
        }

        val fixed = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> {
                val host = if (sourceUrl.startsWith("http")) {
                    val proto = sourceUrl.substringBefore("://")
                    val h = sourceUrl.substringAfter("://").substringBefore("/")
                    "$proto://$h"
                } else mainUrl
                host + raw
            }
            raw.startsWith("http") -> raw
            else -> fixUrlNull(raw) ?: raw
        }

        return if (fixed.contains("vidmoly.")) {
            val langTag = langTagFromSource(sourceUrl)
            if (langTag.isNotBlank()) "$fixed#$langTag" else fixed
        } else fixed
    }

    private fun langTagFromSource(sourceUrl: String): String = when {
        sourceUrl.contains("dublaj", true) -> "dublaj"
        sourceUrl.contains("altyazi", true) -> "altyazi"
        else -> ""
    }

    private fun decodeBemoly(raw: String): String? {
        return try {
            val encoded = raw.substringAfter("bemoly.php?url=").substringBefore("&").trim()
            if (encoded.isBlank()) return null
            val decoded = URLDecoder.decode(encoded, "UTF-8")
            when {
                decoded.startsWith("//") -> "https:$decoded"
                decoded.startsWith("http") -> decoded
                else -> "https://$decoded"
            }
        } catch (e: Exception) {
            Log.e(name, "bemoly decode hatası: ${e.message}")
            null
        }
    }
}
