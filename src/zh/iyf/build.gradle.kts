import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "iyf"
    versionCode = 2
    contentWarning = ContentWarning.SAFE

    source {
        name = "爱壹帆"
        lang = "zh"
        baseUrl = "https://www.iyf.tv"
    }
}
