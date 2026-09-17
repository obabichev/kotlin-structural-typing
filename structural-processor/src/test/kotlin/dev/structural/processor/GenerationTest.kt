package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationTest {
    private val sized = """
        package test
        import dev.structural.Structural
        import test.model.*
        import kotlin.coroutines.*
        @Structural interface Sized { val width: Int; val height: Int }
    """

    private fun compileWith(model: String, code: String): Compiled =
        compile(kotlin("Model.kt", "package test.model\n$model"), kotlin("Geometry.kt", sized + code))

    private fun Compiled.runMain(): Any? {
        assertTrue(succeeded, messages)
        return call("test.GeometryKt", "run")
    }

    @Test
    fun `original example`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int, val color: String)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Rectangular(1, 2, "red"))
            """,
        )
        assertEquals(2, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "public fun size(target: Rectangular): Int")
        assertContains(compiled.generatedFile("Rectangular_AsSized.kt"), "internal class Rectangular_AsSized(")
    }

    @Test
    fun `member function gets an extension overload`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            class Canvas { fun area(target: Sized) = target.width * target.height }
            fun run() = Canvas().area(Rectangular(2, 3))
            """,
        )
        assertEquals(6, compiled.runMain())
        assertContains(compiled.generatedFile("Canvas_Structural.kt"), "public fun Canvas.area(target: Rectangular): Int")
    }

    @Test
    fun `extension function with other parameters`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun String.describe(target: Sized, suffix: String) = this + (target.width * target.height) + suffix
            fun run() = "area=".describe(Rectangular(2, 2), "!")
            """,
        )
        assertEquals("area=4!", compiled.runMain())
    }

    @Test
    fun `suspend function`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            suspend fun measure(target: Sized) = target.width
            fun run(): Int {
                var result = 0
                val block: suspend () -> Int = { measure(Rectangular(7, 1)) }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
                return result
            }
            """,
        )
        assertEquals(7, compiled.runMain())
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
                    increment(counter)
                    return counter.count
                }
                """,
            ),
        )
        assertEquals(2, compiled.runMain())
    }

    @Test
    fun `base class overload covers subclasses and nominal subclass gets a direct overload`() {
        val compiled = compileWith(
            """
            open class Base(val width: Int, val height: Int)
            class Mid : Base(1, 2)
            class Child : Base(3, 4), test.Sized
            """,
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Base(1, 1)) + size(Mid()) * 10 + size(Child()) * 100
            """,
        )
        assertEquals(1221, compiled.runMain())
        val overloads = compiled.generatedFile("Geometry_Structural.kt")
        assertContains(overloads, "fun size(target: Base)")
        assertContains(overloads, "fun size(target: Child)")
        assertFalse("target: Mid" in overloads, overloads)
        assertContains(overloads, "val structuralArgument: Sized = target\n")
    }

    @Test
    fun `class matching several interfaces is reported and skipped`() {
        val compiled = compileWith(
            """
            class Labeled(val width: Int, val height: Int, val name: String)
            class Person(val name: String)
            """,
            """
            @Structural interface Named { val name: String }
            fun describe(target: Sized) = "sized"
            fun describe(target: Named) = "named"
            fun run() = describe(Person("x"))
            """,
        )
        assertEquals("named", compiled.runMain())
        assertContains(
            compiled.messages,
            "[structural] Not generating test.describe for test.model.Labeled: it matches several @Structural interfaces (test.Named, test.Sized)",
        )
    }

    @Test
    fun `internal candidates get internal overloads`() {
        val compiled = compileWith(
            "internal class Secret(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Secret(2, 5))
            """,
        )
        assertEquals(10, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "internal fun size(target: Secret): Int")
    }

    @Test
    fun `hand-written overload is kept`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun size(target: Rectangular) = -1
            fun run() = size(Rectangular(2, 2))
            """,
        )
        assertEquals(-1, compiled.runMain())
    }

    @Test
    fun `proxies compare and print through their target`() {
        val compiled = compileWith(
            "data class Point(val width: Int, val height: Int)",
            """
            fun identity(target: Sized): Sized = target
            fun run() = listOf(identity(Point(1, 2)) == identity(Point(1, 2)), identity(Point(1, 2)).toString())
            """,
        )
        assertEquals(listOf(true, "Point(width=1, height=2)"), compiled.runMain())
    }

    @Test
    fun `calling an unsupported function still fails to compile`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun fit(a: Sized, b: Sized) = 0
            fun run() = fit(Rectangular(1, 2), Rectangular(1, 2))
            """,
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "[structural] Skipping test.fit")
        assertContains(compiled.messages, "mismatch", ignoreCase = true)
    }

    @Test
    fun `generated names avoid clashes with parameters and properties`() {
        val compiled = compileWith(
            """
            class Rectangular(val width: Int, val height: Int)
            class Arrow(val target: String)
            """,
            """
            @Structural interface Aimed { val target: String }
            fun pick(target: Sized, structuralArgument: Int) = target.width + structuralArgument
            fun aim(aimed: Aimed) = aimed.target
            fun run() = pick(Rectangular(1, 2), 10).toString() + aim(Arrow("x"))
            """,
        )
        assertEquals("11x", compiled.runMain())
    }
}
