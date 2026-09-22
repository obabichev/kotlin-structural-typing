package com.obabichev.structural.compiler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What crosses a module boundary. A module compiled with the plugin publishes its @Structural interfaces in
 * `META-INF/structural/interfaces.txt`, so classes in a module that depends on it can implement them by shape. The
 * other direction stays impossible: a class already compiled can't gain a supertype. See docs/roadmap.md.
 */
class OtherModulesTest {
    /** Compiles [sources] and returns the directory holding the class files. */
    private fun library(vararg sources: com.tschuchort.compiletesting.SourceFile, withPlugin: Boolean = true): File {
        val compiled = compile(*sources, withPlugin = withPlugin)
        assertTrue(compiled.succeeded, compiled.messages)
        return File(compiled.loadClass("test.Marker").protectionDomain.codeSource.location.toURI())
    }

    private val marker = kotlin("Marker.kt", "package test\nclass Marker")

    @Test
    fun `a module publishes the interfaces it declares`() {
        val classes = library(SIZED, marker)
        val index = File(classes, StructuralIndexFile.PATH)
        assertTrue(index.isFile, "expected an index at ${index.path}")
        assertEquals("test/Sized\n", index.readText())
    }

    @Test
    fun `no index is written by a module that declares none`() {
        val classes = library(marker)
        assertFalse(File(classes, StructuralIndexFile.PATH).exists())
    }

    @Test
    fun `a class implements an interface published by another module`() {
        val classes = library(SIZED, marker)
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package app
                import test.Sized
                import test.size
                class Rectangular(val width: Int, val height: Int)
                fun run(): Any = listOf(size(Rectangular(2, 3)), Rectangular(2, 3) is Sized)
                """,
            ),
            classpath = listOf(classes),
        )
        assertEquals(listOf(6, true), compiled.run("app.MainKt"))
    }

    @Test
    fun `an interface from a module compiled without the plugin is not found`() {
        val classes = library(SIZED, marker, withPlugin = false)
        val compiled = compile(
            kotlin("Main.kt", "package app\nclass Rectangular(val width: Int, val height: Int)\nfun run() = test.size(Rectangular(2, 2))"),
            classpath = listOf(classes),
        )
        assertFalse(compiled.succeeded, "without an index there is nothing to discover")
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `the build can name the interfaces instead of the plugin finding them`() {
        val classes = library(SIZED, marker)
        assertTrue(File(classes, StructuralIndexFile.PATH).delete(), "nothing left to discover on the classpath")

        val compiled = compile(
            kotlin("Main.kt", "package app\nclass Rectangular(val width: Int, val height: Int)\nfun run() = test.size(Rectangular(2, 3))"),
            classpath = listOf(classes),
            interfaces = listOf("test/Sized"),
        )
        assertEquals(6, compiled.run("app.MainKt"), "IntelliJ gets the interfaces this way, not from the classpath")
    }

    @Test
    fun `a class from another module never gains the interface`() {
        val classes = library(kotlin("Model.kt", "package test.model\nclass Rectangular(val width: Int, val height: Int)"), marker)
        val compiled = compile(
            SIZED,
            kotlin("Main.kt", "package test\nimport test.model.Rectangular\nfun run() = size(Rectangular(2, 2))"),
            classpath = listOf(classes),
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `classes compiled with the plugin keep the interface when used from another module`() {
        val libraryCompilation = compile(SIZED, marker, kotlin("Model.kt", "package test\nclass Rectangular(val width: Int, val height: Int)"))
        assertTrue(libraryCompilation.succeeded, libraryCompilation.messages)
        val classes = File(libraryCompilation.loadClass("test.Marker").protectionDomain.codeSource.location.toURI())

        val compiled = compile(
            kotlin("Main.kt", "package test.app\nimport test.Rectangular\nimport test.size\nfun run() = size(Rectangular(2, 3))"),
            classpath = listOf(classes),
            withPlugin = false,
        )
        assertEquals(6, compiled.run("test.app.MainKt"), "the interface is in the class file, so no plugin is needed here")
    }
}
