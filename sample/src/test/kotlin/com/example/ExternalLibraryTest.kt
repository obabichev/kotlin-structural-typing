package com.example

import com.example.external.releaseIso
import com.obabichev.structural.runtime.StructuralClassLoader
import com.obabichev.structural.runtime.StructuralIndex
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A class from a third-party library used as a @Structural interface. `kotlinx.datetime.LocalDate` has `year`,
 * `monthNumber` and `dayOfMonth`; `com.example.shapes.Dated` requires exactly those, and the two know nothing about
 * each other.
 *
 * Both halves are needed. The compiler believes the match, so `Dates.kt` compiles and the call becomes a cast; the
 * classloader makes the cast true by adding the interface to the class as it reads it.
 */
class ExternalLibraryTest {
    private fun structuralLoader(): StructuralClassLoader = StructuralClassLoader(
        urls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it).toURI().toURL() }
            .toTypedArray(),
        parent = ClassLoader.getPlatformClassLoader(),
        index = StructuralIndex.of("com.example.shapes.Dated"),
    )

    @Test
    fun `a library class is the interface when loaded by the structural classloader`() {
        val loaded = structuralLoader().loadClass("com.example.external.DatesKt")
        assertEquals("2026-10-02", loaded.getMethod("releaseIso").invoke(null))
    }

    @Test
    fun `the same call fails on an ordinary classloader, where the interface was never added`() {
        val failure = try {
            releaseIso()
            fail("the library's class file does not implement the interface, so the cast has to fail")
        } catch (e: ClassCastException) {
            e
        }
        assertTrue(failure.message.orEmpty().contains("LocalDate"), "unexpected failure: ${failure.message}")
    }

    @Test
    fun `classes of the library and of this module mix in one collection`() {
        val loaded = structuralLoader().loadClass("com.example.external.DatesKt")
        assertEquals(2, (loaded.getMethod("dates").invoke(null) as List<*>).size)
    }
}
