package com.obabichev.structural.compiler

import org.jetbrains.kotlin.name.ClassId
import java.io.File
import java.util.jar.JarFile

/**
 * The index of `@Structural` interfaces a module publishes, so other modules can find them: the compiler can load an
 * interface from a dependency by [ClassId] and list the classifiers of a known package, but it can't enumerate the
 * packages on the classpath, so the names have to be written down at compile time.
 *
 * One line per interface, its class id in the compiler's form (`com/example/Sized`, `com/example/Outer.Nested`). The
 * file lands in the module's output directory, which Gradle packs into the jar.
 */
internal object StructuralIndexFile {
    const val PATH: String = "META-INF/structural/interfaces.txt"

    fun render(classIds: Collection<ClassId>): String =
        classIds.map { it.asString() }.distinct().sorted().joinToString("\n", postfix = "\n")

    fun parse(text: String): List<ClassId> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { runCatching { ClassId.fromString(it) }.getOrNull() }
        .toList()

    fun writeTo(outputDirectory: File, classIds: Collection<ClassId>) {
        val target = File(outputDirectory, PATH)
        if (classIds.isEmpty()) {
            target.delete() // A module that no longer declares any must not keep publishing an old list.
            return
        }
        target.parentFile.mkdirs()
        target.writeText(render(classIds))
    }

    /** The interfaces published by everything on [classpath]; entries that aren't readable are skipped. */
    fun readFrom(classpath: List<File>): List<ClassId> = classpath.flatMap { entry ->
        runCatching {
            when {
                entry.isDirectory -> File(entry, PATH).takeIf { it.isFile }?.readText()
                entry.isFile && entry.extension == "jar" -> JarFile(entry).use { jar ->
                    jar.getJarEntry(PATH)?.let { jar.getInputStream(it).bufferedReader().readText() }
                }
                else -> null
            }
        }.getOrNull()?.let(::parse).orEmpty()
    }
}
