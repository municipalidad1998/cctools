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

object DoramasYtProvider : Provider {

    override val name = "DoramasYT"
    override val baseUrl = "https://www.doramasyt.com"
    override val language = "es"
    override val logo = "https://www.doramasyt.com/favicon.ico"
    private const val TAG = "DoramasYtProvider"

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
        .create(DoramasYtService::class.java)

    private interface DoramasYtService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        try {
            val document = async { service.getPage(baseUrl) }.await()

            // Featured / latest episodes
            val latestEpisodes = document.select("div.items article, div.listupd article, article.shortcode-in-short").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .tt")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")

                if (link.contains("/dorama/")) {
                    TvShow(id = id, title = title, poster = poster)
                } else {
                    Episode(id = id, number = 0, title = title, poster = poster)
                }
            }

            if (latestEpisodes.isNotEmpty()) {
                categories.add(Category("Últimos Capítulos", latestEpisodes.filterIsInstance<Episode>()))
            }

            // Recent series
            val recentSeries = document.select("div.serie_cont article, .serieslist article, .list-series article").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .tt")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")
                TvShow(id = id, title = title, poster = poster)
            }

            if (recentSeries.isNotEmpty()) {
                categories.add(Category("Series Recientes", recentSeries))
            }

        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("dorama", "Doramas"),
                Genre("pelicula", "Películas"),
            )
        }

        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) "$baseUrl/?s=$encodedQuery" else "$baseUrl/page/$page/?s=$encodedQuery"
            val document = service.getPage(url)

            document.select("div.search-page article, div.result-item article, article.shortcode-in-short").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .tt")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")

                if (link.contains("/dorama/")) {
                    TvShow(id = id, title = title, poster = poster)
                } else {
                    Movie(id = id, title = title, poster = poster)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/page/$page"
            val document = service.getPage(url)
            parseMovieList(document)
        } catch (e: Exception) {
            Log.e(TAG, "getMovies error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/doramas" else "$baseUrl/doramas/page/$page"
            val document = service.getPage(url)
            parseTvShowList(document)
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows error: ${e.message}")
            emptyList()
        }
    }

    private fun parseMovieList(document: Document): List<Movie> {
        return document.select("div.items article, .movie-item, article.shortcode-in-short").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .tt")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")
            Movie(id = id, title = title, poster = poster)
        }
    }

    private fun parseTvShowList(document: Document): List<TvShow> {
        return document.select("div.items article, .serieslist article, article.shortcode-in-short").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .tt")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val id = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")
            TvShow(id = id, title = title, poster = poster)
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val title = document.selectFirst("h1, .titulo, .entry-title")?.text() ?: ""
        val overview = document.selectFirst(".wp-content, .description, .entry-content p")?.text()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val banner = document.selectFirst(".bg, .header-image img")?.attr("src")
        val genres = document.select("div.genres a, .generos a, .sgeneros a").map {
            Genre(id = it.attr("href").removePrefix(baseUrl), name = it.text())
        }

        return Movie(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            banner = banner,
            genres = genres
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val title = document.selectFirst("h1, .titulo, .entry-title")?.text() ?: ""
        val overview = document.selectFirst(".wp-content, .description, .entry-content p")?.text()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val genres = document.select("div.genres a, .generos a, .sgeneros a").map {
            Genre(id = it.attr("href").removePrefix(baseUrl), name = it.text())
        }

        // Parse seasons
        val seasonElements = document.select("div.se-c, .seasons-list .se-c, #seasons .se-c")
        val seasons = seasonElements.mapIndexed { index, el ->
            val seasonNum = el.selectFirst(".se-q .title")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: (index + 1)
            Season(
                id = "$id/season/$seasonNum",
                number = seasonNum,
                title = "Temporada $seasonNum"
            )
        }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            genres = genres,
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

        return document.select("div.se-c:nth-child($seasonNum) li, .episodios li, .episodes-list li").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".episodiotitle, .title, h3")?.text() ?: ""
            val epNum = el.selectFirst(".numerando, .number")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val epId = link.removePrefix(baseUrl).removePrefix("/").removeSuffix("/")

            Episode(id = epId, number = epNum, title = title.ifEmpty { "Episodio $epNum" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = when (videoType) {
            is Video.Type.Movie -> if (id.startsWith("http")) id else "$baseUrl/$id"
            is Video.Type.Episode -> if (id.startsWith("http")) id else "$baseUrl/$id"
        }

        return try {
            val document = service.getPage(url)

            // Try multiple server selector patterns
            val servers = mutableListOf<Video.Server>()

            document.select("div#playeroptions li, .dooplay_player_option, ul#playeroptionsul li, .option-selector li").forEach { el ->
                val serverName = el.selectFirst(".title, .server-name, span")?.text() ?: "Server"
                val dataPost = el.attr("data-post")
                val dataNume = el.attr("data-nume")
                val dataServer = el.attr("data-server")

                if (dataPost.isNotEmpty()) {
                    servers.add(Video.Server(id = "$dataPost|$dataNume|$dataServer", name = serverName))
                }
            }

            // Fallback: look for iframe sources
            if (servers.isEmpty()) {
                document.select("iframe, .player-frame iframe, #player-embed iframe").forEach { el ->
                    val src = el.attr("src").ifEmpty { el.attr("data-src") }
                    if (src.isNotEmpty()) {
                        servers.add(Video.Server(id = src, name = "Player", src = src))
                    }
                }
            }

            // Another fallback: look for embedded video links
            if (servers.isEmpty()) {
                document.select("a[data-src], a[href*=\"player\"], .play-btn a").forEach { el ->
                    val src = el.attr("data-src").ifEmpty { el.attr("href") }
                    if (src.isNotEmpty() && src.startsWith("http")) {
                        servers.add(Video.Server(id = src, name = "Player", src = src))
                    }
                }
            }

            servers
        } catch (e: Exception) {
            Log.e(TAG, "getServers error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // If server.id contains pipe separators, it's an AJAX call
        if (server.id.contains("|")) {
            val parts = server.id.split("|")
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", baseUrl)
                        .build()
                    chain.proceed(req)
                }
                .dns(DnsResolver.doh)
                .build()

            val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
            val body = okhttp3.FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", parts[0])
                .add("nume", parts[1])
                .add("type", "movie")
                .build()

            val response = client.newCall(
                okhttp3.Request.Builder().url(ajaxUrl).post(body).build()
            ).execute()

            val responseBody = response.body?.string() ?: ""
            val embedUrl = Regex("""src=['"]([^'"]+)['"]""").find(responseBody)?.groupValues?.get(1)
                ?: throw Exception("No video URL found")

            return Extractor.extract(embedUrl, server)
        }

        return Extractor.extract(server.id, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id/page/$page"
        val document = service.getPage(url)
        val shows = parseTvShowList(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
