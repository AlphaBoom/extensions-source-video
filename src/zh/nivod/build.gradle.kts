plugins {
    id("video-extension")
}

videoExtension {
    name = "Nivod"
    sourceClass = ".Nivod"
    versionCode = 3
    source {
        name = "泥视频"
        lang = "zh"
        baseUrl = "https://www.nivod.cc"
    }
}
