plugins {
    id("video-extension")
}

videoExtension {
    name = "RouVideo"
    sourceClass = ".RouVideoFactory"
    versionCode = 18
    contentWarning = 2
    source {
        name = "肉視頻"
        lang = "all"
        baseUrl = "https://rou.video/home"
    }

    source {
        name = "肉視頻"
        lang = "zh"
        baseUrl = "https://rou.video/home"
    }

    source {
        name = "肉視頻"
        lang = "en"
        baseUrl = "https://rou.video/home"
    }

    source {
        name = "肉視頻"
        lang = "vi"
        baseUrl = "https://rou.video/home"
    }
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:i18n"))
    implementation(project(":lib:hls-proxy"))
}
