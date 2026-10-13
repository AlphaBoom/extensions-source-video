plugins {
    id("video-extension")
}

videoExtension {
    name = "Xfani"
    sourceClass = ".Xfani"
    versionCode = 10
    source {
        name = "稀饭动漫"
        lang = "zh"
        baseUrl = "https://anime.xifanacg.com"
    }
}
