package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Class hierarchies: members inherited from superclasses count, subclasses of a matching class implement the interface
 * through it, and classes that declare the interface themselves are left alone, including the normal error for a missing
 * `override`.
 */
class InheritanceTest {
    @Test
    fun `subclasses and inherited properties`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                open class Frame(val width: Int, val height: Int)
                class PictureFrame : Frame(10, 20)
                open class Base(val width: Int)
                class Tall(val height: Int) : Base(1)
                class Window : Frame(3, 4), Sized
                fun run() = listOf(size(PictureFrame()), size(Tall(7)), size(Window()))
                """,
            ),
        )
        assertEquals(listOf(200, 7, 12), compiled.run())
    }

    @Test
    fun `functions inherited from a superclass`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                open class Base { fun area(): Int = 1; fun describe(): String = "base" }
                class Unit1(val name: String) : Base() { fun scaled(factor: Int): Int = factor }
                fun run() = measure(Unit1("u"))
                """,
            ),
        )
        assertEquals("u:1/2/base", compiled.run())
    }

    @Test
    fun `missing override on a declared supertype is still a normal error`() {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\nclass Explicit(val width: Int, val height: Int) : Sized"))
        assertFalse(compiled.succeeded)
        assertTrue(compiled.errors.any { "override" in it }, compiled.messages)
    }
}
