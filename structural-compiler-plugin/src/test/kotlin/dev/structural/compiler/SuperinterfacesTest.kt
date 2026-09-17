package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Members of superinterfaces are required too, whether the superinterfaces are Kotlin or Java and annotated or not,
 * unless a more derived interface implements them. A class that gets the interface also implements its superinterfaces.
 */
class SuperinterfacesTest {
    private val interfaces = kotlin(
        "Sized.kt",
        """
        package test
        import dev.structural.Structural
        interface HasWidth { val width: Int }
        interface Labeled { val label: String get() = "shape" }
        @Structural interface Sized : HasWidth, Labeled { val height: Int }
        fun size(target: Sized) = target.width * target.height
        """,
    )

    @Test
    fun `members of superinterfaces are required and the class implements them too`() {
        val compiled = compile(
            interfaces,
            kotlin(
                "Main.kt",
                """
                package test
                class Rect(val width: Int, val height: Int)
                fun run(): List<Any> {
                    val rect = Rect(2, 3)
                    return listOf(size(rect), (rect as Any) is HasWidth, rect.label)
                }
                """,
            ),
        )
        assertEquals(listOf(6, true, "shape"), compiled.run())
    }

    @Test
    fun `class missing a superinterface member does not match and still compiles`() {
        val compiled = compile(
            interfaces,
            kotlin("Main.kt", "package test\nclass Tall(val height: Int)\nfun run() = (Tall(1) as Any is Sized).toString()"),
        )
        assertEquals("false", compiled.run())
    }

    @Test
    fun `superinterfaces declared in files after the class`() {
        val compiled = compile(
            kotlin("A_Main.kt", "package test\nclass Rect(val width: Int, val height: Int)\nfun run() = size(Rect(4, 5))"),
            interfaces,
        )
        assertEquals(20, compiled.run("test.A_MainKt"))
    }

    @Test
    fun `members implemented by a more derived interface are not required`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                interface Named { val name: String; fun greet(): String }
                @Structural interface Friendly : Named { override fun greet(): String = "hi " + name }
                class Person(val name: String)
                fun welcome(friend: Friendly) = friend.greet()
                fun run() = welcome(Person("Ann"))
                """,
            ),
        )
        assertEquals("hi Ann", compiled.run())
    }

    @Test
    fun `superinterfaces from the standard library`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Handle : AutoCloseable { val name: String }
                class File(val name: String) {
                    var closed = false
                    fun close() { closed = true }
                }
                fun release(handle: Handle) = handle.close()
                fun run(): Boolean { val file = File("a"); release(file); return file.closed }
                """,
            ),
        )
        assertEquals(true, compiled.run())
    }
}
