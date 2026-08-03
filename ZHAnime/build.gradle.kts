plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    // The name of the extension repository
    name = "ZHAnime"
    // A brief description of what this provider does
    description = "Scraper for ZH Anime"
    version = 1
    // The type of media provided
    tvTypes = listOf("Anime")
    authors = listOf("Codegeasse")
}

android {
    namespace = "com.zh.anime"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // The Cloudstream API dependency (provided by the host app)
    val csapi = "2.0.0" 
    compileOnly("com.github.recloudstream:cloudstream:$csapi")
    
    // JSoup for HTML parsing
    implementation("org.jsoup:jsoup:1.15.3")
}
