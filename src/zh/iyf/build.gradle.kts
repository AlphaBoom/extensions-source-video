plugins {
    id("video-extension")
}

videoExtension {
    name = "iyf"
    sourceClass = ".Iyf"
    versionCode = 2
    source {
        name = "爱壹帆"
        lang = "zh"
        baseUrl = "https://www.iyf.tv"
    }
}
