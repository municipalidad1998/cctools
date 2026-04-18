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
import retrofit2.http.Url
import java.io.File
import java.util.Locale
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
            .build()
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
            val script = document.selectFirst("script#__NEXT_DATA__")?.data() 
                ?: document.select("script").firstOrNull { it.data().contains("props") }?.data()
                ?: return emptyList()

            val jsonObject = JsonParser.parseString(script).asJsonObject
            val props = jsonObject.getAsJsonObject("props")
            val pageProps = props.getAsJsonObject("pageProps")
            val apolloState = pageProps.getAsJsonObject("apolloState")

            // This is a simplified extraction based on the discovered JSON structure
            val categories = mutableListOf<Category>()
            
            // Logic to extract categories from apolloState
            apolloState.entrySet().forEach { entry ->
                if (entry.key.contains("Label:")) {
                    val label = entry.value.asJsonObject
                    val name = label.get("name").asString
                    val slug = label.get("slug").asString
                    categories.add(Category(name = name, list = emptyList())) // List would be fetched via search/genre
                }
            }

            categories
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            val url = "$baseUrl/?s=$query"
            val document = serviceHtml.getPage(url)
            val results = mutableListOf<AppAdapter.Item>()
            
            // Fallback to CSS selectors if JSON parsing is too complex for search
            document.select("a[href*='/dorama/'], a[href*='/pelicula/']").forEach { element ->
                val title = element.text()
                val href = element.attr("href")
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                
                if (href.contains("/dorama/")) {
                    results.add(TvShow(id = href.removePrefix("/"), title = title, poster = getPosterUrl(poster)))
                } else {
                    results.add(Movie(id = href.removePrefix("/"), title = title, poster = getPosterUrl(poster)))
                }
            }
            results.distinctBy { it.toString() }
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
        val title = document.title()
        return Movie(id = id, title = title, overview = "Descripción no disponible", poster = "")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = serviceHtml.getPage(url)
        val title = document.title()
        return TvShow(id = id, title = title, overview = "Descripción no disponible", poster = "")
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val url = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = serviceHtml.getPage(url)
            val servers = mutableListOf<Video.Server>()
            
            document.select("a[href*='embed'], a[href*='player']").forEach { element ->
                val link = element.attr("href")
                val name = element.text().ifBlank { "Server" }
                servers.add(Video.Server(id = link, name = name))
            }
            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id, shows = search(id, page))
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
}
