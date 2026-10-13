plugins {
    id("lib-multisrc")
}

extra["baseVersionCode"] = 7

dependencies {
    api(project(":lib:megacloud-extractor"))
    api(project(":lib:streamtape-extractor"))
}
