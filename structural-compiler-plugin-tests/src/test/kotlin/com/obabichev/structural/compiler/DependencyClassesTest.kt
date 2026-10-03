package com.obabichev.structural.compiler

import com.obabichev.structural.runtime.StructuralClassLoader
import com.obabichev.structural.runtime.StructuralIndex
import com.tschuchort.compiletesting.SourceFile
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classes from dependencies gain a `@Structural` interface as the compiler deserializes them, so code in this module
 * can pass one where the interface is expected. The interface has to come from a dependency too: a class file is read
 * long before a source interface has resolved members. See StructuralDeserialization.
 */
class DependencyClassesTest {
    private fun library(vararg sources: SourceFile): File {
        val compiled = compile(*sources, withPlugin = false)
        assertTrue(compiled.succeeded, compiled.messages)
        return File(compiled.loadClass("test.Marker").protectionDomain.codeSource.location.toURI())
    }

    private val marker = kotlin("Marker.kt", "package test\nclass Marker")

    /** `Sized` and `size()` as a dependency, compiled by an ordinary build with no plugin. */
    private fun sizedLibrary(): File = library(SIZED, marker)

    @Test
    fun `a class from a dependency can be passed as the interface`() {
        val classes = sizedLibrary()
        val model = library(kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)"), marker)

        val compiled = compile(
            kotlin("Main.kt", "package app\nimport dep.Rectangular\nimport test.size\nfun run() = size(Rectangular(2, 3))"),
            classpath = listOf(classes, model),
        )

        assertTrue(compiled.succeeded, compiled.messages)
    }

    @Test
    fun `a class from a dependency that does not match is still rejected`() {
        val classes = sizedLibrary()
        val model = library(kotlin("Model.kt", "package dep\nclass Unrelated(val depth: Int)"), marker)

        val compiled = compile(
            kotlin("Main.kt", "package app\nimport dep.Unrelated\nimport test.size\nfun run() = size(Unrelated(2))"),
            classpath = listOf(classes, model),
        )

        assertFalse(compiled.succeeded)
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `the interface is usable as a type, not only as an argument`() {
        val classes = sizedLibrary()
        val model = library(kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)"), marker)

        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package app
                import dep.Rectangular
                import test.Sized
                fun run(): List<Sized> = listOf(Rectangular(2, 3))
                """,
            ),
            classpath = listOf(classes, model),
        )

        assertTrue(compiled.succeeded, compiled.messages)
    }

    /**
     * The two halves together. The compiler believes the dependency class implements the interface and emits a
     * `CHECKCAST` at the call site; the classloader makes that true by adding the interface to the class as it reads
     * it. Neither half is any use on its own, which the test after this one shows.
     */
    @Test
    fun `the compiled call works when the class is loaded by the structural classloader`() {
        val classes = sizedLibrary()
        val model = library(kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)"), marker)
        val compiled = compile(
            kotlin("Main.kt", "package app\nimport dep.Rectangular\nimport test.size\nfun run() = size(Rectangular(2, 3))"),
            classpath = listOf(classes, model),
        )
        assertTrue(compiled.succeeded, compiled.messages)
        val app = File(compiled.loadClass("app.MainKt").protectionDomain.codeSource.location.toURI())

        val loader = StructuralClassLoader(
            urls = arrayOf(app, classes, model, stdlib()).map { it.toURI().toURL() }.toTypedArray(),
            parent = ClassLoader.getPlatformClassLoader(),
            index = StructuralIndex.of("test.Sized"),
        )

        assertEquals(6, loader.loadClass("app.MainKt").getMethod("run").invoke(null))
    }

    @Test
    fun `without the classloader the compiled call fails at the cast`() {
        val classes = sizedLibrary()
        val model = library(kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)"), marker)
        val compiled = compile(
            kotlin("Main.kt", "package app\nimport dep.Rectangular\nimport test.size\nfun run() = size(Rectangular(2, 3))"),
            classpath = listOf(classes, model),
        )
        assertTrue(compiled.succeeded, compiled.messages)

        val failure = try {
            compiled.loadClass("app.MainKt").getMethod("run").invoke(null)
            fail("the class file does not implement the interface, so the cast has to fail")
        } catch (e: InvocationTargetException) {
            e.targetException
        }
        assertTrue(failure is ClassCastException, "expected a failed cast but got ${'$'}failure")
    }

    /**
     * Which interfaces take part is the build's decision, not a property of the classpath: the plugin matches against
     * the ones the build names -- published by a module built with the plugin, and the only channel IntelliJ passes on
     * -- and scans class files only when nothing was named.
     */
    @Test
    fun `only the interfaces the build names are matched against`() {
        val classes = library(
            kotlin(
                "Shapes.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Sized { val width: Int; val height: Int }
                @Structural interface Named { val name: String }
                fun size(target: Sized) = target.width * target.height
                fun label(target: Named) = target.name
                """,
            ),
            marker,
        )
        val model = library(
            kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)\nclass Person(val name: String)"),
            marker,
        )

        val named = compile(
            kotlin("Main.kt", "package app\nimport dep.Rectangular\nimport test.size\nfun run() = size(Rectangular(2, 3))"),
            classpath = listOf(classes, model),
            interfaces = listOf("test/Sized"),
        )
        assertTrue(named.succeeded, named.messages)

        val notNamed = compile(
            kotlin("Other.kt", "package app\nimport dep.Person\nimport test.label\nfun run() = label(Person(\"ada\"))"),
            classpath = listOf(classes, model),
            interfaces = listOf("test/Sized"),
        )
        assertFalse(notNamed.succeeded, "Named was not named by the build, so nothing should match it")
        assertContains(notNamed.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    private fun stdlib(): File = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
}
