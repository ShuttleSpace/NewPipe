package org.schabi.newpipe.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

/**
 * Downloads the sing-box libbox AAR from a GitHub release when it is missing.
 *
 * Declared as a standalone task class (instead of an inline `doLast` lambda in the
 * app's `build.gradle.kts`) so the action does not capture the build script object,
 * which keeps the task configuration-cache friendly.
 */
abstract class DownloadLibboxTask : DefaultTask() {
    @get:Input
    abstract val url: org.gradle.api.provider.Property<String>

    @get:OutputFile
    abstract val target: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun run() {
        val out = target.get().asFile
        if (out.exists()) {
            logger.lifecycle("libbox.aar already present at ${out.absolutePath}, skipping download")
            return
        }
        out.parentFile.mkdirs()
        logger.lifecycle("Downloading libbox.aar from ${url.get()}")
        URI(url.get()).toURL().openStream().use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
