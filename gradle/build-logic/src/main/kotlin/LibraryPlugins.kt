import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal fun Project.addCommonDependencies() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add("compileOnly", libs.findBundle("common").get())
}

internal fun Project.configureAndroidKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}

class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        configureAndroidKotlin()
        extensions.configure<LibraryExtension> {
            compileSdk = AndroidConfig.compileSdk
            buildToolsVersion = "37.0.0"
            defaultConfig.minSdk = AndroidConfig.minSdk
            namespace = "eu.kanade.tachiyomi.lib.${name.replace("-", "") }"
            compileOptions {
                sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
            }
        }
        addCommonDependencies()
    }
}

class MultisrcPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("lib-android")
        extensions.configure<LibraryExtension> {
            namespace = "eu.kanade.tachiyomi.multisrc.${project.name}"
            sourceSets.getByName("main").apply {
                manifest.srcFile("AndroidManifest.xml")
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
    }
}

class KotlinLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
            targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("compileOnly", libs.findLibrary("kotlin-stdlib").get())
    }
}
