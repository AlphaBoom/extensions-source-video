import com.android.build.api.dsl.LibraryExtension

plugins {
    id("lib-android")
}

extensions.configure<LibraryExtension> {
    namespace = "eu.kanade.tachiyomi.lib.core"
    sourceSets.getByName("main").apply {
        manifest.srcFile("AndroidManifest.xml")
        res.directories.clear()
        res.directories.add("res")
    }
}
