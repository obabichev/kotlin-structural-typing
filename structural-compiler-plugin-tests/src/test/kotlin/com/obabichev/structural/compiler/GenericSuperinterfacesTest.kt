package com.obabichev.structural.compiler

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A @Structural interface may extend a generic one when it says what the arguments are: the members inherited from it
 * are required with those arguments substituted in, so `Ranked : Comparable<String>` asks for `compareTo(String)`.
 *
 * Everything the arguments reach is substituted, including a parameter inside another type such as `List<T>` or
 * `Iterator<T>`. A generic @Structural interface is matched too, with the arguments the class gives it; that is
 * GenericInterfacesTest.
 */
class GenericSuperinterfacesTest {
    @Test
    fun `a class implements an interface extending a generic one`() {
        val compiled = compile(
            kotlin(
                "Ranked.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Ranked : Comparable<String>
                fun rank(ranked: Ranked): Int = ranked.compareTo("m")
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Word(private val word: String) {
                    operator fun compareTo(other: String): Int = word.compareTo(other)
                }
                fun run(): Any = listOf(rank(Word("z")) > 0, (Word("a") as Any) is Comparable<*>)
                """,
            ),
        )
        assertEquals(listOf(true, true), compiled.run())
    }

    @Test
    fun `a property inherited from a generic interface is required with the argument`() {
        val compiled = compile(
            kotlin(
                "Box.kt",
                """
                package test
                import com.obabichev.structural.Structural
                interface Box<T> { val value: T }
                @Structural interface IntBox : Box<Int>
                fun open(box: IntBox): Int = box.value
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Holder(val value: Int)
                class Label(val value: String)
                fun run(): Any = listOf(open(Holder(7)), (Label("a") as Any) is IntBox)
                """,
            ),
        )
        assertEquals(listOf(7, false), compiled.run())
    }

    @Test
    fun `the arguments travel down a chain of interfaces`() {
        val compiled = compile(
            kotlin(
                "Chain.kt",
                """
                package test
                import com.obabichev.structural.Structural
                interface Source<T> { fun load(): T }
                interface Strings : Source<String>
                @Structural interface Texts : Strings
                fun read(texts: Texts): String = texts.load()
                """,
            ),
            kotlin("Main.kt", "package test\nclass Note(private val text: String) { fun load(): String = text }\nfun run() = read(Note(\"hi\"))"),
        )
        assertEquals("hi", compiled.run())
    }

    @Test
    fun `the argument has to match, not only the member name`() {
        val compiled = compile(
            kotlin(
                "Chain.kt",
                """
                package test
                import com.obabichev.structural.Structural
                interface Source<T> { fun load(): T }
                @Structural interface Texts : Source<String>
                fun read(texts: Texts): String = texts.load()
                """,
            ),
            kotlin("Main.kt", "package test\nclass Counter { fun load(): Int = 1 }\nfun run() = read(Counter())"),
        )
        assertFalse(compiled.succeeded, "load(): Int must not satisfy load(): String")
        assertContains(compiled.errors.joinToString("\n"), "mismatch", ignoreCase = true)
    }

    @Test
    fun `a parameter used inside another type is substituted too`() {
        val compiled = compile(
            kotlin(
                "Holder.kt",
                """
                package test
                import com.obabichev.structural.Structural
                interface Holder<T> { val items: List<T> }
                @Structural interface Texts : Holder<String>
                fun all(texts: Texts): List<String> = texts.items
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Doc(val items: List<String>)
                class Counts(val items: List<Int>)
                fun run(): Any = listOf(all(Doc(listOf("a"))), (Counts(listOf(1)) as Any) is Texts)
                """,
            ),
        )
        assertEquals(listOf(listOf("a"), false), compiled.run())
    }

    @Test
    fun `an interface extending Iterable is usable as Iterable`() {
        val compiled = compile(
            kotlin(
                "Tags.kt",
                """
                package test
                import com.obabichev.structural.Structural
                @Structural interface Tags : Iterable<String>
                """,
            ),
            kotlin(
                "Main.kt",
                """
                package test
                class Labels(private val values: List<String>) {
                    operator fun iterator(): Iterator<String> = values.iterator()
                }
                fun run(): Any = Labels(listOf("a", "b")).joinToString()
                """,
            ),
        )
        assertEquals("a, b", compiled.run())
    }

    /** The order files are compiled in must not decide what matches; it did while arguments went unresolved. */
    @Test
    fun `the file the interface is in does not change the answer`() {
        val source = { name: String ->
            kotlin(
                name,
                """
                package test
                import com.obabichev.structural.Structural
                interface Source<T> { fun load(): T }
                @Structural interface Texts : Source<String>
                fun read(texts: Texts): String = texts.load()
                """,
            )
        }
        val main = kotlin("Main.kt", "package test\nclass Note(private val text: String) { fun load(): String = text }\nfun run() = read(Note(\"hi\"))")
        for (name in listOf("AaaBefore.kt", "ZzzAfter.kt")) {
            val compiled = compile(source(name), main)
            assertTrue(compiled.succeeded, "$name: ${compiled.messages}")
            assertEquals("hi", compiled.run())
        }
    }
}
