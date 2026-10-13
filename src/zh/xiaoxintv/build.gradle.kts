import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Xiaoxintv"
    versionCode = 3
    contentWarning = ContentWarning.SAFE

    source {
        name = "小宝影院"
        lang = "zh"
        baseUrl = "https://xiaoheimi.cc"
    }
}
