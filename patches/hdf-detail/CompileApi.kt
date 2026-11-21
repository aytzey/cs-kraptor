// Compile-only CloudStream API surface; only com.kraptor classes enter the plugin.
package com.lagradost.cloudstream3
import com.lagradost.cloudstream3.utils.ExtractorLink
abstract class MainAPI {
    abstract val name: String
    abstract val mainUrl: String
    abstract suspend fun search(query: String, page: Int): SearchResponseList?
    abstract suspend fun load(url: String): LoadResponse?
    abstract suspend fun loadLinks(data: String, casting: Boolean, subtitles: (SubtitleFile)->Unit, callback: (ExtractorLink)->Unit): Boolean
}
object APIHolder {
    var testProvider: MainAPI? = null
    fun getApiFromNameNull(name: String?): MainAPI? = testProvider?.takeIf { it.name == name }
}
class ErrorLoadingException(message: String): Exception(message)
class SubtitleFile
interface SearchResponse { val name: String; val url: String; val apiName: String }
class SearchResponseList(val items: List<SearchResponse>)
interface LoadResponse { var name: String; var url: String; var apiName: String; var uniqueUrl: String; var year: Int?; var plot: String? }
abstract class MovieLoadResponse: LoadResponse { abstract var dataUrl: String }
abstract class TvSeriesLoadResponse: LoadResponse { abstract var episodes: List<Episode> }
class Episode(var data: String)
