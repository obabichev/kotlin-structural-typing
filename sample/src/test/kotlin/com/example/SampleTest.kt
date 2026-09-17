package com.example

import com.example.geometry.Canvas
import com.example.geometry.area
import com.example.geometry.asMeasurable
import com.example.geometry.asSized
import com.example.geometry.describe
import com.example.geometry.describeOrNone
import com.example.geometry.fits
import com.example.geometry.increment
import com.example.geometry.label
import com.example.geometry.size
import com.example.geometry.sizeLater
import com.example.geometry.sizedFor
import com.example.geometry.totalArea
import com.example.geometry.totalSize
import com.example.model.Clicks
import com.example.model.PictureFrame
import com.example.model.Rectangular
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
    }

    @Test
    fun `member function`() {
        assertTrue(Canvas("main").fits(Rectangular(10, 10, "red")))
    }

    @Test
    fun `companion object function`() {
        assertEquals("4x5", Canvas.sizedFor(Rectangular(4, 5, "red")).name)
    }

    @Test
    fun `extension function with another receiver`() {
        assertEquals("box 3x4", "box".label(Rectangular(3, 4, "blue")))
    }

    @Test
    fun `extension function on the interface`() {
        assertEquals(6, Rectangular(2, 3, "red").area())
    }

    @Test
    fun `nullable parameter`() {
        assertEquals("2x3none", describeOrNone(Rectangular(2, 3, "red")) + describeOrNone(null))
    }

    @Test
    fun `vararg parameter`() {
        assertEquals(14, totalSize(Rectangular(1, 2, "red"), Rectangular(3, 4, "blue")))
    }

    @Test
    fun `suspend function`() {
        var result = 0
        val block: suspend () -> Int = { sizeLater(Rectangular(2, 5, "green")) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        assertEquals(10, result)
    }

    @Test
    fun `var writes through to the original object`() {
        val clicks = Clicks(1)
        increment(clicks)
        assertEquals(2, clicks.count)
    }

    @Test
    fun `subclass uses the base class overload`() {
        assertEquals(200, size(PictureFrame("oak")))
    }

    @Test
    fun `nominal subclass of a structural match`() {
        assertEquals(1200, size(Window()))
    }

    @Test
    fun `adapter for a function with a default parameter`() {
        assertEquals("5 m", describe(Rope(5).asMeasurable()))
    }

    @Test
    fun `adapters in a collection`() {
        val shapes = listOf(Rectangular(1, 2, "red").asSized(), PictureFrame("oak").asSized(), Window().asSized())
        assertEquals(1402, totalArea(shapes))
    }

    @Test
    fun `adapter keeps identity for nominal implementors`() {
        val window = Window()
        assertSame(window, window.asSized())
    }
}
