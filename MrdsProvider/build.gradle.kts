version = 1

cloudstream {
    description = "MRDS Video Provider"
    authors = listOf("Codegeasse")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=mrds.com&sz=%size%"
}

android {
    namespace = "com.mrds"
    defaultConfig {
        minSdk = 21
    }
}
