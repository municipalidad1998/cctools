package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.extractors.Extractor

object DoramasYtProvider : Provider {
    override val name = "Doramas YT"
    override val baseUrl = "https://www.doramasyt.com"
    override val language = "es"
    override val logo = "https://www.doramasyt.com/favicon.ico"

    override suspend fun getHome(): List<Category> = emptyList()
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw Exception("Not implemented")
    override suspend fun getTvShow(id: String): TvShow = throw Exception("Not implemented")
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not implemented")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not implemented")
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = emptyList()
    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)
}
