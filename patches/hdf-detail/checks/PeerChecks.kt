package com.kraptor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

private class Provider(override val name: String, override val mainUrl: String): MainAPI() {
    var releaseYear = 2026
    var playUrl = ""
    val detailUrl = "$mainUrl/film/hoplayanlar/"
    override suspend fun search(query: String, page: Int): SearchResponseList {
        check(query == "Hoplayanlar" && page == 1)
        return SearchResponseList(listOf(object : SearchResponse {
            override val name = "Hoplayanlar"
            override val apiName = this@Provider.name
            override val url = detailUrl
        }))
    }
    override suspend fun load(url: String): MovieLoadResponse {
        check(url == detailUrl)
        return object : MovieLoadResponse() {
            override var apiName = this@Provider.name
            override var name = "Hoplayanlar"
            override var url = detailUrl
            override var uniqueUrl = detailUrl
            override var year: Int? = releaseYear
            override var plot: String? = "Özet"
            override var dataUrl = detailUrl
        }
    }
    override suspend fun loadLinks(data: String, casting: Boolean, subtitles: (SubtitleFile)->Unit, callback: (ExtractorLink)->Unit): Boolean {
        check(casting)
        playUrl = data
        return true
    }
}

suspend fun main() {
    val owner = Provider("HDFilmCehennemi", "https://source.test")
    val peer = Provider("FullHDFilmizlesene", "https://films.test")
    val site = owner.mainUrl
    val failure = ManualVerification.message
    check(ManualVerification.target("$site/test/\n$failure", site) == "$site/test/")
    check(ManualVerification.target("$site/test/\n404", site) == null)
    for (other in listOf("file:///etc", "https://source.test.other/", "https://source.test@other.test/", "http://source.test/", "https://source.test:8443/")) {
        check(ManualVerification.target("$other\n$failure", site) == null)
    }
    APIHolder.testProvider = peer
    val url = owner.mainUrl + "/hoppers-29/"
    PeerPlayback.remember(url, "Hoplayanlar - Hoppers", "2026")
    val result = PeerPlayback.blocked(owner, url) as MovieLoadResponse
    check(result.url == url && result.uniqueUrl == url && result.apiName == owner.name)
    check(result.plot == "Yedek kaynak: FullHDFilmizlesene\n\nÖzet")
    check(PeerPlayback.links(result.dataUrl, true, {}, {}) && peer.playUrl == peer.detailUrl)
    check(!PeerPlayback.links(result.dataUrl.replace("films.test/", "films.test.example/"), true, {}, {}))
    check(!PeerPlayback.owns(peer.detailUrl))
    peer.releaseYear = 1996
    check(runCatching { PeerPlayback.blocked(owner, url) }.exceptionOrNull() is ErrorLoadingException)
    check(runCatching { PeerPlayback.blocked(owner, "https://unrelated.test/hoppers-2026/") }.exceptionOrNull() is ErrorLoadingException)
}
