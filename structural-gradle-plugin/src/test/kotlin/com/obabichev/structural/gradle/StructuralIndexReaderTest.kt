package com.obabichev.structural.gradle

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reading the index a module publishes is how a build learns which @Structural interfaces its dependencies offer. It is
 * the only route that reaches IntelliJ, which hands compiler plugins no classpath to search themselves.
 */
class StructuralIndexReaderTest {
    private val temp: File = createTempDirectory("structural-index-test").toFile()
    private val path = "META-INF/structural/interfaces.txt"

    private fun directory(contents: String?): File {
        val dir = File(temp, "classes-${contents.hashCode()}").apply { mkdirs() }
        if (contents != null) File(dir, path).apply { parentFile.mkdirs() }.writeText(contents)
        return dir
    }

    private fun jar(contents: String?): File {
        val jar = File(temp, "library-${contents.hashCode()}.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("com/example/Other.class"))
            zip.closeEntry()
            if (contents != null) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `reads the interfaces a jar publishes`() {
        assertEquals(listOf("com/example/Sized"), StructuralIndexReader.read(listOf(jar("com/example/Sized\n"))))
    }

    @Test
    fun `reads the interfaces a classes directory publishes`() {
        assertEquals(listOf("com/example/Sized"), StructuralIndexReader.read(listOf(directory("com/example/Sized\n"))))
    }

    @Test
    fun `ignores dependencies that publish nothing`() {
        assertEquals(emptyList(), StructuralIndexReader.read(listOf(jar(null), directory(null))))
    }

    @Test
    fun `ignores a missing entry and a file that is not a jar`() {
        val notAJar = File(temp, "notes.txt").apply { writeText("whatever") }
        assertEquals(emptyList(), StructuralIndexReader.read(listOf(notAJar, File(temp, "absent.jar"))))
    }

    @Test
    fun `collects every dependency, without duplicates, in a stable order`() {
        val entries = listOf(
            jar("com/example/Sized\ncom/example/Named\n"),
            directory("com/example/Sized\n\n# a comment\ncom/example/Counted\n"),
        )
        assertEquals(
            listOf("com/example/Counted", "com/example/Named", "com/example/Sized"),
            StructuralIndexReader.read(entries),
        )
    }
}
