package dev.structural.intellij

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Recognizes the structural compiler plugin among the compiler plugins configured in a project's build. */
object StructuralCompilerPluginJar {
    const val REGISTRAR = "dev.structural.compiler.StructuralPluginRegistrar"
    private const val SERVICE_FILE = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
    private const val JAR_NAME_PREFIX = "structural-compiler-plugin"

    /**
     * [path] is a jar or a classes directory, as passed to the Kotlin compiler with `-Xplugin`. A jar that doesn't exist yet
     * (the project hasn't been built since the Gradle import) is recognized by its file name.
     */
    fun isStructuralCompilerPlugin(path: Path): Boolean {
        val services = when {
            Files.isDirectory(path) -> path.resolve(SERVICE_FILE).takeIf { Files.isRegularFile(it) }?.let(Files::readString)
            Files.isRegularFile(path) -> readJarEntry(path, SERVICE_FILE)
            else -> return path.fileName?.toString()?.let { it.startsWith(JAR_NAME_PREFIX) && it.endsWith(".jar") } == true
        }
        return services != null && services.lineSequence().any { it.trim() == REGISTRAR }
    }

    private fun readJarEntry(jar: Path, name: String): String? = try {
        ZipFile(jar.toFile()).use { zip ->
            zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
        }
    } catch (_: java.io.IOException) {
        null
    }
}
