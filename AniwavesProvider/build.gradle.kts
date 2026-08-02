import com.lagradost.cloudstream3.gradle.CloudstreamExtensionConfiguration

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.lagradost.cloudstream3.gradle:builder:1.0.4")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
    }
}

apply(plugin = "com.lagradost.cloudstream3.gradle")

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
