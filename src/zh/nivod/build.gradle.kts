import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Nivod"
    versionCode = 3
    contentWarning = ContentWarning.SAFE

    source {
        name = "泥视频"
        lang = "zh"
        baseUrl = "https://www.nivod.cc"
    }
}
