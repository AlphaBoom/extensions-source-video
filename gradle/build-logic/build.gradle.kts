plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    `java-gradle-plugin`
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

dependencies {
    compileOnly(gradleKotlinDsl())
    implementation(localGroovy())
    implementation(libs.gradle.agp)
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.spotless)
}

gradlePlugin {
    plugins {
        register("video-extension") {
            id = "video-extension"
            implementationClass = "VideoExtensionPlugin"
        }
        register("lib-android") {
            id = "lib-android"
            implementationClass = "AndroidLibraryPlugin"
        }
        register("lib-multisrc") {
            id = "lib-multisrc"
            implementationClass = "MultisrcPlugin"
        }
        register("lib-kotlin") {
            id = "lib-kotlin"
            implementationClass = "KotlinLibraryPlugin"
        }
        register("video-format") {
            id = "video-format"
            implementationClass = "VideoFormatPlugin"
        }
    }
}
