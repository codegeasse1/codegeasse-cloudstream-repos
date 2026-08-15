plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 3

cloudstream {
    description = "MoviesMod Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Movie", "TVSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=moviesmod.zone&sz=%size%"
}

android {
    namespace = "com.moviesmod"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
