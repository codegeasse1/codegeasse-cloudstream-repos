plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 3

cloudstream {
    description = "Anikoto Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=anikototv.to&sz=%size%"
}

android {
    namespace = "com.anikoto"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
