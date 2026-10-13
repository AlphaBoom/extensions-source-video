import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Anime1.me"
    versionCode = 5
    contentWarning = ContentWarning.SAFE

    source {
        lang = "zh-hant"
        baseUrl = "https://anime1.me"
    }
}

dependencies {
    implementation(project(":lib:bangumi-scraper"))
    //noinspection UseTomlInstead
    implementation("com.github.houbb:opencc4j:1.8.1")
}
