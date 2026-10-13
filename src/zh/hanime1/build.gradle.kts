plugins {
    id("video-extension")
}

videoExtension {
    name = "Hanime1"
    sourceClass = ".Hanime1"
    versionCode = 13
    contentWarning = 2
    source {
        name = "Hanime1.me"
        lang = "zh"
        baseUrl = "https://hanime1.me"
    }
}
