plugins {
    id("video-extension")
}

videoExtension {
    name = "Anime1.me"
    sourceClass = ".Anime1"
    versionCode = 5
    source {
        name = "Anime1.me"
        lang = "zh-hant"
        baseUrl = "https://anime1.me"
    }
}

dependencies {
    implementation(project(":lib:bangumi-scraper"))
    //noinspection UseTomlInstead
    implementation("com.github.houbb:opencc4j:1.8.1")
}
