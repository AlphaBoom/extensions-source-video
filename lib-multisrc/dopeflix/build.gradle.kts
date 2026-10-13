plugins {
    id("lib-multisrc")
}

extra["baseVersionCode"] = 21

dependencies {
    api(project(":lib:dood-extractor"))
    api(project(":lib:cryptoaes"))
    api(project(":lib:playlist-utils"))
}
