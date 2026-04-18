package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object HiTvProvider : Provider {

    override val name = "HiTV"
    override val baseUrl = "https://home.hitv.vip"
    override val language = "es"
    override val logo = "https://home.hitv.vip/favicon.ico"
    private const val TAG = "HiTvProvider"
    private const val API_BASE = "https://api.hitv.vip/"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()
            chain.proceed(request)
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(API_BASE)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(HiTvService::class.java)

    // API response models
    data class HiTvResponse(
        @SerializedName("data") val data: HiTvData? = null,
        @SerializedName("code") val code: Int = 0,
    )

    data class HiTvData(
        @SerializedName("list") val list: List<HiTvItem>? = null,
        @SerializedName("info") val info: HiTvItem? = null,
        @SerializedName("total") val total: Int = 0,
    )

    data class HiTvItem(
        @SerializedName("id") val id: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("cover_url") val coverUrl: String? = null,
        @SerializedName("poster") val poster: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("year") val year: String? = null,
        @SerializedName("category") val category: String? = null,
        @SerializedName("episodes") val episodes: List<HiTvEpisode>? = null,
        @SerializedName("seasons") val seasons: List<HiTvSeason>? = null,
    )

    data class HiTvEpisode(
        @SerializedName("id") val id: String? = null,
        @SerializedName("number") val number: Int = 0,
        @SerializedName("title") val title: String? = null,
        @SerializedName("cover_url") val coverUrl: String? = null,
    )

    data class HiTvSeason(
        @SerializedName("id") val id: String? = null,
        @SerializedName("number") val number: Int = 0,
        @SerializedName("title") val title: String? = null,
    )

    private interface HiTvService {
        @GET("v1/content/home")
        suspend fun getHome(@Header("Accept-Language") lang: String = "es"): HiTvResponse

        @GET("v1/content/search")
        suspend fun search(@Query("keyword") keyword: String, @Query("page") page: Int = 1): HiTvResponse

        @GET("v1/content/list")
        suspend fun getList(@Query("type") type: String, @Query("page") page: Int = 1): HiTvResponse

        @GET("v1/content/detail")
        suspend fun getDetail(@Query("id") id: String): HiTvResponse
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val response = service.getHome()
            val categories = mutableListOf<Category>()

            response.data?.list?.let { items ->
                val shows = items.mapNotNull { item ->
                    val id = item.id ?: return@mapNotNull null
                    val title = item.title ?: item.name ?: return@mapNotNull null
                    val poster = item.coverUrl ?: item.poster

                    if (item.category?.contains("movie", true) == true) {
                        Movie(id = id, title = title, poster = poster)
                    } else {
                        TvShow(id = id, title = title, poster = poster)
                    }
                }
                if (shows.isNotEmpty()) {
                    categories.add(Category("Destacados", shows))
                }
            }

            categories
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return try {
            val response = service.search(query, page)
            response.data?.list?.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val title = item.title ?: item.name ?: return@mapNotNull null
                val poster = item.coverUrl ?: item.poster

                if (item.category?.contains("movie", true) == true) {
                    Movie(id = id, title = title, poster = poster)
                } else {
                    TvShow(id = id, title = title, poster = poster)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val response = service.getList("movie", page)
            response.data?.list?.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val title = item.title ?: item.name ?: return@mapNotNull null
                Movie(id = id, title = title, poster = item.coverUrl ?: item.poster)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val response = service.getList("tv", page)
            response.data?.list?.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val title = item.title ?: item.name ?: return@mapNotNull null
                TvShow(id = id, title = title, poster = item.coverUrl ?: item.poster)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val response = service.getDetail(id)
        val item = response.data?.info ?: throw Exception("Movie not found")
        return Movie(
            id = id,
            title = item.title ?: item.name ?: "",
            overview = item.description,
            poster = item.coverUrl ?: item.poster,
            released = item.year,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val response = service.getDetail(id)
        val item = response.data?.info ?: throw Exception("Show not found")

        val seasons = item.seasons?.map { s ->
            Season(id = "$id/${s.number}", number = s.number, title = s.title ?: "Temporada ${s.number}")
        } ?: listOf(Season(id = "$id/1", number = 1, title = "Temporada 1"))

        return TvShow(
            id = id,
            title = item.title ?: item.name ?: "",
            overview = item.description,
            poster = item.coverUrl ?: item.poster,
            released = item.year,
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("/")
        val response = service.getDetail(showId)

        return response.data?.info?.episodes?.map { ep ->
            Episode(
                id = "${showId}/ep/${ep.number}",
                number = ep.number,
                title = ep.title ?: "Episodio ${ep.number}",
                poster = ep.coverUrl
            )
        } ?: emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // Note: HiTV content may require VIP. Only public streams are accessible.
        return try {
            val showId = when (videoType) {
                is Video.Type.Movie -> id
                is Video.Type.Episode -> id.substringBefore("/ep/")
            }
            val response = service.getDetail(showId)
            val item = response.data?.info ?: return emptyList()

            // Return available server info if accessible
            listOf(Video.Server(id = "$baseUrl/es-es/play/$showId", name = "HiTV Player"))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // HiTV uses proprietary player; attempt extraction
        return try {
            Extractor.extract(server.id, server)
        } catch (e: Exception) {
            throw Exception("Contenido no disponible sin suscripción VIP. ${e.message}")
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val response = service.getList(id, page)
        val shows = response.data?.list?.mapNotNull { item ->
            val itemId = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            TvShow(id = itemId, title = title, poster = item.coverUrl ?: item.poster) as Show
        } ?: emptyList()
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
