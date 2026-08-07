plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Scraper for DongStream"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://dongstream.com/favicon-32x32.png"
}

android {
    namespace = "com.dongstream"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
}
