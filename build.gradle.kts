import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.2")
        // Pinned commit instead of the -SNAPSHOT the template ships with,
        // since -SNAPSHOT no longer resolves on JitPack.
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        // Matches the Kotlin version CloudStream's current stubs expect.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: com.lagradost.cloudstream3.gradle.CloudstreamExtension.() -> Unit) =
    extensions.getByName<com.lagradost.cloudstream3.gradle.CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // Auto-fills from the GitHub repo this Action runs in — no edit needed.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "")
    }

    android {
        compileSdkVersion(33)

        defaultConfig {
            minSdk = 21
            targetSdk = 33
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    dependencies {
        val implementation by configurations
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        // IMPORTANT: do not bump Jackson above 2.13.1 — newer versions break
        // compatibility with older Android devices running CloudStream.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}
