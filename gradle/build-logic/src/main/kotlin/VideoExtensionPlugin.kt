import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.security.MessageDigest

class VideoExtensionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        configureAndroidKotlin()
        val video = extensions.create<VideoExtension>("videoExtension")
        val packageSuffix = "${parent!!.name}.$name"
        val sourceInfo =
            tasks.register<GenerateSourceInfoTask>("generateSourceInfo") {
                outputDirectory.set(layout.buildDirectory.dir("generated/source-info/assets"))
            }

        extensions.configure<ApplicationExtension> {
            compileSdk = AndroidConfig.compileSdk
            buildToolsVersion = "37.0.0"
            namespace = AndroidConfig.namespace
            defaultConfig {
                minSdk = AndroidConfig.minSdk
                targetSdk = AndroidConfig.targetSdk
                applicationId = "${AndroidConfig.namespace}.$packageSuffix"
            }
            sourceSets.getByName("main").apply {
                // The core manifest is merged from :core. A common empty manifest
                // replaces the old task that rewrote sourceSets during execution.
                manifest.srcFile(if (file("AndroidManifest.xml").exists()) "AndroidManifest.xml" else rootProject.file("common/AndroidManifest.xml"))
                java.directories.clear()
                java.directories.add("src")
                kotlin.directories.clear()
                kotlin.directories.add("src")
                res.directories.clear()
                res.directories.add("res")
                assets.directories.clear()
                assets.directories.add("assets")
            }
            signingConfigs.create("release") {
                storeFile = rootProject.file("signingkey.jks")
                storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
            buildTypes.getByName("release").apply {
                // Publication still requires the real signing key. Local release
                // builds without that key remain unsigned.
                if (rootProject.file("signingkey.jks").exists()) signingConfig = signingConfigs.getByName("release")
                isMinifyEnabled = false
                vcsInfo.include = false
            }
            dependenciesInfo.includeInApk = false
            buildFeatures.buildConfig = true
            compileOptions {
                sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
            }
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            finalizeDsl { android ->
                check(video.name.get().all { it.code < 0x180 }) { "Extension name should be romanized" }
                check(video.sourceClass.get().startsWith(".")) { "sourceClass must be relative to the extension package" }
                check(video.contentWarning.get() in 0..2) { "contentWarning must be 0, 1 or 2" }
                val theme = video.theme.orNull?.let { evaluationDependsOn(":lib-multisrc:$it") }
                theme?.let { dependencies.add("implementation", it) }
                val revision = video.versionCode.get() + (theme?.extensions?.extraProperties?.get("baseVersionCode") as? Int ?: 0)
                check(revision in 1..999) { "Extension revision must be between 1 and 999" }
                check(video.sources.isNotEmpty()) { "Declare every source in videoExtension.source" }
                val sources =
                    video.sources.map { source ->
                        val key = "${source.name.get().lowercase(java.util.Locale.ROOT)}/${source.lang.get()}/${source.versionId.get()}"
                        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
                        val generatedId =
                            java.nio.ByteBuffer
                                .wrap(digest)
                                .long and Long.MAX_VALUE
                        mapOf(
                            "name" to source.name.get(),
                            "lang" to source.lang.get(),
                            "id" to (source.id.orNull ?: generatedId).toString(),
                            "baseUrl" to source.baseUrl.get(),
                            "versionId" to source.versionId.get(),
                        )
                    }
                check(sources.map { it["id"] }.distinct().size == sources.size) { "Duplicate source IDs" }
                val sourceJson = JsonOutput.toJson(sources)
                sourceInfo.configure { content.set(sourceJson) }
                // Include the API level in Android's monotonically increasing code.
                android.defaultConfig.versionCode = AndroidConfig.libVersion * 1000 + revision
                android.defaultConfig.versionName = "${AndroidConfig.libVersion}.$revision"
                android.defaultConfig.manifestPlaceholders.putAll(
                    mapOf(
                        "appName" to "Aniyomi: ${video.name.get()}",
                        "extensionName" to video.name.get(),
                        "extClass" to video.sourceClass.get(),
                        "nsfw" to if (video.contentWarning.get() > 0) 1 else 0,
                        "contentWarning" to video.contentWarning.get(),
                        "torrent" to video.torrent.get(),
                        "extensionLib" to AndroidConfig.libVersion,
                    ),
                )
                video.baseUrl.orNull?.takeIf { it.isNotBlank() }?.let {
                    val uri = java.net.URI(it)
                    require(uri.scheme != null && uri.host != null) { "Invalid baseUrl: $it" }
                    android.defaultConfig.manifestPlaceholders.putAll(mapOf("SOURCEHOST" to uri.host, "SOURCESCHEME" to uri.scheme))
                }
            }
            onVariants { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(sourceInfo) { it.outputDirectory }
                variant.outputs.forEach { output ->
                    output.outputFileName.set(output.versionName.map { "aniyomi-$packageSuffix-v$it.apk" })
                }
            }
        }

        dependencies.add("implementation", project(":core"))
        addCommonDependencies()
    }
}
