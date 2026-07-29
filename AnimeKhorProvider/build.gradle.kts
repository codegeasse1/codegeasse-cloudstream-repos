import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.animekhor"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}

cloudstream {
    // Language tag
    language = "en"
    
    // Status flag: 1 = Working, 2 = Down, 3 = Slow/Buggy
    status = 1
    
    // Provider details
    authors = listOf("Codegeasse")
    description = "Watch Chinese Anime/Donghua English Subbed For Free Online from AnimeKhor"
}
