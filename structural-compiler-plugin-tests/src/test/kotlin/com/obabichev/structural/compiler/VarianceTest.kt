package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A `val` may be narrowed by an override, so a class satisfies a requirement with any subtype -- including types with
 * arguments, under the variance the class declared. `List<out E>` is covariant, so `List<String>` satisfies
 * `List<CharSequence>`; `MutableList<E>` is invariant and does not.
 *
 * The compiler's type checker would answer all of this and can't be used while supertypes are being decided, so these
 * tests pin the comparison the plugin does by hand.
 */
class VarianceTest {
    private fun shelf(type: String) = kotlin(
        "Shelf.kt",
        """
        package test
        import com.obabichev.structural.Structural
        @Structural interface Shelf { val items: $type }
        fun count(shelf: Shelf): Int = shelf.items.count()
        """,
    )

    @Test
    fun `a covariant argument accepts a subtype`() {
        val compiled = compile(
            shelf("List<CharSequence>"),
            kotlin("Main.kt", "package test\nclass Books(val items: List<String>)\nfun run() = count(Books(listOf(\"a\", \"b\")))"),
        )
        assertEquals(2, compiled.run())
    }

    @Test
    fun `an invariant argument does not`() {
        val compiled = compile(
            kotlin(
                "Shelf.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Shelf { val items: MutableList<CharSequence> }
                fun count(shelf: Shelf): Int = shelf.items.count()
                """,
            ),
            kotlin("Main.kt", "package test\nclass Books(val items: MutableList<String>)\nfun run() = count(Books(mutableListOf()))"),
        )
        assertFalse(compiled.succeeded, "MutableList is invariant, so String must not pass for CharSequence")
    }

    @Test
    fun `a subclass with arguments satisfies the interface its superclass names`() {
        val compiled = compile(
            shelf("Collection<CharSequence>"),
            kotlin("Main.kt", "package test\nclass Books(val items: ArrayList<String>)\nfun run() = count(Books(arrayListOf(\"a\")))"),
        )
        assertEquals(1, compiled.run())
    }

    @Test
    fun `a use-site projection is honoured`() {
        val compiled = compile(
            shelf("List<out CharSequence>"),
            kotlin("Main.kt", "package test\nclass Books(val items: List<String>)\nfun run() = count(Books(listOf(\"a\")))"),
        )
        assertEquals(1, compiled.run())
    }

    @Test
    fun `a star projection accepts any argument`() {
        val compiled = compile(
            shelf("List<*>"),
            kotlin("Main.kt", "package test\nclass Books(val items: List<Int>)\nfun run() = count(Books(listOf(1, 2, 3)))"),
        )
        assertEquals(3, compiled.run())
    }

    @Test
    fun `an unrelated argument is still rejected`() {
        val compiled = compile(
            shelf("List<CharSequence>"),
            kotlin("Main.kt", "package test\nclass Numbers(val items: List<Int>)\nfun run() = count(Numbers(listOf(1)))"),
        )
        assertFalse(compiled.succeeded, "Int is not a CharSequence")
    }

    @Test
    fun `a var still requires exactly the same type`() {
        val compiled = compile(
            kotlin(
                "Box.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Box { var items: List<CharSequence> }
                fun first(box: Box): CharSequence = box.items.first()
                """,
            ),
            kotlin("Main.kt", "package test\nclass Narrow(var items: List<String>)\nfun run() = first(Narrow(listOf(\"a\")))"),
        )
        assertFalse(compiled.succeeded, "a var can't be narrowed, variance or not")
    }
}
