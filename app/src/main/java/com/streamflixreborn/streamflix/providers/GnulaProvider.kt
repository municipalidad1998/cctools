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

object GnulaProvider : Provider {

    override val name = "Gnula"
    override val baseUrl = "https://gnula.cc"
    override val language = "es"
    override val logo = "https://gnula.cc/favicon.ico"
    private const val TAG = "GnulaProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9")
                .header("Referer", baseUrl)
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
        .create(GnulaService::class.java)

    private interface GnulaService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    // Gnula uses Cloudflare protection. Domain may vary (wnv5.gnula.cc, etc.)
    // Try multiple known subdomains

    private fun extractId(link: String): String {
        return link.replace(Regex("https?://[^/]+"), "").removePrefix("/").removeSuffix("/")
    }

    override suspend fun getHome(): List<Category> {
        return try {
            // Try multiple Gnula domains
            val domains = listOf("https://gnula.cc", "https://wnv5.gnula.cc", "https://www.gnula.cc")
            var document: Document? = null

            for (domain in domains) {
                try {
                    document = service.getPage(domain)
                    if (document.select("article, .item, .movie-item, .post").isNotEmpty()) break
                } catch (_: Exception) {}
            }

            val doc = document ?: return emptyList()
            val categories = mutableListOf<Category>()

            val movies = doc.select("article, .item, .movie-item, .post, .ml-item, .film-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst("h2, h3, .title, .name, .film-title")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                Movie(id = extractId(link), title = title, poster = poster)
            }

            if (movies.isNotEmpty()) categories.add(Category("Películas", movies.take(20)))
            categories
        } catch (e: Exception) {
            Log.e(TAG, "getHome error (posible Cloudflare): ${e.message}")
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
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/page/$page"
            parseList(service.getPage(url)).filterIsInstance<Movie>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series/page/$page"
            parseList(service.getPage(url)).filterIsInstance<TvShow>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseList(document: Document): List<AppAdapter.Item> {
        return document.select("article, .item, .movie-item, .post, .ml-item, .film-item").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title, .name, .film-title")?.text() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            val id = extractId(link)

            if (link.contains("/pelicula/") || link.contains("/movie/")) {
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
            title = document.selectFirst("h1, .entry-title, .titulo")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .entry-content p, .content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .generos a, .category a").map {
                Genre(id = it.attr("href"), name = it.text())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)

        val seasons = document.select(".season-list .season, .se-c, .temporada").mapIndexed { i, _ ->
            Season(id = "$id/season/${i + 1}", number = i + 1, title = "Temporada ${i + 1}")
        }

        return TvShow(
            id = id,
            title = document.selectFirst("h1, .entry-title, .titulo")?.text() ?: "",
            overview = document.selectFirst(".description, .sinopsis, .entry-content p, .content p")?.text(),
            poster = document.selectFirst("meta[property=og:image]")?.attr("content"),
            genres = document.select(".genres a, .generos a, .category a").map {
                Genre(id = it.attr("href"), name = it.text())
            },
            seasons = if (seasons.isNotEmpty()) seasons else listOf(Season(id = "$id/season/1", number = 1, title = "Temporada 1"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("/season/")
        val url = if (showId.startsWith("http")) showId else "$baseUrl/$showId"
        val document = service.getPage(url)

        return document.select(".episode-item, .ep-item, .chapter, .episodios li, .tab-content li").mapNotNull { el ->
            val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h3, h4")?.text() ?: ""
            val epNum = el.selectFirst(".number, .ep-num")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            Episode(id = extractId(link), number = epNum, title = title.ifEmpty { "Episodio $epNum" }, poster = poster)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return try {
            val document = service.getPage(url)
            val servers = mutableListOf<Video.Server>()

            // Gnula typically uses dooplay player options
            document.select(".player-options li, .dooplay_player_option, .server-item, .option-btn, .tab-pane iframe").forEach { el ->
                val name = el.selectFirst(".title, span")?.text() ?: "Server"
                val dataPost = el.attr("data-post")
                val dataNume = el.attr("data-nume")
                val dataServer = el.attr("data-server")

                if (dataPost.isNotEmpty()) {
                    servers.add(Video.Server(id = "$dataPost|$dataNume|$dataServer", name = name))
                }
            }

            // Fallback: iframes
            if (servers.isEmpty()) {
                document.select("iframe, .player-embed iframe").forEach { el ->
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
        val shows = parseList(document).filterIsInstance<Show>()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
