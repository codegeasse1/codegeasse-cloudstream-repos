plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 2

cloudstream {
    description = "AniKage Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=anikage.cc&sz=%size%"
}

android {
    namespace = "com.anikage"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.json:json:20230227")
}
