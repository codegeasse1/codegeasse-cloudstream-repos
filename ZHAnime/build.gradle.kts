plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Scraper for ZH Anime"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=zhanime.online&sz=%size%"
}

android {
    namespace = "com.zh.anime"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
    // implementation("org.json:json:20230227") // Uncomment if you end up needing JSON parsing
}
