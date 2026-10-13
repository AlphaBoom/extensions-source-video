package io.github.keiyoushi.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the extension's source list into the APK assets, so repository publication can read
 * it from the signed APK without loading extension classes.
 */
@CacheableTask
abstract class GenerateSourceInfoTask : DefaultTask() {
    @get:Input
    abstract val content: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDirectory.get().asFile.resolve(SOURCE_INFO_ASSET)
        out.parentFile.mkdirs()
        out.writeText(content.get())
    }

    companion object {
        const val SOURCE_INFO_ASSET = "extension-sources.json"
    }
}
