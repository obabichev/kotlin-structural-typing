package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdapterGenerationTest {
    private val sized = """
        package test
        import dev.structural.Structural
        import test.model.*
        @Structural interface Sized { val width: Int; val height: Int }
    """

    private fun compileWith(model: String, code: String): Compiled =
        compile(kotlin("Model.kt", "package test.model\n$model"), kotlin("Geometry.kt", sized + code))

    private fun Compiled.runMain(className: String = "test.GeometryKt"): Any? {
        assertTrue(succeeded, messages)
        return call(className, "run")
    }

    @Test
    fun `adapter wraps a matching class`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int, val color: String)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Rectangular(1, 2, "red").asSized())
            """,
        )
        assertEquals(2, compiled.runMain())
        val adapters = compiled.generatedFile("Sized_Adapters.kt")
        assertContains(adapters, "public fun Sized.asSized(): Sized = this")
        assertContains(adapters, "public fun Rectangular.asSized(): Sized = Rectangular_AsSized(this)")
        assertFalse("[structural]" in compiled.messages, compiled.messages)
    }

    @Test
    fun `adapters work for functions that overloads cannot support`() {
        val compiled = compileWith(
            """
            class Rectangular(val width: Int, val height: Int)
            class Square(val side: Int) {
                val width: Int get() = side
                val height: Int get() = side
            }
            """,
            """
            fun total(items: List<Sized>, scale: Int = 1) = items.sumOf { it.width * it.height } * scale
            fun <T> tagged(target: Sized, tag: T) = tag.toString() + target.width
            fun run() = total(listOf(Rectangular(1, 2).asSized(), Square(3).asSized())).toString() + tagged(Square(4).asSized(), "sq")
            """,
        )
        assertEquals("11sq4", compiled.runMain())
    }

    @Test
    fun `subclasses reuse the base adapter and nominal subclasses keep identity`() {
        val compiled = compileWith(
            """
            open class Base(val width: Int, val height: Int)
            class Mid : Base(1, 2)
            class Child : Base(3, 4), test.Sized
            """,
            """
            fun run(): List<Any> {
                val child = Child()
                return listOf(Mid().asSized().height, child.asSized() === child)
            }
            """,
        )
        assertEquals(listOf(2, true), compiled.runMain())
        val adapters = compiled.generatedFile("Sized_Adapters.kt")
        assertContains(adapters, "public fun Base.asSized(): Sized = Base_AsSized(this)")
        assertContains(adapters, "public fun Child.asSized(): Sized = this")
        assertFalse("Mid.asSized" in adapters, adapters)
    }

    @Test
    fun `interfaces without functions still get adapters`() {
        val compiled = compileWith("class Rectangular(val width: Int, val height: Int)", "fun run() = Rectangular(3, 4).asSized().width")
        assertEquals(3, compiled.runMain())
    }

    @Test
    fun `one import brings adapters for all classes`() {
        val compiled = compile(
            kotlin(
                "Model.kt",
                """
                package test.model
                class Rectangular(val width: Int, val height: Int)
                class Square(val width: Int) { val height: Int get() = width }
                """,
            ),
            kotlin("Geometry.kt", sized + "fun size(target: Sized) = target.width * target.height"),
            kotlin(
                "App.kt",
                """
                package test.app
                import test.asSized
                import test.size
                import test.model.Rectangular
                import test.model.Square
                fun run() = size(Rectangular(1, 2).asSized()) + size(Square(3).asSized())
                """,
            ),
        )
        assertEquals(11, compiled.runMain("test.app.AppKt"))
    }

    @Test
    fun `internal classes get internal adapters`() {
        val compiled = compileWith(
            "internal class Secret(val width: Int, val height: Int)",
            "fun run() = Secret(2, 5).asSized().width",
        )
        assertEquals(2, compiled.runMain())
        assertContains(compiled.generatedFile("Sized_Adapters.kt"), "internal fun Secret.asSized(): Sized")
    }

    @Test
    fun `var properties write through`() {
        val compiled = compile(
            kotlin("Model.kt", "package test.model\nclass Counter(var count: Int)"),
            kotlin(
                "Geometry.kt",
                """
                package test
                import dev.structural.Structural
                import test.model.Counter
                @Structural interface Counting { var count: Int }
                fun increment(target: Counting) { target.count++ }
                fun run(): Int {
                    val counter = Counter(1)
                    increment(counter.asCounting())
                    return counter.count
                }
                """,
            ),
        )
        assertEquals(2, compiled.runMain())
    }

    @Test
    fun `proxies compare and print through their target`() {
        val compiled = compileWith(
            "data class Point(val width: Int, val height: Int)",
            "fun run() = listOf(Point(1, 2).asSized() == Point(1, 2).asSized(), Point(1, 2).asSized().toString())",
        )
        assertEquals(listOf(true, "Point(width=1, height=2)"), compiled.runMain())
    }

    @Test
    fun `proxy property is renamed when the interface has a target property`() {
        val compiled = compileWith(
            "class Arrow(val target: String)",
            """
            @Structural interface Aimed { val target: String }
            fun run() = Arrow("x").asAimed().target
            """,
        )
        assertEquals("x", compiled.runMain())
        assertContains(compiled.generatedFile("Arrow_AsAimed.kt"), "val target_: Arrow")
    }
}
