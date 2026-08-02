buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        // Corrected JitPack path for the CloudStream Gradle Builder
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
    }
}

// Corrected plugin application ID
apply(plugin = "com.github.recloudstream.gradle")

cloudstream {
    name = "Aniwaves"
    description = "Aniwaves Extension"
    version = 1
    authors = listOf("Codegeasse")
    mainProject = true
}

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")
}

dependencies {
    val cloudstreamApi = "com.github.recloudstream:cloudstream:master-SNAPSHOT"
    compileOnly(cloudstreamApi)
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.json:json:20230227")
}
