import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Jable"
    versionCode = 3
    contentWarning = ContentWarning.NSFW

    source {
        lang = "zh"
        baseUrl = "https://jable.tv"
    }

    source {
        lang = "en"
        baseUrl = "https://jable.tv"
    }

    source {
        lang = "ja"
        baseUrl = "https://jable.tv"
    }
}
