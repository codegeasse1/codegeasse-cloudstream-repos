plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
}

android {
    namespace = "com.aniwaves"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        targetSdk = 35
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
    val cloudstreamApi = "com.github.recloudstream.cloudstream:library:-SNAPSHOT"
    compileOnly(cloudstreamApi)
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.json:json:20230227")
}
