plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "TaiAV Extension for m.taiav.com"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=m.taiav.com&sz=%size%"
}

android {
    namespace = "com.kanav"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
