package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralTypingTest {
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
    fun `val accepts a subtype and var requires the exact type`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Measurable { val length: Number }
                @Structural interface Counter { var count: Int }
                class Rope(val length: Int)
                class Clicks(var count: Int)
                fun describe(target: Measurable) = target.length.toString()
                fun increment(target: Counter) { target.count++ }
                fun run(): String {
                    val clicks = Clicks(1)
                    increment(clicks)
                    return describe(Rope(5)) + clicks.count
                }
                """,
            ),
        )
        assertEquals("52", compiled.run())
    }

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

    @Test
    fun `interface properties with a default getter are not required`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Shape {
                    val width: Int
                    val label: String get() = "shape"
                }
                class Line(val width: Int)
                fun describe(shape: Shape) = shape.label + shape.width
                fun run() = describe(Line(3))
                """,
            ),
        )
        assertEquals("shape3", compiled.run())
    }
}
