plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "ppp.porn Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=asiangirl.porn&sz=%size%"
}

android {
    namespace = "com.pppporn"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}
