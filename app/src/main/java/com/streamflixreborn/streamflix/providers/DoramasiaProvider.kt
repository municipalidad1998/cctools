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
import retrofit2.http.Body
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
                    .build()
                chain.proceed(request)
            }
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
            val featuredShows = mutableListOf<TvShow>()
            
            // Extract from carousel/sliders
            document.select("[class*='swiper'], [class*='carousel'], [class*='slider'] a").forEach { element ->
                val title = element.attr("title").ifBlank { element.text() }
                val href = element.attr("href")
                val img = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src") ?: ""
                
                if (href.isNotBlank() && title.isNotBlank()) {
                    featuredShows.add(TvShow(id = href, title = title, poster = getPosterUrl(img)))
                }
            }
            
            // Extract from grid items
            document.select("[class*='card'], [class*='grid'], [class*='item'] a").forEach { element ->
                val title = element.attr("title").ifBlank { element.text() }
                val href = element.attr("href")
                val img = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src") ?: ""
                
                if (href.isNotBlank() && title.isNotBlank() && !featuredShows.any { it.id == href }) {
                    featuredShows.add(TvShow(id = href, title = title, poster = getPosterUrl(img)))
                }
            }
            
            listOf(Category(name = "Destacados", list = featuredShows.distinctBy { it.id }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            val url = "$baseUrl/?s=${query.replace(" ", "+")}"
            val document = serviceHtml.getPage(url)
            val results = mutableListOf<AppAdapter.Item>()
            
            // Search results extraction
            document.select("article, [class*='result'], [class*='item']").forEach { article ->
                val link = article.selectFirst("a")?.attr("href") ?: ""
                val title = article.selectFirst("h2, h3, [class*='title']")?.text() ?: ""
                val img = article.selectFirst("img")?.attr("src") ?: 
                         article.selectFirst("img")?.attr("data-src") ?: ""
                
                if (link.isNotBlank() && title.isNotBlank()) {
                    results.add(TvShow(id = link, title = title, poster = getPosterUrl(img)))
                }
            }
            
            results.distinctBy { it.toString() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return search("pelicula", page).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return search("dorama", page).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = serviceHtml.getPage(url)
        val title = document.selectFirst("h1, [class*='title']")?.text() ?: document.title()
        val overview = document.selectFirst("[class*='sinopsis'], [class*='description']")?.text() ?: ""
        val poster = document.selectFirst("[class*='poster'] img, .poster img")?.attr("src") ?: ""
        
        return Movie(id = id, title = title, overview = overview, poster = getPosterUrl(poster))
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = serviceHtml.getPage(url)
        val title = document.selectFirst("h1, [class*='title']")?.text() ?: document.title()
        val overview = document.selectFirst("[class*='sinopsis'], [class*='description']")?.text() ?: ""
        val poster = document.selectFirst("[class*='poster'] img, .poster img")?.attr("src") ?: ""
        
        return TvShow(id = id, title = title, overview = overview, poster = getPosterUrl(poster))
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val document = serviceHtml.getPage(seasonId)
            val episodes = mutableListOf<Episode>()
            
            document.select("[class*='episode'], [class*='capitulo']").forEach { episodeElement ->
                val link = episodeElement.selectFirst("a")?.attr("href") ?: ""
                val title = episodeElement.selectFirst("[class*='title']")?.text() ?: "Episodio"
                val number = title.filter { it.isDigit() }.toIntOrNull() ?: 0
                val img = episodeElement.selectFirst("img")?.attr("src") ?: ""
                
                episodes.add(Episode(id = link, number = number, title = title, poster = getPosterUrl(img)))
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
            
            // Extract iframes
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    servers.add(Video.Server(id = src, name = "Video Server"))
                }
            }
            
            // Extract video player links
            document.select("[class*='player'], [class*='video'] a").forEach { link ->
                val href = link.attr("href")
                val name = link.text().ifBlank { "Server ${servers.size + 1}" }
                if (href.isNotBlank() && href.contains("embed")) {
                    servers.add(Video.Server(id = href, name = name))
                }
            }
            
            servers.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id.replace("-", " ").capitalize(), 
                   shows = search(id, page).filterIsInstance<Show>())
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
}
