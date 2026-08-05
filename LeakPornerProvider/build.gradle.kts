// build.gradle.kts

plugins {
    id("com.android.library")
    kotlin("android")
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
    implementation(project(":cloudstream3"))
}

// Extension metadata – CloudStream uses this to identify the extension
ext {
    set("extName", "LeakPornerProvider")
    set("extVersionCode", 1)
    set("extVersionName", "1.0.0")
    set("extDescription", "Watch Onlyfans leaks from leakporner.org")
    set("extAuthor", "YourName")
    set("extLanguage", "en")
    set("extNetworkType", ProviderType.NSFW)  // NSFW provider
}
