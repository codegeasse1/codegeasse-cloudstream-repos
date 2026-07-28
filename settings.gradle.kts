rootProject.name = "anime4i-cloudstream"

// Auto-includes any top-level folder that has its own build.gradle.kts,
// so adding a second provider later is just "drop the folder in".
File(rootDir, ".").listFiles()?.forEach { file ->
    if (file.isDirectory && File(file, "build.gradle.kts").exists()) {
        include(file.name)
    }
}
