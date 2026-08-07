plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Scraper for 麻豆视频 (9191md)"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.9191md.me/template/ym005_pc/html/style/images/favicon.ico"
}

android {
    namespace = "com.md9191.video"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
}
