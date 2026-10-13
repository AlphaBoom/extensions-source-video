import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/** Formatting is explicit: builds never rewrite source files. */
class VideoFormatPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.diffplug.spotless")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt", "lib/**/*.kt", "lib-multisrc/**/*.kt", "gradle/build-logic/src/**/*.kt")
                targetExclude("**/build/**")
                ktlint(libs.findVersion("ktlint").get().requiredVersion)
                    .setEditorConfigPath(rootProject.file(".editorconfig"))
                trimTrailingWhitespace()
                endWithNewline()
            }
            kotlinGradle {
                target("*.kts", "core/*.kts", "src/*/*/*.kts", "lib/*/*.kts", "lib-multisrc/*/*.kts", "gradle/build-logic/*.kts")
                ktlint(libs.findVersion("ktlint").get().requiredVersion)
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}
