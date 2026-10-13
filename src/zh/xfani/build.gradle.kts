import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Xfani"
    versionCode = 11
    contentWarning = ContentWarning.SAFE

    source {
        name = "稀饭动漫"
        lang = "zh"
        baseUrl = "https://next.xifanacg.com"
    }
}
