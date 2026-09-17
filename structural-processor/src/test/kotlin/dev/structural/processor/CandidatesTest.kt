package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatesTest {
    @Test
    fun `finds visible non-generic classes in configured packages and subpackages`() {
        val model = kotlin(
            "Model.kt",
            """
            package test.model
            class Plain
            internal class Internal
            private class Private
            class Generic<T>
            annotation class Marker
            enum class Color { RED }
            object Singleton
            class Outer {
                class Nested
                inner class Inner
                private class Hidden
                companion object
            }
            """,
        )
        val other = kotlin("Other.kt", "package test.other\nclass Elsewhere")
        val sub = kotlin("Sub.kt", "package test.model.sub\nclass Deeper")

        val (names, _) = resolve(model, other, sub) { resolver, _ ->
            Candidates.find(resolver.getAllFiles().toList(), Config(listOf("test.model"))).map { it.fqName }.toSet()
        }

        assertEquals(
            setOf(
                "test.model.Plain",
                "test.model.Internal",
                "test.model.Color",
                "test.model.Singleton",
                "test.model.Outer",
                "test.model.Outer.Nested",
                "test.model.sub.Deeper",
            ),
            names,
        )
    }
}
