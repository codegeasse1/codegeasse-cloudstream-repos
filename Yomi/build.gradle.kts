cloudstream {
    description = "Watch Anime Free from Yomi"
    authors = listOf("Codegeasse")
    
    status = 1
    
    tvTypes = listOf("Anime")
    
    iconUrl = "https://www.google.com/s2/favicons?domain=yomi.to&sz=%size%"
}

android {
    namespace = "com.yomi"
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.json:json:20230227")
}
