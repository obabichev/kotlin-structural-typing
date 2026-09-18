package com.obabichev.structural.compiler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Everything has to be in the module being compiled: the plugin only finds @Structural interfaces declared there, and
 * only classes compiled there can gain a supertype. Neither direction works across modules yet, and both fail with a
 * plain type mismatch instead of breaking anything else. See docs/roadmap.md.
 */
class OtherModulesTest {
    /** Compiles [sources] without the plugin and returns the directory holding the class files. */
    private fun library(vararg sources: com.tschuchort.compiletesting.SourceFile): File {
        val compiled = compile(*sources, withPlugin = false)
        assertTrue(compiled.succeeded, compiled.messages)
        return File(compiled.loadClass("test.Marker").protectionDomain.codeSource.location.toURI())
    }

    private val marker = kotlin("Marker.kt", "package test\nclass Marker")

    @Test
    fun `an interface from another module is not found`() {
        val classes = library(SIZED, marker)
        val compiled = compile(
            kotlin("Main.kt", "package test\nclass Rectangular(val width: Int, val height: Int)\nfun run() = size(Rectangular(2, 2))"),
            classpath = listOf(classes),
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
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
