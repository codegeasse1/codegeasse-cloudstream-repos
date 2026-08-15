plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Scraper for CoomerVideo"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://official.coomer.com.co/favicon.ico"
}

android {
    namespace = "com.coomervideo"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
}
