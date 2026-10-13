import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Source information travels inside the signed APK, without loading host stubs. */
@CacheableTask
abstract class GenerateSourceInfoTask : DefaultTask() {
    @get:Input
    abstract val content: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("extension-sources.json").writeText(content.get())
    }
}
