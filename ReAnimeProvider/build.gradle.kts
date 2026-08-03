plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 2

cloudstream {
    description = "Re:Anime Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=reanime.to&sz=%size%"
}

android {
    namespace = "com.reanime"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
