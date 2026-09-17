package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enum classes match like other classes, including through their built-in `name`. The compiler handles supertypes of
 * enum classes differently from other classes, so the plugin adds the interface to them directly as well
 * (see StructuralSupertypeGenerator).
 */
class EnumClassesTest {
    @Test
    fun `enum classes`() {
        val compiled = compile(
            SIZED,
            kotlin(
                "Main.kt",
                """
                package test
                enum class Paper(val width: Int, val height: Int) { A4(210, 297), A5(148, 210) }
                fun run() = Paper.entries.map { size(it) }
                """,
            ),
        )
        assertEquals(listOf(210 * 297, 148 * 210), compiled.run())
    }

    @Test
    fun `enum classes with functions`() {
        val compiled = compile(
            SHAPE,
            kotlin(
                "Main.kt",
                """
                package test
                enum class Tile(val name2: String, val side: Int) {
                    SMALL("s", 1), LARGE("l", 3);
                    fun area(): Int = side * side
                    fun scaled(factor: Int): Int = area() * factor
                    fun describe(): String = "tile"
                }
                fun run() = Tile.entries.joinToString { measure(it) }
                """,
            ),
        )
        assertEquals("SMALL:1/2/tile, LARGE:9/18/tile", compiled.run())
    }

    @Test
    fun `the interface is added once`() {
        val compiled = compile(SIZED, kotlin("Main.kt", "package test\nenum class Paper(val width: Int, val height: Int) { A4(210, 297) }"))
        assertTrue(compiled.succeeded, compiled.messages)
        assertEquals(listOf("test.Sized"), compiled.loadClass("test.Paper").interfaces.map { it.name })
    }
}
