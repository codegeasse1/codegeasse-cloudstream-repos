plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle") // ✅ Required to build .cs3 extensions
}

// ✅ CloudStream's native DSL for extension metadata (Replaces the hacky _ext properties)
cloudstream {
    name = "LeakPorner"
    description = "Watch Onlyfans leaks from leakporner.org"
    version = 1
    authors = listOf("Codegeasse")
    tvTypes = listOf("NSFW")
    language = "en"
}

android {
    namespace = "com.leakporner"
    compileSdk = 33 // CloudStream currently targets 33, but 34 is fine if your environment supports it
    
    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // ✅ Matches your GitHub Actions workflow which fetches the jar locally
    compileOnly(files("cloudstream.jar"))
    
    // (Optional but recommended) Include Jsoup if you are scraping HTML
    implementation("org.jsoup:jsoup:1.15.3") 
}
