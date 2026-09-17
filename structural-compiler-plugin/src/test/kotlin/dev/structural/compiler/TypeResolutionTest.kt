package dev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Supertypes are decided before the compiler has resolved types, so the plugin resolves them itself: each type with the
 * imports of the file declaring it, and subtyping by walking declared supertypes, independent of file order. It must never
 * leave the compiler in a state that breaks unrelated code.
 */
class TypeResolutionTest {
    private val colors = arrayOf(
        kotlin("A.kt", "package a\nclass Color"),
        kotlin("B.kt", "package b\nclass Color"),
        kotlin(
            "Painted.kt",
            """
            package test
            import dev.structural.Structural
            import a.Color
            @Structural interface Painted { val color: Color }
            """,
        ),
    )

    private val node = """
        package test
        import dev.structural.Structural
        @Structural interface HasParent { val parent: Base }
        class Leaf(val parent: Derived)
        fun run() = (Leaf(Derived()) as Any is HasParent).toString()
    """
    private val types = "open class Base\nclass Derived : Base()"

    @Test
    fun `interface types are resolved with the interface file's imports`() {
        val compiled = compile(
            *colors,
            kotlin(
                "Main.kt",
                """
                package test.model
                import b.Color
                class Car(val color: Color)
                fun run() = (Car(Color()) as Any is test.Painted).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run("test.model.MainKt"), "b.Color must not satisfy a.Color")
    }

    @Test
    fun `same type written differently still matches`() {
        val compiled = compile(
            *colors,
            kotlin(
                "Main.kt",
                """
                package test.model
                class Car(val color: a.Color)
                fun run() = (Car(a.Color()) as Any is test.Painted).toString()
                """,
            ),
        )
        assertEquals("true", compiled.run("test.model.MainKt"))
    }

    @Test
    fun `subtypes match whatever order and package they are declared in`() {
        val later = compile(kotlin("A_Node.kt", node), kotlin("Z_Types.kt", "package test\n$types"))
        val earlier = compile(kotlin("A_Types.kt", "package test\n$types"), kotlin("Z_Node.kt", node))
        val otherPackage = compile(
            kotlin("Node.kt", node.replace("package test\n", "package test\nimport test.types.*\n")),
            kotlin("Types.kt", "package test.types\n$types"),
        )
        assertEquals(
            listOf("true", "true", "true"),
            listOf(later.run("test.A_NodeKt"), earlier.run("test.Z_NodeKt"), otherPackage.run("test.NodeKt")),
        )
    }

    @Test
    fun `checking a class against an interface doesn't break unrelated subtyping`() {
        // Base and Derived are processed after Leaf. Checking Leaf against HasParent must not make the compiler cache
        // incomplete supertypes of Derived: `use()` has to compile exactly as without the plugin.
        val compiled = compile(
            kotlin(
                "A_Node.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface HasParent { val parent: Base; val name: String }
                class Leaf(val parent: Derived)
                fun use(): Base = Derived()
                fun run() = (use() is Derived).toString()
                """,
            ),
            kotlin("Z_Types.kt", "package test\nopen class Base\nclass Derived : Base()"),
        )
        assertEquals("true", compiled.run("test.A_NodeKt"))
    }

    @Test
    fun `types nested in the interface don't resolve yet, so nothing matches`() {
        val compiled = compile(
            kotlin(
                "Main.kt",
                """
                package test
                import dev.structural.Structural
                @Structural interface Shape { enum class Kind { ROUND }; val kind: Kind }
                class Ball(val kind: Shape.Kind)
                fun run() = (Ball(Shape.Kind.ROUND) as Any is Shape).toString()
                """,
            ),
        )
        assertEquals("false", compiled.run(), "known limitation, see docs/known-issues.md")
    }
}
