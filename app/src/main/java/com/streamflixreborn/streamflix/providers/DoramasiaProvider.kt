package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

object DoramasiaProvider : Provider {
    override val name = "Doramasia"
    override val baseUrl = "https://doramasia.com"
    override val language = "es"
    override val logo = "https://doramasia.com/favicon.ico"

    private val client = getOkHttpClient()
    private val service = Retrofit.Builder()
        .baseUrl("https://sv1.fluxcedene.net/api/") // Re-using API if found in original
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(DoramasiaApiService::class.java)

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
            .build()
    }

    private interface DoramasiaApiService {
        @POST("gql")
        suspend fun getApiResponse(@retrofit2.http.Body body: okhttp3.RequestBody): Any
    }

    private interface DoramasiaService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private fun getPosterUrl(path: String?): String {
        if (path == null) return ""
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val document = serviceHtml.getPage(baseUrl)
            val results = mutableListOf<TvShow>()
            
            document.select("h2 a, h3 a").forEach { element ->
                val title = element.text()
                val href = element.attr("href")
                if (href.isNotEmpty()) {
                    results.add(TvShow(id = href, title = title, poster = ""))
                }
            }
            
            listOf(Category(name = "Recientes", list = results))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            val url = "$baseUrl/?s=$query"
            val document = serviceHtml.getPage(url)
            val results = mutableListOf<AppAdapter.Item>()
            
            document.select("article h2 a, .entry-title a").forEach { element ->
                val title = element.text()
                val href = element.attr("href")
                val img = element.parent()?.selectFirst("img")?.attr("src") ?: ""
                
                results.add(TvShow(id = href, title = title, poster = getPosterUrl(img)))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return search("peliculas", page).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return search("doramas", page).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = serviceHtml.getPage(url)
        return Movie(id = id, title = document.title(), overview = "Descripción no disponible", poster = "")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = serviceHtml.getPage(url)
        return TvShow(id = id, title = document.title(), overview = "Descripción no disponible", poster = "")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val document = serviceHtml.getPage(seasonId)
            val episodes = mutableListOf<Episode>()
            
            document.select("a[href*='episodio'], a[href*='episode']").forEach { element ->
                episodes.add(Episode(id = element.attr("href"), number = 0, title = element.text(), poster = ""))
            }
            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val url = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = serviceHtml.getPage(url)
            val servers = mutableListOf<Video.Server>()
            
            document.select("iframe").forEach { element ->
                val src = element.attr("src")
                if (src.isNotEmpty()) {
                    servers.add(Video.Server(id = src, name = "Server"))
                }
            }
            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id, shows = search(id, page).filterIsInstance<Show>())
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
}
