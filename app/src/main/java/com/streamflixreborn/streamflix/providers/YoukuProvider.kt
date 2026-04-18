package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object YoukuProvider : Provider {

    override val name = "Youku"
    override val baseUrl = "https://www.youku.tv"
    override val language = "zh"
    override val logo = "https://img.alicdn.com/tfs/TB1We..A.H1gK0jSZSyXXX4lpXa-192-192.png"
    private const val TAG = "YoukuProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9,zh-CN;q=0.8")
                .header("Referer", "$baseUrl/")
                .build())
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    // Youku API models
    data class YoukuResponse(
        @SerializedName("data") val data: Any? = null,
        @SerializedName("list") val list: List<YoukuItem>? = null,
    )

    data class YoukuItem(
        @SerializedName("id") val id: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("img") val img: String? = null,
        @SerializedName("posterUrl") val posterUrl: String? = null,
        @SerializedName("summary") val summary: String? = null,
        @SerializedName("totalEpisodes") val totalEpisodes: Int = 0,
        @SerializedName("area") val area: String? = null,
        @SerializedName("genre") val genre: String? = null,
    )

    // Note: Youku's main content requires VIP/subscription.
    // This provider only indexes publicly visible metadata for browsing.
    // Actual playback of VIP content is not supported.

    override suspend fun getHome(): List<Category> {
        return try {
            listOf(
                Category("TV Shows", listOf(
                    TvShow(id = "browse/drama", title = "电视剧 (Series)", poster = null),
                    TvShow(id = "browse/variety", title = "综艺 (Variedades)", poster = null),
                    TvShow(id = "browse/anime", title = "动漫 (Anime)", poster = null),
                )),
                Category("Movies", listOf(
                    Movie(id = "browse/movie", title = "电影 (Películas)", poster = null),
                ))
            )
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            // Youku search requires their internal API, which needs auth tokens
            // We scrape the search results page instead
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?keyword=$encoded"
            val client2 = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build())
                }.dns(DnsResolver.doh).build()

            val request = okhttp3.Request.Builder().url(url).build()
            val response = client2.newCall(request).execute()
            val html = response.body?.string() ?: ""

            // Parse search results from HTML
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select(".search-result-item, .result-item, .s-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst(".title, h3, h2")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("src")
                val id = link.substringAfterLast("/").substringBefore(".")
                TvShow(id = "show/$id", title = title, poster = poster)
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = "$baseUrl/channel/webhome/c_99.html"
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = client.newCall(request).execute()
            val doc = org.jsoup.Jsoup.parse(response.body?.string() ?: "")

            doc.select(".card-item, .movie-item, .list-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst(".title, .name")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.substringAfterLast("/").substringBefore(".")
                Movie(id = "movie/$id", title = title, poster = poster)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMovies error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = "$baseUrl/channel/webhome/c_97.html"
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = client.newCall(request).execute()
            val doc = org.jsoup.Jsoup.parse(response.body?.string() ?: "")

            doc.select(".card-item, .show-item, .list-item").mapNotNull { el ->
                val link = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = el.selectFirst(".title, .name")?.text() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                val id = link.substringAfterLast("/").substringBefore(".")
                TvShow(id = "show/$id", title = title, poster = poster)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val vid = id.substringAfter("movie/")
        return Movie(
            id = id,
            title = vid,
            overview = null,
            poster = null,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val vid = id.substringAfter("show/")
        return TvShow(
            id = id,
            title = vid,
            overview = null,
            poster = null,
            seasons = listOf(Season(id = "$id/season/1", number = 1, title = "Episodios"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // Youku episode listing requires internal API
        return emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // Youku video playback requires VIP/authentication for most content
        return listOf(Video.Server(id = "$baseUrl/v/$id", name = "Youku Player"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Youku content is DRM-protected; VIP content cannot be played without subscription
        throw Exception("El contenido de Youku requiere suscripción VIP para su reproducción.")
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val shows = when (id) {
            "drama" -> getTvShows(page)
            "movie" -> getMovies(page)
            else -> getTvShows(page)
        }
        return Genre(id = id, name = id, shows = shows.filterIsInstance<Show>())
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
