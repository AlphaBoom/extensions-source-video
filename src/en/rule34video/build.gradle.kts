import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Rule34Video"
    versionCode = 12
    contentWarning = ContentWarning.NSFW

    deeplink {
        host("rule34video.com")
        host("www.rule34video.com")
        path("/videos/..*")
        path("/video/..*")
    }

    source {
        lang = "en"
        baseUrl = "https://rule34video.com"
    }
}
