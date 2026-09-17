package dev.structural.intellij

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralCompilerPluginJarTest {
    private val serviceFile = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
    private val temp: Path = Files.createTempDirectory("structural-jar-test")

    private fun jar(vararg entries: Pair<String, String>): Path {
        val jar = Files.createTempFile(temp, "plugin", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `recognizes a jar registering the structural plugin`() {
        assertTrue(StructuralCompilerPluginJar.isStructuralCompilerPlugin(jar(serviceFile to "com.other.Registrar\n${StructuralCompilerPluginJar.REGISTRAR}\n")))
    }

    @Test
    fun `ignores other compiler plugins`() {
        assertFalse(StructuralCompilerPluginJar.isStructuralCompilerPlugin(jar(serviceFile to "org.jetbrains.kotlin.compose.Registrar")))
        assertFalse(StructuralCompilerPluginJar.isStructuralCompilerPlugin(jar("other.txt" to "x")))
    }

    @Test
    fun `recognizes a classes directory`() {
        val dir = temp.resolve("classes")
        dir.resolve(serviceFile).parent.createDirectories()
        dir.resolve(serviceFile).writeText(StructuralCompilerPluginJar.REGISTRAR)
        assertTrue(StructuralCompilerPluginJar.isStructuralCompilerPlugin(dir))
    }

    @Test
    fun `a jar that isn't built yet is recognized by name`() {
        assertTrue(StructuralCompilerPluginJar.isStructuralCompilerPlugin(temp.resolve("libs/structural-compiler-plugin.jar")))
        assertTrue(StructuralCompilerPluginJar.isStructuralCompilerPlugin(temp.resolve("structural-compiler-plugin-0.1.0.jar")))
    }

    @Test
    fun `missing or broken files are not the plugin`() {
        assertFalse(StructuralCompilerPluginJar.isStructuralCompilerPlugin(temp.resolve("missing.jar")))
        val broken = temp.resolve("broken.jar").also { it.writeText("not a zip") }
        assertFalse(StructuralCompilerPluginJar.isStructuralCompilerPlugin(broken))
    }
}
