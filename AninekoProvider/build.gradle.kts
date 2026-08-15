plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "AniNeko Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=anineko.to&sz=%size%"
}

android {
    namespace = "com.anineko"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
