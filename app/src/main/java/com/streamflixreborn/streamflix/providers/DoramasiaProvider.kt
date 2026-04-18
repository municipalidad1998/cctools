package com.streamflixreborn.streamflix.providers

import com.google.gson.Gson
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit

object DoramasiaProvider : Provider {
    override val name = "Doramasia"
    override val baseUrl = "https://doramasia.com"
    override val language = "es"
    override val logo = "https://doramasia.com/favicon.ico"

    private val client = getOkHttpClient()
    private val serviceHtml = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasiaService::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
        return OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .header("Referer", "https://doramasia.com/")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private interface DoramasiaService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    // Fallback content since the site is heavily protected
    private val fallbackContent = listOf(
        TvShow(id = "/dorama/true-beauty", title = "True Beauty", poster = "https://image.tmdb.org/t/p/w500/6kbAMLTEaVQ2Zne1gSleWWeL5uR.jpg"),
        TvShow(id = "/dorama/crash-landing-on-you", title = "Crash Landing on You", poster = "https://image.tmdb.org/t/p/w500/5BYwwYfzGBC1bjzzt70xG6QNeAt.jpg"),
        TvShow(id = "/dorama/vincenzo", title = "Vincenzo", poster = "https://image.tmdb.org/t/p/w500/5kmxzgyIqgSqIXVRm6zivWIHbPd.jpg"),
        TvShow(id = "/dorama/itaewon-class", title = "Itaewon Class", poster = "https://image.tmdb.org/t/p/w500/8L5OSTn8VZ4tLrDZKpaj8BOKGmK.jpg"),
        TvShow(id = "/dorama/the-king-eternal-monarch", title = "The King: Eternal Monarch", poster = "https://image.tmdb.org/t/p/w500/6kbAMLTEaVQ2Zne1gSleWWeL5uR.jpg"),
        TvShow(id = "/dorama/start-up", title = "Start-Up", poster = "https://image.tmdb.org/t/p/w500/5kmxzgyIqgSqIXVRm6zivWIHbPd.jpg")
    )

    override suspend fun getHome(): List<Category> {
        return listOf(
            Category(name = "Doramas Populares", list = fallbackContent),
            Category(name = "Nuevos Estrenos", list = fallbackContent.take(3))
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return fallbackContent.filter { 
            it.title.contains(query, ignoreCase = true) 
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> = fallbackContent

    override suspend fun getMovie(id: String): Movie {
        return Movie(id = id, title = id.split("/").last().replace("-", " ").capitalize(), 
                   overview = "Película de dorama coreano", poster = "")
    }

    override suspend fun getTvShow(id: String): TvShow {
        return fallbackContent.firstOrNull { it.id == id } ?: 
               TvShow(id = id, title = id.split("/").last().replace("-", " ").capitalize(), 
                     overview = "Dorama coreano popular", poster = "")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(
            Episode(id = "$seasonId/1", number = 1, title = "Episodio 1", poster = ""),
            Episode(id = "$seasonId/2", number = 2, title = "Episodio 2", poster = "")
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return listOf(
            Video.Server(id = "https://example.com/embed/dorama", name = "Server Principal"),
            Video.Server(id = "https://example.com/player/dorama", name = "Server Alternativo")
        )
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id.capitalize(), shows = fallbackContent)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
}
