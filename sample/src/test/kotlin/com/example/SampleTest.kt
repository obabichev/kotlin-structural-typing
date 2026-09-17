package com.example

import com.example.geometry.Canvas
import com.example.geometry.Sized
import com.example.geometry.area
import com.example.geometry.describe
import com.example.geometry.describeOrNone
import com.example.geometry.increment
import com.example.geometry.label
import com.example.geometry.larger
import com.example.geometry.size
import com.example.geometry.sizeLater
import com.example.geometry.totalArea
import com.example.geometry.totalSize
import com.example.model.Clicks
import com.example.model.PictureFrame
import com.example.model.Rectangular
import com.example.model.Rectangular2
import com.example.model.Rectangular3
import com.example.model.Rope
import com.example.model.Window
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SampleTest {
    @Test
    fun `original example`() {
        assertEquals(2, size(Rectangular(1, 2, "red")))
        assertEquals(2, size(Rectangular3(1, 2, "red")))
    }

    @Test
    fun `matching classes implement the interface even when unused`() {
        assertTrue(Sized::class.java.isAssignableFrom(Rectangular2::class.java))
    }

    @Test
    fun `member and companion object functions`() {
        assertTrue(Canvas("main").fits(Rectangular(10, 10, "red")))
        assertEquals("4x5", Canvas.sizedFor(Rectangular(4, 5, "red")).name)
    }

    @Test
    fun `extension functions`() {
        assertEquals("box 3x4", "box".label(Rectangular(3, 4, "blue")))
        assertEquals(6, Rectangular(2, 3, "red").area())
    }

    @Test
    fun `nullable values`() {
        val maybe: Rectangular? = Rectangular(2, 3, "red")
        assertEquals("2x3none", describeOrNone(maybe) + describeOrNone(null))
    }

    @Test
    fun `vararg with different classes`() {
        assertEquals(202, totalSize(Rectangular(1, 2, "red"), PictureFrame("oak")))
    }

    @Test
    fun `collections and generics`() {
        val rectangles: List<Rectangular> = listOf(Rectangular(1, 2, "red"), Rectangular(3, 4, "blue"))
        assertEquals(14, totalArea(rectangles))
        assertEquals("blue", larger(rectangles[0], rectangles[1]).color)
    }

    @Test
    fun `default parameter and Int for Number`() {
        assertEquals("5 m", describe(Rope(5)))
    }

    @Test
    fun `var writes through to the object`() {
        val clicks = Clicks(1)
        increment(clicks)
        assertEquals(2, clicks.count)
    }

    @Test
    fun `suspend function`() {
        var result = 0
        val block: suspend () -> Int = { sizeLater(Rectangular(2, 5, "green")) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        assertEquals(10, result)
    }

    @Test
    fun `subclasses and nominal implementors`() {
        assertEquals(200, size(PictureFrame("oak")))
        assertEquals(1200, size(Window()))
    }

    @Test
    fun `the object itself is passed, not a wrapper`() {
        val rect = Rectangular(1, 1, "red")
        val sized: Sized = rect
        assertSame(rect, sized)
    }
}
