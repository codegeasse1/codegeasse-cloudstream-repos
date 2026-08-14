plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

// use an integer for version numbers
version = 5

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Watch Chinese Anime / Donghua from AnimeKhor"
    authors = listOf("Codegeasse")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=animekhor.org&sz=%size%"
}

android {
    namespace = "com.animekhor"
    compileSdk = 33
    
    defaultConfig {
        minSdk = 21
    }
}
