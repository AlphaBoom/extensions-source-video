plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("video-format")
}

tasks.named<Delete>("clean") {
    delete(layout.buildDirectory)
    dependsOn(gradle.includedBuild("build-logic").task(":clean"))
}
