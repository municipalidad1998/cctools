package com.streamflixreborn.streamflix.providers

import android.util.Base64
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

object PelisflixProvider : Provider {

    override val name = "Pelisflix"
    override val baseUrl = "https://pelisflix200.app"
    override val language = "es"
    override val logo = "https://pelisflix200.app/favicon.ico"
    private const val TAG = "PelisflixProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9")
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
        .create(PelisflixService::class.java)

    private interface PelisflixService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private fun extractId(link: String): String {
        return link.replace(Regex("https?://[^/]+"), "").removePrefix("/").removeSuffix("/")
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        try {
            val homeDeferred = async { service.getPage(baseUrl) }
            val moviesDeferred = async { service.getPage("$baseUrl/peliculas-online/") }
            val seriesDeferred = async { service.getPage("$baseUrl/series-online/") }

            val homeDoc = homeDeferred.await()

            // Featured content from homepage
            val featured = homeDoc.select("article, .item, .movie-item, .post, .film-poster, .ml-item, .TPostMv").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .name, .Title")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = extractId(link)

                if (link.contains("/pelicula/")) {
                    Movie(id = id, title = title, poster = poster)
                } else if (link.contains("/serie/")) {
                    TvShow(id = id, title = title, poster = poster)
                } else null
            }

            if (featured.isNotEmpty()) {
                categories.add(Category(Category.FEATURED, featured.take(10)))
            }

            // Movies
            try {
                val movies = parseMovieList(moviesDeferred.await())
                if (movies.isNotEmpty()) categories.add(Category("Películas", movies.take(20)))
            } catch (_: Exception) {}

            // Series
            try {
                val series = parseSeriesList(seriesDeferred.await())
                if (series.isNotEmpty()) categories.add(Category("Series", series.take(20)))
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("accion", "Acción"),
                Genre("comedia", "Comedia"),
                Genre("drama", "Drama"),
                Genre("terror", "Terror"),
                Genre("ciencia-ficcion", "Ciencia Ficción"),
                Genre("romance", "Romance"),
                Genre("animacion", "Animación"),
                Genre("aventura", "Aventura"),
                Genre("thriller", "Thriller"),
            )
        }

        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
            val document = service.getPage(url)
            parseSearchResults(document)
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page == 1) "$baseUrl/peliculas-online/" else "$baseUrl/peliculas-online/page/$page/"
            parseMovieList(service.getPage(url))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/series-online/" else "$baseUrl/series-online/page/$page/"
            parseSeriesList(service.getPage(url))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseMovieList(document: Document): List<Movie> {
        return document.select("article, .item, .movie-item, .post, .film-poster, .TPostMv, .ml-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            if (!link.contains("/pelicula/")) return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .name, .Title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Movie(id = extractId(link), title = title, poster = poster)
        }
    }

    private fun parseSeriesList(document: Document): List<TvShow> {
        return document.select("article, .item, .movie-item, .post, .film-poster, .TPostMv, .ml-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            if (!link.contains("/serie/")) return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .name, .Title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            TvShow(id = extractId(link), title = title, poster = poster)
        }
    }

    private fun parseSearchResults(document: Document): List<AppAdapter.Item> {
        return document.select("article, .item, .movie-item, .post, .film-poster, .TPostMv, .ml-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .name, .Title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val id = extractId(link)

            when {
                link.contains("/pelicula/") -> Movie(id = id, title = title, poster = poster)
                link.contains("/serie/") -> TvShow(id = id, title = title, poster = poster)
                else -> null
            }
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst("h1, .entry-title, .Title, .article-title")?.text() ?: ""
        val overview = document.selectFirst(".description, .sinopsis, .entry-content p, .wp-content p, .TPostBg p")?.text()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val banner = document.selectFirst(".bg, .header-image img, .TPostBg img")?.attr("src")
        val year = document.selectFirst(".date, .year, .info .Yr")?.text()
        val genres = document.select(".genres a, .generos a, .sgeneros a, .genres-list a").map {
            Genre(id = it.attr("href"), name = it.text())
        }
        val rating = document.selectFirst(".rating, .score, .imdb")?.text()?.toDoubleOrNull()

        return Movie(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            banner = banner,
            released = year,
            rating = rating,
            genres = genres
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id/"
        val document = service.getPage(url)

        val title = document.selectFirst("h1, .entry-title, .Title, .article-title")?.text() ?: ""
        val overview = document.selectFirst(".description, .sinopsis, .entry-content p, .wp-content p")?.text()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val genres = document.select(".genres a, .generos a, .sgeneros a, .genres-list a").map {
            Genre(id = it.attr("href"), name = it.text())
        }

        // Parse seasons
        val seasonElements = document.select(".seasons-list .se-c, .season-item, .Wdgt .AABox, .TPTblCn .AABox, .season-selector option")
        val seasons = if (seasonElements.isNotEmpty()) {
            seasonElements.mapIndexed { i, el ->
                val num = el.selectFirst(".title, .num, .se-q")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                    ?: el.attr("value").toIntOrNull()
                    ?: (i + 1)
                Season(id = "$id/season/$num", number = num, title = "Temporada $num")
            }
        } else {
            listOf(Season(id = "$id/season/1", number = 1, title = "Temporada 1"))
        }

        return TvShow(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
            genres = genres,
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/season/")
        if (parts.size != 2) return emptyList()
        val showId = parts[0]
        val seasonNum = parts[1].toIntOrNull() ?: 1

        val url = if (showId.startsWith("http")) showId else "$baseUrl/$showId/"
        val document = service.getPage(url)

        return document.select(".episode-item, .ep-item, .chapter, .episodios li, .TPTblCn tr, .AABox li").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h3, h4, .Num")?.text() ?: ""
            val epNum = el.selectFirst(".number, .ep-num, .Num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Episode(id = extractId(link), number = epNum, title = title.ifEmpty { "Episodio $epNum" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id/"
        return try {
            val document = service.getPage(url)
            val servers = mutableListOf<Video.Server>()

            // Pelisflix uses dooplay-style player
            document.select(".player-options li, .dooplay_player_option, .server-item, .option-btn, .TPlayerNv .Optnt").forEach { el ->
                val name = el.selectFirst(".title, span")?.text() ?: el.text().ifEmpty { "Server" }
                val dataPost = el.attr("data-post")
                val dataNume = el.attr("data-nume")
                val dataServer = el.attr("data-server")

                if (dataPost.isNotEmpty()) {
                    servers.add(Video.Server(id = "$dataPost|$dataNume|$dataServer", name = name))
                }
            }

            // Fallback: iframes
            if (servers.isEmpty()) {
                document.select("iframe, .player-embed iframe, .TPlayer iframe, #player iframe").forEach { el ->
                    val src = el.attr("src").ifEmpty { el.attr("data-src") }
                    if (src.isNotEmpty()) servers.add(Video.Server(id = src, name = "Player", src = src))
                }
            }

            // Another fallback: script-obfuscated embeds
            if (servers.isEmpty()) {
                document.select("script:containsData(player)").forEach { el ->
                    val script = el.data()
                    val urlMatch = Regex("""(https?://[^\s'"]+(?:embed|player|stream)[^\s'"]*)""").find(script)
                    if (urlMatch != null) {
                        servers.add(Video.Server(id = urlMatch.groupValues[1], name = "Embed", src = urlMatch.groupValues[1]))
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

            // Try JSON response
            val jsonMatch = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(resp)
            if (jsonMatch != null) {
                val embedUrl = jsonMatch.groupValues[1].replace("\\/", "/")
                return Extractor.extract(embedUrl, server)
            }

            // Try HTML src
            val embedUrl = Regex("""src=['"]([^'"]+)['"]""").find(resp)?.groupValues?.get(1)
                ?: throw Exception("No video URL found in player response")
            return Extractor.extract(embedUrl, server)
        }
        return Extractor.extract(server.id, server)
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/genero/$id/" else "$baseUrl/genero/$id/page/$page/"
        val document = service.getPage(url)
        val shows = parseSearchResults(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
