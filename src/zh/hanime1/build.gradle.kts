import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hanime1"
    versionCode = 16
    contentWarning = ContentWarning.NSFW

    source {
        name = "Hanime1.me"
        lang = "zh"
        baseUrl = "https://hanime1.me"
    }
}
