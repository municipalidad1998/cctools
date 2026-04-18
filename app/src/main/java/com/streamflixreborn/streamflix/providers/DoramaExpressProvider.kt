package com.streamflixreborn.streamflix.providers

import com.google.gson.Gson
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.File
import java.util.concurrent.TimeUnit

object DoramaExpressProvider : Provider {
    override val name = "Dorama Express"
    override val baseUrl = "https://doramaexpress.com"
    override val language = "es"
    override val logo = "https://doramaexpress.com/favicon.ico"

    private val client = getOkHttpClient()
    private val serviceHtml = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramaExpressService::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
        return OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }

    private interface DoramaExpressService {
        @GET
        suspend fun getPage(@retrofit2.http.Url url: String): Document
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
                
                results.add(TvShow(id = href, title = title, poster = img))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return search("dorama", page).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie = throw Exception("Not implemented")

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            val document = serviceHtml.getPage(id)
            TvShow(id = id, title = document.title(), overview = "", poster = "")
        } catch (e: Exception) {
            throw Exception("Error loading show")
        }
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
            val document = serviceHtml.getPage(id)
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
    override suspend fun getGenre(id: String, page: Int): Genre = Genre(id = id, name = id, shows = search(id, page).filterIsInstance<Show>())
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
}
