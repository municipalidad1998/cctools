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

object DoramaExpressProvider : Provider {

    override val name = "DoramaExpress"
    override val baseUrl = "https://doramaexpress.com"
    override val language = "es"
    override val logo = "https://doramaexpress.com/favicon.ico"
    private const val TAG = "DoramaExpressProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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
        .create(DoramaExpressService::class.java)

    private interface DoramaExpressService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        try {
            val document = service.getPage(baseUrl)

            // Main listing from home page
            val shows = document.select("article, .post, .item, .card-show, .show-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .entry-title")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")
                TvShow(id = id, title = title, poster = poster)
            }

            if (shows.isNotEmpty()) {
                categories.add(Category("Doramas", shows))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()

        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) "$baseUrl/?s=$encodedQuery" else "$baseUrl/page/$page/?s=$encodedQuery"
            val document = service.getPage(url)
            parseShowList(document)
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/page/$page"
            val document = service.getPage(url)
            parseShowList(document).filterIsInstance<Movie>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/doramas" else "$baseUrl/doramas/page/$page"
            val document = service.getPage(url)
            parseShowList(document).filterIsInstance<TvShow>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseShowList(document: Document): List<AppAdapter.Item> {
        return document.select("article, .post, .item, .card-show, .show-item, .series-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .entry-title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")

            if (link.contains("/pelicula/")) {
                Movie(id = id, title = title, poster = poster)
            } else {
                TvShow(id = id, title = title, poster = poster)
            }
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)
        return Movie(
            id = id,
            title = document.selectFirst("h1, .entry-title")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .entry-content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .generos a, .sgeneros a").map {
                Genre(id = it.attr("href"), name = it.text())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val seasons = document.select(".seasons-list .se-c, .season-item, .temporada").mapIndexed { i, el ->
            val num = el.selectFirst(".title, .num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (i + 1)
            Season(id = "$id/season/$num", number = num, title = "Temporada $num")
        }

        return TvShow(
            id = id,
            title = document.selectFirst("h1, .entry-title")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .entry-content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .generos a, .sgeneros a").map {
                Genre(id = it.attr("href"), name = it.text())
            },
            seasons = if (seasons.isNotEmpty()) seasons else listOf(Season(id = "$id/season/1", number = 1, title = "Temporada 1"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/season/")
        if (parts.size != 2) return emptyList()
        val showId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: 1

        val url = if (showId.startsWith("http")) showId else "$baseUrl/$showId"
        val document = service.getPage(url)

        return document.select(".episodes li, .episodios li, .chapter-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h3")?.text() ?: ""
            val epNum = el.selectFirst(".number, .num, .ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Episode(id = link.removePrefix(baseUrl).removePrefix("/"), number = epNum, title = title.ifEmpty { "Episodio $epNum" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return try {
            val document = service.getPage(url)
            val servers = mutableListOf<Video.Server>()

            document.select(".player-options li, .dooplay_player_option, .server-item, .option-btn").forEach { el ->
                val name = el.selectFirst(".title, span")?.text() ?: "Server"
                val dataPost = el.attr("data-post")
                val dataNume = el.attr("data-nume")
                val dataServer = el.attr("data-server")

                if (dataPost.isNotEmpty()) {
                    servers.add(Video.Server(id = "$dataPost|$dataNume|$dataServer", name = name))
                }
            }

            if (servers.isEmpty()) {
                document.select("iframe").forEach { el ->
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
        if (server.id.contains("|")) {
            val parts = server.id.split("|")
            val ajaxClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", baseUrl).build())
                }.dns(DnsResolver.doh).build()

            val body = okhttp3.FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", parts[0])
                .add("nume", parts[1])
                .add("type", "movie")
                .build()

            val response = ajaxClient.newCall(
                okhttp3.Request.Builder().url("$baseUrl/wp-admin/admin-ajax.php").post(body).build()
            ).execute()

            val resp = response.body?.string() ?: ""
            val embedUrl = Regex("""src=['"]([^'"]+)['"]""").find(resp)?.groupValues?.get(1)
                ?: throw Exception("No video URL found")
            return Extractor.extract(embedUrl, server)
        }
        return Extractor.extract(server.id, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id/page/$page"
        val document = service.getPage(url)
        val shows = parseShowList(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
