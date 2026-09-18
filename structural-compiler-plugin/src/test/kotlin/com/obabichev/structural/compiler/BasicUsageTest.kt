package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What implementing an interface by shape gives you: a matching class really implements the interface, so it can be
 * passed anywhere the interface is expected, is found by `is` checks, keeps its identity, and works in collections,
 * generics, varargs and nullable positions. Matching doesn't depend on where classes and interfaces are declared.
 */
class BasicUsageTest {
    @Test
    fun `matching class can be passed where the interface is expected`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Rectangular(val width: Int, val height: Int, val color: String)
                fun run() = size(Rectangular(1, 2, "red"))
                """,
            ),
        )
        assertEquals(2, compiled.run())
        assertEquals(emptyList(), compiled.warnings)
    }

    @Test
    fun `the class really implements the interface`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Rectangular(val width: Int, val height: Int)
                class Unused(val width: Int, val height: Int)
                fun run(): List<Any> {
                    val rect = Rectangular(3, 4)
                    val asAny: Any = rect
                    val sized: Sized = rect
                    return listOf(asAny is Sized, sized === rect)
                }
                """,
            ),
        )
        assertEquals(listOf(true, true), compiled.run())
        val sized = compiled.loadClass("test.Sized")
        assertTrue(sized.isAssignableFrom(compiled.loadClass("test.Unused")), "classes match even if never used")
    }

    @Test
    fun `functions of any shape accept matching classes`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                class Rectangular(val width: Int, val height: Int)
                class Square(val side: Int) {
                    val width: Int get() = side
                    val height: Int get() = side
                }
                fun total(items: List<Sized>, scale: Int = 1) = items.sumOf { size(it) } * scale
                fun count(vararg items: Sized) = items.size
                fun orZero(target: Sized?) = target?.let { size(it) } ?: 0
                fun <T : Sized> larger(a: T, b: T): T = if (size(a) >= size(b)) a else b
                fun Sized.area() = width * height
                fun run(): List<Any> {
                    val maybe: Rectangular? = Rectangular(2, 2)
                    val rectangles: List<Rectangular> = listOf(Rectangular(1, 2), Rectangular(3, 4))
                    return listOf(
                        total(rectangles),
                        count(Rectangular(1, 1), Square(2)),
                        orZero(maybe),
                        larger(Square(3), Square(2)).side,
                        Rectangular(2, 5).area(),
                    )
                }
                """,
            ),
        )
        assertEquals(listOf(14, 2, 4, 3, 10), compiled.run())
    }

    @Test
    fun `classes in other packages and declared before the interface`() {
        val compiled = compile(
            kotlin("A_Model.kt", "package test.model\nclass Box(val width: Int, val height: Int)"),
            SIZED,
            kotlin("Main.kt", "package test\nimport test.model.Box\nfun run() = size(Box(5, 6))"),
        )
        assertEquals(30, compiled.run())
    }

    @Test
    fun `objects`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                object Stamp { val width: Int = 2; val height: Int = 3 }
                fun run() = size(Stamp)
                """,
            ),
        )
        assertEquals(6, compiled.run())
    }
}
