package com.obabichev.structural.gradle

import java.io.File
import java.util.jar.JarFile

/**
 * Reads the `@Structural` interfaces published by dependencies, which the compiler plugin writes into each module's
 * output (`StructuralIndexFile`). The compiler plugin can read these itself when it compiles, but IntelliJ gives a
 * plugin no classpath to read, so the build passes what it finds here as compiler plugin options instead.
 *
 * Kept separate from the compiler plugin's copy on purpose: this module must not depend on the Kotlin compiler.
 */
internal object StructuralIndexReader {
    private const val PATH = "META-INF/structural/interfaces.txt"

    fun read(classpath: Iterable<File>): List<String> = classpath
        .flatMap { entry -> runCatching { contents(entry) }.getOrNull().orEmpty() }
        .distinct()
        .sorted()

    private fun contents(entry: File): List<String> {
        val text = when {
            entry.isDirectory -> File(entry, PATH).takeIf { it.isFile }?.readText()
            entry.isFile && entry.extension == "jar" -> JarFile(entry).use { jar ->
                jar.getJarEntry(PATH)?.let { jar.getInputStream(it).bufferedReader().readText() }
            }
            else -> null
        }
        return text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
