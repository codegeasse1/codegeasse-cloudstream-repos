plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "LeakPorner Extension"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=leakporner.org&sz=%size%"
}

android {
    namespace = "com.leakporner"
    compileSdk = 33
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    compileOnly(files("../cloudstream.jar"))
}
