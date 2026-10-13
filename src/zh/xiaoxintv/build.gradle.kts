plugins {
    id("video-extension")
}

videoExtension {
    name = "Xiaoxintv"
    sourceClass = ".Xiaoxintv"
    versionCode = 3
    source {
        name = "小宝影院"
        lang = "zh"
        baseUrl = "https://xiaoheimi.cc"
    }
}
