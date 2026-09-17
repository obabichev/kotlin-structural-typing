package dev.structural.processor

import com.google.devtools.ksp.getClassDeclarationByName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatcherTest {
    private val interfaces = """
        package test
        import dev.structural.Structural
        @Structural interface Sized { val width: Number; val height: Int }
        @Structural interface Resizable { var width: Int }
        @Structural interface Labeled { val label: String? }
    """

    private fun matches(classes: String, candidate: String, iface: String): Boolean =
        resolve(kotlin("Source.kt", interfaces + classes)) { resolver, logger ->
            Matcher.matchesStructurally(
                resolver.getClassDeclarationByName("test.$candidate")!!,
                InterfaceRegistry(logger).get(resolver.getClassDeclarationByName("test.$iface")!!)!!,
            )
        }.first

    @Test
    fun `val accepts a subtype`() {
        assertTrue(matches("class Rect(val width: Int, val height: Int, val color: String)", "Rect", "Sized"))
    }

    @Test
    fun `missing property does not match`() {
        assertFalse(matches("class Flat(val width: Int)", "Flat", "Sized"))
    }

    @Test
    fun `private property does not match`() {
        assertFalse(matches("class Hidden(private val width: Int, val height: Int)", "Hidden", "Sized"))
    }

    @Test
    fun `inherited property from generic superclass matches with substituted type`() {
        val classes = """
            open class Base<T>(val width: T)
            class Derived(val height: Int) : Base<Double>(1.0)
        """
        assertTrue(matches(classes, "Derived", "Sized"))
    }

    @Test
    fun `nominal implementors are not structural matches`() {
        assertFalse(matches("class Nominal(override val width: Int, override val height: Int) : Sized", "Nominal", "Sized"))
    }

    @Test
    fun `var requires a mutable property of exactly the same type`() {
        assertTrue(matches("class Box(var width: Int)", "Box", "Resizable"))
        assertFalse(matches("class Frozen(val width: Int)", "Frozen", "Resizable"))
        assertFalse(matches("class Wide(var width: Long)", "Wide", "Resizable"))
    }

    @Test
    fun `var with private setter does not match`() {
        assertFalse(matches("class Guarded { var width: Int = 0\n private set }", "Guarded", "Resizable"))
    }

    @Test
    fun `non-null type satisfies nullable val`() {
        assertTrue(matches("class Tag(val label: String)", "Tag", "Labeled"))
        assertTrue(matches("class MaybeTag(val label: String?)", "MaybeTag", "Labeled"))
        assertFalse(matches("class NumberTag(val label: Int)", "NumberTag", "Labeled"))
    }
}
