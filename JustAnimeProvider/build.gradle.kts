plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 3

cloudstream {
    description = "JustAnime Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=justanime.to&sz=%size%"
}

android {
    namespace = "com.justanime"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.json:json:20230227")
}
