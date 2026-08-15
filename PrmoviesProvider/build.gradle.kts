plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Prmovies Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Movie", "TVSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=prmovies.directory&sz=%size%"
}

android {
    namespace = "com.prmovies"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
