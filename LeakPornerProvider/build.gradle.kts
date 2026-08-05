@file:Suppress("LocalVariableName")

plugins {
    id("com.android.library")
    kotlin("android")
}

val apkName = "LeakPornerProvider"

android {
    compileSdk = 34 // Or whatever version you were using

    defaultConfig {
        minSdk = 21
        // ❌ REMOVED: targetSdk (Fixes the deprecation warning)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    // ❌ REMOVED: kotlinOptions { jvmTarget = "17" } from inside here
}

// ✅ FIX 1: New Kotlin compilerOptions DSL (Place this OUTSIDE the android {} block)
kotlin {
    compilerOptions {
        // Note: Cloudstream extensions usually require Java 8 (JVM_1_8). 
        // If you specifically need 17, change JVM_1_8 to JVM_17 below.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// ✅ FIX 2: Pass "NSFW" as a String instead of the unresolved Enum
cloudstream {
    // ... your other cloudstream configs ...
    set("extNetworkType", "NSFW") 
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
