package com.obabichev.structural.runtime

import com.obabichev.structural.Structural
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Structural
interface Sized {
    val width: Int
    val height: Int
}

@Structural
interface Measurable {
    val length: Number
}

@Structural
interface Counter {
    var count: Int
}

fun size(target: Sized) = target.width * target.height

/**
 * The gap the compiler plugin cannot close: classes from a dependency, compiled with no plugin and never recompiled,
 * gain a `@Structural` interface as the loader reads them. The interfaces here come from the parent loader, so the
 * transformed classes and this test see the same ones.
 */
class StructuralClassLoaderTest {
    private val messages = mutableListOf<String>()

    private fun loaderOver(source: com.tschuchort.compiletesting.SourceFile, iface: Class<*>) =
        StructuralClassLoader(
            urls = arrayOf(dependency(source, MARKER_SOURCE).toURI().toURL()),
            parent = javaClass.classLoader,
            index = StructuralIndex.of(iface.name),
            listener = { messages += it },
        )

    private fun instance(loader: ClassLoader, name: String, vararg arguments: Any): Any {
        val type = loader.loadClass(name)
        return type.constructors.single().newInstance(*arguments)
    }

    @Test
    fun `a class from a dependency implements the interface it matches`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int, val color: String)"),
            Sized::class.java,
        )
        val rect = instance(loader, "dep.Rectangular", 2, 3, "red")

        assertTrue(rect is Sized, messages.joinToString("\n"))
        assertEquals(6, size(rect))
        assertEquals(2, rect.width)
    }

    @Test
    fun `the object itself is used, not a wrapper`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Rectangular(val width: Int, val height: Int)"),
            Sized::class.java,
        )
        val rect = instance(loader, "dep.Rectangular", 1, 1)
        assertSame(rect, rect as Sized)
    }

    @Test
    fun `a class that does not match is left alone`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Unrelated(val depth: Int)"),
            Sized::class.java,
        )
        assertFalse(instance(loader, "dep.Unrelated", 1) is Sized)
    }

    @Test
    fun `a val of a subtype gets a bridge`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Rope(val length: Int)"),
            Measurable::class.java,
        )
        val rope = instance(loader, "dep.Rope", 5)

        assertTrue(rope is Measurable, messages.joinToString("\n"))
        assertEquals(5, rope.length)
    }

    @Test
    fun `a var is matched and stays writable through the interface`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Clicks(var count: Int)"),
            Counter::class.java,
        )
        val clicks = instance(loader, "dep.Clicks", 1) as Counter

        clicks.count = 7
        assertEquals(7, clicks.count)
    }

    @Test
    fun `a val cannot satisfy a var`() {
        val loader = loaderOver(
            kotlin("Model.kt", "package dep\nclass Frozen(val count: Int)"),
            Counter::class.java,
        )
        assertFalse(instance(loader, "dep.Frozen", 1) is Counter)
    }

    @Test
    fun `a member inherited from a superclass counts`() {
        val loader = loaderOver(
            kotlin(
                "Model.kt",
                """
                package dep
                open class Frame(val width: Int, val height: Int)
                class PictureFrame(val material: String) : Frame(10, 20)
                """,
            ),
            Sized::class.java,
        )
        val frame = instance(loader, "dep.PictureFrame", "oak")

        assertTrue(frame is Sized, messages.joinToString("\n"))
        assertEquals(200, size(frame))
    }
}
