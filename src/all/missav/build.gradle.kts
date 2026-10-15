import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MissAV"
    versionCode = 33
    contentWarning = ContentWarning.NSFW

    source {
        lang = "all"
        baseUrl {
            mirrors("https://missav.live", "https://missav.ai", "https://missav.ws")
        }
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlist-utils"))
    implementation(project(":lib:javcoverfetcher"))
    implementation(project(":lib:hls-proxy"))
}
