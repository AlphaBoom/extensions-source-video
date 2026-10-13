import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Xfani"
    versionCode = 10
    contentWarning = ContentWarning.SAFE

    source {
        name = "稀饭动漫"
        lang = "zh"
        baseUrl = "https://anime.xifanacg.com"
    }
}
