// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.

package com.kraptor

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class HdFilmCehennemi2 : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi2.biz"
    override var name                 = "HdFilmCehennemi2"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/"                          to "Yeni Eklenenler",
        "${mainUrl}/en-cok-izlenen-filmler/"           to "En Çok İzlenenler",
        "${mainUrl}/en-cok-yorumlananlar/"             to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilenler/"              to "En Çok Beğenilenler",
        "${mainUrl}/imdb-puani/"                       to "IMDB 7+",
        "${mainUrl}/tur/aksiyon-filmleri-izle/"        to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-filmleri-izle/"    to "Bilim Kurgu",
        "${mainUrl}/tur/korku-filmleri/"               to "Korku",
        "${mainUrl}/tur/macera-filmleri/"              to "Macera",
        "${mainUrl}/tur/dram-filmleri/"                to "Dram",
        "${mainUrl}/tur/komedi-filmleri/"              to "Komedi",
        "${mainUrl}/tur/gerilim-filmleri/"             to "Gerilim",
        "${mainUrl}/tur/romantik-filmler/"             to "Romantik",
        "${mainUrl}/tur/fantastik-filmleri/"           to "Fantastik",
        "${mainUrl}/tur/gizem-filmleri/"               to "Gizem",
        "${mainUrl}/tur/suc-filmleri/"                 to "Suç",
        "${mainUrl}/tur/savas-filmleri/"               to "Savaş",
        "${mainUrl}/tur/tarih-filmleri/"               to "Tarih",
        "${mainUrl}/tur/belgesel-filmleri/"            to "Belgesel",
        "${mainUrl}/tur/animasyon-film-izle/"          to "Animasyon",
        "${mainUrl}/tur/aile-filmleri/"                to "Aile",
        "${mainUrl}/tur/biyografi-filmleri/"           to "Biyografi",
        "${mainUrl}/tur/spor-filmleri/"                to "Spor",
        "${mainUrl}/tur/muzik-filmleri/"               to "Müzik",
        "${mainUrl}/tur/western-filmleri/"             to "Western"
    )

    // ================================================================
    // HTTP HEADERS — Cloudflare için
    // ================================================================
    private val siteHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer"         to "$mainUrl/"
    )

    // ================================================================
    // ANA SAYFA
    // ================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) "${request.data}&page=$page"
            else "${request.data}?page=$page"
        }

        Log.d("cehennem", "Ana sayfa URL: $url")
        val document = app.get(url, headers = siteHeaders).document

        // Yeni kart yapısı: <a class="group/poster"><article>...</article></a>
        val cards = document.select("a.group\\/poster")
        Log.d("cehennem", "Bulunan kart sayısı: ${cards.size}")

        val home = cards.mapNotNull { it.toMainPageResult() }
        Log.d("cehennem", "Parse edilen: ${home.size}")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        // Kategori/filtre linklerini atla
        val skipPatterns = listOf(
            "/tur/", "/yil/", "/kategori/", "/ulke/",
            "/dizi-izle", "/filmler", "/en-cok-", "/imdb-puani",
            "/iletisim", "/film-istek", "/seri-filmler"
        )
        if (skipPatterns.any { href.contains(it) }) return null

        val img   = this.selectFirst("img")
        val h3    = this.selectFirst("h3")

        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: h3?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: h3?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ================================================================
    // ARAMA — API endpoint (JSON)
    // ================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val apiUrl = "${mainUrl}/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            Log.d("cehennem", "Arama URL: $apiUrl")

            val response = app.get(apiUrl, headers = siteHeaders).text
            Log.d("cehennem", "Arama yanıtı (ilk 300): ${response.take(300)}")

            val json = JSONObject(response)
            val data = json.optJSONArray("data") ?: return emptyList()
            val results = mutableListOf<SearchResponse>()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                if (title.isBlank()) continue

                val slug = item.optString("slug", "").trim()
                if (slug.isBlank()) continue

                val href = "$mainUrl/$slug"
                val posterUrl = item.optString("posterUrl", "").ifBlank { null }

                results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }

            Log.d("cehennem", "Arama sonucu: ${results.size}")
            results
        } catch (e: Exception) {
            Log.e("cehennem", "Arama hatası: ${e.message}", e)
            // HTML fallback
            try {
                val document = app.get(
                    "${mainUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}",
                    headers = siteHeaders
                ).document
                document.select("a.group\\/poster").mapNotNull { it.toMainPageResult() }
            } catch (e2: Exception) {
                Log.e("cehennem", "Fallback arama hatası: ${e2.message}")
                emptyList()
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ================================================================
    // FİLM DETAY
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d("cehennem", "Load URL: $url")
        val document = app.get(url, headers = siteHeaders).document

        // Başlık: <h1 class="text-4xl font-bold text-white mb-2">Supergirl izle</h1>
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s*izle\\s*$", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*İzle\\s*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: return null

        Log.d("cehennem", "Başlık: $title")

        // Poster: og:image veya detay poster
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img[alt='$title']")?.attr("src")
                ?: document.select("img").firstOrNull { it.attr("alt") == title }?.attr("src")
        )

        // Açıklama
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        // Yıl
        val year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""/yil/(\d{4})""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()

        // Puan — yıldız svg yanındaki sayı (5.8 gibi)
        val rating = document.selectFirst("span:has(svg)")?.text()?.trim()
            ?.replace(Regex("[^0-9.]"), "")
            ?.takeIf { it.isNotBlank() && it.toDoubleOrNull() != null }
            ?: Regex("""IMDB[:\s]*([0-9]+[.,][0-9]+)""", RegexOption.IGNORE_CASE)
                .find(document.html())?.groupValues?.get(1)?.replace(",", ".")

        // Türler
        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Oyuncular: <div class="flex-none w-24 ..."> <h4>Name</h4> <p>Role</p> </div>
        val actors = document.select("div.flex-none.w-24").mapNotNull { el ->
            val name = el.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            val role = el.selectFirst("p")?.text()?.trim()
            Actor(name, role)
        }

        // Fragman
        val trailer = Regex("""youtube\.com/embed/([A-Za-z0-9_-]+)""")
            .find(document.html())?.groupValues?.get(1)
            ?.let { "https://www.youtube.com/embed/$it" }

        Log.d("cehennem", "Yıl: $year, Puan: $rating, Tür: $tags, Oyuncu: ${actors.size}")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            if (!rating.isNullOrBlank()) {
                runCatching { this.score = Score.from10(rating) }
            }
            addActors(actors)
            if (trailer != null) addTrailer(trailer)
        }
    }

    // ================================================================
    // LİNKLERİ ÇEK — 4 katmanlı fallback
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("cehennem", "=== loadLinks BAŞLADI ===")
        Log.d("cehennem", "data: $data")

        val document = try {
            app.get(data, headers = siteHeaders).document
        } catch (e: Exception) {
            Log.e("cehennem", "Sayfa indirilemedi: ${e.message}", e)
            return false
        }

        val html = document.html()
        Log.d("cehennem", "HTML uzunluk: ${html.length}")
        Log.d("cehennem", "videoPlayerData var mı: ${html.contains("videoPlayerData")}")

        val foundUrls = mutableListOf<String>()

        // ============================================================
        // KATMAN 1: videoPlayerData(JSON.parse('...'), 'dual', [])
        // ============================================================
        try {
            val regex = Regex(
                """videoPlayerData\(\s*JSON\.parse\(\s*'((?:[^'\\]|\\.)*)'""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(html)

            if (match != null) {
                var jsonRaw = match.groupValues[1]
                Log.d("cehennem", "KATMAN1 ham JSON (ilk 300): ${jsonRaw.take(300)}")

                // HTML entity + JS escape çöz
                jsonRaw = jsonRaw
                    .replace("&quot;", "\"")
                    .replace("&#039;", "'")
                    .replace("&amp;", "&")
                    .replace("\\u0022", "\"")
                    .replace("\\u0027", "'")
                    .replace("\\/", "/")
                    .replace("\\n", "\n")
                    .replace("\\r", "")
                    .replace("\\\\", "\\")

                Log.d("cehennem", "KATMAN1 temiz JSON (ilk 300): ${jsonRaw.take(300)}")

                val json = JSONObject(jsonRaw)
                val langKeys = json.keys()

                while (langKeys.hasNext()) {
                    val langKey = langKeys.next()
                    val videos = json.optJSONArray(langKey) ?: continue
                    Log.d("cehennem", "Dil: $langKey -> ${videos.length()} video")

                    for (i in 0 until videos.length()) {
                        val video = videos.optJSONObject(i) ?: continue
                        val link        = video.optString("link", "")
                        val templateB64 = video.optString("template", "")
                        val serviceName = video.optString("service_name", "Player")
                        val slug        = video.optString("slug", "")

                        if (link.isBlank() || templateB64.isBlank()) continue

                        val template = try {
                            String(
                                Base64.decode(templateB64, Base64.DEFAULT),
                                Charsets.UTF_8
                            )
                        } catch (e: Exception) {
                            Log.e("cehennem", "Template decode hatası: ${e.message}")
                            continue
                        }

                        Log.d("cehennem", "Template ($serviceName): $template")

                        val iframeHtml = template
                            .replace("{url}", link)
                            .replace("{slug}", slug)

                        // data-src önce, sonra src
                        val srcRegex = Regex("""data-src=["']([^"']+)["']|src=["']([^"']+)["']""")
                        val srcMatch = srcRegex.find(iframeHtml) ?: continue
                        var videoUrl = srcMatch.groupValues[1].ifBlank { srcMatch.groupValues[2] }
                        if (videoUrl.isBlank()) continue

                        if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                        else if (videoUrl.startsWith("/")) videoUrl = "$mainUrl$videoUrl"

                        Log.d("cehennem", "KATMAN1 URL: $videoUrl")
                        foundUrls.add(videoUrl)

                        loadExtractor(
                            url = videoUrl,
                            referer = data,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                }
            } else {
                Log.e("cehennem", "KATMAN1: videoPlayerData regex eşleşmedi")
            }
        } catch (e: Exception) {
            Log.e("cehennem", "KATMAN1 hata: ${e.message}", e)
        }

        // ============================================================
        // KATMAN 2: Doğrudan HTML'de iframe
        // ============================================================
        if (foundUrls.isEmpty()) {
            Log.d("cehennem", "KATMAN2: doğrudan iframe aranıyor")
            document.select("iframe").forEach { iframe ->
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach

                if (src.startsWith("//")) src = "https:$src"
                else if (src.startsWith("/")) src = "$mainUrl$src"

                Log.d("cehennem", "KATMAN2 iframe: $src")
                foundUrls.add(src)

                loadExtractor(src, referer = data, subtitleCallback, callback)
            }
        }

        // ============================================================
        // KATMAN 3: data-src / data-video / data-iframe attributes
        // ============================================================
        if (foundUrls.isEmpty()) {
            Log.d("cehennem", "KATMAN3: data-* attribute aranıyor")
            document.select("[data-video], [data-src], [data-url], [data-iframe], [data-player]").forEach { el ->
                var url = el.attr("data-video").ifBlank {
                    el.attr("data-src").ifBlank {
                        el.attr("data-url").ifBlank {
                            el.attr("data-iframe").ifBlank { el.attr("data-player") }
                        }
                    }
                }
                if (url.isBlank()) return@forEach
                if (!url.contains("player") && !url.contains("embed") && !url.contains("vid")) return@forEach

                if (url.startsWith("//")) url = "https:$url"
                else if (url.startsWith("/")) url = "$mainUrl$url"

                Log.d("cehennem", "KATMAN3: $url")
                foundUrls.add(url)
                loadExtractor(url, referer = data, subtitleCallback, callback)
            }
        }

        // ============================================================
        // KATMAN 4: HTML içinde "vidload.top" veya benzeri player URL'lerini regex ile yakala
        // ============================================================
        if (foundUrls.isEmpty()) {
            Log.d("cehennem", "KATMAN4: HTML içinde player URL regex")
            val playerRegex = Regex("""(https?:)?//(?:vidload|vidmoly|vk|dood|ok\.ru|streamtape|filemoon|vidsrc|vidsrc2|vidsrcme|2embed|multiembed)\.[a-z]+/[^\s"'<>]+""")
            playerRegex.findAll(html).forEach { match ->
                var url = match.value
                if (url.startsWith("//")) url = "https:$url"
                if (foundUrls.contains(url)) return@forEach

                Log.d("cehennem", "KATMAN4: $url")
                foundUrls.add(url)
                loadExtractor(url, referer = data, subtitleCallback, callback)
            }
        }

        Log.d("cehennem", "=== TOPLAM: ${foundUrls.size} URL ===")
        foundUrls.forEach { Log.d("cehennem", "  → $it") }

        return foundUrls.isNotEmpty()
    }
}
