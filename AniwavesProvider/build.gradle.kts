plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    name = "Aniwaves"
    description = "Aniwaves Extension"
    version = 1
    authors = listOf("Codegeasse")
    mainProject = true
}

dependencies {
    val cloudstreamApi = "com.github.recloudstream:cloudstream:master-SNAPSHOT"
    compileOnly(cloudstreamApi)
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.json:json:20230227")
}
