package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object DoramasiaProvider : Provider {

    override val name = "Doramasia"
    override val baseUrl = "https://doramasia.com"
    override val language = "es"
    override val logo = "https://doramasia.com/favicon.ico"
    private const val TAG = "DoramasiaProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build())
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasiaService::class.java)

    private interface DoramasiaService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val document = service.getPage(baseUrl)
            val categories = mutableListOf<Category>()

            val shows = document.select("article, .post-item, .item, .show-card, .dorama-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                TvShow(id = link.removePrefix(baseUrl).removePrefix("/"), title = title, poster = poster)
            }

            if (shows.isNotEmpty()) categories.add(Category("Doramas Asiáticos", shows))
            categories
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
            parseList(service.getPage(url))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/doramas" else "$baseUrl/doramas/page/$page"
            parseList(service.getPage(url)).filterIsInstance<TvShow>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseList(document: Document): List<AppAdapter.Item> {
        return document.select("article, .post-item, .item, .show-card, .dorama-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            TvShow(id = link.removePrefix(baseUrl).removePrefix("/"), title = title, poster = poster)
        }
    }

    override suspend fun getMovie(id: String): Movie = throw Exception("Site only has doramas")

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val seasons = document.select(".season-list .season, .temporada").mapIndexed { i, _ ->
            Season(id = "$id/season/${i + 1}", number = i + 1, title = "Temporada ${i + 1}")
        }

        return TvShow(
            id = id,
            title = document.selectFirst("h1, .entry-title")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .entry-content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            seasons = if (seasons.isNotEmpty()) seasons else listOf(Season(id = "$id/season/1", number = 1, title = "Temporada 1"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("/season/")
        val url = if (showId.startsWith("http")) showId else "$baseUrl/$showId"
        val document = service.getPage(url)

        return document.select(".episode-item, .ep-item, .capitulo, li.episode").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h3, h4")?.text() ?: ""
            val num = el.selectFirst(".number, .ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Episode(id = link.removePrefix(baseUrl).removePrefix("/"), number = num, title = title.ifEmpty { "Episodio $num" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return try {
            val document = service.getPage(url)
            val servers = mutableListOf<Video.Server>()

            document.select("iframe").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.isNotEmpty()) servers.add(Video.Server(id = src, name = "Player", src = src))
            }

            document.select(".server-item, .option-btn").forEach { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("data-url") }
                val name = el.text().ifEmpty { "Server" }
                if (src.isNotEmpty()) servers.add(Video.Server(id = src, name = name, src = src))
            }

            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id/page/$page"
        val document = service.getPage(url)
        val shows = parseList(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
