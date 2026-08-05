// build.gradle.kts

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.extensions") version "1.0.0" // adjust if needed
}

android {
    namespace = "com.leakporner"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":cloudstream3")) // main CloudStream API
    // if you need OkHttp or other libs, add here
}

// Extension metadata – important for CloudStream to recognise it
ext {
    set("extName", "LeakPornerProvider")   // display name in the app
    set("extVersionCode", 1)
    set("extVersionName", "1.0.0")
    set("extDescription", "Watch Onlyfans leaks from LeakPorner.org")
    set("extAuthor", "YourName")
    set("extLanguage", "en")
    set("extNetworkType", ProviderType.SITE) // or ProviderType.NSFW for adult sites
}
