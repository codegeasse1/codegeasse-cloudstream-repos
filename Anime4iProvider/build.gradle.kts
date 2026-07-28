// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Watch Chinese Anime / Donghua from Anime4i"
    authors = listOf("Codegeasse")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=anime4i.com&sz=%size%"
}

android {
    defaultConfig {
        minSdk = 21
    }
}
