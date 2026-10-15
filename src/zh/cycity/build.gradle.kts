import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Cycity"
    versionCode = 4
    contentWarning = ContentWarning.SAFE

    source {
        name = "次元城动漫"
        lang = "zh"
        baseUrl = "https://www.cycani.org"
    }
}
