plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Aniwaves Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=aniwaves.ru&sz=%size%"
}

android {
    namespace = "com.aniwaves"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.json:json:20230227")
}
