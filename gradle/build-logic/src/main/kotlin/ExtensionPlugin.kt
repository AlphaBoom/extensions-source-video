import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import com.google.devtools.ksp.gradle.KspExtension
import io.github.keiyoushi.gradle.api.DeeplinkFilter
import io.github.keiyoushi.gradle.api.dsl.ExtensionDeeplink
import io.github.keiyoushi.gradle.api.dsl.KeiyoushiExtension
import io.github.keiyoushi.gradle.api.dsl.KeiyoushiThemeExtension
import io.github.keiyoushi.gradle.internal.ResolvedSource
import io.github.keiyoushi.gradle.internal.SourceMetadata
import io.github.keiyoushi.gradle.internal.VALID_LIB_VERSIONS
import io.github.keiyoushi.gradle.internal.extensions.alias
import io.github.keiyoushi.gradle.internal.extensions.compileOnly
import io.github.keiyoushi.gradle.internal.extensions.implementation
import io.github.keiyoushi.gradle.internal.extensions.kei
import io.github.keiyoushi.gradle.internal.extensions.ksp
import io.github.keiyoushi.gradle.internal.extensions.libs
import io.github.keiyoushi.gradle.internal.extensions.plugins
import io.github.keiyoushi.gradle.internal.toMetadata
import io.github.keiyoushi.gradle.tasks.GenerateManifestTask
import io.github.keiyoushi.gradle.tasks.GenerateSourceInfoTask
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class ExtensionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)
            alias(libs.plugins.ksp)

            alias(kei.plugins.android.base)
            alias(kei.plugins.spotless)
        }

        val keiyoushi = extensions.create<KeiyoushiExtension>("keiyoushi").apply {
            // Aniyomi only loads extensions-lib 17 APKs from this repository.
            libVersion.convention(VALID_LIB_VERSIONS.last())
            torrent.convention(false)
        }
        val dirSuffix = "${project.parent?.name}.${project.name}"
        val pkgName = keiyoushi.pkgName.orElse(dirSuffix)

        android {
            namespace = "eu.kanade.tachiyomi.animeextension"

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        val themeExtension = keiyoushi.theme.map { themeName ->
            evaluationDependsOn(":lib-multisrc:$themeName").extensions.findByType<KeiyoushiThemeExtension>()
                ?: throw AssertionError("Theme project :lib-multisrc:$themeName must apply kei.plugins.multisrc")
        }

        val versionCodeProvider = themeExtension.flatMap { themeKeiyoushi ->
            val themeLib = themeKeiyoushi.libVersion.get()
            val extLib = keiyoushi.libVersion.get()
            check(themeLib == extLib) {
                "Multisrc libVersion ($themeLib) and extension libVersion ($extLib) must match."
            }
            themeKeiyoushi.baseVersionCode.zip(keiyoushi.versionCode) { base, ext -> base + ext }
        }.orElse(keiyoushi.versionCode)

        val versionNameProvider = keiyoushi.libVersion.flatMap { libVersion ->
            check(libVersion in VALID_LIB_VERSIONS) {
                "libVersion $libVersion is not supported. Supported versions: $VALID_LIB_VERSIONS"
            }
            versionCodeProvider.map { "$libVersion.$it" }
        }

        val androidVersionCodeProvider = keiyoushi.libVersion.flatMap { libVersion ->
            versionCodeProvider.map { versionCode ->
                check(versionCode in 1..999) { "versionCode must be between 1 and 999" }
                libVersion.split(".").joinToString("") { it.padStart(2, '0') }.toInt().times(1000) + versionCode
            }
        }

        val themeDeeplinks = themeExtension
            .flatMap { it.deeplinks }
            .orElse(emptyList())

        val defaultHostsProvider = keiyoushi.sources.map { sources ->
            sources.flatMap { it.resolvedBaseUrl.get().allUrls() }.mapNotNull(::extractHost).distinct()
        }

        val deeplinksProvider = keiyoushi.deeplinks
            .zip(themeDeeplinks) { local, theme -> local + theme }
            .zip(defaultHostsProvider) { specs, defaultHosts ->
                specsToFilters(specs, defaultHosts)
            }

        val manifestTask = tasks.register<GenerateManifestTask>("generateExtensionManifest") {
            this.filters.set(deeplinksProvider)
            this.extensionName.set(keiyoushi.name)
            this.contentWarning.set(keiyoushi.contentWarning)
            this.extensionLib.set(keiyoushi.libVersion)
            this.torrent.set(keiyoushi.torrent)
        }

        // Output locations of both tasks are wired by AGP below.
        val sourceInfoTask = tasks.register<GenerateSourceInfoTask>("generateSourceInfo")

        androidComponents {
            finalizeDsl {
                val suffix = pkgName.get()
                check(APPLICATION_ID_SUFFIX_REGEX.matches(suffix)) {
                    "pkgName '$suffix' is invalid. Expected dot-separated alphanumeric segments (e.g. '$dirSuffix')."
                }
                check(keiyoushi.name.get().all { it.code < 0x180 }) { "Extension name should be romanized" }
                it.defaultConfig.applicationIdSuffix = suffix
            }

            onVariants { variant ->
                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
                variant.sources.manifests.addGeneratedManifestFile(manifestTask) { it.outputFile }
                variant.sources.assets?.addGeneratedSourceDirectory(sourceInfoTask) { it.outputDirectory }

                val filenameProvider = versionNameProvider.map { "aniyomi-${pkgName.get()}-v$it" }

                variant.outputs.forEach { output ->
                    output.versionCode.set(androidVersionCodeProvider)
                    output.versionName.set(versionNameProvider)
                    @Suppress("UnstableApiUsage")
                    output.outputFileName.set(filenameProvider.map { "$it.apk" })
                }
            }
        }

        dependencies {
            addProvider("implementation", keiyoushi.theme.map { project(":lib-multisrc:$it") })
            implementation(project(":core"))
            compileOnly(libs.bundles.common)
            ksp(project(":compiler"))
        }

        afterEvaluate {
            val specs = keiyoushi.sources.get()
            check(specs.isNotEmpty()) { "At least one source { } block is required" }

            val extName = keiyoushi.name.get()
            val resolvedSources = specs.map { spec ->
                val name = spec.name.orElse(extName).get()
                val lang = spec.lang.get()
                val baseUrl = spec.resolvedBaseUrl.get().toMetadata()

                val id = spec.id.orElse(
                    providers.provider {
                        computeSourceId(name, lang, spec.versionId.orElse(1).get())
                    },
                ).get()
                ResolvedSource(name, lang, id, baseUrl)
            }
            check(resolvedSources.map { it.id }.distinct().size == resolvedSources.size) { "Duplicate source IDs" }

            val translationsFile = project(":core").projectDir.resolve("translations/strings.json")
            extensions.configure<KspExtension> {
                arg("kei_sources", Json.encodeToString<List<ResolvedSource>>(resolvedSources))
                arg("kei_translations", translationsFile.absolutePath)
            }
            tasks.matching { it.name.startsWith("ksp") }.configureEach {
                inputs.file(translationsFile)
            }

            val sourceInfos = resolvedSources.map { source ->
                SourceMetadata(
                    id = source.id.toString(),
                    name = source.name,
                    lang = source.lang,
                    baseUrl = source.baseUrl.defaultUrl,
                    mirrorUrls = source.baseUrl.mirrors.map { it.url },
                )
            }
            sourceInfoTask.configure { content.set(Json.encodeToString<List<SourceMetadata>>(sourceInfos)) }

            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private val APPLICATION_ID_SUFFIX_REGEX = Regex("""^\w+(\.\w+)+$""")

private fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }
        .reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}

private fun extractHost(url: String): String? = url.split("://").getOrNull(1)?.split("/")?.first()?.takeIf { it.isNotEmpty() }

private fun specsToFilters(specs: List<ExtensionDeeplink>, defaultHosts: List<String>): List<DeeplinkFilter> = specs.mapNotNull { spec ->
    val hosts = spec.hosts.getOrElse(emptyList()).ifEmpty { defaultHosts }
    val paths = spec.pathPatterns.getOrElse(emptyList())
    if (paths.isNotEmpty()) {
        check(hosts.isNotEmpty()) {
            "deeplink has path patterns but no host could be resolved — set a source baseUrl or specify host() explicitly"
        }
        DeeplinkFilter(hosts, paths)
    } else {
        null
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}
