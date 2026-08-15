version = 1

cloudstream {
    description = "Watch Movies, TV Shows and Anime from Cinephile"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "TvShow")
    iconUrl = "https://www.google.com/s2/favicons?domain=cinephile.live&sz=%size%"
}

android {
    namespace = "com.cinephile"
    defaultConfig {
        minSdk = 21
    }
}
