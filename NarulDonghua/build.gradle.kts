plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Scraper for Narul Donghua"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("Anime", "Movie")
    iconUrl = "https://i2.wp.com/naruldonghua.com/wp-content/uploads/2023/12/cropped-Narul-Donghua-32x32.jpg"
}

android {
    namespace = "com.naruldonghua"
    compileSdk = 33
    
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    val cloudstreamApi = "com.github.recloudstream:cloudstream:master-SNAPSHOT"
    compileOnly(cloudstreamApi)
    
    implementation("org.jsoup:jsoup:1.15.3")
}
