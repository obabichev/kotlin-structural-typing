package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A generic @Structural interface is matched with the arguments the class in front of it gives: `IntBox(val value: Int)`
 * implements `Box<Int>`. The argument is read off the class rather than chosen, so a class that says nothing about a
 * parameter, or whose members disagree about it, does not get the interface at all.
 */
class GenericInterfacesTest {
    private val box = kotlin(
        "Box.kt",
        """
        package test
        import com.obabichev.structural.Structural
        @Structural interface Box<T> { val value: T }
        fun <T> open(box: Box<T>): T = box.value
        fun openInt(box: Box<Int>): Int = box.value
        """,
    )

    @Test
    fun `a class gives the parameter its own type`() {
        val compiled = compile(
            box,
            kotlin(
                "Main.kt",
                """
                package test
                class IntBox(val value: Int)
                class TextBox(val value: String)
                fun run(): Any = listOf(openInt(IntBox(7)), open(TextBox("hi")))
                """,
            ),
        )
        assertEquals(listOf(7, "hi"), compiled.run())
    }

    @Test
    fun `the argument is the class's own type, not a supertype of it`() {
        val compiled = compile(
            box,
            kotlin("Main.kt", "package test\nclass IntBox(val value: Int)\nfun run() = openInt(IntBox(7))"),
        )
        assertEquals(7, compiled.run())
    }

    @Test
    fun `a class is not given the interface with the wrong argument`() {
        val compiled = compile(
            box,
            kotlin("Main.kt", "package test\nclass TextBox(val value: String)\nfun run() = openInt(TextBox(\"hi\"))"),
        )
        assertFalse(compiled.succeeded, "a Box<String> must not pass where Box<Int> is expected")
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `members have to agree about the parameter`() {
        val compiled = compile(
            kotlin(
                "Pair.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Both<T> { val first: T; val second: T }
                fun <T> firstOf(both: Both<T>): T = both.first
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Same(val first: Int, val second: Int)
                class Mixed(val first: Int, val second: String)
                fun run(): Any = listOf(firstOf(Same(1, 2)), (Mixed(1, "a") as Any) is Both<*>)
                """,
            ),
        )
        assertEquals(listOf(1, false), compiled.run())
    }

    @Test
    fun `a parameter inside another type is solved from the class`() {
        val compiled = compile(
            kotlin(
                "Holder.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Holder<T> { val items: List<T> }
                fun firstItem(holder: Holder<String>): String = holder.items.first()
                """,
            ),
            kotlin("Main.kt", "package test\nclass Doc(val items: List<String>)\nfun run() = firstItem(Doc(listOf(\"a\")))"),
        )
        assertEquals("a", compiled.run())
    }

    @Test
    fun `a bound the argument breaks keeps the interface off the class`() {
        val compiled = compile(
            kotlin(
                "Numbers.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Measure<T : Number> { val amount: T }
                fun <T : Number> amountOf(measure: Measure<T>): T = measure.amount
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Weight(val amount: Int)
                class Label(val amount: String)
                fun run(): Any = listOf(amountOf(Weight(2)), (Label("a") as Any) is Measure<*>)
                """,
            ),
        )
        assertEquals(listOf(2, false), compiled.run())
    }

    @Test
    fun `an interface whose parameter no member mentions is still refused, and says so`() {
        val compiled = compile(
            kotlin(
                "Phantom.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Phantom<T> { val name: String }
                """,
            ),
        )
        assertTrue(compiled.succeeded, compiled.messages)
        assertContains(compiled.warnings.single(), "no member mentions 'T', so a class can't say what it is")
    }

    @Test
    fun `a function requirement pins the parameter from its parameters and result`() {
        val compiled = compile(
            kotlin(
                "Source.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Store<T> { fun put(value: T): T }
                fun <T> roundTrip(store: Store<T>, value: T): T = store.put(value)
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Ints { fun put(value: Int): Int = value }
                class Mismatched { fun put(value: Int): String = value.toString() }
                fun run(): Any = listOf(roundTrip(Ints(), 3), (Mismatched() as Any) is Store<*>)
                """,
            ),
        )
        assertEquals(listOf(3, false), compiled.run())
    }
}
