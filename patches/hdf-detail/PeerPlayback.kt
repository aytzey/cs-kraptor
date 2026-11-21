package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

/** An unavailable detail page can use a matching title from another installed source. */
object PeerPlayback {
    private const val prefix = "kraptor-peer:"
    private val series = listOf("DiziYou", "DiziBox")
    private val movies = listOf("FullHDFilmizlesene")
    private val dated = Regex("/(dizi/)?([a-z0-9]+(?:-[a-z0-9]+)*?)-((?:19|20)[0-9]{2})(?:-[0-9]+)?/?")

    private data class Identity(val titles: List<String>, val year: Int)
    private val cards = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Identity>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Identity>?) = size > 512
    })

    @JvmStatic fun remember(url: String, label: String, yearText: String) {
        val year = yearText.trim().toIntOrNull()?.takeIf { it in 1900..2099 } ?: return
        val titles = label.split(" - ").map { it.trim() }.filter { it.length in 1..200 }.distinct()
        if (titles.isNotEmpty()) cards.put(url, Identity(titles, year))
    }

    private fun sameSite(url: String, site: String): Boolean = try {
        val dest = URI(url)
        val origin = URI(site)
        dest.scheme == "https" && origin.scheme == "https" && dest.host != null &&
            dest.host.equals(origin.host, true) && dest.port == origin.port && dest.rawUserInfo == null
    } catch (_: Exception) { false }

    private fun words(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ı','i'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun pack(api: MainAPI, data: String) = "$prefix${api.name}|$data"

    @JvmStatic fun owns(data: String): Boolean = data.startsWith(prefix)

    @JvmStatic suspend fun links(data: String, casting: Boolean, subtitles: (SubtitleFile) -> Unit,
                                 callback: (ExtractorLink) -> Unit): Boolean {
        val source = data.removePrefix(prefix).substringBefore('|')
        if (!owns(data) || (source !in series && source !in movies)) return false
        val api = APIHolder.getApiFromNameNull(source) ?: return false
        val url = data.substringAfter('|', "")
        return sameSite(url, api.mainUrl) && api.loadLinks(url, casting, subtitles, callback)
    }

    @JvmStatic suspend fun blocked(owner: MainAPI, url: String): LoadResponse {
        if (!sameSite(url, owner.mainUrl)) throw ErrorLoadingException("Geçersiz HDFilmCehennemi adresi.")
        val path = URI(url).rawPath
        val isSeries = path.startsWith("/dizi/")
        val identity = cards.get(url) ?: dated.matchEntire(path)?.let {
            Identity(listOf(words(it.groupValues[2])), it.groupValues[3].toInt())
        } ?: throw ManualVerification.unavailable(owner.mainUrl)
        val names = identity.titles.map(::words).toSet()
        for (name in if (isSeries) series else movies) {
            val api = APIHolder.getApiFromNameNull(name) ?: continue
            try {
                val hits = api.search(identity.titles.first(), 1)?.items.orEmpty()
                val hit = hits.filter { it.apiName == name && sameSite(it.url, api.mainUrl) && words(it.name) in names }
                    .distinctBy { it.url }.singleOrNull() ?: continue
                val detail = api.load(hit.url) ?: continue
                if (detail.apiName != name || detail.year != identity.year || words(detail.name) !in names) continue
                when {
                    isSeries && detail is TvSeriesLoadResponse -> {
                        if (detail.episodes.isEmpty() || detail.episodes.any { !sameSite(it.data, api.mainUrl) }) continue
                        detail.episodes.forEach { it.data = pack(api, it.data) }
                    }
                    !isSeries && detail is MovieLoadResponse -> {
                        if (!sameSite(detail.dataUrl, api.mainUrl)) continue
                        detail.dataUrl = pack(api, detail.dataUrl)
                    }
                    else -> continue
                }
                detail.apiName = owner.name
                detail.url = url
                detail.uniqueUrl = url
                detail.plot = "Yedek kaynak: $name" + (detail.plot?.let { "\n\n$it" } ?: "")
                return detail
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { continue }
        }
        throw ManualVerification.unavailable(owner.mainUrl)
    }
}
