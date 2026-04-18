package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object DoramasMp4Provider : Provider {

    override val name = "DoramasMP4"
    override val baseUrl = "https://doramasmp4.io"
    override val language = "es"
    override val logo = "https://doramasmp4.io/favicon.ico"
    private const val TAG = "DoramasMp4Provider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .build()
            chain.proceed(request)
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
        .create(DoramasMp4Service::class.java)

    private interface DoramasMp4Service {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        try {
            val homeDoc = async { service.getPage(baseUrl) }
            val doramasDoc = async { service.getPage("$baseUrl/doramas") }

            // Latest episodes from home
            val latestEpisodes = homeDoc.await().select(".episode-item, .latest-episodes li, .cap-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst(".title, h3, h2")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val epNum = el.selectFirst(".ep-number, .number")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val id = link.removePrefix(baseUrl).removePrefix("/")
                Episode(id = id, number = epNum, title = title, poster = poster)
            }

            if (latestEpisodes.isNotEmpty()) {
                categories.add(Category("Últimos Episodios", latestEpisodes))
            }

            // Popular doramas
            val doramas = parseDoramaList(doramasDoc.await())
            if (doramas.isNotEmpty()) {
                categories.add(Category("Doramas", doramas.take(20)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("estrenos", "Estrenos"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades"),
            )
        }
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) "$baseUrl/search?q=$encodedQuery" else "$baseUrl/search?q=$encodedQuery&page=$page"
            val document = service.getPage(url)
            parseDoramaList(document)
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas?page=$page"
            val document = service.getPage(url)
            document.select(".dorama-item, .item, article, .card").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                Movie(id = link.removePrefix(baseUrl).removePrefix("/"), title = title, poster = poster)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/doramas" else "$baseUrl/doramas?page=$page"
            val document = service.getPage(url)
            parseDoramaList(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseDoramaList(document: Document): List<TvShow> {
        return document.select(".dorama-item, .item, article, .card, .show-card").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .name")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            TvShow(id = link.removePrefix(baseUrl).removePrefix("/"), title = title, poster = poster)
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)
        return Movie(
            id = id,
            title = document.selectFirst("h1, .title-big")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .genero a, .tag a").map {
                Genre(id = it.attr("href").removePrefix(baseUrl), name = it.text())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val seasons = document.select(".season-list .season, .temporada-item, .tab-season").mapIndexed { i, el ->
            val num = el.selectFirst(".num, .number")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (i + 1)
            Season(id = "$id/season/$num", number = num, title = "Temporada $num")
        }

        return TvShow(
            id = id,
            title = document.selectFirst("h1, .title-big")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .genero a, .tag a").map {
                Genre(id = it.attr("href").removePrefix(baseUrl), name = it.text())
            },
            seasons = if (seasons.isNotEmpty()) seasons else listOf(Season(id = "$id/season/1", number = 1, title = "Temporada 1"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/season/")
        if (parts.size != 2) return emptyList()
        val showId = parts[0]

        val url = if (showId.startsWith("http")) showId else "$baseUrl/$showId"
        val document = service.getPage(url)

        return document.select(".episode-item, .ep-item, .chapter, .capitulo").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h3, h4")?.text() ?: ""
            val epNum = el.selectFirst(".number, .ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Episode(id = link.removePrefix(baseUrl).removePrefix("/"), number = epNum, title = title.ifEmpty { "Episodio $epNum" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return try {
            val document = service.getPage(url)
            val servers = mutableListOf<Video.Server>()

            document.select(".server-item, .player-option, .btn-server, .option-server").forEach { el ->
                val name = el.text().ifEmpty { "Server" }
                val dataSrc = el.attr("data-src").ifEmpty { el.attr("data-url") }
                val dataId = el.attr("data-id")

                if (dataSrc.isNotEmpty()) {
                    servers.add(Video.Server(id = dataSrc, name = name, src = dataSrc))
                } else if (dataId.isNotEmpty()) {
                    servers.add(Video.Server(id = dataId, name = name))
                }
            }

            if (servers.isEmpty()) {
                document.select("iframe, .player-embed iframe, #player iframe").forEach { el ->
                    val src = el.attr("src").ifEmpty { el.attr("data-src") }
                    if (src.isNotEmpty()) servers.add(Video.Server(id = src, name = "Player", src = src))
                }
            }

            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = when (id) {
            "doramas" -> if (page == 1) "$baseUrl/doramas" else "$baseUrl/doramas?page=$page"
            "estrenos" -> if (page == 1) "$baseUrl/estrenos" else "$baseUrl/estrenos?page=$page"
            "peliculas" -> if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas?page=$page"
            "variedades" -> if (page == 1) "$baseUrl/variedades" else "$baseUrl/variedades?page=$page"
            else -> if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id?page=$page"
        }
        val document = service.getPage(url)
        val shows = parseDoramaList(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
