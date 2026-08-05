@file:Suppress("LocalVariableName")

plugins {
    id("com.android.library")
    kotlin("android")
}

val apkName = "LeakPornerProvider"

android {
    namespace = "com.leakporner"
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

// NOTE: Avoid calling an unresolved `set(...)` function in this DSL. Use project extra properties
// to expose the metadata expected by the CloudStream plugin at runtime.

// Set extension metadata – CloudStream uses these properties to identify the extension
// Use the Kotlin DSL way to set extra properties on the project
val _ext = project.extensions.extraProperties
_ext["extName"] = "LeakPornerProvider"
_ext["extVersionCode"] = 1
_ext["extVersionName"] = "1.0.0"
_ext["extDescription"] = "Watch Onlyfans leaks from leakporner.org"
_ext["extAuthor"] = "YourName"
_ext["extLanguage"] = "en"
// Provide network type as a plain String (avoid unresolved enum reference)
_ext["extNetworkType"] = "NSFW"


dependencies {
    // Don't reference a non-existent subproject. Use the published CloudStream artifact instead.
       compileOnly(files("cloudstream.jar"))
}
