package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object BetaSeriesProvider : Provider {

    override val name = "BetaSeries"
    override val baseUrl = "https://www.betaseries.com"
    override val language = "es"
    override val logo = "https://www.betaseries.com/images/favicon.png"
    private const val TAG = "BetaSeriesProvider"
    private const val API_BASE = "https://api.betaseries.com/"

    // BetaSeries is primarily a tracking/recommendation platform, not a streaming site.
    // It provides metadata about series/movies but does not host content.
    // This provider indexes their catalog for discovery.

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .build())
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    // BetaSeries API models
    data class BSResponse(
        @SerializedName("shows") val shows: List<BSShow>? = null,
        @SerializedName("movies") val movies: List<BSMovie>? = null,
        @SerializedName("show") val show: BSShow? = null,
        @SerializedName("movie") val movie: BSMovie? = null,
        @SerializedName("episodes") val episodes: List<BSEpisode>? = null,
        @SerializedName("characters") val characters: List<BSCharacter>? = null,
    )

    data class BSShow(
        @SerializedName("id") val id: Int = 0,
        @SerializedName("thetvdb_id") val thetvdbId: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("poster") val poster: String? = null,
        @SerializedName("genres") val genres: Map<String, String>? = null,
        @SerializedName("seasons") val seasons: Int = 0,
        @SerializedName("notes") val notes: BSNotes? = null,
    )

    data class BSMovie(
        @SerializedName("id") val id: Int = 0,
        @SerializedName("tmdb_id") val tmdbId: Int = 0,
        @SerializedName("title") val title: String? = null,
        @SerializedName("synopsis") val synopsis: String? = null,
        @SerializedName("poster") val poster: String? = null,
        @SerializedName("genres") val genres: Map<String, String>? = null,
    )

    data class BSEpisode(
        @SerializedName("id") val id: Int = 0,
        @SerializedName("episode") val episode: Int = 0,
        @SerializedName("season") val season: Int = 0,
        @SerializedName("title") val title: String? = null,
    )

    data class BSNotes(
        @SerializedName("mean") val mean: Double = 0.0,
    )

    data class BSCharacter(
        @SerializedName("id") val id: Int = 0,
        @SerializedName("name") val name: String? = null,
        @SerializedName("show_summary") val showSummary: BSShow? = null,
    )

    private val service = Retrofit.Builder()
        .baseUrl(API_BASE)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(BetaSeriesService::class.java)

    private interface BetaSeriesService {
        @GET("shows/list")
        suspend fun getShowsList(@Query("start") start: Int = 0, @Query("limit") limit: Int = 20): BSResponse

        @GET("shows/search")
        suspend fun searchShows(@Query("title") title: String, @Query("limit") limit: Int = 20): BSResponse

        @GET("shows/display")
        suspend fun getShow(@Query("id") id: Int): BSResponse

        @GET("shows/episodes")
        suspend fun getEpisodes(@Query("id") id: Int): BSResponse

        @GET("movies/search")
        suspend fun searchMovies(@Query("title") title: String, @Query("limit") limit: Int = 20): BSResponse

        @GET("movies/list")
        suspend fun getMoviesList(@Query("start") start: Int = 0, @Query("limit") limit: Int = 20): BSResponse

        @GET("movies/movie")
        suspend fun getMovie(@Query("id") id: Int): BSResponse
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val showsResponse = service.getShowsList(limit = 20)

            val categories = mutableListOf<Category>()

            val shows = showsResponse.shows?.map { show ->
                TvShow(
                    id = "show/${show.id}",
                    title = show.title ?: "",
                    poster = show.poster,
                    overview = show.description,
                    rating = show.notes?.mean
                )
            } ?: emptyList()

            if (shows.isNotEmpty()) {
                categories.add(Category("Series Populares", shows))
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
            val results = mutableListOf<AppAdapter.Item>()

            val showsResponse = service.searchShows(query, 20)
            showsResponse.shows?.forEach { show ->
                results.add(TvShow(
                    id = "show/${show.id}",
                    title = show.title ?: "",
                    poster = show.poster,
                    overview = show.description,
                    rating = show.notes?.mean
                ))
            }

            val moviesResponse = service.searchMovies(query, 20)
            moviesResponse.movies?.forEach { movie ->
                results.add(Movie(
                    id = "movie/${movie.id}",
                    title = movie.title ?: "",
                    poster = movie.poster,
                    overview = movie.synopsis
                ))
            }

            results
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val start = (page - 1) * 20
            val response = service.getMoviesList(start, 20)
            response.movies?.map { movie ->
                Movie(
                    id = "movie/${movie.id}",
                    title = movie.title ?: "",
                    poster = movie.poster,
                    overview = movie.synopsis
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val start = (page - 1) * 20
            val response = service.getShowsList(start, 20)
            response.shows?.map { show ->
                TvShow(
                    id = "show/${show.id}",
                    title = show.title ?: "",
                    poster = show.poster,
                    overview = show.description,
                    rating = show.notes?.mean
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val movieId = id.substringAfter("movie/").toIntOrNull() ?: throw Exception("Invalid movie ID")
        val response = service.getMovie(movieId)
        val movie = response.movie ?: throw Exception("Movie not found")
        return Movie(
            id = id,
            title = movie.title ?: "",
            poster = movie.poster,
            overview = movie.synopsis,
            genres = movie.genres?.map { (_, name) ->
                Genre(id = name, name = name)
            } ?: emptyList()
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val showId = id.substringAfter("show/").toIntOrNull() ?: throw Exception("Invalid show ID")
        val response = service.getShow(showId)
        val show = response.show ?: throw Exception("Show not found")

        val seasons = (1..(show.seasons)).map { num ->
            Season(id = "$id/season/$num", number = num, title = "Temporada $num")
        }

        return TvShow(
            id = id,
            title = show.title ?: "",
            poster = show.poster,
            overview = show.description,
            rating = show.notes?.mean,
            genres = show.genres?.map { (_, name) ->
                Genre(id = name, name = name)
            } ?: emptyList(),
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("/season/").substringAfter("show/").toIntOrNull() ?: return emptyList()
        val seasonNum = seasonId.substringAfter("/season/").toIntOrNull() ?: 1

        return try {
            val response = service.getEpisodes(showId)
            response.episodes
                ?.filter { it.season == seasonNum }
                ?.map { ep ->
                    Episode(
                        id = "show/$showId/episode/${ep.id}",
                        number = ep.episode,
                        title = ep.title ?: "Episodio ${ep.episode}"
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // BetaSeries does not host content - it's a tracking/recommendation platform
        // No video servers available
        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        throw Exception("BetaSeries es una plataforma de seguimiento de series, no aloja contenido de video.")
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        // BetaSeries doesn't have a direct genre listing endpoint
        val shows = getTvShows(page)
        return Genre(id = id, name = id, shows = shows.filterIsInstance<Show>())
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
