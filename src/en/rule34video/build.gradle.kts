plugins {
    id("video-extension")
}

videoExtension {
    name = "Rule34Video"
    sourceClass = ".Rule34Video"
    versionCode = 12
    contentWarning = 2
    source {
        name = "Rule34Video"
        lang = "en"
        baseUrl = "https://rule34video.com"
    }
}
